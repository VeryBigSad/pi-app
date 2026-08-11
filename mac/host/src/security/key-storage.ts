import { createPrivateKey, type KeyObject } from "node:crypto";
import { constants } from "node:fs";
import { open } from "node:fs/promises";
import { join } from "node:path";
import { atomicWriteFile } from "./atomic-file.js";
import type { WrappingSecretProvider } from "./keychain.js";
import { SecurityError } from "./security-error.js";

const MAX_ENCRYPTED_KEY_BYTES = 64 * 1024;
const KEY_NAMES = new Set(["ca", "server"]);

export class EncryptedP256KeyStore {
  private readonly directory: string;
  private readonly secrets: WrappingSecretProvider;

  constructor(directory: string, secrets: WrappingSecretProvider) {
    this.directory = directory;
    this.secrets = secrets;
  }

  async write(name: "ca" | "server", privateKey: CryptoKey): Promise<string> {
    const path = this.pathFor(name);
    if (privateKey.type !== "private" || privateKey.algorithm.name !== "ECDSA" || !privateKey.extractable) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "only extractable P-256 private keys can be stored");
    }
    const algorithm = privateKey.algorithm as EcKeyAlgorithm;
    if (algorithm.namedCurve !== "P-256") {
      throw new SecurityError("SECURITY_INVALID_INPUT", "only extractable P-256 private keys can be stored");
    }

    const secret = await this.secrets.getSecret();
    try {
      const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", privateKey));
      const keyObject = createPrivateKey({ key: pkcs8, format: "der", type: "pkcs8" });
      pkcs8.fill(0);
      const encrypted = keyObject.export({
        cipher: "aes-256-cbc",
        format: "pem",
        passphrase: secret,
        type: "pkcs8",
      });
      await atomicWriteFile(path, encrypted, 0o600);
      return path;
    } catch (error) {
      if (error instanceof SecurityError) throw error;
      throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "private key encryption failed", { cause: error });
    } finally {
      secret.fill(0);
    }
  }

  async read(name: "ca" | "server"): Promise<CryptoKey> {
    const path = this.pathFor(name);
    let encrypted: Buffer;
    try {
      const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        const metadata = await handle.stat();
        if (!metadata.isFile() || (metadata.mode & 0o777) !== 0o600) {
          throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "encrypted private key permissions are unsafe");
        }
        if (metadata.size <= 0 || metadata.size > MAX_ENCRYPTED_KEY_BYTES) {
          throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "encrypted private key size is invalid");
        }
        encrypted = await handle.readFile();
      } finally {
        await handle.close();
      }
      const encoded = encrypted.toString("ascii");
      if (
        !encoded.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----\n") ||
        !encoded.endsWith("-----END ENCRYPTED PRIVATE KEY-----\n")
      ) {
        throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "private key is not encrypted PKCS#8");
      }
    } catch (error) {
      if (error instanceof SecurityError) throw error;
      throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "encrypted private key could not be read", { cause: error });
    }

    const secret = await this.secrets.getSecret();
    try {
      const keyObject = createPrivateKey({ key: encrypted, format: "pem", passphrase: secret });
      return await importP256SigningKey(keyObject);
    } catch (error) {
      throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "encrypted private key could not be decrypted", { cause: error });
    } finally {
      secret.fill(0);
      encrypted.fill(0);
    }
  }

  private pathFor(name: string): string {
    if (!KEY_NAMES.has(name)) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "private key name is invalid");
    }
    return join(this.directory, `${name}.key.pem`);
  }
}

async function importP256SigningKey(keyObject: KeyObject): Promise<CryptoKey> {
  const der = keyObject.export({ format: "der", type: "pkcs8" });
  try {
    return await crypto.subtle.importKey(
      "pkcs8",
      der,
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign"],
    );
  } finally {
    der.fill(0);
  }
}
