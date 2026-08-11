package io.github.verybigsad.pimobile.security

/**
 * Debug-only passkey hooks for local development and devx fakes. Every hook is a no-op
 * in release builds: the exact production RP (`verybigsad.github.io`) and Android origin
 * remain the only accepted values there.
 */
object PasskeyDebugHooks {
    @Volatile private var originOverrideValue: String? = null
    @Volatile private var executorValue: PasskeyExecutor? = null

    /**
     * Additional accepted Android origin (for example the debug signing certificate's
     * `android:apk-key-hash:` origin). Ignored unless [BuildConfig.DEBUG].
     */
    var androidOriginOverride: String?
        get() = if (BuildConfig.DEBUG) originOverrideValue else null
        set(value) {
            if (!BuildConfig.DEBUG) return
            require(value == null || (value.startsWith("android:apk-key-hash:") && value.length <= 128)) {
                "debug origin override must be an android:apk-key-hash origin"
            }
            originOverrideValue = value
        }

    /**
     * Fake passkey executor used instead of the platform CredentialManager. The produced
     * responses still pass the full [PasskeyPolicy] validation. Ignored unless [BuildConfig.DEBUG].
     */
    var executor: PasskeyExecutor?
        get() = if (BuildConfig.DEBUG) executorValue else null
        set(value) {
            if (!BuildConfig.DEBUG) return
            executorValue = value
        }
}

/** Passkey ceremony executor abstraction; production is backed by CredentialManager. */
interface PasskeyExecutor {
    suspend fun createCredential(requestJson: String): String

    suspend fun getCredential(requestJson: String): String
}

internal object PasskeyOrigins {
    fun allowedAndroidOrigins(): Set<String> {
        val debugOverride = PasskeyDebugHooks.androidOriginOverride
        return if (debugOverride == null) {
            setOf(PasskeyIdentity.AndroidOrigin)
        } else {
            setOf(PasskeyIdentity.AndroidOrigin, debugOverride)
        }
    }
}
