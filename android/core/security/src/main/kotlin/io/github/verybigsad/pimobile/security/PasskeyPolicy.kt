package io.github.verybigsad.pimobile.security

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object PasskeyIdentity {
    const val PackageName = "io.github.verybigsad.pimobile"
    const val RpId = "verybigsad.github.io"
    const val AndroidOrigin = "android:apk-key-hash:zDZm83fOTCvWzhmkfzq-RxmsBA_WT_sRnwLprEvd1P4"
}

enum class PasskeyProviderKind {
    PLAY_SERVICES,
    FRAMEWORK,
}

enum class PasskeyLockReason {
    APPLICATION_IDENTITY_MISMATCH,
    PLAY_SERVICES_PROVIDER_REQUIRED,
    FRAMEWORK_PROVIDER_REQUIRED,
    PROVIDER_CONFIGURATION_MISSING,
}

sealed interface PasskeyAvailability {
    data class Available(
        val kind: PasskeyProviderKind,
        val providerCandidateCount: Int,
        val supportsThirdPartyProviders: Boolean,
    ) : PasskeyAvailability

    data class CandidateAvailable(
        val providerCandidateCount: Int,
    ) : PasskeyAvailability

    data class Locked(val reason: PasskeyLockReason) : PasskeyAvailability
}

object PasskeyProviderMatrix {
    fun evaluate(
        sdkInt: Int,
        playServicesAvailable: Boolean,
        frameworkProviderCandidateCount: Int,
    ): PasskeyAvailability {
        require(sdkInt >= 29)
        require(frameworkProviderCandidateCount >= 0)
        return if (sdkInt <= 33) {
            if (playServicesAvailable) {
                PasskeyAvailability.Available(PasskeyProviderKind.PLAY_SERVICES, 1, false)
            } else {
                PasskeyAvailability.Locked(PasskeyLockReason.PLAY_SERVICES_PROVIDER_REQUIRED)
            }
        } else if (frameworkProviderCandidateCount > 0) {
            PasskeyAvailability.CandidateAvailable(frameworkProviderCandidateCount)
        } else {
            PasskeyAvailability.Locked(PasskeyLockReason.FRAMEWORK_PROVIDER_REQUIRED)
        }
    }
}

internal enum class PasskeyCeremonyType(val clientDataType: String) {
    REGISTRATION("webauthn.create"),
    ASSERTION("webauthn.get"),
}

internal data class ValidatedPasskeyRequest(
    val type: PasskeyCeremonyType,
    val challenge: ByteArray,
)

internal object PasskeyPolicy {
    private const val MaxOptionsBytes = 64 * 1024
    private const val MaxResponseBytes = 128 * 1024
    private const val MaxClientDataBytes = 8 * 1024
    private const val MaxAuthenticatorDataBytes = 8 * 1024

    fun registration(optionsJson: String): ValidatedPasskeyRequest {
        val options = StrictJson.objectValue(optionsJson, MaxOptionsBytes, maxDepth = 16, maxArrayItems = 64)
        val rp = options.requireObject("rp")
        require(rp.requireString("id", 253) == PasskeyIdentity.RpId)
        val selection = options.requireObject("authenticatorSelection")
        require(selection.requireString("residentKey", 16) == "required")
        require(selection.requireBoolean("requireResidentKey"))
        require(selection.requireString("userVerification", 16) == "required")
        require(options.requireString("attestation", 16) == "none")
        return ValidatedPasskeyRequest(
            PasskeyCeremonyType.REGISTRATION,
            challenge(options.requireString("challenge", 1024)),
        )
    }

    fun assertion(optionsJson: String): ValidatedPasskeyRequest {
        val options = StrictJson.objectValue(optionsJson, MaxOptionsBytes, maxDepth = 16, maxArrayItems = 64)
        require(options.requireString("rpId", 253) == PasskeyIdentity.RpId)
        require(options.requireString("userVerification", 16) == "required")
        validateAllowCredentials(options["allowCredentials"])
        return ValidatedPasskeyRequest(
            PasskeyCeremonyType.ASSERTION,
            challenge(options.requireString("challenge", 1024)),
        )
    }

    fun validateRegistrationResponse(responseJson: String, request: ValidatedPasskeyRequest) {
        require(request.type == PasskeyCeremonyType.REGISTRATION)
        val response = response(responseJson)
        validateClientData(response.requireString("clientDataJSON", 12 * 1024), request)
        Base64Url.decode(response.requireString("attestationObject", 128 * 1024), maxBytes = 96 * 1024)
    }

    fun validateAssertionResponse(responseJson: String, request: ValidatedPasskeyRequest) {
        require(request.type == PasskeyCeremonyType.ASSERTION)
        val response = response(responseJson)
        validateClientData(response.requireString("clientDataJSON", 12 * 1024), request)
        val authenticatorData = Base64Url.decode(
            response.requireString("authenticatorData", 12 * 1024),
            maxBytes = MaxAuthenticatorDataBytes,
        )
        require(authenticatorData.size >= 37)
        val expectedRpHash = MessageDigest.getInstance("SHA-256").digest(PasskeyIdentity.RpId.encodeToByteArray())
        require(MessageDigest.isEqual(authenticatorData.copyOfRange(0, 32), expectedRpHash))
        val flags = authenticatorData[32].toInt() and 0xff
        require(flags and 0x01 != 0)
        require(flags and 0x04 != 0)
        Base64Url.decode(response.requireString("signature", 2048), maxBytes = 1536)
    }

    private val credentialIdPattern = Regex("^[A-Za-z0-9_-]{1,1024}$")

    private fun validateAllowCredentials(allowCredentials: kotlinx.serialization.json.JsonElement?) {
        if (allowCredentials == null) return
        require(allowCredentials is kotlinx.serialization.json.JsonArray && allowCredentials.size <= 64)
        allowCredentials.forEach { entry ->
            require(entry is JsonObject)
            entry.requireExactKeys(setOf("type", "id"), optional = setOf("transports"))
            require(entry.requireString("type", 32) == "public-key")
            require(credentialIdPattern.matches(entry.requireString("id", 1024)))
            val transports = entry["transports"]
            if (transports != null) {
                require(transports is kotlinx.serialization.json.JsonArray && transports.size <= 8)
                transports.forEach { transport ->
                    require(transport is JsonPrimitive && transport.isString && transport.content.length in 1..64)
                }
            }
        }
    }

    private fun response(responseJson: String): JsonObject {
        val credential = StrictJson.objectValue(responseJson, MaxResponseBytes, maxDepth = 16, maxArrayItems = 64)
        require(credential.requireString("type", 32) == "public-key")
        val id = Base64Url.decode(credential.requireString("id", 2048), maxBytes = 1536)
        val rawId = Base64Url.decode(credential.requireString("rawId", 2048), maxBytes = 1536)
        require(MessageDigest.isEqual(id, rawId))
        return credential.requireObject("response")
    }

    private fun validateClientData(encoded: String, request: ValidatedPasskeyRequest) {
        val bytes = Base64Url.decode(encoded, MaxClientDataBytes)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.encodeToByteArray().contentEquals(bytes))
        val clientData = StrictJson.objectValue(text, MaxClientDataBytes, maxDepth = 5, maxObjectMembers = 16)
        require(clientData.requireString("type", 32) == request.type.clientDataType)
        val responseChallenge = challenge(clientData.requireString("challenge", 1024))
        require(MessageDigest.isEqual(responseChallenge, request.challenge))
        require(clientData.requireString("origin", 256) in PasskeyOrigins.allowedAndroidOrigins())
        val crossOrigin = clientData["crossOrigin"]
        if (crossOrigin != null) {
            require(crossOrigin is JsonPrimitive && !crossOrigin.isString && !clientData.requireBoolean("crossOrigin"))
        }
        require("topOrigin" !in clientData)
    }

    private fun challenge(encoded: String): ByteArray = Base64Url.decode(encoded, maxBytes = 512).also {
        require(it.size >= 16)
    }
}
