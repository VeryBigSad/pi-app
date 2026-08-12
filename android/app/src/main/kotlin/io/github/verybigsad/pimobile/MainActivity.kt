package io.github.verybigsad.pimobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
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

    private var activeVoicePermissionRequestId: Long? = null
    private var benchmarkScenario: BenchmarkScenario? by mutableStateOf(null)

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestId = activeVoicePermissionRequestId ?: return@registerForActivityResult
        activeVoicePermissionRequestId = null
        viewModel.submit(
            AppIntent.VoicePermissionResult(
                requestId = requestId,
                granted = granted,
                permanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeVoicePermissionRequestId = if (savedInstanceState?.containsKey(VOICE_PERMISSION_REQUEST_ID) == true) {
            savedInstanceState.getLong(VOICE_PERMISSION_REQUEST_ID).takeIf { it != NO_VOICE_PERMISSION_REQUEST }
        } else {
            null
        }

        benchmarkScenario = BenchmarkScenario.fromIntent(intent)
        enableEdgeToEdge()
        if (benchmarkScenario == null) {
            maybeRequestNotificationPermissionOnUpdateEnable()
            handleIntent(intent)
        }
        setContent {
            val activeBenchmarkScenario = benchmarkScenario
            if (activeBenchmarkScenario != null) {
                BenchmarkTimelineScreen(activeBenchmarkScenario.runId)
            } else {
                val state by viewModel.state.collectAsState()
                val voicePermissionRequest = state.voicePermissionRequest
                LaunchedEffect(voicePermissionRequest?.requestId) {
                    val request = voicePermissionRequest ?: return@LaunchedEffect
                    if (
                        activeVoicePermissionRequestId != request.requestId &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        activeVoicePermissionRequestId = request.requestId
                        voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        benchmarkScenario = BenchmarkScenario.fromIntent(intent)
        if (benchmarkScenario == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (benchmarkScenario == null) {
            (application as PiMobileApplication).passkeyBridge.attach(this)
            container.notificationPermission.refresh()
            container.refreshPasskeyAvailability()
        }
    }

    override fun onPause() {
        if (benchmarkScenario == null) {
            activeVoicePermissionRequestId?.let { viewModel.submit(AppIntent.VoicePermissionCancelled(it)) }
            (application as PiMobileApplication).passkeyBridge.detach(this)
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(VOICE_PERMISSION_REQUEST_ID, activeVoicePermissionRequestId ?: NO_VOICE_PERMISSION_REQUEST)
        super.onSaveInstanceState(outState)
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
        onOpenVoicePermissionSettings = {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null)),
                )
            }
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
        internal const val EXTRA_BENCHMARK_SCENARIO = "io.github.verybigsad.pimobile.extra.BENCHMARK_SCENARIO"
        internal const val EXTRA_BENCHMARK_RUN_ID = "io.github.verybigsad.pimobile.extra.BENCHMARK_RUN_ID"
        private const val VOICE_PERMISSION_REQUEST_ID = "voice_permission_request_id"
        private const val NO_VOICE_PERMISSION_REQUEST = Long.MIN_VALUE
    }
}

internal data class BenchmarkScenario(
    val runId: Long,
) {
    companion object {
        fun fromIntent(intent: Intent): BenchmarkScenario? {
            if (!isBenchmarkHarnessBuildType(BuildConfig.BUILD_TYPE)) return null
            if (intent.getStringExtra(MainActivity.EXTRA_BENCHMARK_SCENARIO) != "large_timeline") return null
            val runId = intent.getLongExtra(MainActivity.EXTRA_BENCHMARK_RUN_ID, Long.MIN_VALUE)
            return runId.takeIf { it > 0L }?.let(::BenchmarkScenario)
        }
    }
}

internal fun isBenchmarkHarnessBuildType(buildType: String): Boolean =
    buildType == "benchmarkRelease" || buildType == "nonMinifiedRelease"
