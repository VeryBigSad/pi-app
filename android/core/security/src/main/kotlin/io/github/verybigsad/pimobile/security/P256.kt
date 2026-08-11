package io.github.verybigsad.pimobile.security

import java.security.AlgorithmParameters
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

internal fun requireP256(publicKey: PublicKey): ECPublicKey {
    val ec = publicKey as? ECPublicKey ?: throw IllegalArgumentException("P-256 public key is required")
    val expected = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    require(ec.params.curve == expected.curve)
    require(ec.params.generator == expected.generator)
    require(ec.params.order == expected.order)
    require(ec.params.cofactor == expected.cofactor)
    val fieldLimit = java.math.BigInteger.ONE.shiftLeft(expected.curve.field.fieldSize)
    require(ec.w.affineX.signum() >= 0 && ec.w.affineX < fieldLimit)
    require(ec.w.affineY.signum() >= 0 && ec.w.affineY < fieldLimit)
    return ec
}
