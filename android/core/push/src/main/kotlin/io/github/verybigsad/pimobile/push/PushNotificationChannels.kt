package io.github.verybigsad.pimobile.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object PushNotificationChannels {
    const val NEEDS_YOU = "pi_needs_you"
    const val FINISHED = "pi_finished"
    const val SYNC_PROBLEMS = "pi_sync_problems"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                channel(
                    id = NEEDS_YOU,
                    name = "Needs you",
                    description = "Blocking Pi requests and approvals",
                    importance = NotificationManager.IMPORTANCE_HIGH,
                ),
                channel(
                    id = FINISHED,
                    name = "Finished",
                    description = "Pi settlement notifications",
                    importance = NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    id = SYNC_PROBLEMS,
                    name = "Sync problems",
                    description = "Low-priority connection and catch-up problems",
                    importance = NotificationManager.IMPORTANCE_LOW,
                ),
            ),
        )
    }

    private fun channel(
        id: String,
        name: String,
        description: String,
        importance: Int,
    ): NotificationChannel = NotificationChannel(id, name, importance).apply {
        this.description = description
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        setShowBadge(true)
    }
}
