package io.github.verybigsad.pimobile.update

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** In-process bus between the install status receiver/activity and the update manager. */
object UpdateEvents {
    private val callbacks = MutableSharedFlow<InstallCallback>(extraBufferCapacity = 8)
    val installCallbacks: SharedFlow<InstallCallback> = callbacks

    fun emit(callback: InstallCallback) {
        callbacks.tryEmit(callback)
    }
}
