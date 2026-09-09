package app.alextran.immich.background

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.engine.FlutterEngineCache
import java.util.concurrent.TimeUnit

private const val TAG = "BackgroundWorkerApiImpl"

class BackgroundWorkerApiImpl(context: Context) : BackgroundWorkerFgHostApi {
  private val ctx: Context = context.applicationContext

  override fun enable() {
    enqueueMediaObserver(ctx)
    enqueuePeriodicWorker(ctx)
  }

  override fun saveNotificationMessage(title: String, body: String) {
    BackgroundWorkerPreferences(ctx).updateNotificationConfig(title, body)
  }

  override fun configure(settings: BackgroundWorkerSettings) {
    BackgroundWorkerPreferences(ctx).updateSettings(settings)
    enqueueMediaObserver(ctx)
    enqueuePeriodicWorker(ctx)
    // A pending upload worker keeps the constraints it was enqueued with (KEEP policy): drop a
    // stale one (unless it is running) and, with "only while charging", arm the plug-in trigger.
    if (!isBackgroundWorkerRunning()) {
      WorkManager.getInstance(ctx).cancelUniqueWork(BACKGROUND_WORKER_NAME)
    }
    if (settings.requiresCharging) {
      enqueueChargeTrigger(ctx)
      enqueuePlugCheck(ctx)
    } else {
      cancelChargeTrigger(ctx)
      WorkManager.getInstance(ctx).cancelUniqueWork(PLUG_CHECK_NAME)
    }
  }

  override fun disable() {
    WorkManager.getInstance(ctx).apply {
      cancelUniqueWork(OBSERVER_WORKER_NAME)
      cancelUniqueWork(BACKGROUND_WORKER_NAME)
      cancelUniqueWork(PERIODIC_WORKER_NAME)
      cancelUniqueWork(CHARGE_TRIGGER_NAME)
      cancelUniqueWork(PLUG_CHECK_NAME)
    }
    Log.i(TAG, "Cancelled background upload tasks")
  }

  companion object {
    private const val BACKGROUND_WORKER_NAME = "immich/BackgroundWorkerV1"
    private const val OBSERVER_WORKER_NAME = "immich/MediaObserverV1"
    private const val PERIODIC_WORKER_NAME = "immich/PeriodicBackgroundWorkerV1"
    private const val CHARGE_TRIGGER_NAME = "immich/ChargeTriggerV1"
    private const val PLUG_CHECK_NAME = "immich/PlugCheckV1"
    const val ENGINE_CACHE_KEY = "immich::background_worker::engine"


    fun enqueueMediaObserver(ctx: Context) {
      val settings = BackgroundWorkerPreferences(ctx).getSettings()
      val constraints = Constraints.Builder().apply {
        addContentUriTrigger(MediaStore.Images.Media.INTERNAL_CONTENT_URI, true)
        addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
        addContentUriTrigger(MediaStore.Video.Media.INTERNAL_CONTENT_URI, true)
        addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        setTriggerContentUpdateDelay(settings.minimumDelaySeconds, TimeUnit.SECONDS)
        setTriggerContentMaxDelay(settings.minimumDelaySeconds * 10, TimeUnit.SECONDS)
        setRequiresCharging(settings.requiresCharging)
      }.build()

      val work = OneTimeWorkRequestBuilder<MediaObserver>()
        .setConstraints(constraints)
        .build()
      WorkManager.getInstance(ctx)
        .enqueueUniqueWork(OBSERVER_WORKER_NAME, ExistingWorkPolicy.REPLACE, work)

      Log.i(
        TAG,
        "Enqueued media observer worker with name: $OBSERVER_WORKER_NAME and settings: $settings"
      )
    }

    fun enqueuePeriodicWorker(ctx: Context) {
      val settings = BackgroundWorkerPreferences(ctx).getSettings()
      val constraints = Constraints.Builder().apply {
        setRequiresCharging(settings.requiresCharging)
      }.build()

      val work =
        PeriodicWorkRequestBuilder<PeriodicWorker>(
          1,
          TimeUnit.HOURS,
          15,
          TimeUnit.MINUTES
        ).setConstraints(constraints)
          .build()

      WorkManager.getInstance(ctx)
        .enqueueUniquePeriodicWork(PERIODIC_WORKER_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)

      Log.i(TAG, "Enqueued periodic background worker with name: $PERIODIC_WORKER_NAME")
    }

    fun enqueueBackgroundWorker(ctx: Context) {
      // No WorkManager charging constraint here on purpose: phones with a charge limit (OnePlus
      // stops at 90 %) report "not charging" while plugged in, and WorkManager's in-process
      // tracker would then kill the worker the moment it is promoted to a foreground service,
      // even though JobScheduler (which counts "plugged and not draining" as charging) started it.
      // "Only while charging" is enforced by the worker itself (plugged-in check + unplug
      // receiver) and plug-in wake-ups come from the charge trigger below.
      val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
      val work = OneTimeWorkRequestBuilder<BackgroundWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
      WorkManager.getInstance(ctx)
        .enqueueUniqueWork(BACKGROUND_WORKER_NAME, ExistingWorkPolicy.KEEP, work)

      Log.i(TAG, "Enqueued background worker with name: $BACKGROUND_WORKER_NAME")
    }

    /**
     * Sits in JobScheduler until the phone is on a charger (JobScheduler's notion survives a
     * charge limit), then starts the upload worker. Armed whenever an upload was skipped or cut
     * short because the phone was on battery, so plugging in is what triggers the backup.
     */
    fun enqueueChargeTrigger(ctx: Context) {
      val constraints = Constraints.Builder().setRequiresCharging(true).build()
      val work = OneTimeWorkRequestBuilder<ChargeTriggerWorker>().setConstraints(constraints).build()
      WorkManager.getInstance(ctx).enqueueUniqueWork(CHARGE_TRIGGER_NAME, ExistingWorkPolicy.KEEP, work)
      Log.i(TAG, "Armed charge trigger: $CHARGE_TRIGGER_NAME")
    }

    /** Unconstrained 15-minute plug-state check, see [PlugCheckWorker]. */
    fun enqueuePlugCheck(ctx: Context) {
      val work = PeriodicWorkRequestBuilder<PlugCheckWorker>(15, TimeUnit.MINUTES).build()
      WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PLUG_CHECK_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun cancelChargeTrigger(ctx: Context) {
      WorkManager.getInstance(ctx).cancelUniqueWork(CHARGE_TRIGGER_NAME)
    }

    /** True when a charger is connected, whatever the battery is doing (charge limits report "not charging"). */
    fun isPluggedIn(ctx: Context): Boolean {
      val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
      return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun isBackgroundWorkerRunning(): Boolean {
      // Easier to check if the engine is cached as we always cache the engine when starting the worker
      // and remove it when the worker is finished
      return FlutterEngineCache.getInstance().get(ENGINE_CACHE_KEY) != null
    }

    fun cancelBackgroundWorker(ctx: Context) {
      WorkManager.getInstance(ctx).cancelUniqueWork(BACKGROUND_WORKER_NAME)
      FlutterEngineCache.getInstance().remove(ENGINE_CACHE_KEY)

      Log.i(TAG, "Cancelled background upload task")
    }
  }
}
