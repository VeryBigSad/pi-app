package io.github.verybigsad.pimobile.security

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceKeysTest {
    private val keys = DeviceKeys()

    @After
    fun cleanUp() {
        keys.deleteAll()
    }

    @Test
    fun createsSeparateNonExportableP256TlsAndRouteIdentities() {
        val public = keys.getOrCreate(DeviceCertificateTestData.DeviceId)
        assertThat(public.tlsPublicKeySpki).isNotEqualTo(public.routePublicKeySpki)
        assertThat(public.csrDer.first().toInt() and 0xFF).isEqualTo(0x30)

        val store = KeyStore.getInstance(KeyStoreProvider).apply { load(null) }
        val tls = store.getEntry(TlsAlias, null) as KeyStore.PrivateKeyEntry
        val route = store.getEntry(RouteAlias, null) as KeyStore.PrivateKeyEntry
        assertThat(tls.privateKey.encoded).isNull()
        assertThat(route.privateKey.encoded).isNull()
        assertThat(tls.privateKey.algorithm).isEqualTo("EC")
        assertThat(route.privateKey.algorithm).isEqualTo("EC")
        val tlsInfo = keyInfo(tls)
        val routeInfo = keyInfo(route)
        assertThat(tlsInfo.digests.asList()).containsExactly(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
        assertThat(routeInfo.digests.asList()).containsExactly(KeyProperties.DIGEST_SHA256)
        assertThat(tlsInfo.keySize).isEqualTo(256)
        assertThat(routeInfo.keySize).isEqualTo(256)
        val report = keys.securityReport()
        assertThat(report.tlsInsideSecureHardware).isEqualTo(tlsInfo.isInsideSecureHardwareCompat())
        assertThat(report.routeInsideSecureHardware).isEqualTo(routeInfo.isInsideSecureHardwareCompat())
    }

    @Test
    fun routeProofSignsCanonicalJcsWithDerEcdsa() {
        val public = keys.getOrCreate(DeviceCertificateTestData.DeviceId)
        val now = Instant.now()
        val challenge = RouteChallenge.parse(
            """{"routeId":"route-1","nonce":"${Base64Url.encode(ByteArray(32) { 7 })}","expiresAt":"${now.plusSeconds(30)}","rendezvousId":"rv-1","keyId":"device-key-1","audience":"device-data"}""",
            now,
        )
        val proof = keys.signRouteChallenge(challenge)
        EcdsaDer.requireP256Signature(proof.signatureDer())
        val routePublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.routePublicKeySpki))
        assertThat(Signature.getInstance("SHA256withECDSA").run {
            initVerify(routePublic)
            update(challenge.canonicalSignedPayload())
            verify(proof.signatureDer())
        }).isTrue()
        assertThat(proof.toJsonObject().getValue("signed")).isEqualTo(challenge.signedPayload())
    }

    @Test
    fun routePayloadSignerSignsArbitraryBoundedCanonicalPayloads() {
        val public = keys.getOrCreate(DeviceCertificateTestData.DeviceId)
        val payload = """{"audience":"device-data","keyId":"device-key-1","nonce":"${Base64Url.encode(ByteArray(32) { 3 })}"}"""
            .encodeToByteArray()
        val signer = keys.routePayloadSigner()
        val signature = signer.sign(payload)

        EcdsaDer.requireP256Signature(signature)
        val routePublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.routePublicKeySpki))
        assertThat(Signature.getInstance("SHA256withECDSA").run {
            initVerify(routePublic)
            update(payload)
            verify(signature)
        }).isTrue()

        assertThat(runCatching { signer.sign(ByteArray(0)) }.isFailure).isTrue()
        assertThat(runCatching { signer.sign(ByteArray(MaxRoutePayloadBytes + 1)) }.isFailure).isTrue()
    }

    private fun keyInfo(entry: KeyStore.PrivateKeyEntry): KeyInfo =
        KeyFactory.getInstance(entry.privateKey.algorithm, KeyStoreProvider)
            .getKeySpec(entry.privateKey, KeyInfo::class.java)

    @Suppress("DEPRECATION")
    private fun KeyInfo.isInsideSecureHardwareCompat(): Boolean = isInsideSecureHardware
}
