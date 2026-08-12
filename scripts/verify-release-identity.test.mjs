import { describe, expect, it } from "vitest";
import {
  normalizeFingerprint,
  parseProperties,
  parseSignerOutput,
  verifyReleaseFields,
} from "./verify-release-identity.mjs";

const digest = "cc3666f377ce4c2bd6ce19a47f3abe4719ac040fd64ffb119f02e9ac4bddd4fe";
const signerOutput = `Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
Signer #1 certificate SHA-256 digest: ${digest}
`;

const fields = {
  packageName: "io.github.verybigsad.pimobile",
  versionName: "0.1.0",
  versionCode: "1",
  debuggable: "false",
  expectedVersionName: "0.1.0",
  expectedVersionCode: 1,
  tag: "v0.1.0",
};

describe("parseProperties", () => {
  it("parses strict properties", () => {
    expect(parseProperties("# pin\nversionCode=1\nversionName=0.1.0\n")).toEqual(
      new Map([
        ["versionCode", "1"],
        ["versionName", "0.1.0"],
      ]),
    );
  });

  it("rejects duplicate properties", () => {
    expect(() => parseProperties("versionCode=1\nversionCode=2\n")).toThrow("duplicate");
  });
});

describe("parseSignerOutput", () => {
  it("accepts one exact v3 signer", () => {
    expect(parseSignerOutput(signerOutput)).toBe(
      "CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE",
    );
  });

  it("accepts CRLF output from GitHub's Linux apksigner process", () => {
    expect(parseSignerOutput(signerOutput.replaceAll("\n", "\r\n"))).toBe(
      "CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE",
    );
  });

  it("rejects multiple signers", () => {
    const output = `${signerOutput.replace("Number of signers: 1", "Number of signers: 2")}Signer #2 certificate SHA-256 digest: ${"a".repeat(64)}\n`;
    expect(() => parseSignerOutput(output)).toThrow("exactly one");
  });

  it("rejects a signer without v2 or v3", () => {
    expect(() => parseSignerOutput(signerOutput.replace("Verified using v3 scheme (APK Signature Scheme v3): true", "Verified using v3 scheme (APK Signature Scheme v3): false"))).toThrow("neither");
  });
});

describe("verifyReleaseFields", () => {
  it("accepts the viable first release", () => {
    expect(() => verifyReleaseFields(fields)).not.toThrow();
  });

  it.each([
    ["packageName", "com.example.app", "package"],
    ["versionName", "0.1.1", "versionName"],
    ["versionCode", "2", "versionCode"],
    ["debuggable", "true", "debuggable"],
    ["tag", "v0.1.1", "tag"],
  ])("rejects wrong %s", (key, value, message) => {
    expect(() => verifyReleaseFields({ ...fields, [key]: value })).toThrow(message);
  });
});

describe("normalizeFingerprint", () => {
  it("rejects malformed digests", () => {
    expect(() => normalizeFingerprint("not-a-digest")).toThrow("invalid");
  });
});
