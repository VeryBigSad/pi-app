package io.github.verybigsad.pimobile

import android.app.Application
import io.github.verybigsad.pimobile.bridge.ActivityPasskeyBridge

class PiMobileApplication : Application() {
    val passkeyBridge = ActivityPasskeyBridge()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
