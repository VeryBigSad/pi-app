package io.github.verybigsad.pimobile.security

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.Test

class PasskeyPolicyTest {
    private val challenge = ByteArray(32) { it.toByte() }
    private val challengeText = Base64Url.encode(challenge)

    @Test
    fun registrationRequiresExactRpDiscoverabilityUvAndNoAttestation() {
        val request = PasskeyPolicy.registration(registrationOptions())
        assertThat(request.type).isEqualTo(PasskeyCeremonyType.REGISTRATION)
        assertThat(request.challenge).isEqualTo(challenge)

        assertFails { PasskeyPolicy.registration(registrationOptions().replace(PasskeyIdentity.RpId, "github.io")) }
        assertFails { PasskeyPolicy.registration(registrationOptions().replace("\"residentKey\":\"required\"", "\"residentKey\":\"preferred\"")) }
        assertFails { PasskeyPolicy.registration(registrationOptions().replace("\"requireResidentKey\":true", "\"requireResidentKey\":false")) }
        assertFails { PasskeyPolicy.registration(registrationOptions().replace("\"userVerification\":\"required\"", "\"userVerification\":\"preferred\"")) }
        assertFails { PasskeyPolicy.registration(registrationOptions().replace("\"attestation\":\"none\"", "\"attestation\":\"direct\"")) }
        assertFails { PasskeyPolicy.registration(registrationOptions().replaceFirst("\"challenge\":", "\"challenge\":\"duplicate\",\"challenge\":")) }
    }

    @Test
    fun assertionRequiresExactRpAndUv() {
        val request = PasskeyPolicy.assertion(assertionOptions())
        assertThat(request.type).isEqualTo(PasskeyCeremonyType.ASSERTION)
        assertThat(request.challenge).isEqualTo(challenge)

        assertFails { PasskeyPolicy.assertion(assertionOptions().replace(PasskeyIdentity.RpId, "example.test")) }
        assertFails { PasskeyPolicy.assertion(assertionOptions().replace("required", "discouraged")) }
        PasskeyPolicy.assertion(assertionOptions(allowCredentials = """[{"type":"public-key","id":"AQ"}]"""))
        assertFails {
            PasskeyPolicy.assertion(assertionOptions(allowCredentials = """[{"type":"password","id":"AQ"}]"""))
        }
        assertFails {
            PasskeyPolicy.assertion(assertionOptions(allowCredentials = """[{"type":"public-key","id":"A+Q"}]"""))
        }
        assertFails {
            PasskeyPolicy.assertion(assertionOptions(allowCredentials = "[" + """{"type":"public-key","id":"AQ"},""".repeat(64) + """{"type":"public-key","id":"AQ"}]"""))
        }
    }

    @Test
    fun responseMustBindExactAndroidOriginChallengeRpAndUv() {
        val registration = PasskeyPolicy.registration(registrationOptions())
        PasskeyPolicy.validateRegistrationResponse(registrationResponse(), registration)
        assertFails {
            PasskeyPolicy.validateRegistrationResponse(
                registrationResponse(origin = "https://verybigsad.github.io"),
                registration,
            )
        }
        assertFails {
            PasskeyPolicy.validateRegistrationResponse(
                registrationResponse(responseChallenge = Base64Url.encode(ByteArray(32) { 9 })),
                registration,
            )
        }

        val assertion = PasskeyPolicy.assertion(assertionOptions())
        PasskeyPolicy.validateAssertionResponse(assertionResponse(flags = 0x05), assertion)
        assertFails { PasskeyPolicy.validateAssertionResponse(assertionResponse(flags = 0x01), assertion) }
        assertFails { PasskeyPolicy.validateAssertionResponse(assertionResponse(flags = 0x04), assertion) }
        assertFails { PasskeyPolicy.validateAssertionResponse(assertionResponse(flags = 0x05, rpId = "github.io"), assertion) }
    }

    @Test
    fun providerMatrixLocksWithoutRequiredProviderAndAllowsFrameworkThirdParty() {
        assertThat(PasskeyProviderMatrix.evaluate(29, true, 0)).isEqualTo(
            PasskeyAvailability.Available(PasskeyProviderKind.PLAY_SERVICES, 1, false),
        )
        assertThat(PasskeyProviderMatrix.evaluate(33, false, 4)).isEqualTo(
            PasskeyAvailability.Locked(PasskeyLockReason.PLAY_SERVICES_PROVIDER_REQUIRED),
        )
        assertThat(PasskeyProviderMatrix.evaluate(34, false, 1)).isEqualTo(
            PasskeyAvailability.Available(PasskeyProviderKind.FRAMEWORK, 1, true),
        )
        assertThat(PasskeyProviderMatrix.evaluate(36, true, 0)).isEqualTo(
            PasskeyAvailability.Locked(PasskeyLockReason.FRAMEWORK_PROVIDER_REQUIRED),
        )
    }

    @Test
    fun productionIdentityIsFrozen() {
        assertThat(PasskeyIdentity.PackageName).isEqualTo("io.github.verybigsad.pimobile")
        assertThat(PasskeyIdentity.RpId).isEqualTo("verybigsad.github.io")
        assertThat(PasskeyIdentity.AndroidOrigin).isEqualTo(
            "android:apk-key-hash:zDZm83fOTCvWzhmkfzq-RxmsBA_WT_sRnwLprEvd1P4",
        )
    }

    private fun registrationOptions(): String =
        """{"rp":{"id":"${PasskeyIdentity.RpId}","name":"Pi Mobile"},"user":{"id":"${Base64Url.encode(ByteArray(16) { 4 })}","name":"owner","displayName":"Owner"},"challenge":"$challengeText","pubKeyCredParams":[{"type":"public-key","alg":-7}],"authenticatorSelection":{"residentKey":"required","requireResidentKey":true,"userVerification":"required"},"attestation":"none"}"""

    private fun assertionOptions(allowCredentials: String? = null): String {
        val suffix = allowCredentials?.let { "," + "\"allowCredentials\":$it" }.orEmpty()
        return """{"challenge":"$challengeText","rpId":"${PasskeyIdentity.RpId}","userVerification":"required"$suffix}"""
    }

    private fun registrationResponse(
        origin: String = PasskeyIdentity.AndroidOrigin,
        responseChallenge: String = challengeText,
    ): String = credentialResponse(
        """{"clientDataJSON":"${clientData("webauthn.create", origin, responseChallenge)}","attestationObject":"${Base64Url.encode(byteArrayOf(1, 2, 3))}"}""",
    )

    private fun assertionResponse(flags: Int, rpId: String = PasskeyIdentity.RpId): String {
        val authenticatorData = ByteArray(37)
        MessageDigest.getInstance("SHA-256").digest(rpId.encodeToByteArray()).copyInto(authenticatorData)
        authenticatorData[32] = flags.toByte()
        return credentialResponse(
            """{"clientDataJSON":"${clientData("webauthn.get")}","authenticatorData":"${Base64Url.encode(authenticatorData)}","signature":"${Base64Url.encode(byteArrayOf(1, 2, 3))}"}""",
        )
    }

    private fun credentialResponse(response: String): String {
        val id = Base64Url.encode(ByteArray(32) { 5 })
        return """{"id":"$id","rawId":"$id","type":"public-key","response":$response}"""
    }

    private fun clientData(
        type: String,
        origin: String = PasskeyIdentity.AndroidOrigin,
        responseChallenge: String = challengeText,
    ): String = Base64Url.encode(
        """{"type":"$type","challenge":"$responseChallenge","origin":"$origin","crossOrigin":false}""".encodeToByteArray(),
    )

    private fun assertFails(block: () -> Unit) {
        assertThat(runCatching(block).isFailure).isTrue()
    }
}
