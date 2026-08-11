import { createHash } from "node:crypto";
import { readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { X509Certificate } from "@peculiar/x509";
import { atomicWriteFile } from "../security/atomic-file.js";
import { EncryptedP256KeyStore } from "../security/key-storage.js";
import type { WrappingSecretProvider } from "../security/keychain.js";
import {
  generateCertificateAuthority,
  generateServerCertificate,
  type CertificateAuthorityMaterial,
  type ServerCertificateNames,
} from "../security/pki.js";
import { SecurityError } from "../security/security-error.js";

const SERVER_RENEW_WITHIN_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_CERTIFICATE_BYTES = 64 * 1024;

export interface HostTlsMaterial {
  readonly authority: CertificateAuthorityMaterial;
  readonly caCertificatePem: string;
  readonly serverCertificatePem: string;
  readonly serverKey: CryptoKey;
  readonly serverCertificateSha256: string;
  readonly serverNotAfterMs: number;
}

/**
 * Loads or creates the five-year host CA and a 30-day server leaf, keeping the
 * private keys encrypted with the Keychain-wrapped secret. Existing material is
 * never rotated unless it is missing, invalid, or inside the renewal window.
 */
export async function loadOrCreateTlsMaterial(options: {
  readonly keyDirectory: string;
  readonly secrets: WrappingSecretProvider;
  readonly instanceId: string;
  readonly serverNames?: ServerCertificateNames;
  readonly now?: Date;
}): Promise<HostTlsMaterial> {
  const now = options.now ?? new Date();
  const store = new EncryptedP256KeyStore(options.keyDirectory, options.secrets);
  const authority = await loadOrCreateAuthority(store, options.keyDirectory, options.instanceId, now);
  const server = await loadOrCreateServerLeaf(store, options.keyDirectory, authority, options.instanceId, options.serverNames ?? {}, now);
  const digest = createHash("sha256").update(Buffer.from(server.certificate.rawData)).digest("hex");
  return {
    authority,
    caCertificatePem: authority.certificate.toString("pem"),
    serverCertificatePem: server.certificatePem,
    serverKey: server.privateKey,
    serverCertificateSha256: digest,
    serverNotAfterMs: server.certificate.notAfter.getTime(),
  };
}

async function loadOrCreateAuthority(
  store: EncryptedP256KeyStore,
  directory: string,
  instanceId: string,
  now: Date,
): Promise<CertificateAuthorityMaterial> {
  const existing = await readCertificateIfUsable(join(directory, "ca.cert.pem"), now);
  if (existing !== undefined) {
    try {
      const privateKey = await store.read("ca");
      const candidate = { certificate: existing, privateKey };
      await generateServerCertificate(candidate, instanceId, {}, now);
      return candidate;
    } catch {
      // Material failed validation; fall through to regenerate atomically below.
    }
  }
  const generated = await generateCertificateAuthority(instanceId, now);
  await store.write("ca", generated.privateKey);
  await atomicWriteFile(join(directory, "ca.cert.pem"), generated.certificatePem, 0o600);
  return { certificate: generated.certificate, privateKey: generated.privateKey };
}

async function loadOrCreateServerLeaf(
  store: EncryptedP256KeyStore,
  directory: string,
  authority: CertificateAuthorityMaterial,
  instanceId: string,
  names: ServerCertificateNames,
  now: Date,
): Promise<{ certificate: X509Certificate; certificatePem: string; privateKey: CryptoKey }> {
  const certificatePath = join(directory, "server.cert.pem");
  const existing = await readCertificateIfUsable(certificatePath, now);
  if (existing !== undefined && existing.notAfter.getTime() - now.getTime() > SERVER_RENEW_WITHIN_MS) {
    try {
      const privateKey = await store.read("server");
      const pem = existing.toString("pem");
      return { certificate: existing, certificatePem: pem, privateKey };
    } catch {
      // Fall through to renewal.
    }
  }
  const generated = await generateServerCertificate(authority, instanceId, names, now);
  await store.write("server", generated.privateKey);
  const previousPath = join(directory, "server.prev.cert.pem");
  try {
    await stat(certificatePath);
    const previous = await readFile(certificatePath);
    await writeFile(previousPath, previous, { mode: 0o600 });
  } catch {
    await unlink(previousPath).catch(() => undefined);
  }
  await atomicWriteFile(certificatePath, generated.certificatePem, 0o600);
  return generated;
}

async function readCertificateIfUsable(path: string, now: Date): Promise<X509Certificate | undefined> {
  let pem: string;
  try {
    const metadata = await stat(path);
    if (!metadata.isFile() || metadata.size <= 0 || metadata.size > MAX_CERTIFICATE_BYTES) return undefined;
    pem = await readFile(path, "utf8");
  } catch {
    return undefined;
  }
  try {
    const certificate = new X509Certificate(pem);
    if (now < certificate.notBefore || now >= certificate.notAfter) return undefined;
    return certificate;
  } catch (error) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "stored certificate is unparsable", { cause: error });
  }
}

export async function readPreviousServerCertificatePem(keyDirectory: string): Promise<string | undefined> {
  try {
    return await readFile(join(keyDirectory, "server.prev.cert.pem"), "utf8");
  } catch {
    return undefined;
  }
}

export async function listKeyDirectory(keyDirectory: string): Promise<readonly string[]> {
  try {
    return await readdir(keyDirectory);
  } catch {
    return [];
  }
}
