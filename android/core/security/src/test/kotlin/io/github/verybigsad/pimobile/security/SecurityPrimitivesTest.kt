package io.github.verybigsad.pimobile.security

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Test

class SecurityPrimitivesTest {
    @Test
    fun androidOriginUsesUnpaddedBase64UrlSha256() {
        val certificate = byteArrayOf(1, 2, 3, 4)
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate))
        assertThat(AndroidOrigin.fromCertificate(certificate)).isEqualTo("android:apk-key-hash:$expected")
        assertThat(AndroidOrigin.fingerprint(certificate)).matches("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")
    }

    @Test
    fun pkcs10SignatureCoversExactRequestInfo() {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val csr = Pkcs10.encode(pair.public, "pimobile-device-test") { requestInfo ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(requestInfo)
                sign()
            }
        }
        val outer = DerReader(csr).read(0x30)
        val requestInfo = outer.readEncoded(0x30)
        outer.read(0x30)
        val bitString = outer.readBytes(0x03)
        assertThat(bitString.first()).isEqualTo(0)
        assertThat(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(requestInfo)
            verify(bitString.copyOfRange(1, bitString.size))
        }).isTrue()
        assertThat(requestInfo.indexOfSubarray(pair.public.encoded)).isAtLeast(0)
    }
}

private fun ByteArray.indexOfSubarray(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    for (index in 0..size - needle.size) {
        if (needle.indices.all { this[index + it] == needle[it] }) return index
    }
    return -1
}

private class DerReader(private val bytes: ByteArray) {
    private var offset = 0

    fun read(expectedTag: Int): DerReader = DerReader(readBytes(expectedTag))

    fun readEncoded(expectedTag: Int): ByteArray {
        val start = offset
        val body = readBytes(expectedTag)
        return bytes.copyOfRange(start, offset).also { require(it.size > body.size) }
    }

    fun readBytes(expectedTag: Int): ByteArray {
        require(offset < bytes.size && bytes[offset++].toInt() and 0xFF == expectedTag)
        val length = readLength()
        require(length >= 0 && offset + length <= bytes.size)
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    private fun readLength(): Int {
        val first = bytes[offset++].toInt() and 0xFF
        if (first < 0x80) return first
        val count = first and 0x7F
        require(count in 1..4 && offset + count <= bytes.size)
        var result = 0
        repeat(count) { result = (result shl 8) or (bytes[offset++].toInt() and 0xFF) }
        return result
    }
}
