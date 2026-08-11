package io.github.verybigsad.pimobile

import android.app.Application
import io.github.verybigsad.pimobile.bridge.ActivityPasskeyBridge
import io.github.verybigsad.pimobile.security.AndroidOrigin
import io.github.verybigsad.pimobile.security.DebugPasskeyAuthenticator
import io.github.verybigsad.pimobile.security.PasskeyDebugHooks

class PiMobileApplication : Application() {
    val passkeyBridge = ActivityPasskeyBridge()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) installDebugPasskeySupport()
        container = AppContainer(this)
        container.start()
    }

    // Debug builds sign with the local debug key, so accept that signing origin and
    // back passkey ceremonies with a local authenticator. Both hooks compile out to
    // no-ops in release via PasskeyDebugHooks/BuildConfig gating.
    private fun installDebugPasskeySupport() {
        runCatching { PasskeyDebugHooks.androidOriginOverride = AndroidOrigin.current(this) }
        PasskeyDebugHooks.executor = DebugPasskeyAuthenticator { AndroidOrigin.current(this) }
    }
}
