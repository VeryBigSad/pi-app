package io.github.verybigsad.pimobile.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

object WakeWorkScheduler {
    fun enqueue(context: Context, wakeId: OpaqueWakeId): UUID {
        val request = OneTimeWorkRequestBuilder<WakeReconnectWorker>()
            .setInputData(workDataOf(WakeReconnectWorker.INPUT_WAKE_ID to wakeId.value))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WakeWorkNames.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    private const val MIN_BACKOFF_SECONDS = 30L
    private const val WORK_TAG = "pi-push-reconnect"
}

class WakeReconnectWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        PushNotificationChannels.create(applicationContext)
        val parsed = inputData.getString(INPUT_WAKE_ID)?.let(OpaqueWakePayload::parse)
            ?: WakePayloadParseResult.Invalid(WakePayloadInvalidReason.EMPTY)
        if (parsed is WakePayloadParseResult.Invalid) {
            UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.InvalidPayload(parsed.reason))
            return Result.failure(workDataOf(OUTPUT_ERROR_CODE to parsed.reason.name))
        }
        parsed as WakePayloadParseResult.Valid
        PushRuntimeInitializer.ensureRestored(applicationContext)
        val receipts = WakeReceiptStore(applicationContext)
        if (runCatching { receipts.contains(parsed.wakeId) }.getOrDefault(false)) {
            UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.DuplicateSuppressed)
            return Result.success()
        }

        UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.Reconnecting)
        val reconnectResult = try {
            withTimeout(RECONNECT_TIMEOUT_MILLIS) {
                UnifiedPushRuntime.reconnect(parsed.wakeId)
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return when (reconnectResult) {
            WakeReconnectResult.COMPLETED -> complete(receipts, parsed.wakeId, UnifiedPushDeliveryState.CatchUpCompleted)
            WakeReconnectResult.REJECTED -> complete(receipts, parsed.wakeId, UnifiedPushDeliveryState.WakeRejected)
            WakeReconnectResult.RETRY, null -> retryOrExhaust()
        }
    }

    private fun complete(
        receipts: WakeReceiptStore,
        wakeId: OpaqueWakeId,
        state: UnifiedPushDeliveryState,
    ): Result = runCatching {
        receipts.record(wakeId)
    }.fold(
        onSuccess = {
            UnifiedPushRuntime.updateDelivery(state)
            Result.success()
        },
        onFailure = {
            retryOrExhaust()
        },
    )

    private fun retryOrExhaust(): Result {
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.RetryExhausted)
            return Result.failure(workDataOf(OUTPUT_ERROR_CODE to RETRY_EXHAUSTED_CODE))
        }
        return Result.retry()
    }

    companion object {
        const val INPUT_WAKE_ID = "opaque_wake_id"
        const val OUTPUT_ERROR_CODE = "error_code"
        const val MAX_ATTEMPTS = 6
        const val RECONNECT_TIMEOUT_MILLIS = 30_000L
        const val RETRY_EXHAUSTED_CODE = "PUSH_RETRY_EXHAUSTED"
    }
}
