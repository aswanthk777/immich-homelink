package app.alextran.immich.background

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Runs once the phone is on a charger (JobScheduler constraint) and kicks off the upload worker. */
class ChargeTriggerWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  private val ctx: Context = context.applicationContext

  override fun doWork(): Result {
    Log.i("ChargeTrigger", "Charger connected, starting background worker")
    BackgroundWorkerApiImpl.enqueueBackgroundWorker(ctx)
    return Result.success()
  }
}
