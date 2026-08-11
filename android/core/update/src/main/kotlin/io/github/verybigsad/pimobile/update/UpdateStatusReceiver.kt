package io.github.verybigsad.pimobile.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives PackageInstaller status callbacks; hands off to the running activity or the manager. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, -1L)
        val event = PackageInstallerStatusMapper.map(intent)
        UpdateEvents.emit(InstallCallback(versionCode, event))
        // Bring up the status UI so an OS user-action prompt has somewhere to land.
        val launch = Intent(context, UpdateStatusActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_VERSION_CODE, versionCode)
        }
        runCatching { context.startActivity(launch) }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.verybigsad.pimobile.update.INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "versionCode"
    }
}

data class InstallCallback(val versionCode: Long, val event: InstallStatusEvent)
