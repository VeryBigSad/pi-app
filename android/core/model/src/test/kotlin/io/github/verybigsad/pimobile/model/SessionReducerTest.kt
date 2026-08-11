package io.github.verybigsad.pimobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionReducerTest {
    private val sessionId = SessionId("session")
    private val macId = MacId("mac-1")

    @Test
    fun readySurvivesTrustRefreshWhenSerialMatchesAndNothingExpired() {
        val state = readyState(serial = "serial-a", trustNotAfter = 2_000, passkeyExpires = 1_500)
        val refreshed = reduce(state, SessionAction.TrustChanged(trusted("serial-a", 2_000)), now = 1_000)
        assertThat(refreshed.connection).isEqualTo(state.connection)
        assertThat(refreshed.trust).isEqualTo(trusted("serial-a", 2_000))
    }

    @Test
    fun certificateRotationDropsStaleReadyEvenWithSameMacId() {
        val state = readyState(serial = "serial-a", trustNotAfter = 2_000, passkeyExpires = 1_500)
        val rotated = reduce(state, SessionAction.TrustChanged(trusted("serial-b", 3_000)), now = 1_000)
        assertThat(rotated.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun readyWithMismatchedSerialIsRejectedOnArrival() {
        val trusted = trusted("serial-a", 2_000)
        var state = SessionState.initial(metadata()).copy(trust = trusted)
        val forgedReady = ConnectionState.Ready(
            path = TransportPath.DIRECT,
            macId = macId,
            userAuthentication = passkey(expires = 1_500),
            deviceAuthentication = MutualTlsAuthentication("serial-b", verifiedAtEpochMillis = 900),
        )
        state = reduce(state, SessionAction.ConnectionChanged(forgedReady), now = 1_000)
        assertThat(state.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun readyWithMismatchedMacIdIsRejectedOnArrival() {
        val trusted = trusted("serial-a", 2_000)
        var state = SessionState.initial(metadata()).copy(trust = trusted)
        val wrongMac = ConnectionState.Ready(
            path = TransportPath.DIRECT,
            macId = MacId("mac-2"),
            userAuthentication = passkey(expires = 1_500),
            deviceAuthentication = MutualTlsAuthentication("serial-a", verifiedAtEpochMillis = 900),
        )
        state = reduce(state, SessionAction.ConnectionChanged(wrongMac), now = 1_000)
        assertThat(state.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun expiredCertificateDropsReadyOnTrustRefresh() {
        val state = readyState(serial = "serial-a", trustNotAfter = 1_000, passkeyExpires = 1_500)
        val expired = reduce(state, SessionAction.TrustChanged(trusted("serial-a", 1_000)), now = 1_000)
        assertThat(expired.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun expiredCertificateReadyIsRejectedOnArrival() {
        val trusted = trusted("serial-a", 1_000)
        var state = SessionState.initial(metadata()).copy(trust = trusted)
        val ready = ConnectionState.Ready(
            path = TransportPath.RELAY,
            macId = macId,
            userAuthentication = passkey(expires = 5_000),
            deviceAuthentication = MutualTlsAuthentication("serial-a", verifiedAtEpochMillis = 900),
        )
        state = reduce(state, SessionAction.ConnectionChanged(ready), now = 1_000)
        assertThat(state.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun expiredPasskeyDowngradesReadyToDeviceAuthenticated() {
        val state = readyState(serial = "serial-a", trustNotAfter = 5_000, passkeyExpires = 1_000)
        val downgraded = reduce(state, SessionAction.TrustChanged(trusted("serial-a", 5_000)), now = 1_000)
        assertThat(downgraded.connection).isEqualTo(
            ConnectionState.DeviceAuthenticated(
                TransportPath.DIRECT,
                macId,
                MutualTlsAuthentication("serial-a", verifiedAtEpochMillis = 900),
            ),
        )
    }

    @Test
    fun expiredPasskeyReadyArrivesAsDeviceAuthenticated() {
        val trusted = trusted("serial-a", 5_000)
        var state = SessionState.initial(metadata()).copy(trust = trusted)
        val ready = ConnectionState.Ready(
            path = TransportPath.DIRECT,
            macId = macId,
            userAuthentication = passkey(expires = 1_000),
            deviceAuthentication = MutualTlsAuthentication("serial-a", verifiedAtEpochMillis = 900),
        )
        state = reduce(state, SessionAction.ConnectionChanged(ready), now = 1_000)
        assertThat(state.connection).isEqualTo(
            ConnectionState.DeviceAuthenticated(
                TransportPath.DIRECT,
                macId,
                MutualTlsAuthentication("serial-a", verifiedAtEpochMillis = 900),
            ),
        )
    }

    @Test
    fun revocationReplacesAnyConnectionWithRevokedState() {
        val state = readyState(serial = "serial-a", trustNotAfter = 2_000, passkeyExpires = 1_500)
        val revoked = reduce(state, SessionAction.TrustChanged(TrustState.Revoked(macId, 1_100, "user-request")), now = 1_000)
        assertThat(revoked.connection).isEqualTo(ConnectionState.Revoked(macId, 1_100))
        // A replayed stale READY after revocation is never re-accepted.
        val replayed = reduce(
            revoked,
            SessionAction.ConnectionChanged(state.connection),
            now = 1_100,
        )
        assertThat(replayed.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun processRestoredStaleReadyIsDroppedByTrustRefresh() {
        // Simulate a process restart: state restored from cache still carries a READY connection
        // authenticated with the pre-rotation certificate.
        val restored = readyState(serial = "serial-old", trustNotAfter = 9_000, passkeyExpires = 9_000)
        // Host rotated the certificate while the process was dead; trust refresh carries serial-new.
        val refreshed = reduce(restored, SessionAction.TrustChanged(trusted("serial-new", 9_000)), now = 1_000)
        assertThat(refreshed.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))

        // And a fresh READY arriving with the rotated certificate is accepted.
        val reauthenticated = reduce(
            refreshed,
            SessionAction.ConnectionChanged(
                ConnectionState.Ready(
                    path = TransportPath.DIRECT,
                    macId = macId,
                    userAuthentication = passkey(expires = 9_000),
                    deviceAuthentication = MutualTlsAuthentication("serial-new", verifiedAtEpochMillis = 1_100),
                ),
            ),
            now = 1_100,
        )
        assertThat(reauthenticated.connection).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun unpairedDropsConnectionAndRejectsReady() {
        val state = readyState(serial = "serial-a", trustNotAfter = 2_000, passkeyExpires = 1_500)
        val unpaired = reduce(state, SessionAction.TrustChanged(TrustState.Unpaired), now = 1_000)
        assertThat(unpaired.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
        val replayed = reduce(unpaired, SessionAction.ConnectionChanged(state.connection), now = 1_000)
        assertThat(replayed.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
    }

    @Test
    fun nonAuthenticatedConnectionsPassThroughUnchanged() {
        val connecting = ConnectionState.Connecting(TransportPath.RELAY, attempt = 2)
        val state = SessionState.initial(metadata()).copy(connection = connecting)
        val unchanged = reduce(state, SessionAction.TrustChanged(trusted("serial-a", 2_000)), now = 1_000)
        assertThat(unchanged.connection).isEqualTo(connecting)
    }

    @Test
    fun hostAuthExpiryReplacesReadyExpiryVerbatim() {
        val state = readyState(serial = "serial-a", trustNotAfter = 9_000, passkeyExpires = 9_000)
        val shortened = reduce(state, SessionAction.AuthExpiryReceived(1_200), now = 1_000)
        val ready = shortened.connection as ConnectionState.Ready
        assertThat(ready.userAuthentication.expiresAtEpochMillis).isEqualTo(1_200)

        val extended = reduce(state, SessionAction.AuthExpiryReceived(9_500), now = 1_000)
        assertThat(
            (extended.connection as ConnectionState.Ready).userAuthentication.expiresAtEpochMillis,
        ).isEqualTo(9_500)
    }

    @Test
    fun hostAuthExpiryInThePastDowngradesReadyToDeviceAuthenticated() {
        val state = readyState(serial = "serial-a", trustNotAfter = 9_000, passkeyExpires = 9_000)
        val downgraded = reduce(state, SessionAction.AuthExpiryReceived(1_000), now = 1_500)
        assertThat(downgraded.connection).isEqualTo(
            ConnectionState.DeviceAuthenticated(
                TransportPath.DIRECT,
                macId,
                MutualTlsAuthentication("serial-a", verifiedAtEpochMillis = 900),
            ),
        )
    }

    @Test
    fun hostAuthExpiryAtOrBeforeVerificationDowngradesImmediately() {
        val state = readyState(serial = "serial-a", trustNotAfter = 9_000, passkeyExpires = 9_000)
        val downgraded = reduce(state, SessionAction.AuthExpiryReceived(900), now = 905)
        assertThat(downgraded.connection).isInstanceOf(ConnectionState.DeviceAuthenticated::class.java)
    }

    @Test
    fun hostAuthExpiryIsIgnoredOutsideReady() {
        val state = SessionState.initial(metadata()).copy(trust = trusted("serial-a", 9_000))
        val unchanged = reduce(state, SessionAction.AuthExpiryReceived(1_200), now = 1_000)
        assertThat(unchanged).isEqualTo(state)
    }

    private fun reduce(state: SessionState, action: SessionAction, now: Long): SessionState =
        SessionReducer.reduce(state, action, now)

    private fun readyState(serial: String, trustNotAfter: Long, passkeyExpires: Long): SessionState =
        SessionState.initial(metadata()).copy(
            trust = trusted(serial, trustNotAfter),
            connection = ConnectionState.Ready(
                path = TransportPath.DIRECT,
                macId = macId,
                userAuthentication = passkey(expires = passkeyExpires),
                deviceAuthentication = MutualTlsAuthentication(serial, verifiedAtEpochMillis = 900),
            ),
        )

    private fun trusted(serial: String, notAfter: Long) = TrustState.Trusted(
        macId = macId,
        macDisplayName = "Work Mac",
        certificateSerial = serial,
        certificateNotAfterEpochMillis = notAfter,
    )

    private fun passkey(expires: Long) = PasskeyAuthentication(
        assertionId = "assertion-1",
        verifiedAtEpochMillis = 900,
        expiresAtEpochMillis = expires,
    )

    private fun metadata() = SessionMetadata(
        id = sessionId,
        macId = macId,
        displayName = "Session",
        repositoryPath = "/repo",
        worktreePath = "/repo",
        parentSessionId = null,
        updatedAtEpochMillis = 1,
    )
}
