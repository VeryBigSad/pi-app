package io.github.verybigsad.pimobile.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class EndpointUploadResult {
    UPLOADED,
    RETRY_REQUIRED,
    REJECTED,
}

/**
 * Uploads the durable push endpoint to the application backend. Invoked from background
 * workers, possibly in a fresh process; implementations must not rely on in-memory state.
 */
interface UnifiedPushEndpointUploader {
    suspend fun upload(endpoint: UnifiedPushEndpoint): EndpointUploadResult

    suspend fun remove(instance: String): EndpointUploadResult
}

/**
 * Endpoint registrar that persists every registration/removal durably before returning,
 * then schedules the upload via WorkManager. Survives process death: the pending upload
 * is re-read from disk by [EndpointUploadWorker].
 */
class DurableEndpointRegistrar private constructor(
    private val store: PushRegistrationStore,
    private val enqueueUpload: () -> Unit,
) : UnifiedPushEndpointRegistrar {
    constructor(context: Context) : this(
        PushRegistrationStore(context.applicationContext),
        { EndpointUploadScheduler.enqueue(context.applicationContext) },
    )

    override fun register(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult {
        runCatching { store.saveEndpoint(endpoint) }
            .getOrElse { return EndpointRegistrationResult.RETRY_REQUIRED }
        runCatching { enqueueUpload() }
        return EndpointRegistrationResult.ACCEPTED
    }

    override fun unregister(instance: String): EndpointRegistrationResult {
        runCatching { store.saveRemoval(instance) }
            .getOrElse { return EndpointRegistrationResult.RETRY_REQUIRED }
        runCatching { enqueueUpload() }
        return EndpointRegistrationResult.ACCEPTED
    }

    internal companion object {
        fun forTest(
            store: PushRegistrationStore,
            enqueueUpload: () -> Unit,
        ): DurableEndpointRegistrar = DurableEndpointRegistrar(store, enqueueUpload)
    }
}

object EndpointUploadScheduler {
    fun enqueue(context: Context): UUID {
        val request = OneTimeWorkRequestBuilder<EndpointUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    private const val MIN_BACKOFF_SECONDS = 30L
    private const val WORK_TAG = "pi-push-endpoint-upload"
    const val UNIQUE_WORK_NAME = "pi-push-endpoint-upload"
}
