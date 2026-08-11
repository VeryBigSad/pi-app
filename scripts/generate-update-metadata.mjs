#!/usr/bin/env node
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";

/**
 * Deterministically generates update-v1.json from gradle/app-version.properties and a signed APK.
 * Usage: node scripts/generate-update-metadata.mjs <apk> <tag> [--out <path>] [--published-at <ISO>]
 * The APK certificate SHA-256 must be supplied via --certificate-sha256 (from apksigner) so this
 * script stays toolchain-free; generation fails closed on any mismatch with the canonical pin.
 * publishedAt is deterministic: --published-at wins, otherwise the git committer date of <tag>.
 * The tag must equal v{versionName} from gradle/app-version.properties (version cross-check).
 */

export const PIN = "CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE";
export const PACKAGE_NAME = "io.github.verybigsad.pimobile";
export const METADATA_MAX_BYTES = 16 * 1024;
export const RELEASES_REPO = "VeryBigSad/pi-app-releases";

const SHA256_HEX = /^[0-9a-f]{64}$/u;
const SHA256_COLON = /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/u;
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u;

export function parseVersionProperties(text) {
  const out = {};
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (trimmed === "" || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq < 1) continue;
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  const versionCode = Number.parseInt(out.versionCode ?? "", 10);
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) throw new Error("versionCode missing or invalid");
  if (typeof out.versionName !== "string" || out.versionName.length === 0 || out.versionName.length > 64) {
    throw new Error("versionName missing or invalid");
  }
  return { versionCode, versionName: out.versionName };
}

export function buildMetadata({ versionCode, versionName, sizeBytes, sha256, certificateSha256, tag, publishedAt }) {
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) throw new Error("versionCode invalid");
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) throw new Error("sizeBytes invalid");
  if (!SHA256_HEX.test(sha256)) throw new Error("apk sha256 invalid");
  if (!SHA256_COLON.test(certificateSha256)) throw new Error("certificate sha256 invalid");
  if (certificateSha256 !== PIN) throw new Error("certificate sha256 differs from canonical pin");
  if (typeof tag !== "string" || !/^v\d+\.\d+\.\d+$/u.test(tag)) throw new Error("tag must be vX.Y.Z");
  if (!ISO_INSTANT.test(publishedAt)) throw new Error("publishedAt must be an ISO instant");
  return {
    schemaVersion: 1,
    channel: "stable",
    packageName: PACKAGE_NAME,
    versionCode,
    versionName,
    publishedAt,
    releasePageUrl: `https://github.com/${RELEASES_REPO}/releases/tag/${tag}`,
    apk: {
      url: `https://github.com/${RELEASES_REPO}/releases/download/${tag}/app-release.apk`,
      sizeBytes,
      sha256,
      certificateSha256,
    },
  };
}

export function resolvePublishedAt({ explicit, tag, cwd = process.cwd() }) {
  const toInstant = (raw, source) => {
    const ms = Date.parse(raw);
    if (Number.isNaN(ms)) throw new Error(`${source} not a valid date: ${raw}`);
    return new Date(ms).toISOString().replace(/\.\d{3}Z$/u, "Z");
  };
  if (explicit !== undefined) return toInstant(explicit, "--published-at");
  const raw = execFileSync("git", ["log", "-1", "--format=%cI", tag], { cwd, encoding: "utf8" }).trim();
  return toInstant(raw, `git tag date for ${tag}`);
}

export function serializeMetadata(metadata) {
  // Deterministic: fixed key order, 2-space indent, trailing newline; must stay under 16 KiB.
  const text = `${JSON.stringify(metadata, null, 2)}\n`;
  if (Buffer.byteLength(text, "utf8") > METADATA_MAX_BYTES) throw new Error("metadata exceeds 16KiB");
  return text;
}

export async function main(argv, { stdout = process.stdout, cwd = process.cwd() } = {}) {
  const args = [...argv];
  const apk = resolve(cwd, args.shift() ?? "");
  const tag = args.shift();
  const outIndex = args.indexOf("--out");
  const out = outIndex >= 0 ? resolve(cwd, args[outIndex + 1] ?? "") : undefined;
  const certIndex = args.indexOf("--certificate-sha256");
  const certificateSha256 = certIndex >= 0 ? args[certIndex + 1] : undefined;
  if (certificateSha256 === undefined) throw new Error("--certificate-sha256 required");
  const publishedAtIndex = args.indexOf("--published-at");
  const explicitPublishedAt = publishedAtIndex >= 0 ? args[publishedAtIndex + 1] : undefined;
  const versionProps = parseVersionProperties(
    await readFile(new URL("../gradle/app-version.properties", import.meta.url), "utf8"),
  );
  if (tag !== `v${versionProps.versionName}`) {
    throw new Error(`tag ${tag} does not match versionName ${versionProps.versionName} (versionCode ${versionProps.versionCode})`);
  }
  const apkBytes = await readFile(apk);
  const sha256 = createHash("sha256").update(apkBytes).digest("hex");
  const { size: sizeBytes } = await stat(apk);
  const publishedAt = resolvePublishedAt({ explicit: explicitPublishedAt, tag, cwd });
  const metadata = buildMetadata({
    ...versionProps,
    sizeBytes,
    sha256,
    certificateSha256: certificateSha256.toUpperCase(),
    tag,
    publishedAt,
  });
  const text = serializeMetadata(metadata);
  if (out !== undefined) {
    const { writeFile } = await import("node:fs/promises");
    await writeFile(out, text);
  }
  stdout.write(text);
  return metadata;
}

const invoked = process.argv[1] !== undefined && import.meta.url.endsWith(process.argv[1].split("/").pop());
if (invoked) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
