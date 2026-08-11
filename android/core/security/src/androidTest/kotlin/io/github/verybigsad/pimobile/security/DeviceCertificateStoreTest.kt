package io.github.verybigsad.pimobile.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import javax.security.auth.x500.X500Principal
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCertificateStoreTest {
    private val keys = DeviceKeys()

    @After
    fun cleanUp() {
        keys.deleteAll()
    }

    @Test
    fun installsValidatedClientChainOnOriginalKeystoreKeyAndRestrictsKeyManager() {
        val generated = keys.getOrCreate(DeviceCertificateTestData.DeviceId)
        val tlsPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(generated.tlsPublicKeySpki))
        val chain = DeviceCertificateTestData.chain(tlsPublicKey)

        val identity = keys.installClientCertificateChain(DeviceCertificateTestData.DeviceId, chain)
        assertThat(identity.deviceId).isEqualTo(DeviceCertificateTestData.DeviceId)
        assertThat(identity.leafFingerprint.matchesCertificate(chain.first())).isTrue()
        assertThat(identity.issuerFingerprint.matchesCertificate(chain.last())).isTrue()

        val manager = keys.tlsKeyManager(DeviceCertificateTestData.DeviceId)
        assertThat(manager.getClientAliases("EC", null).asList()).containsExactly(TlsAlias)
        assertThat(manager.getClientAliases("RSA", null)).isNull()
        assertThat(manager.chooseClientAlias(arrayOf("RSA", "EC"), null, null)).isEqualTo(TlsAlias)
        assertThat(manager.chooseServerAlias("EC", null, null)).isNull()
        assertThat(manager.getCertificateChain(RouteAlias)).isNull()
        assertThat(manager.getPrivateKey(RouteAlias)).isNull()
        assertThat(manager.getPrivateKey(TlsAlias)!!.encoded).isNull()
        assertThat(manager.getCertificateChain(TlsAlias)!!.map { Base64Url.encode(it.encoded) })
            .containsExactlyElementsIn(chain.map(Base64Url::encode)).inOrder()

        val message = "same-keystore-key".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(manager.getPrivateKey(TlsAlias))
            update(message)
            sign()
        }
        val leaf = CertificateFactory.getInstance("X.509").generateCertificate(chain.first().inputStream()) as X509Certificate
        assertThat(Signature.getInstance("SHA256withECDSA").run {
            initVerify(leaf.publicKey)
            update(message)
            verify(signature)
        }).isTrue()

        val stored = KeyStore.getInstance(KeyStoreProvider).apply { load(null) }.getEntry(TlsAlias, null) as KeyStore.PrivateKeyEntry
        assertThat(stored.certificateChain.map { Base64Url.encode(it.encoded) })
            .containsExactlyElementsIn(chain.map(Base64Url::encode)).inOrder()
        assertThat(stored.certificate.publicKey.encoded).isEqualTo(generated.tlsPublicKeySpki)
    }

    @Test
    fun rejectsWrongKeySanValidityAndChainOrderBeforeReplacingIdentity() {
        val generated = keys.getOrCreate(DeviceCertificateTestData.DeviceId)
        val tlsPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(generated.tlsPublicKeySpki))
        val original = (KeyStore.getInstance(KeyStoreProvider).apply { load(null) }
            .getEntry(TlsAlias, null) as KeyStore.PrivateKeyEntry).certificate.encoded

        assertFails {
            keys.installClientCertificateChain(
                DeviceCertificateTestData.DeviceId,
                DeviceCertificateTestData.chain(DeviceCertificateTestData.unrelatedKeyPair().public),
            )
        }
        assertFails {
            keys.installClientCertificateChain(
                DeviceCertificateTestData.DeviceId,
                DeviceCertificateTestData.chain(tlsPublicKey, "550e8400-e29b-41d4-a716-446655440001"),
            )
        }
        assertFails {
            keys.installClientCertificateChain(
                DeviceCertificateTestData.DeviceId,
                DeviceCertificateTestData.chain(tlsPublicKey, leafValidityMillis = 32L * 24 * 60 * 60 * 1000),
            )
        }
        val valid = DeviceCertificateTestData.chain(tlsPublicKey)
        assertFails { keys.installClientCertificateChain(DeviceCertificateTestData.DeviceId, valid.reversed()) }

        val after = (KeyStore.getInstance(KeyStoreProvider).apply { load(null) }
            .getEntry(TlsAlias, null) as KeyStore.PrivateKeyEntry).certificate.encoded
        assertThat(after).isEqualTo(original)
    }

    @Test
    fun keyManagerHonorsIssuerSelectionAndReturnsDefensiveChainArray() {
        val generated = keys.getOrCreate(DeviceCertificateTestData.DeviceId)
        val tlsPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(generated.tlsPublicKeySpki))
        val chain = DeviceCertificateTestData.chain(tlsPublicKey)
        keys.installClientCertificateChain(DeviceCertificateTestData.DeviceId, chain)
        val manager = keys.tlsKeyManager(DeviceCertificateTestData.DeviceId)
        val certificates = manager.getCertificateChain(TlsAlias)!!
        val issuer = certificates.last().subjectX500Principal
        assertThat(manager.getClientAliases("EC", arrayOf(issuer)).asList()).containsExactly(TlsAlias)
        assertThat(manager.getClientAliases("EC", arrayOf(X500Principal("CN=Other")))).isNull()
        certificates[0] = certificates.last()
        assertThat(manager.getCertificateChain(TlsAlias)!!.first().basicConstraints).isEqualTo(-1)
    }

    private fun assertFails(block: () -> Unit) {
        assertThat(runCatching(block).isFailure).isTrue()
    }
}
