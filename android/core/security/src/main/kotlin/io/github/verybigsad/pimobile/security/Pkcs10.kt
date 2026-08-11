package io.github.verybigsad.pimobile.security

import java.io.ByteArrayOutputStream
import java.security.PublicKey

object Pkcs10 {
    fun encode(
        publicKey: PublicKey,
        commonName: String,
        sign: (ByteArray) -> ByteArray,
    ): ByteArray {
        require(commonName.isNotBlank() && commonName.toByteArray().size <= 128)
        val subject = Der.sequence(
            Der.set(
                Der.sequence(
                    Der.oid(byteArrayOf(0x55, 0x04, 0x03)),
                    Der.utf8(commonName),
                ),
            ),
        )
        val requestInfo = Der.sequence(
            Der.integerZero(),
            subject,
            publicKey.encoded,
            Der.contextZero(),
        )
        val signature = sign(requestInfo)
        require(signature.isNotEmpty() && signature.size <= 256)
        val algorithm = Der.sequence(
            Der.oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)),
        )
        return Der.sequence(requestInfo, algorithm, Der.bitString(signature))
    }
}

private object Der {
    fun sequence(vararg values: ByteArray): ByteArray = value(0x30, join(values))
    fun set(vararg values: ByteArray): ByteArray = value(0x31, join(values))
    fun oid(bytes: ByteArray): ByteArray = value(0x06, bytes)
    fun utf8(text: String): ByteArray = value(0x0C, text.toByteArray(Charsets.UTF_8))
    fun integerZero(): ByteArray = byteArrayOf(0x02, 0x01, 0x00)
    fun contextZero(): ByteArray = byteArrayOf(0xA0.toByte(), 0x00)
    fun bitString(bytes: ByteArray): ByteArray = value(0x03, byteArrayOf(0x00) + bytes)

    private fun value(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(size: Int): ByteArray {
        require(size >= 0)
        if (size < 0x80) return byteArrayOf(size.toByte())
        val bytes = ByteArrayOutputStream()
        var remaining = size
        while (remaining > 0) {
            bytes.write(remaining and 0xFF)
            remaining = remaining ushr 8
        }
        val littleEndian = bytes.toByteArray()
        return byteArrayOf((0x80 or littleEndian.size).toByte()) + littleEndian.reversedArray()
    }

    private fun join(values: Array<out ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        values.forEach { output.write(it) }
        return output.toByteArray()
    }
}
