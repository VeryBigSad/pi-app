package io.github.verybigsad.pimobile.push

class UnifiedPushEndpoint internal constructor(
    val url: String,
    val instance: String,
    val temporary: Boolean,
    val publicKey: String?,
    val authSecret: String?,
) {
    override fun toString(): String =
        "UnifiedPushEndpoint(instance=$instance, temporary=$temporary, hasPublicKeySet=${publicKey != null})"
}

enum class EndpointRegistrationResult {
    ACCEPTED,
    RETRY_REQUIRED,
    REJECTED,
}

interface UnifiedPushEndpointRegistrar {
    /** Durably accepts endpoint registration work without blocking the connector callback thread. */
    fun register(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult

    /** Durably accepts endpoint removal work without blocking the connector callback thread. */
    fun unregister(instance: String): EndpointRegistrationResult
}

fun interface UnifiedPushWakeReconnector {
    suspend fun reconnect(wakeId: OpaqueWakeId): WakeReconnectResult
}

enum class WakeReconnectResult {
    COMPLETED,
    RETRY,
    REJECTED,
}

enum class ProviderUnavailableReason {
    NO_DISTRIBUTOR,
    CONNECTOR_ERROR,
}

sealed interface UnifiedPushProviderState {
    data object NotChecked : UnifiedPushProviderState
    data class ProviderUnavailable(val reason: ProviderUnavailableReason) : UnifiedPushProviderState
    data class ProviderSelectionRequired(val availableCount: Int) : UnifiedPushProviderState
    data class ProviderSelected(val packageName: String) : UnifiedPushProviderState
}

enum class RegistrationFailure {
    INTERNAL_ERROR,
    NETWORK,
    ACTION_REQUIRED,
    VAPID_REQUIRED,
}

enum class EndpointInvalidReason {
    WRONG_INSTANCE,
    URL_EMPTY,
    URL_TOO_LARGE,
    URL_NOT_HTTPS,
    URL_INVALID,
    INVALID_KEY_SET,
}

sealed interface UnifiedPushRegistrationState {
    data object NotConfigured : UnifiedPushRegistrationState
    data object ProviderUnavailable : UnifiedPushRegistrationState
    data object RegistrationRequested : UnifiedPushRegistrationState
    data class EndpointAvailable(val temporary: Boolean) : UnifiedPushRegistrationState
    data class EndpointRetryRequired(val temporary: Boolean) : UnifiedPushRegistrationState
    data class EndpointRejected(val reason: EndpointInvalidReason?) : UnifiedPushRegistrationState
    data object EndpointUnregistrationRetryRequired : UnifiedPushRegistrationState
    data object EndpointUnregistrationRejected : UnifiedPushRegistrationState
    data class RegistrationFailed(val reason: RegistrationFailure) : UnifiedPushRegistrationState
    data object TemporarilyUnavailable : UnifiedPushRegistrationState
    data object Unregistered : UnifiedPushRegistrationState
}

sealed interface UnifiedPushDeliveryState {
    data object Idle : UnifiedPushDeliveryState
    data object WakeQueued : UnifiedPushDeliveryState
    data object Reconnecting : UnifiedPushDeliveryState
    data object CatchUpCompleted : UnifiedPushDeliveryState
    data object DuplicateSuppressed : UnifiedPushDeliveryState
    data object WakeRejected : UnifiedPushDeliveryState
    data class InvalidPayload(val reason: WakePayloadInvalidReason) : UnifiedPushDeliveryState
    data object RetryExhausted : UnifiedPushDeliveryState
}

data class UnifiedPushState(
    val provider: UnifiedPushProviderState = UnifiedPushProviderState.NotChecked,
    val registration: UnifiedPushRegistrationState = UnifiedPushRegistrationState.NotConfigured,
    val delivery: UnifiedPushDeliveryState = UnifiedPushDeliveryState.Idle,
)
