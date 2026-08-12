# ADR-0020: Assisted secure self-update

Status: Accepted
Date: 2026-08-11

## Context

Pi Mobile is sideloaded and not distributed through a store, so it needs its own update path. A fully automatic updater would let a single compromised metadata host push arbitrary code; a purely manual updater would strand users on old protocol versions. The middle ground is an *assisted* updater: the app checks automatically, but a human explicitly approves every download and every install, and the OS still shows its own confirmation.

## Decision

**Assisted, never automatic.** The app automatically fetches metadata only. Downloads, unknown-sources permission requests, and `PackageInstaller` commits each require an explicit in-app user action bound to the exact candidate `versionCode` (one-time authorization recorded in the store). Notifications only deep-link to the update sheet; they never authorize anything.

**Metadata.** One static, public, anonymous document at `https://verybigsad.github.io/pi-mobile/update-v1.json`, hard-bounded at 16 KiB, schemaVersion 1, fail-closed parse (unknown keys rejected). Fields: schemaVersion, channel, packageName, versionCode, versionName, publishedAt, releasePageUrl, apk{url,sizeBytes,sha256,certificateSha256}. `versionCode` is the sole ordering authority, gated by a persisted monotonic high-water mark that starts at the installed versionCode; `versionName` is display-only.

**Identity.** Trust comes from the APK signature, not from the transport (no TLS pinning). After a SHA-256-verified download, the APK signer is extracted via `GET_SIGNING_CERTIFICATES`, must be exactly one signer with no lineage history, and its SHA-256 must equal the compile-time pin `CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE`. Debuggable builds disable the updater entirely.

**Distribution.** The signed APK is mirrored to immutable releases of the public repository `VeryBigSad/pi-app`, so downloads are anonymous. Publication is a manual `workflow_dispatch` GitHub workflow that builds the signed APK from protected Environment secrets or accepts only a source-run/commit/SHA-bound pre-signed artifact. It verifies package, canonical versionName/versionCode/tag, exactly one pinned signer, and bytes before creating a new draft with both APK and deterministic metadata. Only after GitHub publishes and attests an immutable tag/release, and anonymous downloads match exactly, does the workflow copy that immutable metadata asset to `VeryBigSad/verybigsad.github.io`. Existing releases/assets are never overwritten. See [Android release runbook](../releasing-android.md).

**Mechanics.** OkHttp `CoroutineWorker` resumable download (Range/If-Range, 206 append, 200 restart, 416 finalize-if-complete, etag-based) into `noBackupFilesDir` with an atomic tmp+rename store under a file lock and ~2x size free-space preflight. `REQUEST_INSTALL_PACKAGES` with a `canRequestPackageInstalls` gate and `ACTION_MANAGE_UNKNOWN_APP_SOURCES` guidance. `PackageInstaller` `MODE_FULL_INSTALL` with fsync, explicit internal `PendingIntent`, `USER_ACTION_REQUIRED` handling on API 31+, and a status activity. WorkManager: 24h periodic with 6h flex, battery-not-low, plus expedited on-demand checks; metered networks require explicit confirmation before download. Cleanup keeps exactly one candidate for 7 days and abandons orphan installer sessions. Failures surface stable `UPDATE_*` error codes.

## Consequences

A compromised metadata host or mirror cannot push code: forged APKs fail the signature pin, and replayed old metadata fails the high-water mark. Users must still tap through two consent steps (in-app + OS) per update. Any change to the signing identity requires shipping a new pin as a normal app update first. No rollback retention: downgrades are not supported. Release signing secrets live only in the protected release Environment; CI never sees them.
