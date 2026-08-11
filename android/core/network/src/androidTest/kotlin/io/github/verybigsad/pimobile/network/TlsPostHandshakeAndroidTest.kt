package io.github.verybigsad.pimobile.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Post-handshake delivery on the real Android TLS stack (Conscrypt): the server sends its
 * first application record immediately after the TLS 1.3 handshake — the flight in which a
 * TLS 1.3 server also emits its post-handshake NewSessionTicket records — and then waits
 * for the client's first application write (the PIMB pair.begin frame). Both directions
 * must make progress: the read side must deliver the server-first record, and the write
 * side must never stall behind pending post-handshake inbound.
 */
@RunWith(AndroidJUnit4::class)
class TlsPostHandshakeAndroidTest {

    @Test
    fun pairBeginReachesServerAfterPostHandshakeFlight(): Unit = runBlocking {
        val serverSocket = serverContext().serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val frame = PimbCodec.encode(FrameKind.Json, """{"type":"pair.begin","origin":"android"}""".encodeToByteArray())
        val server = async(Dispatchers.IO) {
            val socket = serverSocket.accept() as SSLSocket
            socket.use {
                it.startHandshake()
                // Server-first record right after the handshake, then wait for pair.begin.
                it.outputStream.write(GREETING)
                it.outputStream.flush()
                val received = it.inputStream.readExact(frame.size)
                it.outputStream.write(received)
                it.outputStream.flush()
                received
            }
        }
        val context = TlsContexts.provisional(
            expectedLeafSha256 = pin(),
            expectedIdentity = CertificateIdentity(CertificateRole.MAC_SERVER, MAC_INSTANCE_ID),
            clock = Clock.systemUTC(),
        )
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val channel = TlsByteChannel.connect(
                StreamByteChannel.connect("127.0.0.1", serverSocket.localPort),
                context,
                "localhost",
                serverSocket.localPort,
            )
            try {
                assertThat(channel.readExact(GREETING.size)).isEqualTo(GREETING)
                channel.write(frame)
                assertThat(channel.readExact(frame.size)).isEqualTo(frame)
            } finally {
                channel.close()
            }
        }
        assertThat(server.await()).isEqualTo(frame)
        serverSocket.close()
    }

    private suspend fun TlsByteChannel.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) break
            offset += count
        }
        check(offset == size) { "Channel closed after $offset of $size bytes" }
        return result
    }

    private fun java.io.InputStream.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) break
            offset += count
        }
        check(offset == size) { "Server stream closed after $offset of $size bytes" }
        return result
    }

    private fun pin(): ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate().encoded)

    private fun certificate(): X509Certificate = requireNotNull(javaClass.getResourceAsStream(CERT_RESOURCE)).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    private fun privateKey(): PrivateKey {
        val text = requireNotNull(javaClass.getResource(KEY_RESOURCE)).readText()
        val encoded = text.lineSequence().filterNot { it.startsWith("---") }.joinToString("")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    private fun serverContext(): SSLContext {
        val password = "test-only".toCharArray()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                it.load(null)
                it.setKeyEntry("leaf", privateKey(), password, arrayOf(certificate()))
            }
            init(store, password)
            keyManagers
        }
        return SSLContext.getInstance("TLSv1.3").also { it.init(keyManagers, null, null) }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 20_000L
        const val MAC_INSTANCE_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val CERT_RESOURCE = "/pki/server-uuid-cert.crt"
        const val KEY_RESOURCE = "/pki/server-uuid-key.pk8"
        val GREETING = "server-first-after-handshake".encodeToByteArray()
    }
}
