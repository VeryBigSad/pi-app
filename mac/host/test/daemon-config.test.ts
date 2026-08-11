import { describe, expect, it } from "vitest";
import { readDebugAndroidOrigins } from "../src/daemon/daemon.js";
import { ANDROID_WEBAUTHN_ORIGIN } from "../src/security/webauthn.js";
import { SecurityError } from "../src/security/security-error.js";

const DEBUG_ORIGIN = "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

describe("debugAndroidOrigins daemon config", () => {
  it("defaults to release-only verification when unset", () => {
    expect(readDebugAndroidOrigins(undefined)).toEqual({});
  });

  it("accepts exact android:apk-key-hash origin strings and dedupes them", () => {
    expect(readDebugAndroidOrigins([DEBUG_ORIGIN, DEBUG_ORIGIN])).toEqual({ debugAndroidOrigins: [DEBUG_ORIGIN] });
  });

  it("rejects malformed values", () => {
    expect(() => readDebugAndroidOrigins("not-an-array")).toThrow(SecurityError);
    expect(() => readDebugAndroidOrigins([])).toThrow(SecurityError);
    expect(() => readDebugAndroidOrigins(["https://evil.example"])).toThrow(SecurityError);
    expect(() => readDebugAndroidOrigins([ANDROID_WEBAUTHN_ORIGIN])).toThrow(SecurityError);
    expect(() => readDebugAndroidOrigins(
      new Array(9).fill(0).map((_, index) => `android:apk-key-hash:${String(index).padStart(43, "A")}`),
    )).toThrow(SecurityError);
  });
});
