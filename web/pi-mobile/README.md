# Pi Mobile update feed

Static, public, anonymous metadata consumed by the assisted self-updater
(`android/core/update`). The live document is served from
`https://verybigsad.github.io/pi-mobile/update-v1.json` (repository
`VeryBigSad/verybigsad.github.io`) and is written only by the manual release
workflow (`.github/workflows/android-release.yml`).

Rules:

- Hard bound: 16 KiB; the client refuses anything larger before parsing.
- `versionCode` is the sole ordering authority; `versionName` is display-only.
- `apk.certificateSha256` must equal the compile-time signing-certificate pin
  (`CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE`).
- The APK is mirrored anonymously from the public mirror repo
  `VeryBigSad/pi-app` GitHub Releases.
- Unknown keys are rejected (fail-closed parse). Bump `schemaVersion` for any
  incompatible change and ship tolerant clients first.
- Schema: `update-v1.schema.json`. Generation: `scripts/generate-update-metadata.mjs`.
- Policy: see `docs/adr/0020-secure-self-update.md`.
