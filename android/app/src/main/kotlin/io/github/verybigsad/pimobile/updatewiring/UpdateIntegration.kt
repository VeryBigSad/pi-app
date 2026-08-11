package io.github.verybigsad.pimobile.updatewiring

import android.content.Context
import android.content.Intent
import io.github.verybigsad.pimobile.update.ApkDownloader
import io.github.verybigsad.pimobile.update.MetadataFetcher
import io.github.verybigsad.pimobile.update.UpdateError
import io.github.verybigsad.pimobile.update.UpdateException
import io.github.verybigsad.pimobile.update.UpdateManager
import io.github.verybigsad.pimobile.update.UpdateManagerHolder
import io.github.verybigsad.pimobile.update.UpdateRuntime
import io.github.verybigsad.pimobile.update.UpdateScheduler
import io.github.verybigsad.pimobile.update.UpdateState
import io.github.verybigsad.pimobile.update.UpdateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-side composition of the assisted self-updater. Disabled in debuggable builds. */
class UpdateIntegration(context: Context, currentVersionCode: Long) {
    private val appContext = context.applicationContext
    private val integrationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val manager: UpdateManager = UpdateManager(
        context = context,
        store = UpdateStore(context.noBackupFilesDir.resolve("updates")),
        fetcher = MetadataFetcher(),
        downloader = ApkDownloader(),
        currentVersionCode = currentVersionCode,
    )

    val state: StateFlow<UpdateState> get() = manager.state

    @Volatile
    var enabled: Boolean = false
        private set

    /** Candidate versionCode awaiting an explicit metered-network confirmation; null otherwise. */
    private val mutableMeteredConfirmation = MutableStateFlow<Long?>(null)
    val meteredConfirmationRequired: StateFlow<Long?> = mutableMeteredConfirmation.asStateFlow()

    val lastCheckedEpochMillis: () -> Long? = {
        manager.store.read().lastCheckAtMillis.takeIf { it > 0L }
    }

    fun start(context: Context) {
        enabled = UpdateRuntime.isEnabled(context)
        if (!enabled) return
        UpdateManagerHolder.manager = manager
        UpdateScheduler.schedulePeriodic(context)
        manager.cleanup()
    }

    fun checkNow() = launch {
        runCatching { manager.checkForUpdate() }
    }

    /** User confirmed the download in the sheet; metered networks need a second explicit confirm. */
    fun confirmDownload(versionCode: Long) = launch {
        try {
            manager.requestDownload(versionCode)
            mutableMeteredConfirmation.value = null
        } catch (error: UpdateException) {
            if (error.code == UpdateError.METERED_CONFIRM_REQUIRED) {
                mutableMeteredConfirmation.value = versionCode
            }
        }
    }

    fun confirmMeteredDownload(versionCode: Long) = launch {
        mutableMeteredConfirmation.value = null
        runCatching { manager.requestDownload(versionCode, meteredConfirmed = true) }
    }

    fun pauseDownload() = launch {
        runCatching { manager.pauseDownload() }
    }

    fun resumeDownload() = launch {
        runCatching { manager.resumeDownload() }
    }

    fun cancelDownload() = launch {
        mutableMeteredConfirmation.value = null
        runCatching { manager.cancelDownload() }
    }

    fun authorizeInstall(versionCode: Long) = launch {
        runCatching { manager.authorizeInstall(versionCode) }
    }

    /** ACTION_MANAGE_UNKNOWN_APP_SOURCES for this app; the host activity starts it. */
    fun installPermissionIntent(): Intent = manager.unknownSourcesSettingsIntent()

    fun canInstallUnknownApps(): Boolean = manager.canInstallUnknownApps()

    private fun launch(block: suspend () -> Unit) {
        integrationScope.launch { block() }
    }
}
