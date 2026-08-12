package io.github.verybigsad.pimobile.wire

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class HostInboundRouterTest {
    private fun envelope(type: String): WireMessages.Envelope =
        WireMessages.parseEnvelope(WireMessages.encode(type, buildJsonObject { }))!!

    @Test
    fun `sync complete maps to SyncComplete event`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        router.handle(envelope("sync.complete"))
        assertThat(events).containsExactly(HostConnectionEvent.SyncComplete)
    }

    @Test
    fun `sync complete does not consume pending replay-through tracking`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        router.handle(envelope("sync.complete"))
        router.handle(envelope("sync.complete"))
        assertThat(events).hasSize(2)
        assertThat(events.all { it == HostConnectionEvent.SyncComplete }).isTrue()
    }
}
