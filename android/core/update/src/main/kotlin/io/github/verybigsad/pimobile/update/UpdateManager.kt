package io.github.verybigsad.pimobile.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Assisted self-update orchestrator. Automatic behavior is limited to checks; every download,
 * install permission request, and install commit requires an explicit user action in the app.
 * Authorization is bound to the exact candidate versionCode.
 */
class UpdateManager(
    private val context: Context,
    val store: UpdateStore,
    private val fetcher: MetadataFetcher,
    private val downloader: ApkDownloader,
    private val currentVersionCode: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val installController = UpdateInstallController(context, store)

    @Volatile
    private var activeDownloadCall: okhttp3.Call? = null

    init {
        if (!UpdateRuntime.isEnabled(context)) {
            _state.value = UpdateState.Disabled
        } else {
            restore()
        }
    }

    private fun restore() {
        val snapshot = store.read()
        val candidate = snapshot.candidate?.toDomain()
        when {
            candidate == null -> _state.value = UpdateState.Idle
            candidate.versionCode <= currentVersionCode -> {
                // Never resurrect an already-installed/current version as actionable state.
                cleanupCandidate(candidate.versionCode)
                store.mutate { it.copy(candidate = null, authorizedVersionCode = null, sessionId = null) }
                _state.value = UpdateState.Idle
            }
            candidate.verified && store.candidateFile(candidate.versionCode).isFile ->
                _state.value = UpdateState.ReadyToInstall(candidate)
            else -> _state.value = UpdateState.Available(candidate)
        }
        installController.abandonOrphanSessions()
        cleanup()
        scope.launch {
            UpdateEvents.installCallbacks.collect { callback -> onInstallCallback(callback) }
        }
    }

    /** Automatic check path (periodic/expedited worker) and manual "check now". */
    suspend fun checkForUpdate(): UpdateState {
        ensureEnabled()
        val current = _state.value
        // A candidate is in flight: periodic checks are no-ops, never transitions.
        when (current) {
            is UpdateState.Downloading,
            is UpdateState.Paused,
            is UpdateState.Verifying,
            is UpdateState.ReadyToInstall,
            is UpdateState.Staging,
            is UpdateState.AwaitingSystemConfirmation,
            is UpdateState.Installing,
            -> return current
            else -> Unit
        }
        transition(UpdateState.Checking)
        return try {
            val metadata = UpdateMetadataParser.parse(fetcher.fetch())
            val outcome = evaluate(metadata)
            transition(outcome)
            outcome
        } catch (error: UpdateException) {
            val failed = UpdateState.Failed(error.code, error.message.orEmpty())
            transition(failed)
            failed
        }
    }

    private fun evaluate(metadata: UpdateMetadata): UpdateState {
        val snapshot = store.read()
        val highWaterMark = maxOf(snapshot.highWaterMark, currentVersionCode)
        if (metadata.versionCode <= highWaterMark) {
            store.mutate { it.copy(lastCheckAtMillis = System.currentTimeMillis()) }
            return UpdateState.Idle
        }
        val candidate = UpdateCandidate(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            publishedAt = metadata.publishedAt,
            releasePageUrl = metadata.releasePageUrl,
            apkUrl = metadata.apk.url,
            apkSizeBytes = metadata.apk.sizeBytes,
            apkSha256 = metadata.apk.sha256,
        )
        store.mutate {
            it.copy(
                candidate = candidate.toPersisted(System.currentTimeMillis()),
                highWaterMark = metadata.versionCode,
                authorizedVersionCode = null,
                sessionId = null,
                lastCheckAtMillis = System.currentTimeMillis(),
            )
        }
        return UpdateState.Available(candidate)
    }

    /**
     * User tapped "download". On metered networks requires [meteredConfirmed]; never downloads
     * anything that was not selected from the Available state.
     */
    suspend fun requestDownload(candidateVersionCode: Long, meteredConfirmed: Boolean = false): UpdateState {
        ensureEnabled()
        val current = _state.value
        val candidate = when (current) {
            is UpdateState.Available -> current.candidate
            is UpdateState.InstallPermissionRequired -> current.candidate
            is UpdateState.Paused -> current.candidate
            else -> throw UpdateException(UpdateError.METADATA_INVALID, "no available candidate")
        }
        if (candidate.versionCode != candidateVersionCode) {
            throw UpdateException(UpdateError.AUTHORIZATION_MISMATCH, "candidate mismatch")
        }
        if (isMetered() && !meteredConfirmed) {
            throw UpdateException(UpdateError.METERED_CONFIRM_REQUIRED, "confirm metered download")
        }
        val downloading = UpdateState.Downloading(candidate)
        transition(downloading)
        UpdateScheduler.enqueueDownload(context, candidate.versionCode)
        return downloading
    }

    /** User tapped "pause": keeps partial bytes on disk; the worker's failure is ignored. */
    suspend fun pauseDownload(): UpdateState {
        ensureEnabled()
        val current = _state.value
        if (current !is UpdateState.Downloading) {
            throw UpdateException(UpdateError.METADATA_INVALID, "no active download")
        }
        val paused = UpdateState.Paused(current.candidate)
        transition(paused)
        activeDownloadCall?.cancel()
        UpdateScheduler.cancelDownload(context)
        return paused
    }

    /** User tapped "resume": continues from retained partial bytes; authorization carries over. */
    suspend fun resumeDownload(): UpdateState {
        ensureEnabled()
        val current = _state.value
        if (current !is UpdateState.Paused) {
            throw UpdateException(UpdateError.METADATA_INVALID, "no paused download")
        }
        val downloading = UpdateState.Downloading(current.candidate)
        transition(downloading)
        UpdateScheduler.enqueueDownload(context, current.candidate.versionCode)
        return downloading
    }

    /** User tapped "cancel": discards partial bytes and the persisted candidate. */
    suspend fun cancelDownload(): UpdateState {
        ensureEnabled()
        val current = _state.value
        val candidate = when (current) {
            is UpdateState.Downloading -> current.candidate
            is UpdateState.Paused -> current.candidate
            else -> throw UpdateException(UpdateError.METADATA_INVALID, "no download to cancel")
        }
        activeDownloadCall?.cancel()
        UpdateScheduler.cancelDownload(context)
        cleanupCandidate(candidate.versionCode)
        store.mutate { it.copy(candidate = null, authorizedVersionCode = null, sessionId = null) }
        val idle = UpdateState.Idle
        transition(idle)
        return idle
    }

    /** Worker entry point; performs download + verification for the persisted candidate. */
    suspend fun runDownload(expectedVersionCode: Long): androidx.work.ListenableWorker.Result {
        ensureEnabled()
        val candidate = store.read().candidate?.toDomain()
            ?: return androidx.work.ListenableWorker.Result.failure()
        // Authorization is bound end-to-end to the exact candidate the user selected.
        if (candidate.versionCode != expectedVersionCode) {
            val failed = UpdateState.Failed(
                UpdateError.AUTHORIZATION_MISMATCH,
                "persisted ${candidate.versionCode} != requested $expectedVersionCode",
                candidate,
            )
            // Poison record; legal from DOWNLOADING/PAUSED/AVAILABLE, forced otherwise.
            if (UpdateStateMachine.canTransition(_state.value, failed)) transition(failed) else _state.value = failed
            return androidx.work.ListenableWorker.Result.failure()
        }
        if (candidate.versionCode <= currentVersionCode) return androidx.work.ListenableWorker.Result.success()
        // Worker may run after a process restart where the in-memory state was never Downloading.
        if (_state.value !is UpdateState.Downloading) {
            _state.value = UpdateState.Downloading(candidate)
        }
        return try {
            val outcome = downloader.download(
                candidate,
                store.candidateFile(candidate.versionCode),
                store.partialFile(candidate.versionCode),
                onProgress = { bytes ->
                    val current = _state.value
                    if (current is UpdateState.Downloading) {
                        _state.value = current.copy(candidate = current.candidate.copy(downloadedBytes = bytes))
                    }
                },
                onCall = { call -> activeDownloadCall = call },
            )
            val downloaded = candidate.copy(downloadedBytes = outcome.bytesWritten, etag = outcome.etag)
            store.mutate { it.copy(candidate = downloaded.toPersisted(System.currentTimeMillis())) }
            transition(UpdateState.Verifying(downloaded))
            withContext(Dispatchers.IO) {
                SignatureVerifier.verifyAgainstPin(context.packageManager, outcome.file)
            }
            val verified = downloaded.copy(verified = true)
            store.mutate { it.copy(candidate = verified.toPersisted(System.currentTimeMillis())) }
            val ready = UpdateState.ReadyToInstall(verified)
            transition(ready)
            UpdateNotifier(context).notifyReady(verified)
            androidx.work.ListenableWorker.Result.success()
        } catch (error: UpdateException) {
            // User-initiated pause/cancel wins over worker-reported failure.
            if (_state.value is UpdateState.Paused || _state.value is UpdateState.Idle) {
                return androidx.work.ListenableWorker.Result.success()
            }
            transition(UpdateState.Failed(error.code, error.message.orEmpty(), candidate))
            when (error.code) {
                UpdateError.DOWNLOAD_FAILED -> androidx.work.ListenableWorker.Result.retry()
                else -> androidx.work.ListenableWorker.Result.failure()
            }
        }
    }

    /** User tapped "install". Binds the one-time authorization to the exact candidate. */
    suspend fun authorizeInstall(candidateVersionCode: Long): UpdateState {
        ensureEnabled()
        val current = _state.value
        val candidate = when (current) {
            is UpdateState.ReadyToInstall -> current.candidate
            is UpdateState.Available -> current.candidate
            is UpdateState.InstallPermissionRequired -> current.candidate
            else -> throw UpdateException(UpdateError.METADATA_INVALID, "nothing to authorize")
        }
        if (candidate.versionCode != candidateVersionCode) {
            throw UpdateException(UpdateError.AUTHORIZATION_MISMATCH, "authorization mismatch")
        }
        if (!canInstallUnknownApps()) {
            val gate = UpdateState.InstallPermissionRequired(candidate)
            transition(gate)
            return gate
        }
        store.mutate { it.copy(authorizedVersionCode = candidateVersionCode) }
        if (!candidate.verified) {
            return requestDownload(candidateVersionCode, meteredConfirmed = true)
        }
        val sessionId = withContext(Dispatchers.IO) {
            installController.stageAndCommit(candidate, store.candidateFile(candidate.versionCode))
        }
        val staging = UpdateState.Staging(candidate, sessionId)
        transition(staging)
        val awaiting = UpdateState.AwaitingSystemConfirmation(candidate, sessionId)
        transition(awaiting)
        return awaiting
    }

    /** Unknown-sources guidance; opens ACTION_MANAGE_UNKNOWN_APP_SOURCES for this app. */
    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun canInstallUnknownApps(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun onInstallCallback(callback: InstallCallback) {
        val current = _state.value
        val candidate = when (current) {
            is UpdateState.AwaitingSystemConfirmation -> current.candidate
            is UpdateState.Installing -> current.candidate
            is UpdateState.Staging -> current.candidate
            else -> null
        } ?: return
        if (callback.versionCode != candidate.versionCode) return
        when (callback.event.state) {
            InstallStatusState.USER_ACTION_REQUIRED ->
                transition(UpdateState.AwaitingSystemConfirmation(candidate, store.read().sessionId ?: -1))
            InstallStatusState.SUCCESS -> {
                transition(UpdateState.Installed(candidate.versionCode))
                cleanupCandidate(candidate.versionCode)
            }
            InstallStatusState.FAILURE ->
                transition(UpdateState.Failed(callback.event.code ?: UpdateError.INSTALL_FAILED, callback.event.message, candidate))
        }
    }

    /** One candidate at a time, 7-day retention, orphan session abandonment. */
    fun cleanup() {
        val snapshot = store.read()
        val candidate = snapshot.candidate
        val now = System.currentTimeMillis()
        if (candidate != null && now - candidate.createdAtMillis > UpdateConfig.CANDIDATE_RETENTION_MILLIS) {
            cleanupCandidate(candidate.versionCode)
            store.mutate { it.copy(candidate = null, authorizedVersionCode = null, sessionId = null) }
        }
        installController.abandonOrphanSessions()
        val keep = store.read().candidate?.versionCode
        storeDir()?.listFiles()?.forEach { file ->
            val isLive = keep != null && file.name.contains("candidate-$keep")
            val isState = file.name.startsWith("state.")
            if (!isLive && !isState) file.delete()
        }
    }

    private fun cleanupCandidate(versionCode: Long) {
        store.candidateFile(versionCode).delete()
        store.partialFile(versionCode).delete()
    }

    private fun storeDir(): File? = store.candidateFile(0L).parentFile

    private fun isMetered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return connectivity.isActiveNetworkMetered
    }

    private fun ensureEnabled() {
        if (!UpdateRuntime.isEnabled(context)) {
            _state.value = UpdateState.Disabled
            throw UpdateException(UpdateError.DISABLED, "updater disabled in debuggable build")
        }
    }

    private fun transition(to: UpdateState) {
        UpdateStateMachine.requireTransition(_state.value, to)
        _state.value = to
    }

    companion object {
        fun installerSessionCount(context: Context): Int =
            context.packageManager.packageInstaller.mySessions.count {
                it.appPackageName == UpdateConfig.PACKAGE_NAME
            }

        fun hasInstallPermission(context: Context): Boolean =
            context.packageManager.canRequestPackageInstalls()
    }
}
