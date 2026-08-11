package io.github.verybigsad.pimobile.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulates process death: everything held in process memory ([UnifiedPushRuntime],
 * initializer handlers) is dropped while on-disk state survives, then the production
 * initialization path restores it synchronously before the worker runs.
 */
@RunWith(AndroidJUnit4::class)
class ColdProcessRestoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedPushRuntime.clear()
        PushRuntimeInitializer.resetForTesting()
        PushRegistrationStore(context).clear()
        context.getSharedPreferences(RECEIPTS_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        UnifiedPushRuntime.clear()
        PushRuntimeInitializer.resetForTesting()
        PushRegistrationStore(context).clear()
        context.getSharedPreferences(RECEIPTS_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun endpointRegistrationSurvivesSimulatedProcessDeath() {
        PushRuntimeInitializer.install(context, NoOpUploader()) { WakeReconnectResult.COMPLETED }

        assertThat(UnifiedPushRuntime.registerEndpoint(endpoint()))
            .isEqualTo(EndpointRegistrationResult.ACCEPTED)

        simulateProcessDeath()
        val restored = PushRegistrationStore(context).load()

        assertThat(restored).isNotNull()
        assertThat(restored!!.endpoint!!.url).isEqualTo(ENDPOINT_URL)
        assertThat(restored.endpoint.instance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(restored.uploadState).isEqualTo(EndpointUploadState.PENDING)
        assertThat(File(context.noBackupFilesDir, PushRegistrationStore.STORE_FILE_NAME).exists()).isTrue()
    }

    @Test
    fun workerReconnectsAfterColdStartRestore() = runBlocking {
        PushRuntimeInitializer.install(context, NoOpUploader()) { WakeReconnectResult.COMPLETED }
        UnifiedPushRuntime.registerEndpoint(endpoint())
        simulateProcessDeath()

        val reconnectCalls = AtomicInteger()
        val received = AtomicReference<OpaqueWakeId?>()
        coldStartInstall(reconnectCalls, received)
        val result = wakeWorker(WAKE_ID).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(reconnectCalls.get()).isEqualTo(1)
        assertThat(received.get()?.value).isEqualTo(WAKE_ID)
        assertThat(UnifiedPushRuntime.state.value.delivery)
            .isEqualTo(UnifiedPushDeliveryState.CatchUpCompleted)
    }

    @Test
    fun workerRestoresIntegrationFromRetainedHandlersAfterRuntimeLoss() = runBlocking {
        val reconnectCalls = AtomicInteger()
        val received = AtomicReference<OpaqueWakeId?>()
        coldStartInstall(reconnectCalls, received)
        UnifiedPushRuntime.registerEndpoint(endpoint())

        UnifiedPushRuntime.clear()
        val result = wakeWorker(WAKE_ID).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(reconnectCalls.get()).isEqualTo(1)
        assertThat(received.get()?.value).isEqualTo(WAKE_ID)
        assertThat(UnifiedPushRuntime.state.value.delivery)
            .isEqualTo(UnifiedPushDeliveryState.CatchUpCompleted)
    }

    @Test
    fun duplicateWakeStaysSuppressedAcrossSimulatedRestart() = runBlocking {
        val reconnectCalls = AtomicInteger()
        val received = AtomicReference<OpaqueWakeId?>()
        coldStartInstall(reconnectCalls, received)
        assertThat(wakeWorker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.success())

        simulateProcessDeath()
        coldStartInstall(reconnectCalls, received)

        assertThat(wakeWorker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(reconnectCalls.get()).isEqualTo(1)
        assertThat(UnifiedPushRuntime.state.value.delivery)
            .isEqualTo(UnifiedPushDeliveryState.DuplicateSuppressed)
    }

    @Test
    fun forgedWakeIsRejectedAfterColdStartWithoutReconnect() = runBlocking {
        simulateProcessDeath()
        val reconnectCalls = AtomicInteger()
        val received = AtomicReference<OpaqueWakeId?>()
        coldStartInstall(reconnectCalls, received)

        val result = wakeWorker("session:private-content").doWork()

        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    WakeReconnectWorker.OUTPUT_ERROR_CODE to WakePayloadInvalidReason.INVALID_WAKE_ID.name,
                ),
            ),
        )
        assertThat(reconnectCalls.get()).isEqualTo(0)
    }

    @Test
    fun restoredPendingUploadIsRescheduledAndSurfaced() {
        PushRuntimeInitializer.install(context, NoOpUploader()) { WakeReconnectResult.COMPLETED }
        UnifiedPushRuntime.registerEndpoint(endpoint())
        simulateProcessDeath()

        coldStartInstall(AtomicInteger(), AtomicReference())

        assertThat(UnifiedPushRuntime.state.value.registration)
            .isEqualTo(UnifiedPushRegistrationState.EndpointRetryRequired(false))
    }

    private fun coldStartInstall(
        reconnectCalls: AtomicInteger,
        received: AtomicReference<OpaqueWakeId?>,
    ) {
        PushRuntimeInitializer.install(context, NoOpUploader()) { wakeId ->
            reconnectCalls.incrementAndGet()
            received.set(wakeId)
            WakeReconnectResult.COMPLETED
        }
    }

    private fun simulateProcessDeath() {
        UnifiedPushRuntime.clear()
        PushRuntimeInitializer.resetForTesting()
    }

    private fun wakeWorker(wakeId: String): WakeReconnectWorker =
        TestListenableWorkerBuilder<WakeReconnectWorker>(context)
            .setInputData(workDataOf(WakeReconnectWorker.INPUT_WAKE_ID to wakeId))
            .build()

    private fun endpoint() = UnifiedPushEndpoint(
        url = ENDPOINT_URL,
        instance = UnifiedPushClient.PUSH_INSTANCE,
        temporary = false,
        publicKey = null,
        authSecret = null,
    )

    private class NoOpUploader : UnifiedPushEndpointUploader {
        override suspend fun upload(endpoint: UnifiedPushEndpoint): EndpointUploadResult =
            EndpointUploadResult.UPLOADED

        override suspend fun remove(instance: String): EndpointUploadResult =
            EndpointUploadResult.UPLOADED
    }

    companion object {
        private const val WAKE_ID = "abcdefghijklmnopqrstuv"
        private const val ENDPOINT_URL = "https://push.example/up/private"
        private const val RECEIPTS_PREFS = "pi_push_receipts_v1"
    }
}
