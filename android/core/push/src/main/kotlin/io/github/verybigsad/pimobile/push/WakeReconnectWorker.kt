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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

object WakeWorkScheduler {
    fun enqueue(context: Context, wakeId: OpaqueWakeId): UUID {
        val appContext = context.applicationContext
        WakePendingStore(appContext).enqueue(wakeId)
        return enqueueStored(appContext, wakeId)
    }

    fun restorePending(context: Context) {
        val appContext = context.applicationContext
        WakePendingStore(appContext).all().forEach { enqueueStored(appContext, it) }
    }

    private fun enqueueStored(context: Context, wakeId: OpaqueWakeId): UUID {
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
        WorkManager.getInstance(context).enqueueUniqueWork(
            WakeWorkNames.workName(wakeId),
            ExistingWorkPolicy.KEEP,
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
    override suspend fun doWork(): Result = drainMutex.withLock {
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
        val pending = WakePendingStore(applicationContext)
        if (runCatching { pending.enqueue(parsed.wakeId) }.isFailure) return retryOrExhaust(parsed.wakeId, pending)
        if (runCatching { receipts.contains(parsed.wakeId) }.getOrDefault(false)) {
            runCatching { pending.remove(parsed.wakeId) }
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
            WakeReconnectResult.COMPLETED -> complete(receipts, pending, parsed.wakeId, UnifiedPushDeliveryState.CatchUpCompleted)
            WakeReconnectResult.REJECTED -> complete(receipts, pending, parsed.wakeId, UnifiedPushDeliveryState.WakeRejected)
            WakeReconnectResult.RETRY, null -> retryOrExhaust(parsed.wakeId, pending)
        }
    }

    private fun complete(
        receipts: WakeReceiptStore,
        pending: WakePendingStore,
        wakeId: OpaqueWakeId,
        state: UnifiedPushDeliveryState,
    ): Result = runCatching {
        receipts.record(wakeId)
        pending.remove(wakeId)
    }.fold(
        onSuccess = {
            UnifiedPushRuntime.updateDelivery(state)
            Result.success()
        },
        onFailure = {
            retryOrExhaust(wakeId, pending)
        },
    )

    private fun retryOrExhaust(wakeId: OpaqueWakeId, pending: WakePendingStore): Result {
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            runCatching { pending.remove(wakeId) }
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
        private val drainMutex = Mutex()
    }
}
