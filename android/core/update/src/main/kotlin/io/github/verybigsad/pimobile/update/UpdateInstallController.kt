package io.github.verybigsad.pimobile.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Stages a verified APK into a PackageInstaller MODE_FULL_INSTALL session and commits it with an
 * explicit internal PendingIntent. Refuses to open any session for a candidate that was not
 * verified — the [UpdateStore] snapshot is the authority.
 */
class UpdateInstallController(private val context: Context, private val store: UpdateStore) {
    fun stageAndCommit(candidate: UpdateCandidate, apkFile: File): Int {
        val snapshot = store.read()
        val persisted = snapshot.candidate
        if (persisted == null || persisted.versionCode != candidate.versionCode || !persisted.verified) {
            throw UpdateException(UpdateError.NOT_VERIFIED, "no verified candidate ${candidate.versionCode}")
        }
        if (snapshot.authorizedVersionCode != candidate.versionCode) {
            throw UpdateException(UpdateError.AUTHORIZATION_MISMATCH, "install not authorized for ${candidate.versionCode}")
        }
        // Verify BEFORE createSession so a rejected candidate never allocates a
        // PackageInstaller session (abandonment is asynchronous on some API levels).
        reverifyCandidateBytes(candidate, apkFile)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(UpdateConfig.PACKAGE_NAME)
            setSize(candidate.apkSizeBytes)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (error: Exception) {
            throw UpdateException(UpdateError.STAGING_FAILED, "createSession failed", error)
        }
        try {
            installer.openSession(sessionId).use { session ->
                // Second verification immediately before write closes the TOCTOU window.
                reverifyCandidateBytes(candidate, apkFile)
                session.openWrite("base.apk", 0, candidate.apkSizeBytes).use { out ->
                    apkFile.inputStream().buffered().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val statusIntent = Intent(context, UpdateStatusReceiver::class.java).apply {
                    action = UpdateStatusReceiver.ACTION_INSTALL_STATUS
                    putExtra(UpdateStatusReceiver.EXTRA_VERSION_CODE, candidate.versionCode)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: UpdateException) {
            abandonQuietly(installer, sessionId)
            throw error
        } catch (error: Exception) {
            abandonQuietly(installer, sessionId)
            throw UpdateException(UpdateError.STAGING_FAILED, "staging failed", error)
        }
        store.mutate { it.copy(sessionId = sessionId) }
        return sessionId
    }

    fun abandonOrphanSessions() {
        val installer = context.packageManager.packageInstaller
        val persistedSession = store.read().sessionId
        for (session in installer.mySessions) {
            if (session.appPackageName == UpdateConfig.PACKAGE_NAME && session.sessionId != persistedSession) {
                abandonQuietly(installer, session.sessionId)
            }
        }
    }

    private fun reverifyCandidateBytes(candidate: UpdateCandidate, apkFile: File) {
        if (!apkFile.isFile || apkFile.length() != candidate.apkSizeBytes) {
            throw UpdateException(UpdateError.DOWNLOAD_SIZE_MISMATCH, "staged file size drift")
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().buffered().use { input ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                digest.update(chunk, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(candidate.apkSha256, ignoreCase = true)) {
            throw UpdateException(UpdateError.DOWNLOAD_HASH_MISMATCH, "staged file hash drift")
        }
        SignatureVerifier.verifyAgainstPin(context.packageManager, apkFile)
    }

    private fun abandonQuietly(installer: PackageInstaller, sessionId: Int) {
        runCatching { installer.abandonSession(sessionId) }
    }
}
