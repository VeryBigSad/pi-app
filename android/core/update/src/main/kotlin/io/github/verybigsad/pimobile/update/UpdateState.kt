package io.github.verybigsad.pimobile.update

/** Update candidate identified by versionCode, the sole ordering authority. */
data class UpdateCandidate(
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val etag: String? = null,
    val downloadedBytes: Long = 0L,
    val verified: Boolean = false,
) {
    val key: String get() = versionCode.toString()
}

sealed interface UpdateState {
    /** Debuggable build or user policy: updater never runs. */
    data object Disabled : UpdateState

    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Newer versionCode found; nothing downloaded. Never auto-downloads. */
    data class Available(val candidate: UpdateCandidate) : UpdateState

    /** Download granted but OS unknown-sources gate not yet open. */
    data class InstallPermissionRequired(val candidate: UpdateCandidate) : UpdateState

    data class Downloading(val candidate: UpdateCandidate) : UpdateState

    /** Download paused by the user; partial bytes retained on disk for resume. */
    data class Paused(val candidate: UpdateCandidate) : UpdateState

    data class Verifying(val candidate: UpdateCandidate) : UpdateState

    /** Hash + signature verified; authorization is bound to this exact candidate. */
    data class ReadyToInstall(val candidate: UpdateCandidate) : UpdateState

    /** Bytes being staged into a PackageInstaller session. */
    data class Staging(val candidate: UpdateCandidate, val sessionId: Int) : UpdateState

    /** Session committed; OS prompt pending. */
    data class AwaitingSystemConfirmation(val candidate: UpdateCandidate, val sessionId: Int) : UpdateState

    /** OS reported install in progress. */
    data class Installing(val candidate: UpdateCandidate, val sessionId: Int) : UpdateState

    data class Installed(val versionCode: Long) : UpdateState

    data class Failed(val code: String, val message: String, val candidate: UpdateCandidate? = null) : UpdateState
}

fun UpdateState.toPersistableCode(): String = when (this) {
    UpdateState.Disabled -> "DISABLED"
    UpdateState.Idle -> "IDLE"
    UpdateState.Checking -> "CHECKING"
    is UpdateState.Available -> "AVAILABLE"
    is UpdateState.InstallPermissionRequired -> "INSTALL_PERMISSION_REQUIRED"
    is UpdateState.Downloading -> "DOWNLOADING"
    is UpdateState.Paused -> "PAUSED"
    is UpdateState.Verifying -> "VERIFYING"
    is UpdateState.ReadyToInstall -> "READY_TO_INSTALL"
    is UpdateState.Staging -> "STAGING"
    is UpdateState.AwaitingSystemConfirmation -> "AWAITING_SYSTEM_CONFIRMATION"
    is UpdateState.Installing -> "INSTALLING"
    is UpdateState.Installed -> "INSTALLED"
    is UpdateState.Failed -> "FAILED"
}

/** Pure transition guard; illegal transitions throw [UpdateException]. */
object UpdateStateMachine {
    private val legal: Map<String, Set<String>> = mapOf(
        "DISABLED" to emptySet(),
        "IDLE" to setOf("CHECKING"),
        "CHECKING" to setOf("IDLE", "AVAILABLE", "FAILED", "DOWNLOADING", "PAUSED", "READY_TO_INSTALL"),
        "AVAILABLE" to setOf("INSTALL_PERMISSION_REQUIRED", "DOWNLOADING", "CHECKING", "IDLE", "FAILED"),
        "INSTALL_PERMISSION_REQUIRED" to setOf("DOWNLOADING", "AVAILABLE", "FAILED"),
        "DOWNLOADING" to setOf("VERIFYING", "PAUSED", "FAILED", "IDLE", "CHECKING"),
        "PAUSED" to setOf("DOWNLOADING", "FAILED", "IDLE", "CHECKING"),
        "VERIFYING" to setOf("READY_TO_INSTALL", "FAILED"),
        "READY_TO_INSTALL" to setOf("STAGING", "FAILED", "IDLE", "CHECKING"),
        "STAGING" to setOf("AWAITING_SYSTEM_CONFIRMATION", "FAILED"),
        "AWAITING_SYSTEM_CONFIRMATION" to setOf("INSTALLING", "INSTALLED", "FAILED", "READY_TO_INSTALL"),
        "INSTALLING" to setOf("INSTALLED", "FAILED"),
        "INSTALLED" to setOf("IDLE", "CHECKING"),
        "FAILED" to setOf("IDLE", "CHECKING"),
    )

    fun requireTransition(from: UpdateState, to: UpdateState) {
        val allowed = legal[from.toPersistableCode()].orEmpty()
        if (to.toPersistableCode() !in allowed) {
            throw UpdateException(
                UpdateError.METADATA_INVALID,
                "illegal transition ${from.toPersistableCode()} -> ${to.toPersistableCode()}",
            )
        }
    }

    fun canTransition(from: UpdateState, to: UpdateState): Boolean =
        to.toPersistableCode() in legal[from.toPersistableCode()].orEmpty()
}
