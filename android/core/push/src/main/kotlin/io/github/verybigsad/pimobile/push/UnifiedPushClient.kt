package io.github.verybigsad.pimobile.push

import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush

internal interface UnifiedPushConnectorPlatform {
    fun distributors(): List<String>

    fun providerLabel(packageName: String): CharSequence?

    fun savedDistributor(): String?

    fun saveDistributor(packageName: String)

    fun register(instance: String, messageForDistributor: String?, vapid: String?)

    fun unregister(instance: String)
}

private class AndroidUnifiedPushConnectorPlatform(context: Context) : UnifiedPushConnectorPlatform {
    private val appContext = context.applicationContext

    override fun distributors(): List<String> = UnifiedPush.getDistributors(appContext)

    override fun providerLabel(packageName: String): CharSequence? = runCatching {
        appContext.packageManager.getApplicationLabel(
            appContext.packageManager.getApplicationInfo(packageName, 0),
        )
    }.getOrNull()

    override fun savedDistributor(): String? = UnifiedPush.getSavedDistributor(appContext)

    override fun saveDistributor(packageName: String) {
        UnifiedPush.saveDistributor(appContext, packageName)
    }

    override fun register(instance: String, messageForDistributor: String?, vapid: String?) {
        UnifiedPush.register(appContext, instance, messageForDistributor, vapid)
    }

    override fun unregister(instance: String) {
        UnifiedPush.unregister(appContext, instance)
    }
}

/** A locally installed UnifiedPush distributor suitable for safe settings display. */
data class UnifiedPushProvider(
    val packageName: String,
    val displayName: String,
) {
    init {
        require(packageName.isNotBlank())
        require(displayName.isNotBlank())
    }
}

class UnifiedPushClient internal constructor(
    private val platform: UnifiedPushConnectorPlatform,
) {
    constructor(context: Context) : this(AndroidUnifiedPushConnectorPlatform(context))

    fun availableProviders(): List<String> = providerList().getOrElse {
        UnifiedPushRuntime.updateProvider(
            UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR),
        )
        emptyList()
    }

    /**
     * Returns locally installed distributors only. Labels are best-effort and sanitized because
     * they are package-provided text; endpoint data is intentionally never included.
     */
    fun availableProviderChoices(): List<UnifiedPushProvider> = availableProviders().map { packageName ->
        UnifiedPushProvider(
            packageName = packageName,
            displayName = safeProviderLabel(platform.providerLabel(packageName), packageName),
        )
    }

    fun refreshProviderState(): UnifiedPushProviderState {
        val providers = providerList().getOrElse {
            return UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR)
                .also(UnifiedPushRuntime::updateProvider)
        }
        if (providers.isEmpty()) {
            return UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.NO_DISTRIBUTOR)
                .also(UnifiedPushRuntime::updateProvider)
        }
        val selected = runCatching(platform::savedDistributor).getOrElse {
            return UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR)
                .also(UnifiedPushRuntime::updateProvider)
        }
        return when {
            selected != null && selected in providers -> UnifiedPushProviderState.ProviderSelected(selected)
            providers.size == 1 -> selectProvider(providers.single())
            else -> UnifiedPushProviderState.ProviderSelectionRequired(providers.size)
        }.also(UnifiedPushRuntime::updateProvider)
    }

    fun selectProvider(packageName: String): UnifiedPushProviderState {
        val providers = providerList().getOrElse {
            return UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR)
                .also(UnifiedPushRuntime::updateProvider)
        }
        if (providers.isEmpty()) {
            UnifiedPushRuntime.updateRegistration(UnifiedPushRegistrationState.ProviderUnavailable)
            return UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.NO_DISTRIBUTOR)
                .also(UnifiedPushRuntime::updateProvider)
        }
        if (packageName !in providers) {
            return UnifiedPushProviderState.ProviderSelectionRequired(providers.size)
                .also(UnifiedPushRuntime::updateProvider)
        }
        return runCatching { platform.saveDistributor(packageName) }.fold(
            onSuccess = {
                UnifiedPushProviderState.ProviderSelected(packageName)
                    .also(UnifiedPushRuntime::updateProvider)
            },
            onFailure = {
                UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR)
                    .also(UnifiedPushRuntime::updateProvider)
            },
        )
    }

    fun requestRegistration(): UnifiedPushRegistrationState {
        val providers = providerList().getOrElse {
            UnifiedPushRuntime.updateProvider(
                UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR),
            )
            return UnifiedPushRegistrationState.ProviderUnavailable
                .also(UnifiedPushRuntime::updateRegistration)
        }
        if (providers.isEmpty()) {
            UnifiedPushRuntime.updateProvider(
                UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.NO_DISTRIBUTOR),
            )
            return UnifiedPushRegistrationState.ProviderUnavailable
                .also(UnifiedPushRuntime::updateRegistration)
        }
        val selected = runCatching(platform::savedDistributor).getOrElse {
            UnifiedPushRuntime.updateProvider(
                UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR),
            )
            return UnifiedPushRegistrationState.ProviderUnavailable
                .also(UnifiedPushRuntime::updateRegistration)
        }
        if (selected == null || selected !in providers) {
            UnifiedPushRuntime.updateProvider(UnifiedPushProviderState.ProviderSelectionRequired(providers.size))
            return UnifiedPushRegistrationState.NotConfigured
                .also(UnifiedPushRuntime::updateRegistration)
        }
        UnifiedPushRuntime.updateProvider(UnifiedPushProviderState.ProviderSelected(selected))
        return runCatching {
            platform.register(
                instance = PUSH_INSTANCE,
                messageForDistributor = null,
                vapid = null,
            )
        }.fold(
            onSuccess = {
                UnifiedPushRegistrationState.RegistrationRequested
                    .also(UnifiedPushRuntime::updateRegistration)
            },
            onFailure = {
                UnifiedPushRegistrationState.RegistrationFailed(RegistrationFailure.INTERNAL_ERROR)
                    .also(UnifiedPushRuntime::updateRegistration)
            },
        )
    }

    fun unregister(): UnifiedPushRegistrationState {
        if (runCatching { platform.unregister(PUSH_INSTANCE) }.isFailure) {
            return UnifiedPushRegistrationState.RegistrationFailed(RegistrationFailure.INTERNAL_ERROR)
                .also(UnifiedPushRuntime::updateRegistration)
        }
        val endpointResult = runCatching {
            UnifiedPushRuntime.unregisterEndpoint(PUSH_INSTANCE)
        }.getOrNull()
        return when (endpointResult) {
            EndpointRegistrationResult.ACCEPTED -> UnifiedPushRegistrationState.Unregistered
            EndpointRegistrationResult.RETRY_REQUIRED -> {
                UnifiedPushRegistrationState.EndpointUnregistrationRetryRequired
            }
            EndpointRegistrationResult.REJECTED -> UnifiedPushRegistrationState.EndpointUnregistrationRejected
            null -> UnifiedPushRegistrationState.NotConfigured
        }.also(UnifiedPushRuntime::updateRegistration)
    }

    private fun providerList(): Result<List<String>> = runCatching {
        platform.distributors().filter(String::isNotBlank).distinct().sorted()
    }

    private fun safeProviderLabel(label: CharSequence?, fallback: String): String = label
        ?.toString()
        ?.filterNot { it.isISOControl() || it in '\u200B'..'\u200F' || it in '\u202A'..'\u202E' }
        ?.trim()
        ?.take(80)
        ?.takeIf(String::isNotBlank)
        ?: fallback

    companion object {
        const val PUSH_INSTANCE = "pi-mobile-wake-v1"
    }
}

internal fun FailedReason.toRegistrationFailure(): RegistrationFailure = when (this) {
    FailedReason.INTERNAL_ERROR -> RegistrationFailure.INTERNAL_ERROR
    FailedReason.NETWORK -> RegistrationFailure.NETWORK
    FailedReason.ACTION_REQUIRED -> RegistrationFailure.ACTION_REQUIRED
    FailedReason.VAPID_REQUIRED -> RegistrationFailure.VAPID_REQUIRED
}
