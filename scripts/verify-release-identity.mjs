#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { join, resolve } from "node:path";

const identity = await readFile(new URL("../release/identity.properties", import.meta.url), "utf8");
const expected = /^certificateSha256=(\S+)$/mu.exec(identity)?.[1];
if (expected === undefined) throw new Error("release/identity.properties missing certificateSha256");
const apk = resolve(process.argv[2] ?? "android/app/build/outputs/apk/release/app-release.apk");

function locateApksigner() {
  const sdk = process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME;
  if (sdk !== undefined) {
    const buildToolsDir = join(sdk, "build-tools");
    const versions = readdirSync(buildToolsDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && /^\d+\.\d+\.\d+$/u.test(entry.name))
      .map((entry) => entry.name)
      .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    for (const version of versions) {
      const candidate = join(buildToolsDir, version, "apksigner");
      try {
        execFileSync(candidate, ["--version"], { encoding: "utf8" });
        return candidate;
      } catch {
        // keep scanning
      }
    }
  }
  try {
    return execFileSync("which", ["apksigner"], { encoding: "utf8" }).trim();
  } catch {
    throw new Error("apksigner not found: set ANDROID_SDK_ROOT or put apksigner on PATH");
  }
}

const apksigner = locateApksigner();
const output = execFileSync(apksigner, ["verify", "--print-certs", apk], { encoding: "utf8" });
const digests = [...output.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$/gmu)];
if (digests.length !== 1) throw new Error(`expected exactly one signer, got ${digests.length}`);
const match = digests[0];
const fingerprint = match[1].toUpperCase().match(/.{2}/gu)?.join(":");
if (fingerprint !== expected) throw new Error("signed APK certificate differs from DAL");
const dal = JSON.parse(await readFile(new URL("../web/.well-known/assetlinks.template.json", import.meta.url), "utf8"));
if (dal[0]?.target?.sha256_cert_fingerprints?.[0] !== expected) throw new Error("repository DAL certificate differs");
const origin = Buffer.from(match[1], "hex").toString("base64url");
process.stdout.write(`android:apk-key-hash:${origin}\n`);
