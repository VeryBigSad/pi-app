package io.github.verybigsad.pimobile.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushService : PushService() {
    override fun onCreate() {
        super.onCreate()
        PushNotificationChannels.create(applicationContext)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        when (val parsed = PushEndpointParser.parse(endpoint, instance)) {
            is EndpointParseResult.Invalid -> {
                UnifiedPushRuntime.updateRegistration(
                    UnifiedPushRegistrationState.EndpointRejected(parsed.reason),
                )
            }
            is EndpointParseResult.Valid -> {
                val result = runCatching {
                    UnifiedPushRuntime.registerEndpoint(parsed.endpoint)
                }.getOrNull()
                val state = when (result) {
                    EndpointRegistrationResult.ACCEPTED -> {
                        UnifiedPushRegistrationState.EndpointAvailable(parsed.endpoint.temporary)
                    }
                    EndpointRegistrationResult.RETRY_REQUIRED -> {
                        UnifiedPushRegistrationState.EndpointRetryRequired(parsed.endpoint.temporary)
                    }
                    EndpointRegistrationResult.REJECTED -> {
                        UnifiedPushRegistrationState.EndpointRejected(null)
                    }
                    null -> UnifiedPushRegistrationState.NotConfigured
                }
                UnifiedPushRuntime.updateRegistration(state)
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (instance != UnifiedPushClient.PUSH_INSTANCE) {
            return
        }
        when (val parsed = OpaqueWakePayload.parse(message.content)) {
            is WakePayloadParseResult.Invalid -> {
                UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.InvalidPayload(parsed.reason))
            }
            is WakePayloadParseResult.Valid -> {
                runCatching {
                    WakeWorkScheduler.enqueue(applicationContext, parsed.wakeId)
                }.fold(
                    onSuccess = {
                        UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.WakeQueued)
                    },
                    onFailure = {
                        UnifiedPushRuntime.updateDelivery(UnifiedPushDeliveryState.RetryExhausted)
                    },
                )
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (instance == UnifiedPushClient.PUSH_INSTANCE) {
            UnifiedPushRuntime.updateRegistration(
                UnifiedPushRegistrationState.RegistrationFailed(reason.toRegistrationFailure()),
            )
        }
    }

    override fun onUnregistered(instance: String) {
        if (instance != UnifiedPushClient.PUSH_INSTANCE) {
            return
        }
        val endpointResult = runCatching {
            UnifiedPushRuntime.unregisterEndpoint(instance)
        }.getOrNull()
        val state = when (endpointResult) {
            EndpointRegistrationResult.ACCEPTED -> UnifiedPushRegistrationState.Unregistered
            EndpointRegistrationResult.RETRY_REQUIRED -> {
                UnifiedPushRegistrationState.EndpointUnregistrationRetryRequired
            }
            EndpointRegistrationResult.REJECTED -> UnifiedPushRegistrationState.EndpointUnregistrationRejected
            null -> UnifiedPushRegistrationState.NotConfigured
        }
        UnifiedPushRuntime.updateRegistration(state)
    }

    override fun onTempUnavailable(instance: String) {
        if (instance == UnifiedPushClient.PUSH_INSTANCE) {
            UnifiedPushRuntime.updateRegistration(UnifiedPushRegistrationState.TemporarilyUnavailable)
        }
    }
}
