# ADR-0019: Conservative API 36 build tuple

Status: Accepted
Date: 2026-08-09

## Context

The repository needs reproducible Android, Node, Go, and Terraform builds. AGP 9 adds built-in Kotlin migration and API 37 support that Pi Mobile does not need; selecting every latest component increases Stage 0 risk.

## Decision

Freeze Android on Gradle `8.13` (distribution SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`), AGP `8.13.2`, Kotlin/Compose compiler `2.4.10`, KSP `2.3.11`, Compose BOM `2026.06.01`, JDK 21 build runtime, JVM bytecode 17, and compile/target/min SDK `36/36/29`. Build Tools remain `36.0.0`. AGP 8.13 officially supports API 36.1 and Kotlin 2.4 supports AGP 8.5.2–9.1, so this tuple avoids AGP 9 migration without running unsupported versions.

Node is exactly `22.23.2` with npm `10.9.8`, one exact-version workspace lock, TypeScript `6.0.3`, and native-dependency packaging smoke. Go uses `go 1.26.0` plus toolchain `go1.26.2`. Terraform code supports the installed `1.5.7` floor but CI/applies use a separately pinned current 1.x binary and committed provider lock.

Pi Mobile 1.0 Mac hosting targets Apple Silicon macOS 14+ only. Intel and Windows/Linux hosts are future ports, not silently untested support.

## Consequences

Version catalog, Gradle dependency verification/locking, wrapper checksum, npm lock, Go sums, Terraform lock, SBOM/licenses, deterministic generated assets, and arm64 packaged-native smoke are release gates. Any upgrade requires API 29/34/36 Android suites, Kotlin/TypeScript fixture parity, native packaging, and an ADR update.
