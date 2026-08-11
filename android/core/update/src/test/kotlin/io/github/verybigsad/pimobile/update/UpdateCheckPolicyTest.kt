package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateCheckPolicyTest {
    @Test
    fun poisonCodesAreTerminal() {
        for (code in listOf(
            UpdateError.DISABLED,
            UpdateError.METADATA_INVALID,
            UpdateError.METADATA_TOO_LARGE,
            UpdateError.METADATA_UNTRUSTED,
            UpdateError.SIGNATURE_MISMATCH,
            UpdateError.SIGNATURE_UNREADABLE,
            UpdateError.AUTHORIZATION_MISMATCH,
            UpdateError.NOT_VERIFIED,
        )) {
            assertThat(UpdateCheckPolicy.isTerminal(code)).isTrue()
        }
    }

    @Test
    fun transientCodesAreNotTerminal() {
        assertThat(UpdateCheckPolicy.isTerminal(UpdateError.METADATA_FETCH_FAILED)).isFalse()
        assertThat(UpdateCheckPolicy.isTerminal(UpdateError.DOWNLOAD_FAILED)).isFalse()
    }
}
