package io.github.verybigsad.pimobile.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object UnifiedPushRuntime {
    private data class Integration(
        val endpointRegistrar: UnifiedPushEndpointRegistrar,
        val endpointUploader: UnifiedPushEndpointUploader?,
        val wakeReconnector: UnifiedPushWakeReconnector,
    )

    private val mutableState = MutableStateFlow(UnifiedPushState())

    @Volatile
    private var integration: Integration? = null

    val state: StateFlow<UnifiedPushState> = mutableState.asStateFlow()

    @Synchronized
    fun install(
        endpointRegistrar: UnifiedPushEndpointRegistrar,
        endpointUploader: UnifiedPushEndpointUploader? = null,
        wakeReconnector: UnifiedPushWakeReconnector,
    ) {
        integration = Integration(endpointRegistrar, endpointUploader, wakeReconnector)
    }

    @Synchronized
    fun clear() {
        integration = null
        mutableState.value = UnifiedPushState()
    }

    internal fun updateProvider(provider: UnifiedPushProviderState) {
        mutableState.update { it.copy(provider = provider) }
    }

    internal fun updateRegistration(registration: UnifiedPushRegistrationState) {
        mutableState.update { it.copy(registration = registration) }
    }

    internal fun updateDelivery(delivery: UnifiedPushDeliveryState) {
        mutableState.update { it.copy(delivery = delivery) }
    }

    internal fun hasIntegration(): Boolean = integration != null

    internal fun endpointUploader(): UnifiedPushEndpointUploader? = integration?.endpointUploader

    internal fun registerEndpoint(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult? =
        integration?.endpointRegistrar?.register(endpoint)

    internal fun unregisterEndpoint(instance: String): EndpointRegistrationResult? =
        integration?.endpointRegistrar?.unregister(instance)

    internal suspend fun reconnect(wakeId: OpaqueWakeId): WakeReconnectResult? =
        integration?.wakeReconnector?.reconnect(wakeId)
}
