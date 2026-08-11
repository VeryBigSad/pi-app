package io.github.verybigsad.pimobile.update

import android.content.Intent
import android.content.pm.PackageInstaller

data class InstallStatusEvent(
    val state: InstallStatusState,
    val code: String?,
    val message: String,
)

enum class InstallStatusState { USER_ACTION_REQUIRED, SUCCESS, FAILURE }

/** Pure status-int mapping, kept JVM-testable; ints mirror PackageInstaller constants. */
object InstallStatusCodes {
    // Values are android.content.pm.PackageInstaller.STATUS_* platform constants.
    const val SUCCESS = 0
    const val PENDING_USER_ACTION = 1
    const val FAILURE = 2
    const val FAILURE_BLOCKED = 3
    const val FAILURE_ABORTED = 4
    const val FAILURE_INVALID = 5
    const val FAILURE_CONFLICT = 6
    const val FAILURE_STORAGE = 7
    const val FAILURE_INCOMPATIBLE = 8

    const val DISPLAY_MESSAGE_MAX = 256

    /** Status messages are OS-supplied, UI-bound strings: strip controls, bound length. */
    fun sanitizeDisplayMessage(message: String): String {
        val cleaned = message.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .trim().replace(Regex(" +"), " ")
        return if (cleaned.length <= DISPLAY_MESSAGE_MAX) cleaned else cleaned.take(DISPLAY_MESSAGE_MAX)
    }

    fun map(status: Int, message: String): InstallStatusEvent = when (status) {
        SUCCESS ->
            InstallStatusEvent(InstallStatusState.SUCCESS, null, message)
        PENDING_USER_ACTION ->
            InstallStatusEvent(InstallStatusState.USER_ACTION_REQUIRED, UpdateError.USER_ACTION_REQUIRED, message)
        FAILURE_ABORTED ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.INSTALL_CANCELLED, message)
        FAILURE_BLOCKED ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.INSTALL_FAILED, "blocked: $message")
        FAILURE_CONFLICT ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.SIGNATURE_MISMATCH, "conflict: $message")
        FAILURE_INCOMPATIBLE ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.INSTALL_FAILED, "incompatible: $message")
        FAILURE_INVALID ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.INSTALL_FAILED, "invalid: $message")
        FAILURE_STORAGE ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.DOWNLOAD_INSUFFICIENT_SPACE, message)
        else ->
            InstallStatusEvent(InstallStatusState.FAILURE, UpdateError.INSTALL_FAILED, message)
    }

    fun mapUntrusted(status: Int, rawMessage: String): InstallStatusEvent =
        map(status, sanitizeDisplayMessage(rawMessage))
}

/** Maps PackageInstaller status broadcast extras to stable update error codes. */
object PackageInstallerStatusMapper {
    fun map(intent: Intent): InstallStatusEvent {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        return InstallStatusCodes.mapUntrusted(status, message)
    }
}
