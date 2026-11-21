package app.alextran.immich.background

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Runs once the phone is on a charger (JobScheduler constraint) and kicks off the upload worker. */
class ChargeTriggerWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  private val ctx: Context = context.applicationContext

  override fun doWork(): Result {
    // The CHARGING constraint follows the battery status, the upload worker checks the plug. When they
    // disagree (status still "charging" with no charger plugged, e.g. right after unplugging), starting the
    // worker makes it re-arm this trigger at once and the two loop until JobScheduler's quota runs out.
    // Retry instead: the trigger stays armed for the next real plug-in and a lasting mismatch backs off.
    if (!BackgroundWorkerApiImpl.isPluggedIn(ctx)) {
      Log.i("ChargeTrigger", "Charging reported but no charger is plugged in, waiting")
      return Result.retry()
    }
    Log.i("ChargeTrigger", "Charger connected, starting background worker")
    BackgroundWorkerApiImpl.enqueueBackgroundWorker(ctx)
    return Result.success()
  }
}
