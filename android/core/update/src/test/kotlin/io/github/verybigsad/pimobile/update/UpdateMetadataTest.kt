package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateMetadataTest {
    private val pin = "CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE"
    private val sha = "a".repeat(64)

    private fun validJson(): String = """
        {
          "schemaVersion": 1,
          "channel": "stable",
          "packageName": "io.github.verybigsad.pimobile",
          "versionCode": 2,
          "versionName": "0.2.0",
          "publishedAt": "2026-08-11T00:00:00Z",
          "releasePageUrl": "https://github.com/VeryBigSad/pi-app/releases/tag/v0.2.0",
          "apk": {
            "url": "https://github.com/VeryBigSad/pi-app/releases/download/v0.2.0/app-release.apk",
            "sizeBytes": 12345678,
            "sha256": "$sha",
            "certificateSha256": "$pin"
          }
        }
    """.trimIndent()

    @Test
    fun parsesValidMetadata() {
        val metadata = UpdateMetadataParser.parse(validJson().encodeToByteArray(), pinnedCertificateSha256 = pin)
        assertThat(metadata.versionCode).isEqualTo(2L)
        assertThat(metadata.apk.sha256).isEqualTo(sha)
    }

    @Test
    fun rejectsOversizeDocument() {
        val huge = validJson() + " ".repeat(17 * 1024)
        val error = runCatching {
            UpdateMetadataParser.parse(huge.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_TOO_LARGE)
    }

    @Test
    fun rejectsUnknownKeysFailClosed() {
        val tampered = validJson().replace("\"channel\": \"stable\"", "\"channel\": \"stable\", \"extra\": 1")
        val error = runCatching {
            UpdateMetadataParser.parse(tampered.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_INVALID)
    }

    @Test
    fun rejectsWrongPackage() {
        val tampered = validJson().replace("io.github.verybigsad.pimobile", "example.evil")
        val error = runCatching {
            UpdateMetadataParser.parse(tampered.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_UNTRUSTED)
    }

    @Test
    fun rejectsNonPinnedCertificate() {
        val tampered = validJson().replace(pin, "b".repeat(64).chunked(2).joinToString(":").uppercase())
        val error = runCatching {
            UpdateMetadataParser.parse(tampered.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_UNTRUSTED)
    }

    @Test
    fun rejectsWrongSchemaVersion() {
        val tampered = validJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val error = runCatching {
            UpdateMetadataParser.parse(tampered.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_INVALID)
    }

    @Test
    fun rejectsHttpApkUrl() {
        val tampered = validJson().replace("https://github.com/VeryBigSad/pi-app/releases/download", "http://github.com/x")
        val error = runCatching {
            UpdateMetadataParser.parse(tampered.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_INVALID)
    }

    @Test
    fun rejectsNegativeSize() {
        val tampered = validJson().replace("12345678", "-1")
        val error = runCatching {
            UpdateMetadataParser.parse(tampered.encodeToByteArray(), pinnedCertificateSha256 = pin)
        }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_INVALID)
    }
}
