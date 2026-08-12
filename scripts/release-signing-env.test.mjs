import { execFileSync } from "node:child_process";
import { chmod, mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

const roots = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("release-signing-env", () => {
  it("exports complete signing inputs to the command without printing the secret", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-signing-env-"));
    roots.push(root);
    const bin = join(root, "bin");
    await mkdir(bin);
    const security = join(bin, "security");
    await writeFile(security, "#!/bin/sh\nprintf '%s\\n' 'private-test-password'\n");
    await chmod(security, 0o700);

    const output = execFileSync(resolve("scripts/release-signing-env"), [
      "sh",
      "-c",
      "test \"$PI_MOBILE_KEYSTORE_PASSWORD\" = private-test-password && test \"$PI_MOBILE_KEY_PASSWORD\" = private-test-password && test \"$PI_MOBILE_KEY_ALIAS\" = pimobile-release && test -n \"$PI_MOBILE_KEYSTORE_PATH\" && printf SIGNING_ENV_READY",
    ], {
      cwd: resolve("."),
      env: { ...process.env, HOME: root, PATH: `${bin}:${process.env.PATH ?? ""}` },
      encoding: "utf8",
    });

    expect(output).toBe("SIGNING_ENV_READY");
    expect(output).not.toContain("private-test-password");
  });
});
