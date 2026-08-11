package io.github.verybigsad.pimobile.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

/**
 * Notification is a pure deep-link into the update sheet. It never downloads or authorizes
 * anything; tapping only opens the app UI where the user decides.
 */
class UpdateNotifier(private val context: Context) {
    fun notifyReady(candidate: UpdateCandidate) {
        ensureChannel()
        val openSheet = Intent(Intent.ACTION_VIEW, Uri.parse("pimobile://update")).apply {
            setPackage(context.packageName)
        }
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_UPDATE,
            openSheet,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update ${candidate.versionName} ready")
            .setContentText("Open Pi Mobile to review and install")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager?.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "app-updates"
        const val NOTIFICATION_ID = 0x0B51
        private const val REQUEST_UPDATE = 0x0B52
    }
}
