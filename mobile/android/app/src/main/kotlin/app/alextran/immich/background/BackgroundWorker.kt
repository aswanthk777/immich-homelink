package app.alextran.immich.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.alextran.immich.MainActivity
import app.alextran.immich.R
import app.alextran.immich.homelink.HomeLinkEngine
import app.alextran.immich.homelink.HomeLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import java.util.concurrent.TimeUnit

private const val TAG = "BackgroundWorker"

class BackgroundWorker(context: Context, params: WorkerParameters) :
  ListenableWorker(context, params), BackgroundWorkerBgHostApi {
  private val ctx: Context = context.applicationContext

  /// The Flutter loader that loads the native Flutter library and resources.
  /// This must be initialized before starting the Flutter engine.
  private var loader: FlutterLoader = FlutterInjector.instance().flutterLoader()

  /// The Flutter engine created specifically for background execution.
  /// This is a separate instance from the main Flutter engine that handles the UI.
  /// It operates in its own isolate and doesn't share memory with the main engine.
  /// Must be properly started, registered, and torn down during background execution.
  private var engine: FlutterEngine? = null

  // Used to call methods on the flutter side
  private var flutterApi: BackgroundWorkerFlutterApi? = null

  /// Result returned when the background task completes. This is used to signal
  /// to the WorkManager that the task has finished, either successfully or with failure.
  private val completionHandler: SettableFuture<Result> = SettableFuture.create()

  /// Flag to track whether the background task has completed to prevent duplicate completions
  private var isComplete = false

  private val notificationManager =
    ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private var foregroundFuture: ListenableFuture<Void>? = null

  private val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var requiresCharging = false
  private var unplugReceiver: BroadcastReceiver? = null

  /** Unplugged mid-backup: stop right away (tunnel included) and re-arm the plug-in trigger. */
  private fun registerUnplugReceiver() {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_DISCONNECTED || isComplete) return
        Log.i(TAG, "Charger disconnected, stopping the backup until the next charge")
        BackgroundWorkerApiImpl.enqueueChargeTrigger(ctx)
        close()
      }
    }
    ContextCompat.registerReceiver(ctx, receiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
    unplugReceiver = receiver
  }

  private fun unregisterUnplugReceiver() {
    unplugReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
    unplugReceiver = null
  }

  companion object {
    private const val NOTIFICATION_CHANNEL_ID = "immich::background_worker::notif"
    private const val NOTIFICATION_ID = 100
  }

  override fun startWork(): ListenableFuture<Result> {
    if (BackgroundWorkerPreferences(ctx).isLocked() && BackgroundEngineLock.connectEngines > 0) {
      Log.i(TAG, "Foreground engine active, skipping background worker")
      return Futures.immediateFuture(Result.success())
    }

    Log.i(TAG, "Starting background upload worker")

    // "Only while charging": nothing runs on battery. Arm the plug-in trigger and go back to sleep.
    requiresCharging = BackgroundWorkerPreferences(ctx).getSettings().requiresCharging
    if (requiresCharging && !BackgroundWorkerApiImpl.isPluggedIn(ctx)) {
      Log.i(TAG, "Phone is on battery, backup waits for the charger")
      BackgroundWorkerApiImpl.enqueueChargeTrigger(ctx)
      return Futures.immediateFuture(Result.success())
    }
    if (requiresCharging) registerUnplugReceiver()
    BackgroundWorkerPreferences(ctx).setLastBackgroundRun(System.currentTimeMillis())

    if (!loader.initialized()) {
      loader.startInitialization(ctx)
    }

    val notificationChannel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      ctx.getString(R.string.background_worker_notification_channel_name),
      NotificationManager.IMPORTANCE_LOW
    )
    notificationManager.createNotificationChannel(notificationChannel)
    val notificationConfig = BackgroundWorkerPreferences(ctx).getNotificationConfig()
    showNotification(notificationConfig.first, notificationConfig.second)

    // Home Link: reach the server first (home network, else the per-app tunnel) so the whole
    // Flutter side just sees a reachable server. No link -> retry later with backoff.
    if (HomeLinkEngine.isEnabled) {
      linkScope.launch {
        waitForForegroundPromotion(3000)
        val status = runCatching { HomeLinkEngine.acquire("bg", 30_000) }.getOrNull()
        Log.i(TAG, "Home Link for background worker: ${status?.state} ${status?.detail ?: ""}")
        val usable = status != null && (status.state == HomeLinkState.LAN || status.state == HomeLinkState.TUNNEL ||
          // VPN consent missing: nothing to wait for, let the upload fail fast on its own
          (status.state == HomeLinkState.ERROR && status.detail?.contains("permission") == true))
        Handler(Looper.getMainLooper()).post {
          if (isStopped || isComplete) return@post
          if (usable) startFlutterEngine() else complete(Result.retry())
        }
      }
    } else {
      startFlutterEngine()
    }

    return completionHandler
  }

  private fun startFlutterEngine() {
    loader.ensureInitializationCompleteAsync(ctx, null, Handler(Looper.getMainLooper())) {
      if (isStopped || isComplete) {
        return@ensureInitializationCompleteAsync
      }

      engine = FlutterEngine(ctx)
      FlutterEngineCache.getInstance().put(BackgroundWorkerApiImpl.ENGINE_CACHE_KEY, engine!!)

      // Register custom plugins
      MainActivity.registerPlugins(ctx, engine!!)
      flutterApi =
        BackgroundWorkerFlutterApi(binaryMessenger = engine!!.dartExecutor.binaryMessenger)
      BackgroundWorkerBgHostApi.setUp(
        binaryMessenger = engine!!.dartExecutor.binaryMessenger,
        api = this
      )

      engine!!.dartExecutor.executeDartEntrypoint(
        DartExecutor.DartEntrypoint(
          loader.findAppBundlePath(),
          "package:immich_mobile/domain/services/background_worker.service.dart",
          "backgroundSyncNativeEntrypoint"
        )
      )
    }
  }

  /**
   * Called by the Flutter side when it has finished initialization and is ready to receive commands.
   * Routes the appropriate task type (refresh or processing) to the corresponding Flutter method.
   * This method acts as a bridge between the native Android background task system and Flutter.
   */
  override fun onInitialized() {
    flutterApi?.onAndroidUpload(maxMinutesArg = 20) { handleHostResult(it) }
  }

  // TODO: Move this to a separate NotificationManager class
  private fun showNotification(title: String, content: String) {
    val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.drawable.notification_icon)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setTicker(title)
      .setContentTitle(title)
      .setContentText(content)
      .build()

    if (isIgnoringBatteryOptimizations()) {
      foregroundFuture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        setForegroundAsync(
          ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_DATA_SYNC
          )
        )
      } else {
        setForegroundAsync(ForegroundInfo(NOTIFICATION_ID, notification))
      }
    } else {
      notificationManager.notify(NOTIFICATION_ID, notification)
    }
  }

  override fun close() {
    if (isComplete) {
      return
    }

    val api = flutterApi
    if (api == null) {
      Handler(Looper.getMainLooper()).postAtFrontOfQueue {
        complete(Result.failure())
      }
      return
    }

    Handler(Looper.getMainLooper()).postAtFrontOfQueue {
      api.cancel {
        complete(Result.failure())
      }
    }

    waitForForegroundPromotion()

    Handler(Looper.getMainLooper()).postDelayed({
      complete(Result.failure())
    }, 5000)
  }

  /**
   * Called when the system has to stop this worker because constraints are
   * no longer met or the system needs resources for more important tasks
   * This is also called when the worker has been explicitly cancelled or replaced
   */
  override fun onStopped() {
    Log.d(TAG, "About to stop BackupWorker")
    close()
  }

  private fun handleHostResult(result: kotlin.Result<Unit>) {
    if (isComplete) {
      return
    }

    result.fold(
      onSuccess = { _ -> complete(Result.success()) },
      onFailure = { _ -> onStopped() }
    )
  }

  /**
   * Cleans up resources by destroying the Flutter engine context and invokes the completion handler.
   * This method ensures that the background task is marked as complete, releases the Flutter engine,
   * and notifies the caller of the task's success or failure. This is the final step in the
   * background task lifecycle and should only be called once per task instance.
   *
   * - Parameter success: Indicates whether the background task completed successfully
   */
  private fun complete(success: Result) {
    Log.d(TAG, "About to complete BackupWorker with result: $success")
    isComplete = true
    if (engine != null) {
      MainActivity.cancelPlugins(engine!!)
    }
    engine?.destroy()
    engine = null
    flutterApi = null
    notificationManager.cancel(NOTIFICATION_ID)
    FlutterEngineCache.getInstance().remove(BackgroundWorkerApiImpl.ENGINE_CACHE_KEY)
    waitForForegroundPromotion()
    HomeLinkEngine.release("bg")
    HomeLinkEngine.release("app")
    linkScope.cancel()
    unregisterUnplugReceiver()
    completionHandler.set(success)
  }

  /**
   * Returns `true` if the app is ignoring battery optimizations
   */
  private fun isIgnoringBatteryOptimizations(): Boolean {
    val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(ctx.packageName)
  }

  /**
   *  Calls to setForegroundAsync() that do not complete before completion of a ListenableWorker will signal an IllegalStateException
   * https://android-review.googlesource.com/c/platform/frameworks/support/+/1262743
   * Wait for a short period of time for the foreground promotion to complete before completing the worker
   */
  private fun waitForForegroundPromotion(timeoutMs: Long = 500) {
    val foregroundFuture = this.foregroundFuture
    if (foregroundFuture != null && !foregroundFuture.isCancelled && !foregroundFuture.isDone) {
      try {
        foregroundFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: Exception) {
        // ignored, there is nothing to be done
      }
    }
  }
}
