package io.github.verybigsad.pimobile.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/** Extracted signer identity of an APK. Exactly-one-signer is enforced at extraction. */
data class ApkSignerIdentity(val sha256ColonHex: String)

object SignatureVerifier {
    fun sha256ColonHex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }

    /**
     * Real GET_SIGNING_CERTIFICATES path. Fails closed on unreadable package, missing signing
     * info, or anything other than exactly one signer (no lineage history, no multi-signer).
     */
    fun extractSigner(packageManager: PackageManager, apkFile: File): ApkSignerIdentity {
        val info: PackageInfo = packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw UpdateException(UpdateError.SIGNATURE_UNREADABLE, "package unreadable")
        return extractSigner(info)
    }

    fun extractSigner(info: PackageInfo): ApkSignerIdentity {
        val signingInfo = info.signingInfo
            ?: throw UpdateException(UpdateError.SIGNATURE_UNREADABLE, "no signing info")
        if (signingInfo.hasMultipleSigners()) {
            throw UpdateException(UpdateError.SIGNATURE_MISMATCH, "multiple signers")
        }
        if (signingInfo.hasPastSigningCertificates()) {
            throw UpdateException(UpdateError.SIGNATURE_MISMATCH, "signing lineage history present")
        }
        val signers: Array<Signature> = signingInfo.apkContentsSigners
        if (signers.size != 1) {
            throw UpdateException(UpdateError.SIGNATURE_MISMATCH, "expected one signer, got ${signers.size}")
        }
        return ApkSignerIdentity(sha256ColonHex(signers[0].toByteArray()))
    }

    fun verifyAgainstPin(packageManager: PackageManager, apkFile: File, pin: String = UpdateConfig.CERTIFICATE_SHA256) {
        val identity = extractSigner(packageManager, apkFile)
        if (!identity.sha256ColonHex.equals(pin, ignoreCase = true)) {
            throw UpdateException(UpdateError.SIGNATURE_MISMATCH, "signer ${identity.sha256ColonHex}")
        }
    }
}
