package app.alextran.immich

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.alextran.immich.background.BackgroundEngineLock
import app.alextran.immich.background.BackgroundWorkerApiImpl
import app.alextran.immich.homelink.HomeLinkEngine

class ImmichApp : Application() {
  override fun onCreate() {
    super.onCreate()
    HomeLinkEngine.init(this)
    // The tunnel is wanted while any of our screens is visible, and let go once none is.
    registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
      private var visible = 0
      override fun onActivityResumed(activity: Activity) { if (visible++ == 0) HomeLinkEngine.foreground(true) }
      override fun onActivityPaused(activity: Activity) { if (--visible <= 0) { visible = 0; HomeLinkEngine.foreground(false) } }
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
    // After the process is killed (by user or system), the first trigger (taking a new picture) is lost.
    // Thus, the BackupWorker is not started. If the system kills the process after each initialization
    // (because of low memory etc.), the backup is never performed.
    // As a workaround, we also run a backup check when initializing the application
    Handler(Looper.getMainLooper()).postDelayed({
      // We can only check the engine count and not the status of the lock here,
      // as the previous start might have been killed without unlocking.
      if (BackgroundEngineLock.connectEngines > 0) return@postDelayed
      BackgroundWorkerApiImpl.enqueueBackgroundWorker(this)
    }, 15000)
  }
}
