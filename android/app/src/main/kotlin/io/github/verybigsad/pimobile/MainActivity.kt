package io.github.verybigsad.pimobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.notifications.NotificationPermissionPolicy
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.ui.AppActivityActions
import io.github.verybigsad.pimobile.ui.PiAppRoot

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel((application as PiMobileApplication).container) as T
        }
    }

    private val container: AppContainer get() = (application as PiMobileApplication).container

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        container.notificationPermission.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermissionOnUpdateEnable()
        handleIntent(intent)
        setContent {
            val state by viewModel.state.collectAsState()
            // Recents/thumbnails never show authenticated content.
            val secure = state.trust is io.github.verybigsad.pimobile.model.TrustState.Trusted
            if (secure) {
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            PiAppRoot(viewModel, activityActions())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as PiMobileApplication).passkeyBridge.attach(this)
        container.notificationPermission.refresh()
    }

    override fun onPause() {
        (application as PiMobileApplication).passkeyBridge.detach(this)
        super.onPause()
    }

    private fun activityActions(): AppActivityActions = AppActivityActions(
        onRequestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= 33) {
                container.notificationPermission.markRequested()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenChannelSettings = { channelId ->
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
                )
            }
        },
        onOpenAppNotificationSettings = {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        },
        onOpenInstallPermissionSettings = {
            runCatching { startActivity(container.updateIntegration.installPermissionIntent()) }
        },
    )

    /**
     * First time the assisted updater is enabled, ask for POST_NOTIFICATIONS once (Android 13+).
     * A denial never re-prompts; settings shows an honest banner instead.
     */
    private fun maybeRequestNotificationPermissionOnUpdateEnable() {
        val controller = container.notificationPermission
        if (NotificationPermissionPolicy.shouldRequestOnUpdateEnable(
                sdkInt = Build.VERSION.SDK_INT,
                granted = controller.grantedNow(),
                requestedBefore = controller.requestedBefore,
                updatesEnabled = container.updateIntegration.enabled,
            )
        ) {
            controller.markRequested()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Intents never route directly; the coordinator validates and holds pending destinations. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null && data.scheme == "pimobile" && data.host == "pair") {
            viewModel.submit(AppIntent.PairingUriScanned(data.toString()))
            return
        }
        if (data != null && data.scheme == "pimobile" && data.host == "update") {
            viewModel.submit(AppIntent.OpenUpdateSheet)
            return
        }
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf(String::isNotBlank)
            ?.let(::SessionId)
        if (sessionId != null) {
            viewModel.submit(AppIntent.DeepLink(sessionId))
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "io.github.verybigsad.pimobile.extra.SESSION_ID"
    }
}
