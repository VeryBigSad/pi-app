#!/usr/bin/env node
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { accessSync, constants, readdirSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const PACKAGE_NAME = "io.github.verybigsad.pimobile";
const SHA256_HEX = /^[0-9a-f]{64}$/u;
const FINGERPRINT = /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/u;

export function parseProperties(text) {
  const values = new Map();
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "" || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator < 1) throw new Error(`invalid property line: ${line}`);
    const key = line.slice(0, separator).trim();
    if (values.has(key)) throw new Error(`duplicate property: ${key}`);
    values.set(key, line.slice(separator + 1).trim());
  }
  return values;
}

export function normalizeFingerprint(hex) {
  if (!SHA256_HEX.test(hex)) throw new Error("certificate SHA-256 digest is invalid");
  return hex.toUpperCase().match(/.{2}/gu).join(":");
}

export function parseSignerOutput(output) {
  const signerCount = /^Number of signers: ([0-9]+)$/mu.exec(output)?.[1];
  const certificateLines = output.split("\n").filter((line) => line.includes("certificate SHA-256 digest:"));
  if (signerCount !== "1" || certificateLines.length !== 1) {
    throw new Error(`expected exactly one signer certificate, got signers=${signerCount ?? "unknown"}, certificates=${certificateLines.length}`);
  }
  const digest = /^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$/u.exec(certificateLines[0])?.[1];
  if (digest === undefined) throw new Error("unexpected signer certificate output");
  const v2 = /^Verified using v2 scheme \(APK Signature Scheme v2\): true$/mu.test(output);
  const v3 = /^Verified using v3 scheme \(APK Signature Scheme v3\): true$/mu.test(output);
  if (!v2 && !v3) throw new Error("APK has neither a verified v2 nor v3 signature");
  return normalizeFingerprint(digest);
}

export function verifyReleaseFields({
  packageName,
  versionName,
  versionCode,
  debuggable,
  expectedVersionName,
  expectedVersionCode,
  tag,
}) {
  if (packageName !== PACKAGE_NAME) throw new Error(`APK package ${packageName} != ${PACKAGE_NAME}`);
  if (versionName !== expectedVersionName) {
    throw new Error(`APK versionName ${versionName} != ${expectedVersionName}`);
  }
  if (!/^[1-9][0-9]*$/u.test(versionCode) || Number(versionCode) !== expectedVersionCode) {
    throw new Error(`APK versionCode ${versionCode} != ${expectedVersionCode}`);
  }
  if (debuggable !== "false") throw new Error(`release APK debuggable=${debuggable}`);
  if (tag !== `v${expectedVersionName}` || !/^v[0-9]+\.[0-9]+\.[0-9]+$/u.test(tag)) {
    throw new Error(`tag ${tag} != v${expectedVersionName}`);
  }
}

function executable(path) {
  try {
    accessSync(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function locateTool(name) {
  const override = process.env[name.toUpperCase()];
  if (override !== undefined && executable(override)) return override;
  const sdk = process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME;
  if (name === "apksigner" && sdk !== undefined) {
    const directory = join(sdk, "build-tools");
    let versions = [];
    try {
      versions = readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory() && /^\d+\.\d+\.\d+$/u.test(entry.name))
        .map((entry) => entry.name)
        .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    } catch {
      versions = [];
    }
    for (const version of versions) {
      const candidate = join(directory, version, name);
      if (executable(candidate)) return candidate;
    }
  }
  try {
    return execFileSync("which", [name], { encoding: "utf8" }).trim();
  } catch {
    throw new Error(`${name} not found`);
  }
}

function option(args, name) {
  const index = args.indexOf(name);
  if (index < 0) return undefined;
  const value = args[index + 1];
  if (value === undefined || value.startsWith("--")) throw new Error(`${name} requires a value`);
  return value;
}

export async function verifyApk(apkPath, { tag, expectedSha256 } = {}) {
  const apk = resolve(apkPath);
  const version = parseProperties(await readFile(new URL("../gradle/app-version.properties", import.meta.url), "utf8"));
  const expectedVersionCodeText = version.get("versionCode") ?? "";
  if (!/^[1-9][0-9]*$/u.test(expectedVersionCodeText)) throw new Error("canonical versionCode is invalid");
  const expectedVersionCode = Number(expectedVersionCodeText);
  if (!Number.isSafeInteger(expectedVersionCode)) throw new Error("canonical versionCode is unsafe");
  const expectedVersionName = version.get("versionName") ?? "";
  if (!/^[0-9]+\.[0-9]+\.[0-9]+$/u.test(expectedVersionName)) throw new Error("canonical versionName is invalid");

  const identity = parseProperties(await readFile(new URL("../release/identity.properties", import.meta.url), "utf8"));
  const expectedCertificate = identity.get("certificateSha256") ?? "";
  if (!FINGERPRINT.test(expectedCertificate)) throw new Error("canonical certificateSha256 is invalid");

  const bytes = await readFile(apk);
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  if (expectedSha256 !== undefined) {
    const normalizedExpected = expectedSha256.toLowerCase();
    if (!SHA256_HEX.test(normalizedExpected)) throw new Error("expected APK SHA-256 is invalid");
    if (sha256 !== normalizedExpected) throw new Error(`APK SHA-256 ${sha256} != ${normalizedExpected}`);
  }

  const apkanalyzer = locateTool("apkanalyzer");
  const analyze = (verb) => execFileSync(apkanalyzer, ["manifest", verb, apk], { encoding: "utf8" }).trim();
  const resolvedTag = tag ?? `v${expectedVersionName}`;
  verifyReleaseFields({
    packageName: analyze("application-id"),
    versionName: analyze("version-name"),
    versionCode: analyze("version-code"),
    debuggable: analyze("debuggable"),
    expectedVersionName,
    expectedVersionCode,
    tag: resolvedTag,
  });

  const apksigner = locateTool("apksigner");
  const signerOutput = execFileSync(apksigner, ["verify", "--verbose", "--print-certs", apk], { encoding: "utf8" });
  const certificateSha256 = parseSignerOutput(signerOutput);
  if (certificateSha256 !== expectedCertificate) throw new Error("signed APK certificate differs from canonical pin");

  const dal = JSON.parse(await readFile(new URL("../web/.well-known/assetlinks.template.json", import.meta.url), "utf8"));
  const dalFingerprints = dal.flatMap((entry) => entry?.target?.sha256_cert_fingerprints ?? []);
  if (dalFingerprints.length !== 1 || dalFingerprints[0] !== expectedCertificate) {
    throw new Error("repository DAL certificate differs from canonical pin");
  }

  const apkKeyHash = Buffer.from(certificateSha256.replaceAll(":", ""), "hex").toString("base64url");
  return {
    apk: basename(apk),
    packageName: PACKAGE_NAME,
    versionName: expectedVersionName,
    versionCode: expectedVersionCode,
    tag: resolvedTag,
    sha256,
    certificateSha256,
    apkKeyHash: `android:apk-key-hash:${apkKeyHash}`,
  };
}

export async function main(argv, stdout = process.stdout) {
  const apk = argv[0] ?? "android/app/build/outputs/apk/release/app-release.apk";
  const tag = option(argv, "--tag");
  const expectedSha256 = option(argv, "--expected-sha256");
  const out = option(argv, "--out");
  const result = await verifyApk(apk, { tag, expectedSha256 });
  if (out !== undefined) await writeFile(resolve(out), `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 });
  stdout.write(`${result.apkKeyHash}\n`);
  return result;
}

if (process.argv[1] !== undefined && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
