package io.github.verybigsad.pimobile.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndpointUploadWorkerTest {
    private lateinit var context: Context
    private lateinit var uploader: RecordingUploader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedPushRuntime.clear()
        PushRuntimeInitializer.resetForTesting()
        PushRegistrationStore(context).clear()
        uploader = RecordingUploader()
    }

    @After
    fun tearDown() {
        UnifiedPushRuntime.clear()
        PushRuntimeInitializer.resetForTesting()
        PushRegistrationStore(context).clear()
    }

    @Test
    fun pendingEndpointIsUploadedAndMarkedUploaded() = runBlocking {
        install()
        PushRegistrationStore(context).saveEndpoint(endpoint())

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(uploader.uploaded).containsExactly(ENDPOINT_URL)
        assertThat(PushRegistrationStore(context).load()!!.uploadState)
            .isEqualTo(EndpointUploadState.UPLOADED)
    }

    @Test
    fun retryableUploadStaysPendingAndRetries() = runBlocking {
        install()
        uploader.uploadResult = EndpointUploadResult.RETRY_REQUIRED
        PushRegistrationStore(context).saveEndpoint(endpoint())

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(PushRegistrationStore(context).load()!!.uploadState)
            .isEqualTo(EndpointUploadState.PENDING)
    }

    @Test
    fun rejectedUploadIsTerminalAndDropsEndpoint() = runBlocking {
        install()
        uploader.uploadResult = EndpointUploadResult.REJECTED
        PushRegistrationStore(context).saveEndpoint(endpoint())

        val result = worker().doWork()

        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    EndpointUploadWorker.OUTPUT_ERROR_CODE to EndpointUploadWorker.UPLOAD_REJECTED_CODE,
                ),
            ),
        )
        assertThat(PushRegistrationStore(context).load()!!.endpoint).isNull()
        assertThat(UnifiedPushRuntime.state.value.registration)
            .isEqualTo(UnifiedPushRegistrationState.EndpointRejected(null))
    }

    @Test
    fun missingUploaderRetriesWithBound() = runBlocking {
        PushRegistrationStore(context).saveEndpoint(endpoint())

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker(runAttemptCount = EndpointUploadWorker.MAX_ATTEMPTS - 1).doWork()).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    EndpointUploadWorker.OUTPUT_ERROR_CODE to EndpointUploadWorker.RETRY_EXHAUSTED_CODE,
                ),
            ),
        )
    }

    @Test
    fun uploaderExceptionIsTreatedAsRetryable() = runBlocking {
        install()
        uploader.throwOnUpload = true
        PushRegistrationStore(context).saveEndpoint(endpoint())

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(PushRegistrationStore(context).load()!!.uploadState)
            .isEqualTo(EndpointUploadState.PENDING)
    }

    @Test
    fun pendingRemovalCallsRemoveAndClears() = runBlocking {
        install()
        PushRegistrationStore(context).saveRemoval(UnifiedPushClient.PUSH_INSTANCE)

        val result = worker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(uploader.removed).containsExactly(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(PushRegistrationStore(context).load()!!.pendingRemoval).isNull()
    }

    @Test
    fun rejectedRemovalIsTerminal() = runBlocking {
        install()
        uploader.removeResult = EndpointUploadResult.REJECTED
        PushRegistrationStore(context).saveRemoval(UnifiedPushClient.PUSH_INSTANCE)

        val result = worker().doWork()

        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    EndpointUploadWorker.OUTPUT_ERROR_CODE to EndpointUploadWorker.REMOVAL_REJECTED_CODE,
                ),
            ),
        )
    }

    @Test
    fun emptyStoreSucceedsWithoutUploader() = runBlocking {
        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(uploader.uploaded).isEmpty()
        assertThat(uploader.removed).isEmpty()
    }

    private fun install() {
        PushRuntimeInitializer.install(context, uploader) { WakeReconnectResult.COMPLETED }
    }

    private fun worker(runAttemptCount: Int = 0): EndpointUploadWorker =
        TestListenableWorkerBuilder<EndpointUploadWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .build()

    private fun endpoint() = UnifiedPushEndpoint(
        url = ENDPOINT_URL,
        instance = UnifiedPushClient.PUSH_INSTANCE,
        temporary = false,
        publicKey = null,
        authSecret = null,
    )

    private class RecordingUploader : UnifiedPushEndpointUploader {
        var uploadResult = EndpointUploadResult.UPLOADED
        var removeResult = EndpointUploadResult.UPLOADED
        var throwOnUpload = false
        val uploaded = mutableListOf<String>()
        val removed = mutableListOf<String>()

        override suspend fun upload(endpoint: UnifiedPushEndpoint): EndpointUploadResult {
            if (throwOnUpload) error("private upload detail")
            uploaded += endpoint.url
            return uploadResult
        }

        override suspend fun remove(instance: String): EndpointUploadResult {
            removed += instance
            return removeResult
        }
    }

    companion object {
        private const val ENDPOINT_URL = "https://push.example/up/private"
    }
}
