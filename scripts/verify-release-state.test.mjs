import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { buildMetadata, PIN, serializeMetadata } from "./generate-update-metadata.mjs";
import {
  assertCandidateVersion,
  assertFeedAdvance,
  assertImmutablePolicy,
  validatePublishedRelease,
} from "./verify-release-state.mjs";

const metadata = buildMetadata({
  versionCode: 1,
  versionName: "0.1.0",
  sizeBytes: 123,
  sha256: "a".repeat(64),
  certificateSha256: PIN,
  tag: "v0.1.0",
  publishedAt: "2026-08-12T00:00:00Z",
});
const metadataText = serializeMetadata(metadata);
const release = {
  tag_name: "v0.1.0",
  draft: false,
  prerelease: false,
  immutable: true,
  assets: [
    {
      name: "app-release.apk",
      state: "uploaded",
      size: 123,
      digest: `sha256:${"a".repeat(64)}`,
    },
    {
      name: "update-v1.json",
      state: "uploaded",
      size: Buffer.byteLength(metadataText),
      digest: `sha256:${createHash("sha256").update(metadataText).digest("hex")}`,
    },
  ],
};

describe("validatePublishedRelease", () => {
  it("accepts an immutable release bound to its metadata", () => {
    expect(validatePublishedRelease(release, metadataText)).toEqual(metadata);
  });

  it("rejects a mutable release", () => {
    expect(() => validatePublishedRelease({ ...release, immutable: false }, metadataText)).toThrow("not immutable");
  });

  it("rejects an APK digest mismatch", () => {
    const assets = release.assets.map((asset) =>
      asset.name === "app-release.apk" ? { ...asset, digest: `sha256:${"b".repeat(64)}` } : asset,
    );
    expect(() => validatePublishedRelease({ ...release, assets }, metadataText)).toThrow("differs from metadata");
  });

  it("rejects metadata asset tampering", () => {
    expect(() => validatePublishedRelease(release, `${metadataText} `)).toThrow("metadata asset digest differs");
  });

  it("rejects extra mutable assets", () => {
    const assets = [...release.assets, { name: "extra.apk", state: "uploaded", size: 1, digest: `sha256:${"c".repeat(64)}` }];
    expect(() => validatePublishedRelease({ ...release, assets }, metadataText)).toThrow("exactly");
  });
});

describe("assertImmutablePolicy", () => {
  it("accepts repository or owner enforcement", () => {
    expect(() => assertImmutablePolicy({ enabled: true, enforced_by_owner: false })).not.toThrow();
    expect(() => assertImmutablePolicy({ enabled: false, enforced_by_owner: true })).not.toThrow();
  });

  it("rejects disabled immutable releases", () => {
    expect(() => assertImmutablePolicy({ enabled: false, enforced_by_owner: false })).toThrow("must be enabled");
  });
});

describe("assertFeedAdvance", () => {
  it("accepts a strictly newer feed", () => {
    expect(assertFeedAdvance(metadataText, serializeMetadata({ ...metadata, versionCode: 2 }))).toEqual({
      previousVersionCode: 1,
      nextVersionCode: 2,
    });
  });

  it("rejects feed replay", () => {
    expect(() => assertFeedAdvance(metadataText, metadataText)).toThrow("not lower");
  });
});

describe("assertCandidateVersion", () => {
  it("keeps v0.1.0/versionCode 1 viable with no releases", () => {
    expect(
      assertCandidateVersion({
        tag: "v0.1.0",
        versionName: "0.1.0",
        versionCode: 1,
        publishedVersions: [],
      }),
    ).toBe(0);
  });

  it("requires a monotonically increasing versionCode", () => {
    expect(() =>
      assertCandidateVersion({
        tag: "v0.2.0",
        versionName: "0.2.0",
        versionCode: 1,
        publishedVersions: [metadata],
      }),
    ).toThrow("greater");
  });

  it("rejects duplicate published versionCode values", () => {
    expect(() =>
      assertCandidateVersion({
        tag: "v0.2.0",
        versionName: "0.2.0",
        versionCode: 2,
        publishedVersions: [metadata, metadata],
      }),
    ).toThrow("duplicate");
  });
});
