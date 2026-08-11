package io.github.verybigsad.pimobile.update

import android.content.Context
import android.content.pm.ApplicationInfo

object UpdateRuntime {
    /** Assisted updater is disabled in debuggable builds. */
    fun isEnabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
}
