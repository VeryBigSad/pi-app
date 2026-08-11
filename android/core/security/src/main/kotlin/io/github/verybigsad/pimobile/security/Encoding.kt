package io.github.verybigsad.pimobile.security

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

internal object Base64Url {
    private val pattern = Regex("^[A-Za-z0-9_-]+$")

    fun decode(value: String, maxBytes: Int, exactBytes: Int? = null): ByteArray {
        require(value.isNotEmpty() && value.length <= encodedLength(maxBytes))
        require(pattern.matches(value))
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrElse {
            throw IllegalArgumentException("invalid base64url")
        }
        require(decoded.size <= maxBytes && (exactBytes == null || decoded.size == exactBytes))
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value)
        return decoded
    }

    fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun encodedLength(bytes: Int): Int = ((bytes + 2) / 3) * 4
}

internal object EcdsaDer {
    private val p256Order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    fun requireP256Signature(value: ByteArray): ByteArray {
        require(value.size in 8..72)
        var offset = 0
        require(readByte(value, offset++) == 0x30)
        val sequenceLength = readLength(value, offset).also { offset = it.second }.first
        require(sequenceLength == value.size - offset)
        val r = readInteger(value, offset).also { offset = it.second }.first
        val s = readInteger(value, offset).also { offset = it.second }.first
        require(offset == value.size)
        require(r.signum() > 0 && r < p256Order)
        require(s.signum() > 0 && s < p256Order)
        return value.copyOf()
    }

    private fun readInteger(value: ByteArray, start: Int): Pair<BigInteger, Int> {
        var offset = start
        require(readByte(value, offset++) == 0x02)
        val parsedLength = readLength(value, offset)
        val length = parsedLength.first
        offset = parsedLength.second
        require(length in 1..33 && offset + length <= value.size)
        val bytes = value.copyOfRange(offset, offset + length)
        require(bytes[0].toInt() and 0x80 == 0)
        require(bytes.size == 1 || bytes[0] != 0.toByte() || bytes[1].toInt() and 0x80 != 0)
        return BigInteger(1, bytes) to (offset + length)
    }

    private fun readLength(value: ByteArray, start: Int): Pair<Int, Int> {
        val first = readByte(value, start)
        require(first < 0x80)
        return first to (start + 1)
    }

    private fun readByte(value: ByteArray, index: Int): Int {
        require(index in value.indices)
        return value[index].toInt() and 0xff
    }
}

class CertificateFingerprint private constructor(private val digest: ByteArray) {
    fun bytes(): ByteArray = digest.copyOf()

    fun base64Url(): String = Base64Url.encode(digest)

    fun hex(): String = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun colonHex(): String = digest.joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    fun matchesCertificate(certificateDer: ByteArray): Boolean = MessageDigest.isEqual(
        digest,
        MessageDigest.getInstance("SHA-256").digest(certificateDer),
    )

    override fun equals(other: Any?): Boolean = other is CertificateFingerprint && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = colonHex()

    companion object {
        fun fromBase64Url(value: String): CertificateFingerprint = CertificateFingerprint(
            Base64Url.decode(value, maxBytes = 32, exactBytes = 32),
        )

        fun fromHex(value: String): CertificateFingerprint {
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "invalid sha256 hex" }
            return CertificateFingerprint(ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
        }

        fun fromCertificate(certificateDer: ByteArray): CertificateFingerprint = CertificateFingerprint(
            MessageDigest.getInstance("SHA-256").digest(certificateDer),
        )
    }
}
