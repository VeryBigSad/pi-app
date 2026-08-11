package io.github.verybigsad.pimobile.network

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.sha256Hex
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 29 contract: the TLS exporter is hidden behind the non-SDK interface lists, so
 * pairing must bind through the in-channel `pairingToken` (sha256 -> `sessionBinding`)
 * without touching the exporter. These tests pin both halves of that contract.
 */
@RunWith(AndroidJUnit4::class)
class PairingTokenApi29Test {
    @Test
    fun exporterIsUnavailableOnApi29() {
        assumeTrue(Build.VERSION.SDK_INT == 29)
        assertThat(TlsExporter.isSupported(SSLSocket::class.java)).isFalse()
        assertThat(TlsExporter.isSupported(SSLEngine::class.java)).isFalse()
    }

    @Test
    fun pairingTokenBindingIsPureInChannelCrypto() {
        val token = ByteArray(PairingTokenBytes) { (it * 7).toByte() }
        val encoded = encodeBase64Url(token)
        assertThat(decodeBase64Url(encoded, PairingTokenBytes, exactBytes = PairingTokenBytes)).isEqualTo(token)
        val sessionBinding = sha256Hex(token)
        assertThat(sessionBinding).matches("^[0-9a-f]{64}$")
        // Deterministic cross-check against an independent SHA-256 computation.
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(token).joinToString("") { "%02x".format(it) }
        assertThat(sessionBinding).isEqualTo(expected)
    }

    @Test
    fun pairingTokenRejectsWrongLengthAndEncoding() {
        listOf(
            encodeBase64Url(ByteArray(31)),
            encodeBase64Url(ByteArray(33)),
            "!!!!",
            java.util.Base64.getUrlEncoder().encodeToString(ByteArray(PairingTokenBytes)),
        ).forEach { candidate ->
            assertThat(
                runCatching { decodeBase64Url(candidate, PairingTokenBytes, exactBytes = PairingTokenBytes) }.isFailure,
            ).isTrue()
        }
    }
}
