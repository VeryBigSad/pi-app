package io.github.verybigsad.pimobile.testing

import android.content.Context
import android.os.SystemClock
import io.github.verybigsad.pimobile.PiMobileApplication
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.state.AppIntent

class AppLaunchTestBridge private constructor(
    private val application: PiMobileApplication,
    private val savedProfile: PairedProfile?,
) : AutoCloseable {
    override fun close() {
        if (savedProfile == null) {
            application.container.profiles.delete()
        } else {
            application.container.profiles.save(savedProfile)
        }
    }

    companion object {
        fun begin(context: Context, timeoutMillis: Long = 15_000): AppLaunchTestBridge {
            val application = context.applicationContext as PiMobileApplication
            val savedProfile = application.container.profiles.load()
            val bridge = AppLaunchTestBridge(application, savedProfile)
            application.container.coordinator.submit(AppIntent.UnpairRequested)
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = application.container.coordinator.state.value
                if (state.hydrated && state.trust is TrustState.Unpaired && state.sessions.isEmpty() && state.pairing == null) {
                    return bridge
                }
                SystemClock.sleep(25)
            }
            bridge.close()
            throw AssertionError("APP_LAUNCH_ISOLATION_TIMEOUT")
        }
    }
}
