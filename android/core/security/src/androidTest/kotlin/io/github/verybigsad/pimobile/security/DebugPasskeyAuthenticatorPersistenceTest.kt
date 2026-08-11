package io.github.verybigsad.pimobile.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process-restart round-trip for the debug passkey authenticator: a fresh instance over the
 * same application context must recover credentials (Keystore key + persisted record) and
 * keep the signature counter monotonic across instances.
 */
@RunWith(AndroidJUnit4::class)
class DebugPasskeyAuthenticatorPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val challenge = Base64Url.encode(ByteArray(32) { it.toByte() })
    private val userHandle = Base64Url.encode(ByteArray(32) { (it + 1).toByte() })
    private val createdAliases = mutableListOf<String>()

    @Before
    fun resetStore() {
        AndroidDebugPasskeyCredentialStore(context).deleteAllForTest()
    }

    @After
    fun clean() {
        AndroidDebugPasskeyCredentialStore(context).deleteAllForTest()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        createdAliases.forEach { alias -> runCatching { keyStore.deleteEntry(alias) } }
    }

    @Test
    fun credentialSurvivesNewAuthenticatorInstance() = runBlocking {
        val first = DebugPasskeyAuthenticator(context) { PasskeyIdentity.AndroidOrigin }
        val registrationJson = first.createCredential(registrationOptions())
        val credentialId = StrictJson.objectValue(registrationJson, 128 * 1024).requireString("id", 2048)
        createdAliases += DebugPasskeyKeyAliasPrefix + credentialId

        // Simulate process death: a brand-new authenticator with empty in-memory state.
        val second = DebugPasskeyAuthenticator(context) { PasskeyIdentity.AndroidOrigin }
        assertThat(second.publicKeyFor(credentialId)).isNotNull()
        assertThat(second.signCountFor(credentialId)).isEqualTo(0)

        val options = assertionOptions(credentialId)
        val request = PasskeyPolicy.assertion(options)
        val responseJson = second.getCredential(options)
        PasskeyPolicy.validateAssertionResponse(responseJson, request)

        val response = StrictJson.objectValue(responseJson, 128 * 1024).requireObject("response")
        val authData = Base64Url.decode(response.requireString("authenticatorData", 12 * 1024), 8 * 1024)
        val clientData = Base64Url.decode(response.requireString("clientDataJSON", 12 * 1024), 8 * 1024)
        val signature = Base64Url.decode(response.requireString("signature", 2048), 1536)
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(second.publicKeyFor(credentialId))
            update(authData + MessageDigest.getInstance("SHA-256").digest(clientData))
            verify(signature)
        }
        assertThat(verified).isTrue()
        assertThat(response.requireString("userHandle", 2048)).isEqualTo(userHandle)

        // Counter persists across instances and keeps increasing monotonically.
        val third = DebugPasskeyAuthenticator(context) { PasskeyIdentity.AndroidOrigin }
        assertThat(third.signCountFor(credentialId)).isEqualTo(1)
        third.getCredential(options)
        val fourth = DebugPasskeyAuthenticator(context) { PasskeyIdentity.AndroidOrigin }
        assertThat(fourth.signCountFor(credentialId)).isEqualTo(2)
        Unit
    }

    @Test
    fun corruptedRecordFileYieldsEmptyStateInsteadOfCrashing() {
        File(context.noBackupFilesDir, AndroidDebugPasskeyCredentialStore.FILE_NAME)
            .writeBytes(byteArrayOf(0xFF.toByte(), 0x00, 0x01))
        val authenticator = DebugPasskeyAuthenticator(context) { PasskeyIdentity.AndroidOrigin }
        assertThat(authenticator.publicKeyFor("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).isNull()
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

    private fun assertionOptions(allowId: String): String = """
        {
          "challenge":"$challenge",
          "rpId":"${PasskeyIdentity.RpId}",
          "userVerification":"required",
          "allowCredentials":[{"type":"public-key","id":"$allowId"}]
        }
    """.trimIndent()
}
