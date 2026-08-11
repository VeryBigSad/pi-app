package io.github.verybigsad.pimobile.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApkInfo(
    @SerialName("url") val url: String,
    @SerialName("sizeBytes") val sizeBytes: Long,
    @SerialName("sha256") val sha256: String,
    @SerialName("certificateSha256") val certificateSha256: String,
)

@Serializable
data class UpdateMetadata(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("channel") val channel: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("versionCode") val versionCode: Long,
    @SerialName("versionName") val versionName: String,
    @SerialName("publishedAt") val publishedAt: String,
    @SerialName("releasePageUrl") val releasePageUrl: String,
    @SerialName("apk") val apk: ApkInfo,
)

object UpdateMetadataParser {
    private val json = Json { ignoreUnknownKeys = false }
    private val sha256Regex = Regex("[0-9a-fA-F]{64}")
    private val isoInstant = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")

    /**
     * Fail-closed validation. [rawBytes] must already be bounded to [UpdateConfig.METADATA_MAX_BYTES]
     * by the fetcher; [expectedPackageName] and [pinnedCertificateSha256] come from compile-time config.
     */
    fun parse(
        rawBytes: ByteArray,
        expectedPackageName: String = UpdateConfig.PACKAGE_NAME,
        pinnedCertificateSha256: String = UpdateConfig.CERTIFICATE_SHA256,
    ): UpdateMetadata {
        if (rawBytes.size > UpdateConfig.METADATA_MAX_BYTES) {
            throw UpdateException(UpdateError.METADATA_TOO_LARGE, "metadata ${rawBytes.size} bytes")
        }
        val metadata = try {
            json.decodeFromString<UpdateMetadata>(rawBytes.decodeToString())
        } catch (error: Exception) {
            throw UpdateException(UpdateError.METADATA_INVALID, "metadata not parseable", error)
        }
        if (metadata.schemaVersion != 1) {
            throw UpdateException(UpdateError.METADATA_INVALID, "schemaVersion ${metadata.schemaVersion}")
        }
        if (metadata.packageName != expectedPackageName) {
            throw UpdateException(UpdateError.METADATA_UNTRUSTED, "packageName ${metadata.packageName}")
        }
        if (metadata.channel.isBlank() || metadata.channel.length > 32) {
            throw UpdateException(UpdateError.METADATA_INVALID, "channel")
        }
        if (metadata.versionCode <= 0L) {
            throw UpdateException(UpdateError.METADATA_INVALID, "versionCode")
        }
        if (metadata.versionName.isBlank() || metadata.versionName.length > 64) {
            throw UpdateException(UpdateError.METADATA_INVALID, "versionName")
        }
        if (!isoInstant.matches(metadata.publishedAt)) {
            throw UpdateException(UpdateError.METADATA_INVALID, "publishedAt")
        }
        requireHttps(metadata.releasePageUrl, "releasePageUrl")
        requireHttps(metadata.apk.url, "apk.url")
        if (metadata.apk.sizeBytes <= 0L || metadata.apk.sizeBytes > UpdateConfig.APK_MAX_BYTES) {
            throw UpdateException(UpdateError.METADATA_INVALID, "apk.sizeBytes")
        }
        if (!sha256Regex.matches(metadata.apk.sha256)) {
            throw UpdateException(UpdateError.METADATA_INVALID, "apk.sha256")
        }
        if (!sha256Regex.matches(metadata.apk.certificateSha256.replace(":", ""))) {
            throw UpdateException(UpdateError.METADATA_INVALID, "apk.certificateSha256")
        }
        if (!metadata.apk.certificateSha256.equals(pinnedCertificateSha256, ignoreCase = true)) {
            throw UpdateException(UpdateError.METADATA_UNTRUSTED, "apk.certificateSha256 not pinned")
        }
        return metadata
    }

    private fun requireHttps(url: String, field: String) {
        if (!url.startsWith("https://") || url.length > 2048) {
            throw UpdateException(UpdateError.METADATA_INVALID, "$field not https")
        }
    }
}
