import { describe, expect, it } from "vitest";
import { applyLicenseOverrides, checkLicenses, cyclonedx, normalizeLicense, parseJsonStream, spdx, uniqueComponents } from "./supply-chain.mjs";

const policy = { allowed: ["Apache-2.0", "MIT"], forbidden: ["GPL-3.0-only"] };

describe("supply-chain SBOM helpers", () => {
  it("normalizes known SPDX and Maven license names deterministically", () => {
    expect(normalizeLicense("MIT OR Apache-2.0")).toEqual(["Apache-2.0", "MIT"]);
    expect(normalizeLicense("The Apache Software License, Version 2.0")).toEqual(["Apache-2.0"]);
    expect(normalizeLicense("custom proprietary terms")).toEqual([]);
  });

  it("parses concatenated Go module JSON", () => {
    expect(parseJsonStream('{"Path":"one"}\n{"Path":"two"}\n')).toEqual([{ Path: "one" }, { Path: "two" }]);
  });

  it("sorts and deduplicates components before serializing CycloneDX", () => {
    const components = uniqueComponents([
      { ecosystem: "npm", name: "b", version: "1", purl: "pkg:npm/b@1", licenses: ["MIT"] },
      { ecosystem: "npm", name: "a", version: "1", purl: "pkg:npm/a@1", licenses: ["Apache-2.0"] },
      { ecosystem: "npm", name: "b", version: "1", purl: "pkg:npm/b@1", licenses: ["MIT"] },
    ]);
    expect(cyclonedx(components)).toMatchObject({
      bomFormat: "CycloneDX",
      specVersion: "1.6",
      components: [{ name: "a" }, { name: "b" }],
    });
    expect(spdx(components, "node")).toMatchObject({
      SPDXVersion: "SPDX-2.3",
      documentNamespace: "https://github.com/VeryBigSad/pi-app/sbom/node",
      packages: [{ name: "a" }, { name: "b" }],
    });
  });

  it("applies only exact reviewed overrides", () => {
    const component = { ecosystem: "maven", name: "group:artifact", version: "1", purl: "pkg:maven/group%3Aartifact@1", licenses: [] };
    expect(applyLicenseOverrides([component], { overrides: { maven: { "group:artifact@1": "Apache-2.0" } } })[0].licenses).toEqual(["Apache-2.0"]);
    expect(applyLicenseOverrides([component], { overrides: { maven: { "group:artifact@2": "Apache-2.0" } } })[0].licenses).toEqual([]);
  });

  it("fails closed for unknown, unallowlisted, and forbidden licenses", () => {
    expect(() => checkLicenses([{ purl: "pkg:npm/unknown@1", licenses: [] }], policy)).toThrow("unknown license");
    expect(() => checkLicenses([{ purl: "pkg:npm/other@1", licenses: ["BSD-4-Clause"] }], policy)).toThrow("unallowlisted license");
    expect(() => checkLicenses([{ purl: "pkg:npm/copyleft@1", licenses: ["GPL-3.0-only"] }], policy)).toThrow("forbidden license");
  });
});
