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
class WakeReconnectWorkerTest {
    private lateinit var context: Context
    private lateinit var registrar: NoOpRegistrar
    private var reconnectCalls = 0
    private var reconnectResult = WakeReconnectResult.COMPLETED
    private var receivedWakeId: OpaqueWakeId? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pi_push_receipts_v1", Context.MODE_PRIVATE).edit().clear().commit()
        UnifiedPushRuntime.clear()
        registrar = NoOpRegistrar()
        UnifiedPushRuntime.install(registrar) { wakeId ->
            reconnectCalls += 1
            receivedWakeId = wakeId
            reconnectResult
        }
    }

    @After
    fun tearDown() {
        UnifiedPushRuntime.clear()
        context.getSharedPreferences("pi_push_receipts_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun performsBackgroundReconnectWithOnlyOpaqueWakeId() = runBlocking {
        val result = worker(WAKE_ID).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(reconnectCalls).isEqualTo(1)
        assertThat(receivedWakeId?.value).isEqualTo(WAKE_ID)
        assertThat(UnifiedPushRuntime.state.value.delivery)
            .isEqualTo(UnifiedPushDeliveryState.CatchUpCompleted)
    }

    @Test
    fun completedWakeIsDurablyDeduplicated() = runBlocking {
        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(reconnectCalls).isEqualTo(1)
    }

    @Test
    fun rejectedWakeIsDeduplicatedWithoutGrantingAuthority() = runBlocking {
        reconnectResult = WakeReconnectResult.REJECTED

        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(reconnectCalls).isEqualTo(1)
        assertThat(UnifiedPushRuntime.state.value.delivery).isEqualTo(UnifiedPushDeliveryState.DuplicateSuppressed)
    }

    @Test
    fun retryResultUsesBoundedWorkManagerRetry() = runBlocking {
        reconnectResult = WakeReconnectResult.RETRY

        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(reconnectCalls).isEqualTo(1)
    }

    @Test
    fun reconnectExceptionRetriesWithoutLeakingErrorText() = runBlocking {
        UnifiedPushRuntime.install(registrar) {
            error("private reconnect detail")
        }

        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(UnifiedPushRuntime.state.value.toString()).doesNotContain("private reconnect detail")
    }

    @Test
    fun finalAttemptFailsWithStableCode() = runBlocking {
        reconnectResult = WakeReconnectResult.RETRY

        val result = worker(WAKE_ID, WakeReconnectWorker.MAX_ATTEMPTS - 1).doWork()

        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    WakeReconnectWorker.OUTPUT_ERROR_CODE to WakeReconnectWorker.RETRY_EXHAUSTED_CODE,
                ),
            ),
        )
        assertThat(UnifiedPushRuntime.state.value.delivery)
            .isEqualTo(UnifiedPushDeliveryState.RetryExhausted)
    }

    @Test
    fun invalidInputNeverInvokesReconnect() = runBlocking {
        val result = worker("session:private-content").doWork()

        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    WakeReconnectWorker.OUTPUT_ERROR_CODE to WakePayloadInvalidReason.INVALID_WAKE_ID.name,
                ),
            ),
        )
        assertThat(reconnectCalls).isEqualTo(0)
    }

    @Test
    fun oversizedInputFailsBeforeReconnect() = runBlocking {
        val result = worker("a".repeat(OpaqueWakePayload.MAX_PAYLOAD_BYTES + 1)).doWork()

        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(
                    WakeReconnectWorker.OUTPUT_ERROR_CODE to WakePayloadInvalidReason.TOO_LARGE.name,
                ),
            ),
        )
        assertThat(reconnectCalls).isEqualTo(0)
    }

    @Test
    fun missingRuntimeIntegrationRetriesWithoutCrashing() = runBlocking {
        UnifiedPushRuntime.clear()

        assertThat(worker(WAKE_ID).doWork()).isEqualTo(ListenableWorker.Result.retry())
    }

    private fun worker(wakeId: String, runAttemptCount: Int = 0): WakeReconnectWorker =
        TestListenableWorkerBuilder<WakeReconnectWorker>(context)
            .setInputData(workDataOf(WakeReconnectWorker.INPUT_WAKE_ID to wakeId))
            .setRunAttemptCount(runAttemptCount)
            .build()

    private class NoOpRegistrar : UnifiedPushEndpointRegistrar {
        override fun register(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult =
            EndpointRegistrationResult.ACCEPTED

        override fun unregister(instance: String): EndpointRegistrationResult =
            EndpointRegistrationResult.ACCEPTED
    }

    companion object {
        private const val WAKE_ID = "abcdefghijklmnopqrstuv"
    }
}
