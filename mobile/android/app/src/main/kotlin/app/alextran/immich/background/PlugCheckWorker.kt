package app.alextran.immich.background

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Fallback for "only while charging" that does not depend on JobScheduler's charging flag.
 *
 * JobScheduler only considers a phone "charging" once BatteryStats says so, and on some phones
 * (OnePlus, Android 16) that flag stays false for a long time even on a fast charger, so the
 * charge trigger, the hourly job and the media observer all sit idle. This unconstrained 15-minute
 * job looks at the plug state itself: on the charger, and no upload run in the last 30 minutes,
 * it starts the upload worker. On battery it does nothing and costs a few milliseconds; Doze
 * defers it to maintenance windows anyway.
 */
class PlugCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  private val ctx: Context = context.applicationContext

  override fun doWork(): Result {
    val prefs = BackgroundWorkerPreferences(ctx)
    if (!prefs.getSettings().requiresCharging) return Result.success()
    if (!BackgroundWorkerApiImpl.isPluggedIn(ctx)) return Result.success()
    val since = System.currentTimeMillis() - prefs.getLastBackgroundRun()
    if (since < MIN_INTERVAL_MS) {
      Log.i(TAG, "On charger, last upload run ${since / 60000} min ago, nothing to do")
      return Result.success()
    }
    Log.i(TAG, "On charger and no upload run for ${since / 60000} min, starting background worker")
    BackgroundWorkerApiImpl.enqueueBackgroundWorker(ctx)
    return Result.success()
  }

  companion object {
    private const val TAG = "PlugCheck"
    private const val MIN_INTERVAL_MS = 30 * 60_000L
  }
}
