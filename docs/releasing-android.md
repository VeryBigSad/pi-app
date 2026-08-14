# Android release runbook

The stable Android release is a manual, main-only `workflow_dispatch` through `.github/workflows/android-release.yml`. It never updates an existing tag, release, or asset. GitHub immutable releases must be enabled for `VeryBigSad/pi-app`; the workflow checks that policy before building.

## Canonical release inputs

- `gradle/app-version.properties`: `versionName` and monotonically increasing positive `versionCode`.
- Dispatch `tag`: exactly `v{versionName}` and a new tag.
- `release/identity.properties`: exact release certificate SHA-256.
- Android package: exactly `io.github.verybigsad.pimobile`.
- Signing alias: `PI_MOBILE_KEY_ALIAS`; defaults to `pimobile-release` when unset.

The current canonical release candidate is `v0.1.2` / `versionCode=3`; every later release must use a `versionCode` greater than every immutable release's attached `update-v1.json`. Existing drafts, mutable releases, missing metadata assets, reused tags, duplicate version codes, and feed rollback all fail closed.

## Required GitHub configuration

1. Enable immutable releases for `VeryBigSad/pi-app` in repository settings. This is an external one-time administration step.
2. Create the protected Environment `android-release`; restrict deployment to `main` and require the desired reviewers.
3. Add `RELEASE_PUBLISH_TOKEN` to that Environment. Use a fine-grained token with repository Contents read/write for both `VeryBigSad/pi-app` and `VeryBigSad/verybigsad.github.io`. It must also be able to read repository metadata and the immutable-release policy.
4. Ensure GitHub Pages serves the `VeryBigSad/verybigsad.github.io` default branch.
5. Configure either the protected signing secrets below or a commit-bound pre-signed artifact.

The repository `GITHUB_TOKEN` needs only Actions read and Contents read. It validates and downloads a source run artifact; publishing uses `RELEASE_PUBLISH_TOKEN`.

## Protected signing mode

Configure all three required Environment secrets together:

- `PIMOBILE_KEYSTORE_BASE64`: base64 of the PKCS#12 release keystore.
- `PIMOBILE_KEYSTORE_PASSWORD`.
- `PIMOBILE_KEY_PASSWORD`.

Optional:

- `PIMOBILE_KEY_ALIAS`: alias in the keystore; defaults to `pimobile-release`.

If any required signing secret is present, all must be present and `apk_run_id`/`apk_sha256` must be empty.

For local signing, run Gradle through the Keychain-backed wrapper without printing the password:

```bash
scripts/release-signing-env ./gradlew --no-daemon :android:app:assembleRelease
node scripts/verify-release-identity.mjs android/app/build/outputs/apk/release/app-release.apk --tag v0.1.2
```

The helper expects the keystore at `~/Library/Application Support/PiMobile/signing/release.jks` and the password in the `io.github.verybigsad.pimobile.signing` generic-password Keychain item for the current user. It exports values only to the wrapped command; it emits no secret text.

## Commit-bound pre-signed mode

This is a recovery path when Environment keystore secrets are absent. Supply both:

- `apk_run_id`: a successful `android-signed-artifact.yml` run from `main`, in `VeryBigSad/pi-app`, whose `head_sha` equals the target dispatch commit and which retained exactly one live `app-release-signed` artifact.
- `apk_sha256`: the exact lowercase or uppercase SHA-256 of the APK inside that artifact.

Dispatch `Android Signed Artifact` while the protected keystore secrets are available, then download its artifact and calculate the SHA-256 supplied to the release run. The release checks source repository, branch, commit, exact workflow path, successful conclusion, artifact uniqueness/expiry, and the caller-supplied APK SHA before publication. A run from another commit, failed run, or workflow cannot be used.

## Publication transaction

The workflow performs these gates in order:

1. Check immutable-release policy, absence of the target tag/release, absence of stale drafts, and monotonic `versionCode` across all prior immutable release metadata.
2. Build or retrieve the commit-bound APK.
3. Verify with `apkanalyzer` and `apksigner`: non-debuggable package `io.github.verybigsad.pimobile`, APK `versionName`, APK `versionCode`, tag, exactly one signer certificate, verified v2/v3 signing, canonical signer pin, DAL pin, and exact APK SHA-256.
4. Generate deterministic `update-v1.json` containing that APK SHA, size, package, version, signer, and immutable release URLs.
5. Create a new draft release containing exactly `app-release.apk` and `update-v1.json`; verify API digests and downloaded draft bytes.
6. Publish the draft. GitHub locks its tag/assets and generates the release attestation.
7. Require `immutable=true`, exact tag target commit, valid GitHub release/asset attestations, API SHA-256 digests, anonymous byte-for-byte downloads, and a second APK identity verification.
8. Copy the anonymously downloaded immutable `update-v1.json` asset to the Pages feed. The feed is never generated from, or updated before, an unverified mutable asset.

There is no `--clobber` path. A failed immutable publication is not repaired in place; advance `versionName` and `versionCode` for a new release. A failed draft must be reviewed and removed manually before a retry because the preflight intentionally refuses stale drafts.

## Dispatch checklist

- Main contains the intended code and canonical version files.
- New tag equals `v{versionName}`.
- `versionCode` is greater than every published release.
- Immutable releases are enabled.
- Environment approval and secrets/token are available.
- Signing keystore backup and recovery procedure are current.
- Use protected signing mode, or provide both commit-bound pre-signed inputs.

After the run, verify the immutable badge/attestation on the GitHub release and fetch `https://verybigsad.github.io/pi-mobile/update-v1.json` through the public path. Physical-device update installation remains a separate release acceptance gate.
