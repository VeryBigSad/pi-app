package io.github.verybigsad.pimobile

import android.view.Window
import android.view.WindowManager
import io.github.verybigsad.pimobile.model.TrustState

internal object ScreenCapturePolicy {
    fun apply(window: Window, trust: TrustState) {
        when (trust) {
            TrustState.Unpaired,
            is TrustState.Trusted,
            is TrustState.Revoked,
            -> window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
