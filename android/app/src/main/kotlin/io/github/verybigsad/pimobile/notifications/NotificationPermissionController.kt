package io.github.verybigsad.pimobile.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NotificationPermissionStatus {
    GRANTED,
    DENIED,

    /** Runtime permission not needed (API < 33). */
    NOT_REQUIRED,
}

/** Pure decision logic; unit-tested without Android. */
object NotificationPermissionPolicy {
    fun statusFor(sdkInt: Int, granted: Boolean): NotificationPermissionStatus = when {
        sdkInt < 33 -> NotificationPermissionStatus.NOT_REQUIRED
        granted -> NotificationPermissionStatus.GRANTED
        else -> NotificationPermissionStatus.DENIED
    }

    /** Request once, only when the assisted updater actually runs. Denied stays a banner. */
    fun shouldRequestOnUpdateEnable(
        sdkInt: Int,
        granted: Boolean,
        requestedBefore: Boolean,
        updatesEnabled: Boolean,
    ): Boolean = updatesEnabled && sdkInt >= 33 && !granted && !requestedBefore
}

/** Tracks POST_NOTIFICATIONS state and the one-shot "requested on update enable" flag. */
class NotificationPermissionController(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val mutableStatus = MutableStateFlow(current())
    val status: StateFlow<NotificationPermissionStatus> = mutableStatus.asStateFlow()

    val requestedBefore: Boolean get() = prefs.getBoolean(KEY_REQUESTED, false)

    fun markRequested() {
        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    fun refresh() {
        mutableStatus.value = current()
    }

    fun grantedNow(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun current(): NotificationPermissionStatus =
        NotificationPermissionPolicy.statusFor(Build.VERSION.SDK_INT, grantedNow())

    companion object {
        private const val PREFS = "notification-permission"
        private const val KEY_REQUESTED = "post_notifications_requested"
    }
}
