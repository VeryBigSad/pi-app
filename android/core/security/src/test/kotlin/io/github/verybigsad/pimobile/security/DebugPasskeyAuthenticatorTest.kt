package io.github.verybigsad.pimobile.security

import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DebugPasskeyAuthenticatorTest {
    private val challenge = Base64Url.encode(ByteArray(32) { it.toByte() })
    private val userHandle = Base64Url.encode(ByteArray(32) { (it + 1).toByte() })
    private val authenticator = DebugPasskeyAuthenticator { PasskeyIdentity.AndroidOrigin }

    @After
    fun resetHooks() {
        PasskeyDebugHooks.androidOriginOverride = null
        PasskeyDebugHooks.executor = null
    }

    @Test
    fun registrationResponsePassesPolicyAndCarriesValidCredential() = runBlocking {
        Assume.assumeTrue("positive ceremony path is debug-only", BuildConfig.DEBUG)
        val options = registrationOptions()
        val request = PasskeyPolicy.registration(options)
        val responseJson = authenticator.createCredential(options)

        PasskeyPolicy.validateRegistrationResponse(responseJson, request)

        val credential = StrictJson.objectValue(responseJson, 128 * 1024)
        val response = credential.requireObject("response")
        val attestationObject = Base64Url.decode(response.requireString("attestationObject", 128 * 1024), 96 * 1024)
        val authData = extractAuthData(attestationObject)
        val rpHash = MessageDigest.getInstance("SHA-256").digest(PasskeyIdentity.RpId.encodeToByteArray())
        assertTrue(authData.copyOfRange(0, 32).contentEquals(rpHash))
        assertEquals(0x45, authData[32].toInt() and 0xff) // UP | UV | AT
        val clientData = String(Base64Url.decode(response.requireString("clientDataJSON", 12 * 1024), 8 * 1024))
        assertTrue(clientData.contains("\"type\":\"webauthn.create\""))
        assertTrue(clientData.contains("\"challenge\":\"$challenge\""))
        assertTrue(clientData.contains("\"origin\":\"${PasskeyIdentity.AndroidOrigin}\""))
        assertNotNull(authenticator.publicKeyFor(credential.requireString("id", 2048)))
    }

    @Test
    fun assertionResponsePassesPolicyAndSignatureVerifies() = runBlocking {
        Assume.assumeTrue("positive ceremony path is debug-only", BuildConfig.DEBUG)
        authenticator.createCredential(registrationOptions())
        val options = assertionOptions()
        val request = PasskeyPolicy.assertion(options)
        val responseJson = authenticator.getCredential(options)

        PasskeyPolicy.validateAssertionResponse(responseJson, request)

        val credential = StrictJson.objectValue(responseJson, 128 * 1024)
        val credentialId = credential.requireString("id", 2048)
        val response = credential.requireObject("response")
        val authData = Base64Url.decode(response.requireString("authenticatorData", 12 * 1024), 8 * 1024)
        val clientData = Base64Url.decode(response.requireString("clientDataJSON", 12 * 1024), 8 * 1024)
        val signature = Base64Url.decode(response.requireString("signature", 2048), 1536)
        val publicKey = authenticator.publicKeyFor(credentialId)
        assertNotNull(publicKey)
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(authData + MessageDigest.getInstance("SHA-256").digest(clientData))
            verify(signature)
        }
        assertTrue(verified)
        assertEquals(0x05, authData[32].toInt() and 0xff) // UP | UV, no AT
        assertEquals(1L, authenticator.signCountFor(credentialId))
        authenticator.getCredential(options)
        assertEquals(2L, authenticator.signCountFor(credentialId))
        Unit
    }

    @Test
    fun assertionWithoutMatchingCredentialFails() {
        val options = assertionOptions()
        try {
            runBlocking { authenticator.getCredential(options) }
            fail("expected failure without a registered credential")
        } catch (_: Exception) {
            // expected: no stored credential matches allowCredentials
        }
    }

    @Test
    fun debugHooksAreGatedByBuildType() {
        val origin = "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        if (BuildConfig.DEBUG) {
            PasskeyDebugHooks.androidOriginOverride = origin
            assertEquals(origin, PasskeyDebugHooks.androidOriginOverride)
            PasskeyDebugHooks.executor = authenticator
            assertEquals(authenticator, PasskeyDebugHooks.executor)
        } else {
            PasskeyDebugHooks.androidOriginOverride = origin
            assertNull(PasskeyDebugHooks.androidOriginOverride)
            PasskeyDebugHooks.executor = authenticator
            assertNull(PasskeyDebugHooks.executor)
            try {
                runBlocking { authenticator.createCredential(registrationOptions()) }
                fail("release builds must refuse the debug authenticator")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
    }

    private fun registrationOptions(): String = """
        {
          "challenge":"$challenge",
          "rp":{"id":"${PasskeyIdentity.RpId}","name":"Pi Mobile"},
          "user":{"id":"$userHandle","name":"owner","displayName":"owner"},
          "authenticatorSelection":{"residentKey":"required","requireResidentKey":true,"userVerification":"required"},
          "attestation":"none",
          "pubKeyCredParams":[{"type":"public-key","alg":-7}]
        }
    """.trimIndent()

    private fun assertionOptions(allowId: String? = null): String = """
        {
          "challenge":"$challenge",
          "rpId":"${PasskeyIdentity.RpId}",
          "userVerification":"required"${if (allowId == null) "" else """,
          "allowCredentials":[{"type":"public-key","id":"$allowId"}]"""}
        }
    """.trimIndent()

    // Minimal CBOR walk of {"fmt","attStmt","authData": bytes} to the authData payload.
    private fun extractAuthData(attestationObject: ByteArray): ByteArray {
        var offset = 0
        fun head(): Pair<Int, Int> {
            val first = attestationObject[offset++].toInt() and 0xff
            val major = first shr 5
            var value = first and 0x1f
            if (value == 24) value = attestationObject[offset++].toInt() and 0xff
            else if (value == 25) {
                value = (attestationObject[offset].toInt() and 0xff shl 8) or (attestationObject[offset + 1].toInt() and 0xff)
                offset += 2
            }
            return major to value
        }
        val (rootMajor, entries) = head()
        require(rootMajor == 5)
        repeat(entries) {
            val (keyMajor, keyLength) = head()
            require(keyMajor == 3)
            val key = String(attestationObject, offset, keyLength)
            offset += keyLength
            val (valueMajor, valueLength) = head()
            if (key == "authData") {
                require(valueMajor == 2)
                return attestationObject.copyOfRange(offset, offset + valueLength)
            }
            offset += when (valueMajor) {
                2, 3 -> valueLength
                4, 5 -> 0 // attStmt empty map
                else -> 0
            }
        }
        throw AssertionError("attestation object has no authData")
    }
}
