package app.alextran.immich.homelink

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.crypto.KeyPair
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Pigeon host for Home Link. Activity-aware only for the VPN consent dialog and the QR scanner. */
class HomeLinkApiImpl : HomeLinkHostApi, FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener {
  private var activity: Activity? = null
  private var binding: ActivityPluginBinding? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val main = Handler(Looper.getMainLooper())
  private var vpnCallback: ((Result<Boolean>) -> Unit)? = null
  private var qrCallback: ((Result<String?>) -> Unit)? = null

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    HomeLinkEngine.init(binding.applicationContext)
    HomeLinkHostApi.setUp(binding.binaryMessenger, this)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    HomeLinkHostApi.setUp(binding.binaryMessenger, null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    this.binding = binding
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
  override fun onDetachedFromActivity() {
    binding?.removeActivityResultListener(this)
    binding = null
    activity = null
  }

  private fun <T> post(cb: (Result<T>) -> Unit, r: Result<T>) = main.post { cb(r) }

  override fun getStatus(): HomeLinkStatus = HomeLinkEngine.status()

  override fun getConfig(): HomeLinkConfig = HomeLinkEngine.store.toConfig()

  override fun setConfig(config: HomeLinkConfig, callback: (Result<Unit>) -> Unit) {
    scope.launch { post(callback, runCatching { HomeLinkEngine.setConfig(config) }) }
  }

  override fun ensureLink(holder: String, timeoutMs: Long, callback: (Result<HomeLinkStatus>) -> Unit) {
    scope.launch { post(callback, runCatching { HomeLinkEngine.acquire(holder, timeoutMs) }) }
  }

  override fun releaseLink(holder: String) = HomeLinkEngine.release(holder)

  override fun disconnect(callback: (Result<Unit>) -> Unit) {
    scope.launch { post(callback, runCatching { HomeLinkEngine.disconnect() }) }
  }

  override fun requestVpnPermission(callback: (Result<Boolean>) -> Unit) {
    val act = activity ?: return callback(Result.failure(IllegalStateException("No activity")))
    val intent = VpnService.prepare(act) ?: return callback(Result.success(true))
    vpnCallback?.invoke(Result.success(false))
    vpnCallback = callback
    try {
      act.startActivityForResult(intent, REQ_VPN)
    } catch (e: Exception) {
      vpnCallback = null
      callback(Result.failure(e))
    }
  }

  override fun scanQrCode(callback: (Result<String?>) -> Unit) {
    val act = activity ?: return callback(Result.failure(IllegalStateException("No activity")))
    qrCallback?.invoke(Result.success(null))
    qrCallback = callback
    val intent = ScanOptions()
      .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
      .setPrompt("Scan the WireGuard config QR")
      .setBeepEnabled(false)
      .setOrientationLocked(true)
      .setCaptureActivity(PortraitCaptureActivity::class.java)
      .createScanIntent(act)
    try {
      act.startActivityForResult(intent, REQ_QR)
    } catch (e: Exception) {
      qrCallback = null
      callback(Result.failure(e))
    }
  }

  override fun generateKeyPair(): String = KeyPair().let { "${it.privateKey.toBase64()}\n${it.publicKey.toBase64()}" }

  override fun probeHome(callback: (Result<Long>) -> Unit) {
    scope.launch { post(callback, runCatching { HomeLinkEngine.probeHome() }) }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    when (requestCode) {
      REQ_VPN -> {
        val cb = vpnCallback ?: return false
        vpnCallback = null
        cb(Result.success(resultCode == Activity.RESULT_OK))
        return true
      }
      REQ_QR -> {
        val cb = qrCallback ?: return false
        qrCallback = null
        val text = ScanIntentResult.parseActivityResult(resultCode, data).contents
        cb(Result.success(text))
        return true
      }
    }
    return false
  }

  companion object {
    private const val REQ_VPN = 0x4857
    private const val REQ_QR = 0x4858
  }
}
