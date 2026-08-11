package io.github.verybigsad.pimobile.push

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat

/** Posts the generic locked-state wake notification; content-free by design (opaque wakes). */
class LockedWakeNotifier(private val context: Context) {
    fun notifyActivityPending() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
            ?: return
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, PushNotificationChannels.FINISHED)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pi Mobile")
            .setContentText("Open Pi Mobile to check activity")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(LOCKED_WAKE_NOTIFICATION_ID, notification) }
    }

    companion object {
        const val LOCKED_WAKE_NOTIFICATION_ID = 0x7069
    }
}
