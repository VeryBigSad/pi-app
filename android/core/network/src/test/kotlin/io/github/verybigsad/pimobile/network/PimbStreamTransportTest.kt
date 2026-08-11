package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PimbStreamTransportTest {
    @Test
    fun carriesFragmentedAndCoalescedPimbFramesOverRealSocket() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val first = PimbCodec.encode(FrameKind.Json, "{}".encodeToByteArray())
        val second = PimbCodec.encode(FrameKind.TerminalBytes, ByteArray(16))
        val outbound = PimbCodec.encode(FrameKind.Json, "{\"ok\":true}".encodeToByteArray())
        val peer = async(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.outputStream.write(first, 0, 5)
                socket.outputStream.flush()
                socket.outputStream.write(first.copyOfRange(5, first.size) + second)
                socket.outputStream.flush()
                val received = ByteArray(outbound.size)
                var offset = 0
                while (offset < received.size) {
                    val count = socket.inputStream.read(received, offset, received.size - offset)
                    check(count > 0)
                    offset += count
                }
                check(received.contentEquals(outbound))
            }
        }
        val transport = PimbStreamTransport(StreamByteChannel.connect("localhost", server.localPort))

        val decodedFirst = transport.receive()
        val decodedSecond = transport.receive()
        transport.send(FrameKind.Json, "{\"ok\":true}".encodeToByteArray())

        assertThat(decodedFirst.kind).isEqualTo(FrameKind.Json)
        assertThat(decodedFirst.payload.toString(Charsets.UTF_8)).isEqualTo("{}")
        assertThat(decodedSecond.kind).isEqualTo(FrameKind.TerminalBytes)
        peer.await()
        transport.close()
        server.close()
    }
}
