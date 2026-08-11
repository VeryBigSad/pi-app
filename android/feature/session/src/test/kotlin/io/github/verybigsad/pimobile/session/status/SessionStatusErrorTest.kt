package io.github.verybigsad.pimobile.session.status

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.DisconnectReason
import org.junit.Test

class SessionStatusErrorTest {
    @Test
    fun mapsKnownFailuresWithoutRetainingExceptionMessage() {
        assertThat(SessionStatusError.from(java.net.UnknownHostException("internal-host.example")))
            .isEqualTo(SessionStatusError.NETWORK)
        assertThat(SessionStatusError.from(java.net.SocketTimeoutException("secret transport detail")))
            .isEqualTo(SessionStatusError.TIMEOUT)
        assertThat(SessionStatusError.from(SecurityException("credential value")))
            .isEqualTo(SessionStatusError.AUTHENTICATION)
        assertThat(SessionStatusError.from(RuntimeException("sensitive exception detail")))
            .isEqualTo(SessionStatusError.UNKNOWN)
    }

    @Test
    fun offlineMessageIncludesLastSeenOnlyWhenProvided() {
        assertThat(offlineMessage(DisconnectReason.NETWORK_LOST, "2 minutes ago"))
            .isEqualTo("Network connection was lost. Last seen 2 minutes ago.")
        assertThat(offlineMessage(DisconnectReason.HOST_UNAVAILABLE, null))
            .isEqualTo("Your Mac is unavailable.")
    }
}
