import { describe, expect, it } from "vitest";
import { validateDocument } from "./verify-dal.mjs";

const valid = [{
  relation: ["delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls"],
  target: {
    namespace: "android_app",
    package_name: "io.github.verybigsad.pimobile",
    sha256_cert_fingerprints: ["CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE"]
  }
}];

describe("DAL validation", () => {
  it("accepts the release identity", () => {
    expect(() => validateDocument(valid)).not.toThrow();
  });

  it("rejects another package", () => {
    const invalid = structuredClone(valid);
    invalid[0].target.package_name = "example.invalid";
    expect(() => validateDocument(invalid)).toThrow("package");
  });
});
