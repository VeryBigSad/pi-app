package io.github.verybigsad.pimobile.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Terminal poison codes: retrying can never help; the worker must fail, not retry. */
object UpdateCheckPolicy {
    val terminalCodes: Set<String> = setOf(
        UpdateError.DISABLED,
        UpdateError.METADATA_INVALID,
        UpdateError.METADATA_TOO_LARGE,
        UpdateError.METADATA_UNTRUSTED,
        UpdateError.SIGNATURE_MISMATCH,
        UpdateError.SIGNATURE_UNREADABLE,
        UpdateError.AUTHORIZATION_MISMATCH,
        UpdateError.NOT_VERIFIED,
    )

    fun isTerminal(code: String): Boolean = code in terminalCodes
}

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val manager = UpdateManagerHolder.get(applicationContext) ?: return Result.success()
        return try {
            when (val state = manager.checkForUpdate()) {
                is UpdateState.Failed -> when {
                    UpdateCheckPolicy.isTerminal(state.code) -> Result.failure()
                    state.code == UpdateError.METADATA_FETCH_FAILED -> Result.retry()
                    else -> Result.success()
                }
                else -> Result.success()
            }
        } catch (error: UpdateException) {
            if (UpdateCheckPolicy.isTerminal(error.code)) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "update-check-periodic"
        const val UNIQUE_EXPEDITED = "update-check-expedited"
    }
}

class ApkDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val manager = UpdateManagerHolder.get(applicationContext) ?: return Result.failure()
        val expected = inputData.getLong(KEY_VERSION_CODE, -1L)
        if (expected <= 0L) return Result.failure()
        return manager.runDownload(expected)
    }

    companion object {
        const val UNIQUE = "update-download"
        const val KEY_VERSION_CODE = "versionCode"

        fun inputData(versionCode: Long): Data = workDataOf(KEY_VERSION_CODE to versionCode)
    }
}

/** WorkManager wiring: 24h periodic with 6h flex, battery-not-low; expedited on-demand checks. */
object UpdateScheduler {
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UpdateCheckWorker.UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueExpeditedCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UpdateCheckWorker.UNIQUE_EXPEDITED, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueDownload(context: Context, versionCode: Long) {
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(ApkDownloadWorker.inputData(versionCode))
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ApkDownloadWorker.UNIQUE, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ApkDownloadWorker.UNIQUE)
    }
}

/** Process-scoped holder so workers can reach the app-composed manager. */
object UpdateManagerHolder {
    @Volatile
    var manager: UpdateManager? = null

    fun get(context: Context): UpdateManager? = manager
}
