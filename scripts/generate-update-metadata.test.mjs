import { describe, expect, it } from "vitest";
import { buildMetadata, parseVersionProperties, resolvePublishedAt, serializeMetadata, PIN } from "./generate-update-metadata.mjs";

const sha = "a".repeat(64);

const base = {
  versionCode: 2,
  versionName: "0.2.0",
  sizeBytes: 12345678,
  sha256: sha,
  certificateSha256: PIN,
  tag: "v0.2.0",
  publishedAt: "2026-08-11T00:00:00Z",
};

describe("parseVersionProperties", () => {
  it("parses canonical file", () => {
    expect(parseVersionProperties("# comment\nversionCode=7\nversionName=1.2.3\n")).toEqual({
      versionCode: 7,
      versionName: "1.2.3",
    });
  });

  it("rejects missing versionCode", () => {
    expect(() => parseVersionProperties("versionName=1.0.0")).toThrow("versionCode");
  });

  it("rejects non-numeric versionCode", () => {
    expect(() => parseVersionProperties("versionCode=abc\nversionName=1.0.0")).toThrow("versionCode");
  });
});

describe("buildMetadata", () => {
  it("builds the pinned stable feed entry", () => {
    const metadata = buildMetadata(base);
    expect(metadata.schemaVersion).toBe(1);
    expect(metadata.packageName).toBe("io.github.verybigsad.pimobile");
    expect(metadata.apk.url).toBe(
      "https://github.com/VeryBigSad/pi-app-releases/releases/download/v0.2.0/app-release.apk",
    );
    expect(metadata.apk.certificateSha256).toBe(PIN);
  });

  it("fails closed on wrong certificate", () => {
    const wrong = "CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FF";
    expect(() => buildMetadata({ ...base, certificateSha256: wrong })).toThrow("pin");
  });

  it("rejects bad sha", () => {
    expect(() => buildMetadata({ ...base, sha256: "zz" })).toThrow("sha256");
  });

  it("rejects bad tag", () => {
    expect(() => buildMetadata({ ...base, tag: "release-2" })).toThrow("tag");
  });

  it("rejects bad publishedAt", () => {
    expect(() => buildMetadata({ ...base, publishedAt: "yesterday" })).toThrow("publishedAt");
  });

  it("rejects non-positive versionCode", () => {
    expect(() => buildMetadata({ ...base, versionCode: 0 })).toThrow("versionCode");
  });
});

describe("resolvePublishedAt", () => {
  it("uses the explicit deterministic input", () => {
    expect(resolvePublishedAt({ explicit: "2026-08-11T00:00:00Z", tag: "v0.2.0" })).toBe("2026-08-11T00:00:00Z");
  });

  it("normalizes offsets to Z", () => {
    expect(resolvePublishedAt({ explicit: "2026-08-11T03:00:00+03:00", tag: "v0.2.0" })).toBe("2026-08-11T00:00:00Z");
  });

  it("rejects an invalid explicit date", () => {
    expect(() => resolvePublishedAt({ explicit: "soon", tag: "v0.2.0" })).toThrow("--published-at");
  });

  it("falls back to the git tag date", () => {
    // pi-app repo itself; HEAD resolves even when the tag is absent only via git error — use HEAD ref.
    const out = resolvePublishedAt({ tag: "HEAD" });
    expect(out).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u);
  });
});

describe("serializeMetadata", () => {
  it("is deterministic", () => {
    const first = serializeMetadata(buildMetadata(base));
    const second = serializeMetadata(buildMetadata(base));
    expect(first).toBe(second);
    expect(first.endsWith("\n")).toBe(true);
  });

  it("stays under 16KiB", () => {
    const text = serializeMetadata(buildMetadata(base));
    expect(Buffer.byteLength(text, "utf8")).toBeLessThanOrEqual(16 * 1024);
  });
});
