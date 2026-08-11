package io.github.verybigsad.pimobile.update

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignatureVerifierInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realGetSigningCertificatesYieldsExactlyOneSigner() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val identity = SignatureVerifier.extractSigner(info)
        assertThat(identity.sha256ColonHex).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}")
    }

    @Test
    fun debugSignerDoesNotMatchReleasePin() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val identity = SignatureVerifier.extractSigner(info)
        assertThat(identity.sha256ColonHex.equals(UpdateConfig.CERTIFICATE_SHA256, ignoreCase = true)).isFalse()
    }
}
