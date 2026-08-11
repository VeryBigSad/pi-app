package io.github.verybigsad.pimobile.update

import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallFlowInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unknownSourcesGateReflectsPackageManager() {
        val canInstall = context.packageManager.canRequestPackageInstalls()
        // Test packages cannot hold REQUEST_INSTALL_PACKAGES; the gate must fail closed.
        assertThat(canInstall).isFalse()
    }

    @Test
    fun unknownSourcesSettingsIntentResolves() {
        val manager = UpdateManager(
            context = context,
            store = UpdateStore(context.noBackupFilesDir.resolve("updates-test")),
            fetcher = MetadataFetcher(metadataUrl = "https://127.0.0.1/never"),
            downloader = ApkDownloader(),
            currentVersionCode = 1L,
        )
        val intent = manager.unknownSourcesSettingsIntent()
        assertThat(intent.action).isEqualTo(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        val resolved = intent.resolveActivity(context.packageManager)
        assertThat(resolved).isNotNull()
    }

    @Test
    fun noSessionOpenedBeforeVerified() {
        val store = UpdateStore(context.noBackupFilesDir.resolve("updates-unverified"))
        val unverified = PersistedCandidate(
            versionCode = 99L,
            versionName = "9.9.9",
            publishedAt = "2026-08-11T00:00:00Z",
            releasePageUrl = "https://example.com",
            apkUrl = "https://example.com/a.apk",
            apkSizeBytes = 10L,
            apkSha256 = "a".repeat(64),
            verified = false,
            createdAtMillis = 1L,
        )
        store.write(PersistedUpdateSnapshot(highWaterMark = 99L, candidate = unverified, authorizedVersionCode = 99L))
        val controller = UpdateInstallController(context, store)
        val candidate = unverified.toDomain()
        val sessionsBefore = UpdateManager.installerSessionCount(context)
        val error = runCatching {
            controller.stageAndCommit(candidate, store.candidateFile(candidate.versionCode))
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.NOT_VERIFIED)
        assertThat(UpdateManager.installerSessionCount(context)).isEqualTo(sessionsBefore)
    }

    @Test
    fun stageAndCommitRejectsTamperedBytes() {
        // TOCTOU: verified + authorized candidate, but the on-disk APK no longer matches the hash.
        val store = UpdateStore(context.noBackupFilesDir.resolve("updates-tampered"))
        val tampered = ByteArray(64) { 1 }
        val persisted = PersistedCandidate(
            versionCode = 98L,
            versionName = "9.9.8",
            publishedAt = "2026-08-11T00:00:00Z",
            releasePageUrl = "https://example.com",
            apkUrl = "https://example.com/a.apk",
            apkSizeBytes = tampered.size.toLong(),
            apkSha256 = "b".repeat(64),
            verified = true,
            createdAtMillis = 1L,
        )
        store.write(PersistedUpdateSnapshot(highWaterMark = 98L, candidate = persisted, authorizedVersionCode = 98L))
        store.candidateFile(98L).writeBytes(tampered)
        val controller = UpdateInstallController(context, store)
        val sessionsBefore = UpdateManager.installerSessionCount(context)
        val error = runCatching {
            controller.stageAndCommit(persisted.toDomain(), store.candidateFile(98L))
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_HASH_MISMATCH)
        assertThat(UpdateManager.installerSessionCount(context)).isEqualTo(sessionsBefore)
    }

    @Test
    fun downloadWorkIsUnique() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        UpdateScheduler.enqueueDownload(context, 2L)
        UpdateScheduler.enqueueDownload(context, 2L)
        val infos = workManager.getWorkInfosForUniqueWork(ApkDownloadWorker.UNIQUE).get()
        assertThat(infos.size).isEqualTo(1)
        assertThat(infos[0].state).isAnyOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)
    }

    @Test
    fun periodicCheckWorkIsUnique() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        UpdateScheduler.schedulePeriodic(context)
        UpdateScheduler.schedulePeriodic(context)
        val infos = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.UNIQUE_PERIODIC).get()
        assertThat(infos.size).isEqualTo(1)
    }

    @Test
    fun debuggableBuildDisablesUpdater() = runTest {
        val manager = UpdateManager(
            context = context,
            store = UpdateStore(context.noBackupFilesDir.resolve("updates-debug")),
            fetcher = MetadataFetcher(metadataUrl = "https://127.0.0.1/never"),
            downloader = ApkDownloader(),
            currentVersionCode = 1L,
        )
        // androidTest target is debuggable: manager must refuse to check.
        assertThat(manager.state.value).isEqualTo(UpdateState.Disabled)
        val error = runCatching { manager.checkForUpdate() }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DISABLED)
        assertThat(ListenableWorker.Result.success()).isNotNull()
    }
}
