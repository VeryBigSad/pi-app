import {
  BasicConstraintsExtension,
  ExtendedKeyUsage,
  ExtendedKeyUsageExtension,
  KeyUsageFlags,
  KeyUsagesExtension,
  Name,
  Pkcs10CertificateRequestGenerator,
  SubjectAlternativeNameExtension,
} from "@peculiar/x509";
import { beforeAll, describe, expect, it } from "vitest";
import {
  CA_LIFETIME_YEARS,
  LEAF_LIFETIME_DAYS,
  MAX_ANDROID_CSR_BYTES,
  generateCertificateAuthority,
  generateServerCertificate,
  issueDeviceCertificate,
  verifyAndroidP256Csr,
  type GeneratedCertificateAuthority,
} from "../src/security/pki.js";
import { SecurityError } from "../src/security/security-error.js";

const instanceId = "550e8400-e29b-41d4-a716-446655440000";
const deviceId = "550e8400-e29b-41d4-a716-446655440001";
const now = new Date("2026-08-09T12:00:00.000Z");
const DAY_MS = 24 * 60 * 60 * 1000;
let authority: GeneratedCertificateAuthority;

beforeAll(async () => {
  authority = await generateCertificateAuthority(instanceId, now);
});

describe("Mac pairing PKI", () => {
  it("creates a P-256 CA with a five-year critical CA profile", async () => {
    const basic = authority.certificate.getExtension(BasicConstraintsExtension);
    const usage = authority.certificate.getExtension(KeyUsagesExtension);
    const expectedNotAfter = new Date(authority.certificate.notBefore);
    expectedNotAfter.setUTCFullYear(expectedNotAfter.getUTCFullYear() + CA_LIFETIME_YEARS);

    expect(authority.certificate.publicKey.algorithm).toMatchObject({ name: "ECDSA", namedCurve: "P-256" });
    expect(authority.certificate.notAfter).toEqual(expectedNotAfter);
    expect(basic).toMatchObject({ ca: true, pathLength: 0, critical: true });
    expect(usage).toMatchObject({
      usages: KeyUsageFlags.keyCertSign | KeyUsageFlags.cRLSign,
      critical: true,
    });
    expect(await authority.certificate.isSelfSigned()).toBe(true);
  });

  it("issues a 30-day server leaf with only serverAuth and bound URI/DNS/IP SANs", async () => {
    const leaf = await generateServerCertificate(
      authority,
      instanceId,
      { dnsNames: ["mac.local", "mac.local"], ipAddresses: ["127.0.0.1", "::1"] },
      now,
    );
    const basic = leaf.certificate.getExtension(BasicConstraintsExtension);
    const usage = leaf.certificate.getExtension(KeyUsagesExtension);
    const eku = leaf.certificate.getExtension(ExtendedKeyUsageExtension);
    const san = leaf.certificate.getExtension(SubjectAlternativeNameExtension);

    expect(leaf.certificate.notAfter.getTime() - leaf.certificate.notBefore.getTime()).toBe(
      LEAF_LIFETIME_DAYS * DAY_MS,
    );
    expect(basic).toMatchObject({ ca: false, critical: true });
    expect(usage).toMatchObject({ usages: KeyUsageFlags.digitalSignature, critical: true });
    expect(eku).toMatchObject({ usages: [ExtendedKeyUsage.serverAuth] });
    expect(san?.names.toJSON()).toEqual([
      { type: "url", value: `urn:pimobile:mac:${instanceId}` },
      { type: "dns", value: "mac.local" },
      { type: "ip", value: "127.0.0.1" },
      { type: "ip", value: "::1" },
    ]);
    expect(await leaf.certificate.verify({ publicKey: authority.certificate })).toBe(true);
  });

  it("verifies the bounded Android CSR and issues a client-only leaf on its key", async () => {
    const { csr, publicKeySpki } = await createCsr(deviceId);
    const verified = await verifyAndroidP256Csr(csr, deviceId);
    const issued = await issueDeviceCertificate(authority, deviceId, csr, now);
    const eku = issued.certificate.getExtension(ExtendedKeyUsageExtension);
    const san = issued.certificate.getExtension(SubjectAlternativeNameExtension);

    expect(verified.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect(issued.csrSha256).toBe(verified.sha256);
    expect(eku?.usages).toEqual([ExtendedKeyUsage.clientAuth]);
    expect(san?.names.toJSON()).toEqual([
      { type: "url", value: `urn:pimobile:device:${deviceId}` },
    ]);
    expect(Buffer.from(issued.certificate.publicKey.rawData)).toEqual(publicKeySpki);
    expect(await issued.certificate.verify({ publicKey: authority.certificate })).toBe(true);
  });

  it("rejects CSR signature tampering, wrong CN, non-P-256 keys, trailing DER, and oversize input", async () => {
    const valid = (await createCsr(deviceId)).csr;
    const tampered = new Uint8Array(valid);
    tampered[tampered.length - 1] = (tampered[tampered.length - 1] ?? 0) ^ 1;
    const p384 = await createCsr(deviceId, "P-384");
    const sha384 = await createCsr(deviceId, "P-256", "SHA-384");

    await expect(verifyAndroidP256Csr(tampered, deviceId)).rejects.toMatchObject({ code: "SECURITY_CSR_INVALID" });
    await expect(verifyAndroidP256Csr(valid, instanceId)).rejects.toMatchObject({ code: "SECURITY_CSR_INVALID" });
    await expect(verifyAndroidP256Csr(p384.csr, deviceId)).rejects.toMatchObject({ code: "SECURITY_CSR_INVALID" });
    await expect(verifyAndroidP256Csr(sha384.csr, deviceId)).rejects.toMatchObject({ code: "SECURITY_CSR_INVALID" });
    await expect(verifyAndroidP256Csr(Buffer.concat([valid, Buffer.from([0])]), deviceId)).rejects.toMatchObject({
      code: "SECURITY_CSR_INVALID",
    });
    await expect(verifyAndroidP256Csr(new Uint8Array(MAX_ANDROID_CSR_BYTES + 1), deviceId)).rejects.toBeInstanceOf(SecurityError);
  });

  it("rejects a CA certificate paired with the wrong private key", async () => {
    const otherAuthority = await generateCertificateAuthority(instanceId, now);
    await expect(generateServerCertificate(
      { certificate: authority.certificate, privateKey: otherAuthority.privateKey },
      instanceId,
      {},
      now,
    )).rejects.toMatchObject({ code: "SECURITY_INVALID_INPUT" });
  });
});

async function createCsr(
  commonName: string,
  namedCurve = "P-256",
  hash = "SHA-256",
): Promise<{ csr: Uint8Array; publicKeySpki: Buffer }> {
  const keys = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve },
    true,
    ["sign", "verify"],
  );
  const request = await Pkcs10CertificateRequestGenerator.create({
    name: new Name([{ CN: [commonName] }]),
    keys,
    signingAlgorithm: { name: "ECDSA", hash },
  });
  return { csr: new Uint8Array(request.rawData), publicKeySpki: Buffer.from(request.publicKey.rawData) };
}
