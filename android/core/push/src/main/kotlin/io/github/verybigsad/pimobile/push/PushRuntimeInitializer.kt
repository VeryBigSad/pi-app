package io.github.verybigsad.pimobile.push

import android.content.Context

/**
 * Production entry point for the push module. The application MUST call [install]
 * synchronously from `Application.onCreate` on every process start, before WorkManager
 * executes any queued worker: workers (reconnect and endpoint upload) resolve their
 * integration through this initializer and the durable registration store, not through
 * process memory.
 */
object PushRuntimeInitializer {
    private class Handlers(
        val endpointUploader: UnifiedPushEndpointUploader,
        val wakeReconnector: UnifiedPushWakeReconnector,
    )

    @Volatile
    private var handlers: Handlers? = null

    /**
     * Installs the durable endpoint registrar and the application-provided handlers into
     * [UnifiedPushRuntime], synchronously restores persisted registration state (endpoint,
     * pending uploads, selected provider), and reschedules any interrupted endpoint upload.
     * Safe to call repeatedly; the latest handlers win.
     */
    @Synchronized
    fun install(
        context: Context,
        endpointUploader: UnifiedPushEndpointUploader,
        wakeReconnector: UnifiedPushWakeReconnector,
    ): UnifiedPushClient {
        val appContext = context.applicationContext
        handlers = Handlers(endpointUploader, wakeReconnector)
        UnifiedPushRuntime.install(
            endpointRegistrar = DurableEndpointRegistrar(appContext),
            endpointUploader = endpointUploader,
            wakeReconnector = wakeReconnector,
        )
        restoreFromDurableStore(appContext)
        runCatching { WakeWorkScheduler.restorePending(appContext) }
        return UnifiedPushClient(appContext).also { it.refreshProviderState() }
    }

    /**
     * Re-installs the integration from the handlers captured by the last [install] when the
     * runtime lost it (e.g. after an in-process reset). Invoked by background workers before
     * touching the runtime. Across a real process death, restoration happens in
     * `Application.onCreate` via [install]; without a prior [install] this is a no-op.
     */
    @Synchronized
    fun ensureRestored(context: Context) {
        if (UnifiedPushRuntime.hasIntegration()) {
            return
        }
        val current = handlers ?: return
        install(context, current.endpointUploader, current.wakeReconnector)
    }

    private fun restoreFromDurableStore(context: Context) {
        val snapshot = runCatching { PushRegistrationStore(context).load() }.getOrNull() ?: return
        val uploadPending = snapshot.pendingRemoval != null ||
            (snapshot.endpoint != null && snapshot.uploadState == EndpointUploadState.PENDING)
        if (uploadPending) {
            runCatching { EndpointUploadScheduler.enqueue(context) }
        }
        if (UnifiedPushRuntime.state.value.registration != UnifiedPushRegistrationState.NotConfigured) {
            return
        }
        when {
            snapshot.pendingRemoval != null -> UnifiedPushRuntime.updateRegistration(
                UnifiedPushRegistrationState.EndpointUnregistrationRetryRequired,
            )
            snapshot.endpoint != null && snapshot.uploadState == EndpointUploadState.PENDING ->
                UnifiedPushRuntime.updateRegistration(
                    UnifiedPushRegistrationState.EndpointRetryRequired(snapshot.endpoint.temporary),
                )
            snapshot.endpoint != null -> UnifiedPushRuntime.updateRegistration(
                UnifiedPushRegistrationState.EndpointAvailable(snapshot.endpoint.temporary),
            )
            else -> Unit
        }
    }

    /**
     * Erases durable endpoint state after its owning Mac is unpaired. A pending revoke must not
     * be replayed to a newly paired Mac, because endpoint operations are host-bound.
     */
    fun forgetRegistration(context: Context) {
        PushRegistrationStore(context.applicationContext).clear()
        UnifiedPushRuntime.updateRegistration(UnifiedPushRegistrationState.NotConfigured)
    }

    internal fun resetForTesting() {
        handlers = null
    }
}
