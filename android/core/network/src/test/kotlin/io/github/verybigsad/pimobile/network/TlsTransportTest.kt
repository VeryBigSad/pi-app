package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.time.Clock
import java.time.ZoneOffset
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TlsTransportTest {
    private val clock = Clock.fixed(TestPki.server.notBefore.toInstant().plusSeconds(60), ZoneOffset.UTC)
    private val serverIdentity = CertificateIdentity(CertificateRole.MAC_SERVER, "test-mac")

    @Test
    fun validatesExactServerAndDeviceCertificateProfiles() {
        CertificateProfileValidator(serverIdentity, clock).validate(TestPki.server)
        CertificateProfileValidator(CertificateIdentity(CertificateRole.ANDROID_DEVICE, "test-device"), clock).validate(TestPki.client)

        val wrongIdentity = runCatching {
            CertificateProfileValidator(CertificateIdentity(CertificateRole.MAC_SERVER, "other"), clock).validate(TestPki.server)
        }.exceptionOrNull()
        assertThat(wrongIdentity).isNotNull()
        val revoked = runCatching {
            CertificateProfileValidator(serverIdentity, clock, CertificateRevocationChecker { true }).validate(TestPki.server)
        }.exceptionOrNull()
        assertThat(revoked).isNotNull()
    }

    @Test
    fun provisionalTlsPinsExactLeafAndCarriesBytesOverRealSocket() = runBlocking {
        val server = sslServer(requireClient = false)
        val peer = async(Dispatchers.IO) { echoOnce(server) }
        val raw = StreamByteChannel.connect("localhost", server.localPort)
        val context = TlsContexts.provisional(TestPki.serverPin(), serverIdentity, clock)

        val tls = TlsByteChannel.connect(raw, context, "localhost", server.localPort)
        tls.write("client-data".encodeToByteArray())
        val response = ByteArray(64)
        val count = tls.read(response)

        assertThat(response.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("server-data")
        tls.close()
        peer.await()
        server.close()
    }

    @Test
    fun provisionalTlsRejectsOneBitPinChange() = runBlocking {
        val server = sslServer(requireClient = false)
        val peer = async(Dispatchers.IO) { runCatching { echoOnce(server) } }
        val pin = TestPki.serverPin().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val raw = StreamByteChannel.connect("localhost", server.localPort)

        val error = runCatching {
            TlsByteChannel.connect(raw, TlsContexts.provisional(pin, serverIdentity, clock), "localhost", server.localPort)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(NetworkException::class.java)
        peer.await()
        server.close()
    }

    @Test
    fun provisionalTlsRejectsOptionalClientCertificateRequest() = runBlocking {
        val server = sslServer(requireClient = false, requestClient = true)
        val peer = async(Dispatchers.IO) { runCatching { echoOnce(server) } }
        val raw = StreamByteChannel.connect("localhost", server.localPort)

        val error = runCatching {
            TlsByteChannel.connect(
                raw,
                TlsContexts.provisional(TestPki.serverPin(), serverIdentity, clock),
                "localhost",
                server.localPort,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(NetworkException::class.java)
        peer.await()
        server.close()
    }

    @Test
    fun normalTlsUsesInjectedKeyManagersForRealMutualTls() = runBlocking {
        val server = sslServer(requireClient = true)
        val peer = async(Dispatchers.IO) { echoOnce(server) }
        val context = TlsContexts.mutual(
            TestPki.clientKeyManagers(),
            TestPki.trustManagers(),
            serverIdentity,
            clock,
        )
        val engine = context.newEngine("localhost", server.localPort)
        assertThat(engine.enabledProtocols.asList()).containsExactly("TLSv1.3")
        val raw = StreamByteChannel.connect("localhost", server.localPort)

        val tls = TlsByteChannel.connect(raw, context, "localhost", server.localPort)
        tls.write("client-data".encodeToByteArray())
        val response = ByteArray(64)
        val count = tls.read(response)

        assertThat(response.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("server-data")
        tls.close()
        peer.await()
        server.close()
    }

    @Test
    fun provisionalTlsDrivesUuidSanIdentityFromInvitationMacInstanceId() = runBlocking {
        val certificate = TestPki.serverUuid
        val pin = TestPki.serverUuidPin()
        val identity = CertificateIdentity(CertificateRole.MAC_SERVER, "550e8400-e29b-41d4-a716-446655440000")
        val certClock = Clock.fixed(certificate.notBefore.toInstant().plusSeconds(60), ZoneOffset.UTC)
        val server = TestPki.serverUuidContext().serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3")
        val peer = async(Dispatchers.IO) { echoOnce(server) }
        val raw = StreamByteChannel.connect("localhost", server.localPort)

        val tls = TlsByteChannel.connect(raw, TlsContexts.provisional(pin, identity, certClock), "localhost", server.localPort)
        tls.write("client-data".encodeToByteArray())
        val response = ByteArray(64)
        val count = tls.read(response)

        assertThat(response.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("server-data")
        tls.close()
        peer.await()
        server.close()

        val wrongIdentity = runCatching {
            CertificateProfileValidator(CertificateIdentity(CertificateRole.MAC_SERVER, "550e8400-e29b-41d4-a716-446655440099"), certClock)
                .validate(certificate)
        }.exceptionOrNull()
        assertThat(wrongIdentity).isNotNull()
    }

    @Test
    fun tlsExporterIsUnavailableWithoutConscrypt() {
        val engine = TlsContexts.provisional(TestPki.serverPin(), serverIdentity, clock).newEngine("localhost", 443)
        val error = runCatching { TlsExporter.export(engine) }.exceptionOrNull()
        assertThat((error as? NetworkException)?.code).isEqualTo(NetworkError.EXPORTER_UNSUPPORTED)
    }

    private fun sslServer(requireClient: Boolean, requestClient: Boolean = requireClient): SSLServerSocket = (TestPki.serverContext(requestClient).serverSocketFactory
        .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).also {
        it.enabledProtocols = arrayOf("TLSv1.3")
        if (requireClient) it.needClientAuth = true else it.wantClientAuth = requestClient
    }

    private fun echoOnce(server: SSLServerSocket) {
        (server.accept() as SSLSocket).use { socket ->
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()
            val request = ByteArray("client-data".length)
            var offset = 0
            while (offset < request.size) {
                val count = socket.inputStream.read(request, offset, request.size - offset)
                check(count > 0)
                offset += count
            }
            check(request.toString(Charsets.UTF_8) == "client-data")
            socket.outputStream.write("server-data".encodeToByteArray())
            socket.outputStream.flush()
        }
    }
}
