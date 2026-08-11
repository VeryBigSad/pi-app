package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageInstallerStatusMapperTest {
    @Test
    fun successMapsToNullCode() {
        val event = InstallStatusCodes.map(InstallStatusCodes.SUCCESS, "ok")
        assertThat(event.state).isEqualTo(InstallStatusState.SUCCESS)
        assertThat(event.code).isNull()
    }

    @Test
    fun pendingUserActionMaps() {
        val event = InstallStatusCodes.map(InstallStatusCodes.PENDING_USER_ACTION, "")
        assertThat(event.state).isEqualTo(InstallStatusState.USER_ACTION_REQUIRED)
        assertThat(event.code).isEqualTo(UpdateError.USER_ACTION_REQUIRED)
    }

    @Test
    fun abortedMapsToCancelled() {
        assertThat(InstallStatusCodes.map(InstallStatusCodes.FAILURE_ABORTED, "").code)
            .isEqualTo(UpdateError.INSTALL_CANCELLED)
    }

    @Test
    fun conflictMapsToSignatureMismatch() {
        assertThat(InstallStatusCodes.map(InstallStatusCodes.FAILURE_CONFLICT, "").code)
            .isEqualTo(UpdateError.SIGNATURE_MISMATCH)
    }

    @Test
    fun storageMapsToInsufficientSpace() {
        assertThat(InstallStatusCodes.map(InstallStatusCodes.FAILURE_STORAGE, "").code)
            .isEqualTo(UpdateError.DOWNLOAD_INSUFFICIENT_SPACE)
    }

    @Test
    fun unknownMapsToInstallFailed() {
        assertThat(InstallStatusCodes.map(-99, "weird").code).isEqualTo(UpdateError.INSTALL_FAILED)
    }

    @Test
    fun constantsMirrorPackageInstaller() {
        // android.content.pm.PackageInstaller platform values; guard against silent drift.
        assertThat(InstallStatusCodes.SUCCESS).isEqualTo(0)
        assertThat(InstallStatusCodes.PENDING_USER_ACTION).isEqualTo(1)
        assertThat(InstallStatusCodes.FAILURE).isEqualTo(2)
        assertThat(InstallStatusCodes.FAILURE_BLOCKED).isEqualTo(3)
        assertThat(InstallStatusCodes.FAILURE_ABORTED).isEqualTo(4)
        assertThat(InstallStatusCodes.FAILURE_INVALID).isEqualTo(5)
        assertThat(InstallStatusCodes.FAILURE_CONFLICT).isEqualTo(6)
        assertThat(InstallStatusCodes.FAILURE_STORAGE).isEqualTo(7)
        assertThat(InstallStatusCodes.FAILURE_INCOMPATIBLE).isEqualTo(8)
    }

    @Test
    fun genericFailureMapsToInstallFailed() {
        assertThat(InstallStatusCodes.map(InstallStatusCodes.FAILURE, "x").code)
            .isEqualTo(UpdateError.INSTALL_FAILED)
    }

    @Test
    fun untrustedMessageIsSanitizedAndTruncated() {
        val raw = "evil\u0000\n\t" + "x".repeat(400)
        val event = InstallStatusCodes.mapUntrusted(InstallStatusCodes.FAILURE, raw)
        assertThat(event.message.length).isAtMost(InstallStatusCodes.DISPLAY_MESSAGE_MAX)
        assertThat(event.message).doesNotContain("\u0000")
        assertThat(event.message).doesNotContain("\n")
        assertThat(event.message).doesNotContain("\t")
    }
}
