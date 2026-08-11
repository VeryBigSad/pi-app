package io.github.verybigsad.pimobile.model

sealed interface TrustState {
    data object Unpaired : TrustState

    data class Trusted(
        val macId: MacId,
        val macDisplayName: String,
        val certificateSerial: String,
        val certificateNotAfterEpochMillis: Long,
    ) : TrustState {
        init {
            require(macDisplayName.isNotBlank())
            require(certificateSerial.isNotBlank())
            require(certificateNotAfterEpochMillis >= 0)
        }
    }

    data class Revoked(
        val macId: MacId,
        val revokedAtEpochMillis: Long,
        val reasonCode: String,
    ) : TrustState {
        init {
            require(revokedAtEpochMillis >= 0)
            require(reasonCode.isNotBlank())
        }
    }
}

enum class TransportPath {
    DIRECT,
    RELAY,
}

enum class DisconnectReason {
    NEVER_CONNECTED,
    PROCESS_DEATH,
    NETWORK_LOST,
    HOST_UNAVAILABLE,
    AUTH_REQUIRED,
    TRUST_REQUIRED,
}

data class PasskeyAuthentication(
    val assertionId: String,
    val verifiedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(assertionId.isNotBlank())
        require(verifiedAtEpochMillis >= 0)
        require(expiresAtEpochMillis > verifiedAtEpochMillis)
    }
}

data class MutualTlsAuthentication(
    val certificateSerial: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        require(certificateSerial.isNotBlank())
        require(verifiedAtEpochMillis >= 0)
    }
}

sealed interface ConnectionState {
    data class Disconnected(val reason: DisconnectReason) : ConnectionState

    data class Connecting(
        val path: TransportPath,
        val attempt: Int,
    ) : ConnectionState {
        init {
            require(attempt > 0)
        }
    }

    /** Pairing provisional state never authorizes application data. */
    data class PairingProvisional(
        val path: TransportPath,
        val invitationId: String,
    ) : ConnectionState {
        init {
            require(invitationId.isNotBlank())
        }
    }

    data class DeviceAuthenticated(
        val path: TransportPath,
        val macId: MacId,
        val deviceAuthentication: MutualTlsAuthentication,
    ) : ConnectionState

    /** Ready requires both current passkey verification and mTLS device authentication. */
    data class Ready(
        val path: TransportPath,
        val macId: MacId,
        val userAuthentication: PasskeyAuthentication,
        val deviceAuthentication: MutualTlsAuthentication,
    ) : ConnectionState

    data class Revoked(
        val macId: MacId,
        val revokedAtEpochMillis: Long,
    ) : ConnectionState {
        init {
            require(revokedAtEpochMillis >= 0)
        }
    }
}
