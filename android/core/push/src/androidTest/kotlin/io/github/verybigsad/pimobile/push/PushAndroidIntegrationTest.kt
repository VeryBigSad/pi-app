package io.github.verybigsad.pimobile.push

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushAndroidIntegrationTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        PushNotificationChannels.create(context)
    }

    @After
    fun tearDown() {
        listOf(
            PushNotificationChannels.NEEDS_YOU,
            PushNotificationChannels.FINISHED,
            PushNotificationChannels.SYNC_PROBLEMS,
        ).forEach(notificationManager::deleteNotificationChannel)
    }

    @Test
    fun createsThreePrivatePurposeSpecificChannels() {
        val needsYou = notificationManager.getNotificationChannel(PushNotificationChannels.NEEDS_YOU)
        val finished = notificationManager.getNotificationChannel(PushNotificationChannels.FINISHED)
        val syncProblems = notificationManager.getNotificationChannel(PushNotificationChannels.SYNC_PROBLEMS)

        assertThat(needsYou.name.toString()).isEqualTo("Needs you")
        assertThat(needsYou.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(finished.name.toString()).isEqualTo("Finished")
        assertThat(finished.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        assertThat(syncProblems.name.toString()).isEqualTo("Sync problems")
        assertThat(syncProblems.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        listOf(needsYou, finished, syncProblems).forEach { channel ->
            assertThat(channel.lockscreenVisibility).isNotEqualTo(Notification.VISIBILITY_PUBLIC)
            assertThat(channel.description).doesNotContain("session")
            assertThat(channel.description).doesNotContain("prompt")
            assertThat(channel.description).doesNotContain("result")
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun pushServiceIsPrivateAndAppRetainsApi29Floor() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, UnifiedPushService::class.java),
            0,
        )

        assertThat(service.exported).isFalse()
        assertThat(service.enabled).isTrue()
        assertThat(context.applicationInfo.minSdkVersion).isEqualTo(29)
    }

    @Test
    fun api29WithoutDistributorReportsProviderUnavailable() {
        assertThat(UnifiedPushClient(context).refreshProviderState()).isEqualTo(
            UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.NO_DISTRIBUTOR),
        )
    }

    @Test
    fun firebaseMessagingIsNotPackaged() {
        val firebaseClass = runCatching {
            Class.forName("com.google.firebase.messaging.FirebaseMessagingService")
        }.getOrNull()

        assertThat(firebaseClass).isNull()
    }
}
