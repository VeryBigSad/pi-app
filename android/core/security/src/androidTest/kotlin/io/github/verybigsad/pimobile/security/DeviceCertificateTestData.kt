package io.github.verybigsad.pimobile.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object DeviceCertificateTestData {
    const val DeviceId = "550e8400-e29b-41d4-a716-446655440000"

    fun chain(
        leafPublicKey: PublicKey,
        deviceId: String = DeviceId,
        leafValidityMillis: Long = 30L * 24 * 60 * 60 * 1000,
    ): List<ByteArray> {
        val root = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val now = System.currentTimeMillis()
        val rootName = TestDer.name("Pi Mobile Test CA")
        val rootDer = certificate(
            serial = BigInteger.ONE,
            issuer = rootName,
            subject = rootName,
            notBefore = Date(now - 60_000),
            notAfter = Date(now + 365L * 24 * 60 * 60 * 1000),
            publicKey = root.public,
            extensions = TestDer.caExtensions(),
            signer = root.private,
        )
        val leafDer = certificate(
            serial = BigInteger.valueOf(2),
            issuer = rootName,
            subject = TestDer.name("Pi Mobile Device"),
            notBefore = Date(now - 60_000),
            notAfter = Date(now - 60_000 + leafValidityMillis),
            publicKey = leafPublicKey,
            extensions = TestDer.clientExtensions(deviceId),
            signer = root.private,
        )
        return listOf(leafDer, rootDer)
    }

    fun unrelatedKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun certificate(
        serial: BigInteger,
        issuer: ByteArray,
        subject: ByteArray,
        notBefore: Date,
        notAfter: Date,
        publicKey: PublicKey,
        extensions: ByteArray,
        signer: PrivateKey,
    ): ByteArray {
        val algorithm = TestDer.sequence(TestDer.oid(TestDer.Sha256EcdsaOid))
        val tbs = TestDer.sequence(
            TestDer.context(0, TestDer.integer(BigInteger.valueOf(2))),
            TestDer.integer(serial),
            algorithm,
            issuer,
            TestDer.sequence(TestDer.utcTime(notBefore), TestDer.utcTime(notAfter)),
            subject,
            publicKey.encoded,
            TestDer.context(3, extensions),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer)
            update(tbs)
            sign()
        }
        return TestDer.sequence(tbs, algorithm, TestDer.bitString(signature, 0))
    }
}

private object TestDer {
    val Sha256EcdsaOid = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val commonNameOid = byteArrayOf(0x55, 0x04, 0x03)
    private val basicConstraintsOid = byteArrayOf(0x55, 0x1D, 0x13)
    private val keyUsageOid = byteArrayOf(0x55, 0x1D, 0x0F)
    private val extendedKeyUsageOid = byteArrayOf(0x55, 0x1D, 0x25)
    private val subjectAltNameOid = byteArrayOf(0x55, 0x1D, 0x11)
    private val clientAuthOid = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02)

    fun name(commonName: String): ByteArray = sequence(
        set(sequence(oid(commonNameOid), value(0x0C, commonName.encodeToByteArray()))),
    )

    fun caExtensions(): ByteArray = sequence(
        extension(basicConstraintsOid, sequence(boolean(true)), critical = true),
        extension(keyUsageOid, bitString(byteArrayOf(0x04), 2), critical = true),
    )

    fun clientExtensions(deviceId: String): ByteArray = sequence(
        extension(basicConstraintsOid, sequence(), critical = true),
        extension(keyUsageOid, bitString(byteArrayOf(0x80.toByte()), 7), critical = true),
        extension(extendedKeyUsageOid, sequence(oid(clientAuthOid)), critical = false),
        extension(subjectAltNameOid, sequence(value(0x86, "urn:pimobile:device:$deviceId".encodeToByteArray())), critical = false),
    )

    fun sequence(vararg values: ByteArray): ByteArray = value(0x30, join(values))

    fun set(vararg values: ByteArray): ByteArray = value(0x31, join(values))

    fun oid(body: ByteArray): ByteArray = value(0x06, body)

    fun integer(value: BigInteger): ByteArray = value(0x02, value.toByteArray())

    fun context(number: Int, body: ByteArray): ByteArray = value(0xA0 + number, body)

    fun bitString(body: ByteArray, unusedBits: Int): ByteArray = value(0x03, byteArrayOf(unusedBits.toByte()) + body)

    fun boolean(value: Boolean): ByteArray = byteArrayOf(0x01, 0x01, if (value) 0xFF.toByte() else 0)

    fun utcTime(date: Date): ByteArray {
        val formatter = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return value(0x17, formatter.format(date).encodeToByteArray())
    }

    private fun extension(oid: ByteArray, body: ByteArray, critical: Boolean): ByteArray = sequence(
        oid(oid),
        *if (critical) arrayOf(boolean(true), value(0x04, body)) else arrayOf(value(0x04, body)),
    )

    private fun value(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(size: Int): ByteArray {
        require(size >= 0)
        if (size < 0x80) return byteArrayOf(size.toByte())
        val bytes = ByteArrayOutputStream()
        var remaining = size
        while (remaining > 0) {
            bytes.write(remaining and 0xff)
            remaining = remaining ushr 8
        }
        val encoded = bytes.toByteArray().reversedArray()
        return byteArrayOf((0x80 or encoded.size).toByte()) + encoded
    }

    private fun join(values: Array<out ByteArray>): ByteArray = ByteArrayOutputStream().run {
        values.forEach(::write)
        toByteArray()
    }
}
