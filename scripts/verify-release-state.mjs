#!/usr/bin/env node
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  PACKAGE_NAME,
  PIN,
  RELEASES_REPO,
  parseVersionProperties,
} from "./generate-update-metadata.mjs";

const API_VERSION = "2026-03-10";
const SHA256_HEX = /^[0-9a-f]{64}$/u;

function exactKeys(value, keys, context) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(`${context} must be an object`);
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(`${context} keys are invalid`);
}

export function validatePublishedRelease(release, metadataText) {
  if (release.draft || release.prerelease) throw new Error(`release ${release.tag_name} is not a stable publication`);
  if (release.immutable !== true) throw new Error(`release ${release.tag_name} is not immutable`);
  if (!/^v[0-9]+\.[0-9]+\.[0-9]+$/u.test(release.tag_name)) throw new Error(`release tag ${release.tag_name} is invalid`);
  const names = release.assets.map((asset) => asset.name).sort();
  if (JSON.stringify(names) !== JSON.stringify(["app-release.apk", "update-v1.json"])) {
    throw new Error(`release ${release.tag_name} must contain exactly app-release.apk and update-v1.json`);
  }
  for (const asset of release.assets) {
    if (asset.state !== "uploaded") throw new Error(`release ${release.tag_name} asset ${asset.name} is not uploaded`);
    if (!/^sha256:[0-9a-f]{64}$/u.test(asset.digest ?? "")) {
      throw new Error(`release ${release.tag_name} asset ${asset.name} has no SHA-256 digest`);
    }
  }
  const metadataAsset = release.assets.find((asset) => asset.name === "update-v1.json");
  const metadataDigest = createHash("sha256").update(metadataText).digest("hex");
  if (metadataAsset.digest !== `sha256:${metadataDigest}`) {
    throw new Error(`release ${release.tag_name} metadata asset digest differs`);
  }

  const metadata = JSON.parse(metadataText);
  exactKeys(metadata, ["schemaVersion", "channel", "packageName", "versionCode", "versionName", "publishedAt", "releasePageUrl", "apk"], "metadata");
  exactKeys(metadata.apk, ["url", "sizeBytes", "sha256", "certificateSha256"], "metadata.apk");
  if (metadata.schemaVersion !== 1 || metadata.channel !== "stable") throw new Error(`release ${release.tag_name} metadata schema/channel is invalid`);
  if (metadata.packageName !== PACKAGE_NAME) throw new Error(`release ${release.tag_name} package is invalid`);
  if (!Number.isSafeInteger(metadata.versionCode) || metadata.versionCode <= 0) throw new Error(`release ${release.tag_name} versionCode is invalid`);
  if (release.tag_name !== `v${metadata.versionName}`) throw new Error(`release ${release.tag_name} versionName differs`);
  if (metadata.releasePageUrl !== `https://github.com/${RELEASES_REPO}/releases/tag/${release.tag_name}`) {
    throw new Error(`release ${release.tag_name} page URL differs`);
  }
  if (metadata.apk.url !== `https://github.com/${RELEASES_REPO}/releases/download/${release.tag_name}/app-release.apk`) {
    throw new Error(`release ${release.tag_name} APK URL differs`);
  }
  if (!SHA256_HEX.test(metadata.apk.sha256) || metadata.apk.certificateSha256 !== PIN) {
    throw new Error(`release ${release.tag_name} APK identity is invalid`);
  }
  const apkAsset = release.assets.find((asset) => asset.name === "app-release.apk");
  if (apkAsset.digest !== `sha256:${metadata.apk.sha256}` || apkAsset.size !== metadata.apk.sizeBytes) {
    throw new Error(`release ${release.tag_name} APK asset differs from metadata`);
  }
  return metadata;
}

export function assertImmutablePolicy(policy) {
  if (policy?.enabled !== true && policy?.enforced_by_owner !== true) {
    throw new Error("GitHub immutable releases must be enabled for VeryBigSad/pi-app");
  }
}

export function assertFeedAdvance(currentText, nextText) {
  const current = JSON.parse(currentText);
  const next = JSON.parse(nextText);
  if (current.packageName !== PACKAGE_NAME || next.packageName !== PACKAGE_NAME) {
    throw new Error("feed packageName is invalid");
  }
  if (!Number.isSafeInteger(current.versionCode) || !Number.isSafeInteger(next.versionCode)) {
    throw new Error("feed versionCode is invalid");
  }
  if (current.versionCode >= next.versionCode) {
    throw new Error(`existing feed versionCode ${current.versionCode} is not lower than ${next.versionCode}`);
  }
  return { previousVersionCode: current.versionCode, nextVersionCode: next.versionCode };
}

export function assertCandidateVersion({ tag, versionName, versionCode, publishedVersions }) {
  if (tag !== `v${versionName}` || !/^v[0-9]+\.[0-9]+\.[0-9]+$/u.test(tag)) {
    throw new Error(`tag ${tag} does not match canonical versionName ${versionName}`);
  }
  const codes = publishedVersions.map((entry) => entry.versionCode);
  if (new Set(codes).size !== codes.length) throw new Error("published releases contain duplicate versionCode values");
  const maximum = codes.length === 0 ? 0 : Math.max(...codes);
  if (versionCode <= maximum) throw new Error(`versionCode ${versionCode} must be greater than published maximum ${maximum}`);
  return maximum;
}

function headers(token, accept = "application/vnd.github+json") {
  return {
    Accept: accept,
    Authorization: `Bearer ${token}`,
    "X-GitHub-Api-Version": API_VERSION,
    "User-Agent": "pi-mobile-release-verifier",
  };
}

async function api(path, token, { allow404 = false } = {}) {
  const response = await fetch(`https://api.github.com${path}`, { headers: headers(token) });
  if (allow404 && response.status === 404) return undefined;
  if (!response.ok) throw new Error(`GitHub API ${path} returned HTTP ${response.status}`);
  return response.json();
}

async function listReleases(repo, token) {
  const releases = [];
  for (let page = 1; ; page += 1) {
    const batch = await api(`/repos/${repo}/releases?per_page=100&page=${page}`, token);
    releases.push(...batch);
    if (batch.length < 100) return releases;
  }
}

async function loadMetadata(release) {
  const asset = release.assets.find((candidate) => candidate.name === "update-v1.json");
  if (asset === undefined) throw new Error(`release ${release.tag_name} has no update-v1.json`);
  const response = await fetch(asset.browser_download_url, { redirect: "follow" });
  if (!response.ok) throw new Error(`release ${release.tag_name} metadata download returned HTTP ${response.status}`);
  return response.text();
}

export async function verifyReleaseState({ tag, repo = RELEASES_REPO, token = process.env.GH_TOKEN }) {
  if (token === undefined || token === "") throw new Error("GH_TOKEN required");
  if (repo !== RELEASES_REPO) throw new Error(`release repository must be ${RELEASES_REPO}`);
  assertImmutablePolicy(await api(`/repos/${repo}/immutable-releases`, token));
  const existingRef = await api(`/repos/${repo}/git/ref/tags/${encodeURIComponent(tag)}`, token, { allow404: true });
  if (existingRef !== undefined) throw new Error(`tag ${tag} already exists`);

  const releases = await listReleases(repo, token);
  if (releases.some((release) => release.tag_name === tag)) throw new Error(`release ${tag} already exists`);
  const drafts = releases.filter((release) => release.draft);
  if (drafts.length > 0) throw new Error(`draft releases require cleanup before publishing: ${drafts.map((release) => release.tag_name).join(", ")}`);
  const published = releases.filter((release) => !release.draft);
  const metadataTexts = await Promise.all(published.map(loadMetadata));
  const publishedVersions = published.map((release, index) => validatePublishedRelease(release, metadataTexts[index]));
  const version = parseVersionProperties(await readFile(new URL("../gradle/app-version.properties", import.meta.url), "utf8"));
  const previousVersionCode = assertCandidateVersion({ tag, ...version, publishedVersions });
  return { tag, versionCode: version.versionCode, previousVersionCode, publishedReleaseCount: published.length };
}

export async function main(argv, stdout = process.stdout) {
  if (argv[0] === "--feed-advance") {
    if (argv[1] === undefined || argv[2] === undefined) {
      throw new Error("usage: verify-release-state.mjs --feed-advance <current> <next>");
    }
    const result = assertFeedAdvance(await readFile(resolve(argv[1]), "utf8"), await readFile(resolve(argv[2]), "utf8"));
    stdout.write(`${JSON.stringify(result)}\n`);
    return result;
  }
  const tag = argv[0];
  if (tag === undefined) throw new Error("usage: verify-release-state.mjs <tag>");
  const result = await verifyReleaseState({ tag });
  stdout.write(`${JSON.stringify(result)}\n`);
  return result;
}

if (process.argv[1] !== undefined && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
