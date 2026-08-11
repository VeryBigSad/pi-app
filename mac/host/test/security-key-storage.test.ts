import { chmodSync, mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { createPrivateKey } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { EncryptedP256KeyStore } from "../src/security/key-storage.js";
import {
  MacOsKeychainWrappingSecret,
  type SecurityCommandResult,
  type WrappingSecretProvider,
} from "../src/security/keychain.js";

class FixedSecretProvider implements WrappingSecretProvider {
  constructor(private readonly secret: Buffer) {}

  getSecret(): Promise<Buffer> {
    return Promise.resolve(Buffer.from(this.secret));
  }
}

describe("encrypted Mac PKI key storage", () => {
  it("atomically stores encrypted PKCS#8 mode 0600 and restores the P-256 key", async () => {
    const directory = mkdtempSync(join(tmpdir(), "pimobile-security-"));
    const store = new EncryptedP256KeyStore(directory, new FixedSecretProvider(Buffer.alloc(32, 7)));
    const pair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );

    const path = await store.write("ca", pair.privateKey);
    const pem = readFileSync(path, "utf8");
    expect(pem).toContain("BEGIN ENCRYPTED PRIVATE KEY");
    expect(pem).not.toContain("BEGIN PRIVATE KEY");
    expect(statSync(path).mode & 0o777).toBe(0o600);
    expect(readdirSync(directory)).toEqual(["ca.key.pem"]);

    const restored = await store.read("ca");
    const message = Buffer.from("pairing-key-round-trip");
    const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, restored, message);
    expect(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pair.publicKey, signature, message)).toBe(true);
  });

  it("fails closed on group-readable encrypted key files", async () => {
    const directory = mkdtempSync(join(tmpdir(), "pimobile-security-"));
    const store = new EncryptedP256KeyStore(directory, new FixedSecretProvider(Buffer.alloc(32, 9)));
    const pair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const path = await store.write("server", pair.privateKey);
    chmodSync(path, 0o640);
    await expect(store.read("server")).rejects.toMatchObject({ code: "SECURITY_KEY_STORAGE_FAILED" });

    const pkcs8 = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
    const plaintext = createPrivateKey({ key: Buffer.from(pkcs8), format: "der", type: "pkcs8" })
      .export({ format: "pem", type: "pkcs8" });
    writeFileSync(path, plaintext, { mode: 0o600 });
    chmodSync(path, 0o600);
    await expect(store.read("server")).rejects.toMatchObject({ code: "SECURITY_KEY_STORAGE_FAILED" });
  });

  it("passes a newly random wrapping secret to security CLI stdin and never argv", async () => {
    const calls: { args: readonly string[]; stdin: string | undefined }[] = [];
    let stored = "";
    const runner = (args: readonly string[], stdin: string | undefined): Promise<SecurityCommandResult> => {
      calls.push({ args, stdin });
      if (args[0] === "add-generic-password") {
        stored = stdin?.split("\n")[0] ?? "";
        return Promise.resolve({ exitCode: 0, stdout: "" });
      }
      return Promise.resolve(stored.length === 0
        ? { exitCode: 44, stdout: "" }
        : { exitCode: 0, stdout: `${stored}\n` });
    };
    const keychain = new MacOsKeychainWrappingSecret("pimobile.test", "wrap-v1", runner);

    const secret = await keychain.getSecret();
    const encoded = secret.toString("base64url");
    expect(secret).toHaveLength(32);
    expect(calls).toHaveLength(3);
    expect(calls[1]?.args.at(-1)).toBe("-w");
    expect(calls[1]?.stdin).toBe(`${encoded}\n${encoded}\n`);
    expect(calls.flatMap((call) => call.args)).not.toContain(encoded);
  });
});
