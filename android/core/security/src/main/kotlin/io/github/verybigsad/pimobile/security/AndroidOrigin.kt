package io.github.verybigsad.pimobile.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Base64

object AndroidOrigin {
    fun current(context: Context): String = fromCertificate(currentCertificate(context))

    fun fromCertificate(certificateDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificateDer)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "android:apk-key-hash:$encoded"
    }

    fun fingerprint(certificateDer: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(certificateDer)
        .joinToString(":") { byte -> "%02X".format(byte) }

    @Suppress("DEPRECATION")
    fun currentCertificate(context: Context): ByteArray {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(packageInfo.signingInfo)
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            packageInfo.signatures
        }
        require(signatures?.size == 1) { "exactly one signing certificate is required" }
        return requireNotNull(signatures.first()).toByteArray()
    }
}
