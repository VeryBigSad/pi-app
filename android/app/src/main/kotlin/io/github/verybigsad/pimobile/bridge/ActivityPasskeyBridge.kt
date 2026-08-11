package io.github.verybigsad.pimobile.bridge

import android.app.Activity
import io.github.verybigsad.pimobile.security.PasskeyAvailability
import io.github.verybigsad.pimobile.security.PasskeyClient
import io.github.verybigsad.pimobile.security.PasskeyResult
import io.github.verybigsad.pimobile.state.PasskeyBridgePort
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Activity-bound bridge for Credential Manager ceremonies. The coordinator survives
 * recreation; this bridge does not. One ceremony at a time; results from a stale activity
 * are discarded. Production uses PasskeyClient only — there is no fake-provider path here.
 */
class ActivityPasskeyBridge : PasskeyBridgePort {
    private val activityRef = AtomicBoolean(false)
    private var activity: WeakReference<Activity>? = null

    @Volatile
    private var ceremonyActive = false

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        activityRef.set(true)
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) {
            this.activity = null
            activityRef.set(false)
        }
    }

    fun availability(): PasskeyAvailability? = activity?.get()?.let { PasskeyClient(it).availability() }

    override suspend fun performAssertion(ceremonyId: String, optionsJson: String): Pair<String?, String?> =
        perform(registration = false, optionsJson)

    suspend fun performRegistration(optionsJson: String): Pair<String?, String?> =
        perform(registration = true, optionsJson)

    private suspend fun perform(registration: Boolean, optionsJson: String): Pair<String?, String?> {
        val current = activity?.get() ?: return null to "PASSKEY_NO_FOREGROUND_ACTIVITY"
        if (ceremonyActive) return null to "PASSKEY_CEREMONY_IN_FLIGHT"
        ceremonyActive = true
        try {
            val client = PasskeyClient(current)
            return when (val result = if (registration) client.register(optionsJson) else client.assert(optionsJson)) {
                is PasskeyResult.Registration -> result.responseJson to null
                is PasskeyResult.Assertion -> result.responseJson to null
                is PasskeyResult.Locked -> null to "PASSKEY_LOCKED_${result.reason.name}"
                is PasskeyResult.Failure -> null to result.code
            }
        } finally {
            ceremonyActive = false
        }
    }
}
