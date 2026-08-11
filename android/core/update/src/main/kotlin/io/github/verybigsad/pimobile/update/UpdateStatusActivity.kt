package io.github.verybigsad.pimobile.update

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Hosts the OS confirmation flow: on API 31+ PackageInstaller delivers USER_ACTION_REQUIRED with a
 * fill-in intent that this activity launches so the system prompt can appear.
 */
class UpdateStatusActivity : Activity() {
    private var lastIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        lastIntent = intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // PackageInstaller USER_ACTION_REQUIRED delivers an IntentSender in EXTRA_INTENT.
            val fillInSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(Intent.EXTRA_INTENT, android.content.IntentSender::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (fillInSender != null) {
                runCatching {
                    @Suppress("DEPRECATION")
                    startIntentSenderForResult(fillInSender, REQUEST_CONFIRM, null, 0, 0, 0)
                }
            }
        }
        // On API 29/30 the OS shows its own prompt; this activity only anchors the task.
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIRM) {
            // RESULT_OK only means the user approved the prompt; the authoritative outcome is the
            // PackageInstaller status broadcast. Never emit Installed from here.
            if (resultCode != RESULT_OK) {
                lastIntent?.getLongExtra(UpdateStatusReceiver.EXTRA_VERSION_CODE, -1L)?.let { versionCode ->
                    UpdateEvents.emit(
                        InstallCallback(
                            versionCode,
                            InstallStatusEvent(
                                InstallStatusState.FAILURE,
                                UpdateError.INSTALL_CANCELLED,
                                "user confirmation result $resultCode",
                            ),
                        ),
                    )
                }
            }
            finish()
        }
    }

    companion object {
        private const val REQUEST_CONFIRM = 0x51
    }
}
