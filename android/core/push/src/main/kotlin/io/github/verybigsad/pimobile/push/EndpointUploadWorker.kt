package io.github.verybigsad.pimobile.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

/**
 * Drains the durable endpoint upload queue written by [DurableEndpointRegistrar]. Reads
 * pending work from disk so it can complete uploads in a process started after the one
 * that received the endpoint died.
 */
class EndpointUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        PushRuntimeInitializer.ensureRestored(applicationContext)
        val store = PushRegistrationStore(applicationContext)
        val snapshot = runCatching { store.load() }.getOrNull() ?: return Result.success()
        val uploader = UnifiedPushRuntime.endpointUploader() ?: return retryOrExhaust()
        val pendingRemoval = snapshot.pendingRemoval
        if (pendingRemoval != null) {
            return completeRemoval(store, uploader, pendingRemoval)
        }
        val endpoint = snapshot.endpoint
        if (endpoint == null || snapshot.uploadState == EndpointUploadState.UPLOADED) {
            return Result.success()
        }
        return when (callUploader { uploader.upload(endpoint) }) {
            EndpointUploadResult.UPLOADED -> runCatching { store.markUploaded() }.fold(
                onSuccess = { Result.success() },
                onFailure = { retryOrExhaust() },
            )
            EndpointUploadResult.REJECTED -> {
                runCatching { store.clearEndpoint() }
                UnifiedPushRuntime.updateRegistration(UnifiedPushRegistrationState.EndpointRejected(null))
                Result.failure(workDataOf(OUTPUT_ERROR_CODE to UPLOAD_REJECTED_CODE))
            }
            EndpointUploadResult.RETRY_REQUIRED -> retryOrExhaust()
        }
    }

    private suspend fun completeRemoval(
        store: PushRegistrationStore,
        uploader: UnifiedPushEndpointUploader,
        instance: String,
    ): Result = when (callUploader { uploader.remove(instance) }) {
        EndpointUploadResult.UPLOADED -> runCatching { store.clearPendingRemoval() }.fold(
            onSuccess = { Result.success() },
            onFailure = { retryOrExhaust() },
        )
        EndpointUploadResult.REJECTED -> {
            runCatching { store.clearPendingRemoval() }
            Result.failure(workDataOf(OUTPUT_ERROR_CODE to REMOVAL_REJECTED_CODE))
        }
        EndpointUploadResult.RETRY_REQUIRED -> retryOrExhaust()
    }

    private suspend fun callUploader(
        block: suspend () -> EndpointUploadResult,
    ): EndpointUploadResult = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        EndpointUploadResult.RETRY_REQUIRED
    }

    private fun retryOrExhaust(): Result {
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            return Result.failure(workDataOf(OUTPUT_ERROR_CODE to RETRY_EXHAUSTED_CODE))
        }
        return Result.retry()
    }

    companion object {
        const val OUTPUT_ERROR_CODE = "error_code"
        const val MAX_ATTEMPTS = 6
        const val UPLOAD_REJECTED_CODE = "PUSH_UPLOAD_REJECTED"
        const val REMOVAL_REJECTED_CODE = "PUSH_REMOVAL_REJECTED"
        const val RETRY_EXHAUSTED_CODE = "PUSH_UPLOAD_RETRY_EXHAUSTED"
    }
}
