package io.github.verybigsad.pimobile.network

import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal object TestPki {
    val ca: X509Certificate by lazy { certificate("/pki/ca-cert.crt") }
    val server: X509Certificate by lazy { certificate("/pki/server-cert.crt") }
    val client: X509Certificate by lazy { certificate("/pki/client-cert.crt") }
    val serverUuid: X509Certificate by lazy { certificate("/pki/server-uuid-cert.crt") }
    val serverKey: PrivateKey by lazy { privateKey("/pki/server-key.pk8") }
    val clientKey: PrivateKey by lazy { privateKey("/pki/client-key.pk8") }
    val serverUuidKey: PrivateKey by lazy { privateKey("/pki/server-uuid-key.pk8") }

    fun serverContext(requireClient: Boolean = false): SSLContext {
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(keyManagers(serverKey, server), if (requireClient) trustManagers() else null, null)
        return context
    }

    fun serverUuidContext(): SSLContext {
        val context = SSLContext.getInstance("TLSv1.3")
        val password = "test-only".toCharArray()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            val store = KeyStore.getInstance("JKS").also {
                it.load(null)
                it.setKeyEntry("leaf", serverUuidKey, password, arrayOf(serverUuid))
            }
            init(store, password)
            keyManagers
        }
        context.init(keyManagers, null, null)
        return context
    }

    fun serverUuidPin(): ByteArray = MessageDigest.getInstance("SHA-256").digest(serverUuid.encoded)

    fun clientKeyManagers() = keyManagers(clientKey, client)

    fun trustManagers() = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).also {
            it.load(null)
            it.setCertificateEntry("ca", ca)
        }
        init(store)
        trustManagers
    }

    fun serverPin(): ByteArray = MessageDigest.getInstance("SHA-256").digest(server.encoded)

    private fun keyManagers(key: PrivateKey, certificate: X509Certificate) = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
        val password = "test-only".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).also {
            it.load(null)
            it.setKeyEntry("leaf", key, password, arrayOf(certificate, ca))
        }
        init(store, password)
        keyManagers
    }

    private fun certificate(path: String): X509Certificate = requireNotNull(javaClass.getResourceAsStream(path)).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    private fun privateKey(path: String): PrivateKey {
        val text = requireNotNull(javaClass.getResource(path)).readText()
        val encoded = text.lineSequence().filterNot { it.startsWith("---") }.joinToString("")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }
}
