package io.github.verybigsad.pimobile.update

/** Compile-time update policy constants. No TLS pinning; identity comes from the APK signature pin. */
object UpdateConfig {
    const val METADATA_URL = "https://verybigsad.github.io/pi-mobile/update-v1.json"
    const val PACKAGE_NAME = "io.github.verybigsad.pimobile"
    const val CHANNEL = "stable"

    /**
     * SHA-256 of the release signing certificate, canonical colon-separated uppercase hex.
     * Single-sourced from release/identity.properties (injected as BuildConfig at build time).
     */
    val CERTIFICATE_SHA256: String = BuildConfig.RELEASE_CERT_SHA256

    const val METADATA_MAX_BYTES = 16L * 1024L
    const val APK_MAX_BYTES = 512L * 1024L * 1024L
    const val CANDIDATE_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
    const val DOWNLOAD_PREFLIGHT_FACTOR = 2L
}
