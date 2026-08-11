package io.github.verybigsad.pimobile.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.ZoneOffset
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val ACCEPT_TIMEOUT_MILLIS = 30_000
private const val PEER_IO_TIMEOUT_MILLIS = 10_000

@RunWith(AndroidJUnit4::class)
class TlsExporterAndroidTest {
    @Test
    fun pairingExporterMatchesAcrossLoopbackTls13Peers() = runBlocking {
        val serverCertificate = certificate("/pki/server-uuid-cert.crt")
        val clock = Clock.fixed(serverCertificate.notBefore.toInstant().plusSeconds(60), ZoneOffset.UTC)
        val identity = CertificateIdentity(CertificateRole.MAC_SERVER, "550e8400-e29b-41d4-a716-446655440000")
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(serverCertificate.encoded)
        // API 29 resolves InetAddress.getLoopbackAddress() to ::1; bind the IPv4 loopback
        // explicitly so the client address family always matches.
        val server = serverContext(serverCertificate).serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3")
        // Bound every blocking server-side call so a client-side failure fails the test
        // instead of parking runBlocking on a child that never leaves accept()/read().
        server.soTimeout = ACCEPT_TIMEOUT_MILLIS
        // Android API 29 ships the exporter contract but hides Conscrypt.exportKeyingMaterial
        // behind the non-SDK interface lists with no bundled org.conscrypt provider, so the
        // exporter is genuinely unavailable on this platform; skip explicitly there.
        assumeTrue(
            "TLS exporter requires reflective Conscrypt access, unavailable on API " + android.os.Build.VERSION.SDK_INT,
            TlsExporter.isSupported(SSLSocket::class.java) && TlsExporter.isSupported(javax.net.ssl.SSLEngine::class.java),
        )
        try {
            val peer = async(Dispatchers.IO) {
                (server.accept() as SSLSocket).use { socket ->
                    socket.enabledProtocols = arrayOf("TLSv1.3")
                    socket.soTimeout = PEER_IO_TIMEOUT_MILLIS
                    socket.startHandshake()
                    val exported = TlsExporter.export(socket)
                    val request = ByteArray(4)
                    var offset = 0
                    while (offset < request.size) {
                        val count = socket.inputStream.read(request, offset, request.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    socket.outputStream.write(exported)
                    socket.outputStream.flush()
                }
            }
            val raw = StreamByteChannel.connect("127.0.0.1", server.localPort)
            val tls = TlsByteChannel.connect(raw, TlsContexts.provisional(pin, identity, clock), "localhost", server.localPort)

            val clientExporter = tls.exportKeyingMaterial()
            assertThat(clientExporter).hasLength(TlsExporterBytes)
            tls.write(byteArrayOf(1, 2, 3, 4))
            val serverExporter = ByteArray(TlsExporterBytes)
            var offset = 0
            while (offset < serverExporter.size) {
                val count = tls.read(serverExporter, offset, serverExporter.size - offset)
                check(count > 0)
                offset += count
            }

            assertThat(clientExporter).isEqualTo(serverExporter)
            tls.close()
            peer.await()
        } finally {
            server.close()
        }
    }

    private fun certificate(path: String): X509Certificate = requireNotNull(javaClass.getResourceAsStream(path)).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    private fun privateKey(path: String): PrivateKey {
        val text = requireNotNull(javaClass.getResource(path)).readText()
        val encoded = text.lineSequence().filterNot { it.startsWith("---") }.joinToString("")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    private fun serverContext(certificate: X509Certificate): SSLContext {
        val password = "test-only".toCharArray()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                it.load(null)
                it.setKeyEntry("leaf", privateKey("/pki/server-uuid-key.pk8"), password, arrayOf(certificate))
            }
            init(store, password)
            keyManagers
        }
        return SSLContext.getInstance("TLSv1.3").also { it.init(keyManagers, null, null) }
    }
}
