package app.alextran.immich.homelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.alextran.immich.core.HttpClientManager
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Home Link: keeps the Immich server reachable wherever the phone is, with the least battery.
 *
 *  - On the home network (server answers over Wi-Fi/Ethernet) the app talks to it directly, tunnel down.
 *  - Away from home a **per-app** WireGuard tunnel (only this app's traffic) is brought up on demand:
 *    while the app is on screen, or while a background backup runs. It is dropped again ~20 s after the
 *    last user of the link lets go and no bytes are moving, so an idle phone spends nothing on it.
 *
 * One process-wide instance shared by the UI engine and the background-worker engine.
 * Every public entry point is safe to call from any thread.
 */
object HomeLinkEngine {
  private const val TAG = "HomeLink"
  private const val TUNNEL_NAME = "immich-home"
  private const val IDLE_GRACE_MS = 20_000L         // last hold released -> wait this long
  private const val IDLE_TRAFFIC_WINDOW_MS = 10_000L // ... then require this long with no traffic
  private const val LAN_FLAP_GUARD_MS = 3 * 60_000L  // a LAN path that just failed stays blocked this long
  private const val NETWORK_SETTLE_MS = 2_000L       // debounce for connectivity callbacks
  private const val KEEPALIVE_ACTIVE = 25            // seconds, while someone is using the tunnel
  private const val KEEPALIVE_IDLE = 0               // "keep up when away": no packets while idle
  private const val DEFAULT_MTU = 1280
  private const val FAIL_BACKOFF_MS = 15_000L
  private const val RETRY_MIN_MS = 30_000L           // while someone still wants the link after a failure, try again
  private const val RETRY_MAX_MS = 5 * 60_000L

  private lateinit var app: Context
  lateinit var store: HomeLinkStore
    private set
  private val backend by lazy { GoBackend(app) }
  private val cm by lazy { app.getSystemService(ConnectivityManager::class.java) }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  @Volatile private var initialized = false

  private val tunnel = object : Tunnel {
    override fun getName() = TUNNEL_NAME
    override fun onStateChange(newState: Tunnel.State) {
      Log.i(TAG, "tunnel state -> $newState")
      if (newState == Tunnel.State.DOWN) {
        tunnelUp = false
        activeConfig = null
      }
    }
  }

  // ---- observable state -------------------------------------------------------------------
  @Volatile private var state = HomeLinkState.IDLE
  @Volatile private var detail: String? = null
  @Volatile private var tunnelUp = false
  @Volatile private var activeConfig: String? = null   // wg userspace text of what is up (Config has no value equality)
  @Volatile private var lanNetwork: Network? = null
  @Volatile private var lanBlockedUntil = 0L
  @Volatile private var vpnConsent = false               // last known VpnService.prepare() result, refreshed only when safe
  @Volatile private var lastFailAt = 0L               // a failed tunnel attempt is not retried for FAIL_BACKOFF_MS unless the network changes
  private val holds: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private var idleJob: Job? = null
  private var settleJob: Job? = null
  private var guardJob: Job? = null
  private var retryJob: Job? = null
  @Volatile private var retryDelay = RETRY_MIN_MS

  // ---- lifecycle ----------------------------------------------------------------------------
  fun init(ctx: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      app = ctx.applicationContext
      store = HomeLinkStore(app)
      HttpClientManager.initialize(app)   // probes reuse Immich's client; the worker may need it before any plugin does
      initialized = true
      state = when {
        !store.enabled -> HomeLinkState.DISABLED
        !store.isConfigured -> HomeLinkState.UNCONFIGURED
        else -> HomeLinkState.IDLE
      }
      registerNetworkCallbacks()
      registerPowerCallbacks()
      Log.i(TAG, "initialized: state=$state configured=${store.isConfigured}")
    }
  }

  val isEnabled: Boolean get() = initialized && store.enabled && store.isConfigured

  /** Activity on screen -> keep the link ready; off screen -> let it go (after the idle grace). */
  fun foreground(visible: Boolean) {
    if (!initialized) return
    if (visible) {
      if (!isEnabled) return
      scope.launch { runCatching { acquire("fg", 20_000) }.onFailure { Log.w(TAG, "fg acquire failed", it) } }
    } else {
      release("fg")
      release("app")
    }
  }

  // ---- holds ----------------------------------------------------------------------------------
  /** Make the server reachable and keep it so under [holder]. Returns the resulting status. */
  suspend fun acquire(holder: String, timeoutMs: Long): HomeLinkStatus {
    if (!isEnabled) return evaluate(timeoutMs)   // nothing to hold open
    // Requests from the Dart side while no screen is showing and no background worker runs
    // (websocket reconnects, timers, connectivity listeners) must never keep the link alive.
    // On battery they do not even bring it up; on the charger they get a one-off link that the
    // idle policy tears down again.
    if (holder == "app" && "fg" !in holds && "bg" !in holds) {
      if (!isCharging()) {
        Log.i(TAG, "background request ($holder) on battery -> link not brought up")
        return status()
      }
      val s = evaluate(timeoutMs)
      if (holds.isEmpty()) scheduleIdle()
      return s
    }
    holds += holder
    idleJob?.cancel()
    return evaluate(timeoutMs)
  }

  fun release(holder: String) {
    if (!holds.remove(holder)) return
    Log.i(TAG, "release($holder) -> holds=$holds")
    if (holds.isEmpty()) scheduleIdle()
  }

  /** Tunnel down now, every hold dropped. */
  suspend fun disconnect() = mutex.withLock {
    holds.clear()
    idleJob?.cancel()
    tunnelDown()
    unbindLan()
    state = if (!store.enabled) HomeLinkState.DISABLED else if (!store.isConfigured) HomeLinkState.UNCONFIGURED else HomeLinkState.IDLE
    detail = null
  }

  // ---- configuration --------------------------------------------------------------------------
  /** Validate + persist. Throws IllegalArgumentException with a readable message on bad WireGuard text. */
  suspend fun setConfig(c: HomeLinkConfig) {
    if (!c.wireguardConfig.isNullOrBlank()) parseConfig(c.wireguardConfig, KEEPALIVE_ACTIVE)   // throws
    if (!c.homeUrl.isNullOrBlank()) {
      val u = c.homeUrl.trim()
      require(u.startsWith("http://") || u.startsWith("https://")) { "Home URL must start with http:// or https://" }
    }
    val wasUp = tunnelUp
    store.save(c)
    mutex.withLock {
      // A changed tunnel config must be re-applied; the cheapest way is a clean restart.
      if (wasUp) tunnelDown()
      unbindLan()
      state = when {
        !store.enabled -> HomeLinkState.DISABLED
        !store.isConfigured -> HomeLinkState.UNCONFIGURED
        else -> HomeLinkState.IDLE
      }
      detail = null
    }
    if (isEnabled && (holds.isNotEmpty() || keepUpNow())) {
      scope.launch { runCatching { evaluate(20_000) } }
    }
  }

  // ---- status ---------------------------------------------------------------------------------
  fun status(): HomeLinkStatus {
    var rx: Long? = null; var tx: Long? = null; var hs: Long? = null
    var endpoint: String? = null; var pub: String? = null
    val cfgText = if (initialized) store.wireguardConfig else null
    if (!cfgText.isNullOrBlank()) {
      runCatching {
        val c = Config.parse(cfgText.byteInputStream())
        pub = c.`interface`.keyPair.publicKey.toBase64()
        endpoint = c.peers.firstOrNull()?.endpoint?.orElse(null)?.let { "${it.host}:${it.port}" }
        if (tunnelUp) {
          val s = backend.getStatistics(tunnel)
          rx = s.totalRx(); tx = s.totalTx()
          hs = c.peers.firstOrNull()?.publicKey?.let { k -> s.peer(k)?.latestHandshakeEpochMillis()?.takeIf { it > 0 } }
        }
      }
    }
    val lanName = lanNetwork?.let { n ->
      cm.getNetworkCapabilities(n)?.let { caps ->
        when {
          caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
          caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
          else -> "LAN"
        }
      }
    }
    return HomeLinkStatus(
      state = state,
      vpnPermissionGranted = initialized && (if (foreignVpnActive()) vpnConsent else (VpnService.prepare(app) == null).also { vpnConsent = it }),
      tunnelUp = tunnelUp,
      holds = holds.toList(),
      detail = detail,
      endpoint = endpoint,
      publicKey = pub,
      rxBytes = rx,
      txBytes = tx,
      lastHandshakeMillis = hs,
      homeNetworkName = lanName,
    )
  }

  /** Round-trip to the home server over whatever link is active, or -1. */
  suspend fun probeHome(): Long = withContext(Dispatchers.IO) {
    val url = store.homeUrl ?: return@withContext -1L
    val t = SystemClock.elapsedRealtime()
    if (probe(clientFor(lanNetwork), url, 5)) SystemClock.elapsedRealtime() - t else -1L
  }

  // ---- the decision -----------------------------------------------------------------------------
  /**
   * LAN first, tunnel second. Idempotent: calling it while already linked just re-checks.
   * [timeoutMs] bounds the wait for the tunnel to become usable.
   */
  private suspend fun evaluate(timeoutMs: Long): HomeLinkStatus = mutex.withLock {
    if (!store.enabled) { state = HomeLinkState.DISABLED; return status() }
    if (!store.isConfigured) { state = HomeLinkState.UNCONFIGURED; return status() }
    val homeUrl = store.homeUrl!!

    // 1. Home network: server answers over a local Wi-Fi/Ethernet network -> no tunnel at all.
    if (store.lanDirect && SystemClock.elapsedRealtime() >= lanBlockedUntil) {
      val current = lanNetwork
      val home = if (current != null && probe(clientFor(current), homeUrl)) current else findHome(homeUrl)
      if (home != null) {
        if (tunnelUp) tunnelDown()
        bindLan(home)
        state = HomeLinkState.LAN; detail = null
        retryDelay = RETRY_MIN_MS; retryJob?.cancel()
        Log.i(TAG, "link: LAN direct via $home")
        return status()
      }
    }
    unbindLan()

    // 2. Away: per-app WireGuard tunnel.
    if (!hasInternetNetwork()) {
      tunnelDown()
      state = HomeLinkState.NO_NETWORK; detail = "No network"
      scheduleRetry()
      return status()
    }
    // Another app's VPN owns Android's single VPN slot: never take it away. On some ROMs (OnePlus)
    // even VpnService.prepare() re-assigns the slot to us, so it must not be called at all here.
    // If the server is reachable through that VPN, use it; otherwise wait for it to go away.
    if (foreignVpnActive()) {
      if (probe(clientFor(null), homeUrl)) {
        state = HomeLinkState.LAN; detail = "Through another VPN"
        retryDelay = RETRY_MIN_MS; retryJob?.cancel()
        Log.i(TAG, "link: another VPN is active and reaches home, using it")
      } else {
        state = HomeLinkState.ERROR; detail = "Another VPN is active, Home Link stays off"
        lastFailAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "another VPN is active, not taking the VPN slot")
        scheduleRetry()
      }
      return status()
    }
    if (VpnService.prepare(app) != null) {
      state = HomeLinkState.ERROR; detail = "VPN permission not granted"
      return status()
    }
    vpnConsent = true
    if (state == HomeLinkState.ERROR && SystemClock.elapsedRealtime() - lastFailAt < FAIL_BACKOFF_MS) return status()
    if (!tunnelUp) { state = HomeLinkState.CONNECTING; detail = null }
    try {
      tunnelUp(KEEPALIVE_ACTIVE)
    } catch (e: Exception) {
      Log.w(TAG, "tunnel up failed", e)
      tunnelUp = false; activeConfig = null
      state = HomeLinkState.ERROR; detail = "Tunnel failed: ${e.message ?: e.javaClass.simpleName}"
      lastFailAt = SystemClock.elapsedRealtime()
      scheduleRetry()
      return status()
    }
    val ok = withTimeoutOrNull(timeoutMs.coerceAtLeast(3_000)) {
      val client = clientFor(null)
      while (true) {
        if (probe(client, homeUrl, 3)) return@withTimeoutOrNull true
        delay(700)
      }
      @Suppress("UNREACHABLE_CODE") false
    } == true
    if (ok) {
      state = HomeLinkState.TUNNEL; detail = null
      retryDelay = RETRY_MIN_MS; retryJob?.cancel()
      Log.i(TAG, "link: tunnel")
    } else {
      state = HomeLinkState.ERROR; detail = "Home not reachable through the tunnel"
      lastFailAt = SystemClock.elapsedRealtime()
      Log.w(TAG, "tunnel up but home does not answer within ${timeoutMs}ms")
      if (!keepUpNow()) tunnelDown()
      scheduleRetry()
    }
    return status()
  }

  // ---- tunnel ------------------------------------------------------------------------------------
  private fun parseConfig(text: String, keepalive: Int): Config {
    val src = try {
      Config.parse(text.trim().byteInputStream())
    } catch (e: Exception) {
      throw IllegalArgumentException("WireGuard config: ${e.message ?: e.javaClass.simpleName}", e)
    }
    require(src.peers.isNotEmpty()) { "WireGuard config has no [Peer]" }
    val i = src.`interface`
    val ib = Interface.Builder()
      .setKeyPair(i.keyPair)
      .addAddresses(i.addresses)
      .addDnsServers(i.dnsServers)
      .addDnsSearchDomains(i.dnsSearchDomains)
      .setMtu(i.mtu.orElse(DEFAULT_MTU))
      .includeApplication(app.packageName)          // per-app: only Immich rides the tunnel
    val cb = Config.Builder().setInterface(ib.build())
    for (p in src.peers) {
      val pb = Peer.Builder().setPublicKey(p.publicKey).addAllowedIps(p.allowedIps).setPersistentKeepalive(keepalive)
      p.endpoint.ifPresent { pb.setEndpoint(it) }
      p.preSharedKey.ifPresent { pb.setPreSharedKey(it) }
      cb.addPeer(pb.build())
    }
    return cb.build()
  }

  /** Bring the tunnel up (or re-apply if the wanted config differs). Blocking; call off the main thread. */
  // Both run under NonCancellable: a cancelled network-settle job must never leave the backend half-applied.
  private suspend fun tunnelUp(keepalive: Int) = withContext(NonCancellable + Dispatchers.IO) {
    val cfg = parseConfig(store.wireguardConfig!!, keepalive)
    val text = cfg.toWgUserspaceString()
    if (tunnelUp && activeConfig == text && backend.getState(tunnel) == Tunnel.State.UP) return@withContext
    Log.i(TAG, "tunnel up (keepalive=$keepalive)")
    backend.setState(tunnel, Tunnel.State.UP, cfg)
    activeConfig = text
    tunnelUp = true
  }

  private suspend fun tunnelDown() = withContext(NonCancellable + Dispatchers.IO) {
    if (!tunnelUp && activeConfig == null) return@withContext
    Log.i(TAG, "tunnel down")
    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }.onFailure { Log.w(TAG, "tunnel down failed", it) }
    tunnelUp = false
    activeConfig = null
  }

  private suspend fun stats(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
    runCatching { backend.getStatistics(tunnel).let { it.totalRx() to it.totalTx() } }.getOrNull()
  }

  // ---- LAN ---------------------------------------------------------------------------------------
  private fun localCandidates(): List<Network> = cm.allNetworks.filter { n ->
    cm.getNetworkCapabilities(n)?.let { c ->
      (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
        !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } == true
  }

  /** A VPN network exists and it is not ours (Android allows a single VPN, so if ours is up it is the only one). */
  private fun foreignVpnActive(): Boolean = !tunnelUp && cm.allNetworks.any { n ->
    cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
  }

  private fun hasInternetNetwork(): Boolean = cm.allNetworks.any { n ->
    cm.getNetworkCapabilities(n)?.let { c ->
      c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } == true
  }

  /** First local network on which the home server answers the ping endpoint. */
  private suspend fun findHome(homeUrl: String): Network? {
    for (n in localCandidates()) if (probe(clientFor(n), homeUrl)) return n
    return null
  }

  private fun clientFor(n: Network?): OkHttpClient {
    val b = HttpClientManager.getClient().newBuilder()
      .cache(null)
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(3, TimeUnit.SECONDS)
    if (n != null) {
      b.socketFactory(n.socketFactory)
      b.dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = n.getAllByName(hostname).toList() })
    }
    return b.build()
  }

  private fun pingUrl(homeUrl: String): String {
    val base = homeUrl.trim().trimEnd('/').removeSuffix("/api")
    return "$base/api/server/ping"
  }

  private suspend fun probe(client: OkHttpClient, homeUrl: String, timeoutSec: Long = 3): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
        .newCall(Request.Builder().url(pingUrl(homeUrl)).header("Cache-Control", "no-store").build())
        .execute().use { r -> r.isSuccessful && (r.body?.string()?.contains("pong") == true) }
    }.getOrElse { false }
  }

  /**
   * Bind the whole process to the home network when it is not the default route (home Wi-Fi without
   * internet is exactly when Android would otherwise send us out over mobile data).
   */
  private suspend fun bindLan(n: Network) = withContext(Dispatchers.IO) {
    if (lanNetwork == n) return@withContext
    lanNetwork = n
    val bind = cm.activeNetwork != n
    runCatching { cm.bindProcessToNetwork(if (bind) n else null) }
    runCatching { HttpClientManager.getClient().connectionPool.evictAll() }
    Log.i(TAG, "LAN bound (processBound=$bind)")
  }

  private suspend fun unbindLan() = withContext(Dispatchers.IO) {
    if (lanNetwork == null) return@withContext
    lanNetwork = null
    runCatching { cm.bindProcessToNetwork(null) }
    runCatching { HttpClientManager.getClient().connectionPool.evictAll() }
  }

  // ---- idle policy -------------------------------------------------------------------------------
  private fun scheduleIdle() {
    idleJob?.cancel()
    idleJob = scope.launch {
      delay(IDLE_GRACE_MS)
      while (holds.isEmpty()) {
        if (!tunnelUp) {
          mutex.withLock { if (holds.isEmpty() && state != HomeLinkState.LAN && isEnabled) { state = HomeLinkState.IDLE; detail = null } }
          return@launch
        }
        // On battery nothing of ours may keep running once the app is off screen: drop the tunnel
        // now, even mid-transfer (the upload resumes on the next open or the next charge).
        // Only while charging do we wait for an in-flight transfer to finish.
        if (!isCharging()) {
          mutex.withLock {
            if (holds.isEmpty()) {
              Log.i(TAG, "idle: app off screen and on battery -> tunnel down")
              tunnelDown()
              state = HomeLinkState.IDLE; detail = null
            }
          }
          return@launch
        }
        if (store.keepUpWhenAway) {   // only reached on the charger
          // Stay up, but stop the keepalive packets so an idle phone sends nothing.
          mutex.withLock { if (holds.isEmpty()) runCatching { tunnelUp(KEEPALIVE_IDLE) } }
          return@launch
        }
        val before = stats()
        delay(IDLE_TRAFFIC_WINDOW_MS)
        val after = stats()
        if (before == null || after == null || before == after) {
          mutex.withLock {
            if (holds.isEmpty()) {
              Log.i(TAG, "idle: no traffic for ${IDLE_TRAFFIC_WINDOW_MS}ms -> tunnel down")
              tunnelDown()
              state = HomeLinkState.IDLE; detail = null
            }
          }
          return@launch
        }
        Log.i(TAG, "idle: traffic still moving, keeping the tunnel")
      }
    }
  }

  /** "Keep tunnel up when away" is honoured only on the charger: on battery nothing runs in the background. */
  private fun keepUpNow(): Boolean = store.keepUpWhenAway && isCharging()

  /** A charger is connected (not BatteryManager.isCharging: a charge limit reports "not charging" while plugged). */
  private fun isCharging(): Boolean = runCatching {
    val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
  }.getOrDefault(false)

  /** After a failure, keep trying (30 s, 60 s, … 5 min) as long as something still wants the link. */
  private fun scheduleRetry() {
    retryJob?.cancel()
    val wait = retryDelay
    retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
    retryJob = scope.launch {
      delay(wait)
      if (holds.isEmpty() && !keepUpNow()) return@launch
      if (state != HomeLinkState.ERROR && state != HomeLinkState.NO_NETWORK) return@launch
      Log.i(TAG, "retrying the link after ${wait / 1000}s")
      lastFailAt = 0L
      runCatching { evaluate(20_000) }
    }
  }

  // ---- connectivity changes ------------------------------------------------------------------------
  private fun registerNetworkCallbacks() {
    val cb = object : ConnectivityManager.NetworkCallback() {
      // Our own tunnel coming up/down is a VPN network: never a reason to re-evaluate.
      private fun isVpn(n: Network) = cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
      private val seen = ConcurrentHashMap.newKeySet<Network>()
      override fun onAvailable(network: Network) {
        if (isVpn(network)) return
        // The underlying network is re-announced as default every time our tunnel goes down: not a change.
        if (!seen.add(network)) return
        onNetworkChanged("available $network")
      }
      override fun onLost(network: Network) {
        if (!seen.remove(network)) return   // a VPN network (or one we never saw)
        if (network == lanNetwork) scope.launch { mutex.withLock { unbindLan() } }
        onNetworkChanged("lost $network")
      }
    }
    runCatching { cm.registerDefaultNetworkCallback(cb) }
    runCatching {
      cm.registerNetworkCallback(
        NetworkRequest.Builder()
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
          .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
          .build(), cb
      )
    }
  }

  /** Charger removed with nobody holding the link: the on-battery idle policy applies right away. */
  private fun registerPowerCallbacks() {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_DISCONNECTED || !isEnabled) return
        if (holds.isEmpty() && tunnelUp) {
          Log.i(TAG, "charger disconnected, nobody holds the link -> idle policy")
          scheduleIdle()
        }
      }
    }
    runCatching {
      ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }.onFailure { Log.w(TAG, "power receiver not registered", it) }
  }

  private fun onNetworkChanged(why: String) {
    if (!isEnabled) return
    val wanted = holds.isNotEmpty() || keepUpNow()
    if (!wanted) return
    settleJob?.cancel()   // only the debounce delay is cancellable; the evaluation itself runs to completion
    settleJob = scope.launch {
      delay(NETWORK_SETTLE_MS)
      scope.launch { reevaluate(why) }
    }
  }

  private suspend fun reevaluate(why: String) {
      Log.i(TAG, "network change ($why), re-evaluating")
      lastFailAt = 0L
      retryDelay = RETRY_MIN_MS
      // LAN reached through another app's VPN has no bound network: nothing to flap-guard there.
      val onLan = state == HomeLinkState.LAN && lanNetwork != null
      if (onLan) {
        val still = lanNetwork?.let { probe(clientFor(it), store.homeUrl!!) } == true
        if (still) return
        Log.i(TAG, "home network gone -> tunnel (LAN blocked for ${LAN_FLAP_GUARD_MS / 1000}s)")
        lanBlockedUntil = SystemClock.elapsedRealtime() + LAN_FLAP_GUARD_MS
        guardJob?.cancel()
        guardJob = scope.launch {
          delay(LAN_FLAP_GUARD_MS)
          if (holds.isNotEmpty() || keepUpNow()) runCatching { evaluate(15_000) }
        }
      }
      runCatching { evaluate(15_000) }.onFailure { Log.w(TAG, "re-evaluate failed", it) }
  }
}
