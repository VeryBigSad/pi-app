import { randomBytes, timingSafeEqual } from "node:crypto";
import {
  AuthorityKeyIdentifierExtension,
  BasicConstraintsExtension,
  ExtendedKeyUsage,
  ExtendedKeyUsageExtension,
  KeyUsageFlags,
  KeyUsagesExtension,
  Name,
  Pkcs10CertificateRequest,
  SubjectAlternativeNameExtension,
  SubjectKeyIdentifierExtension,
  X509CertificateGenerator,
  type Extension,
  type X509Certificate,
} from "@peculiar/x509";
import {
  deviceUriIdentity,
  macUriIdentity,
  validateDnsName,
  validateIdentityId,
  validateIpAddress,
} from "./identities.js";
import { SecurityError } from "./security-error.js";

export const MAX_ANDROID_CSR_BYTES = 8 * 1024;
export const CA_LIFETIME_YEARS = 5;
export const LEAF_LIFETIME_DAYS = 30;
const CLOCK_SKEW_MS = 5 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

export interface CertificateAuthorityMaterial {
  readonly certificate: X509Certificate;
  readonly privateKey: CryptoKey;
}

export interface GeneratedCertificateAuthority extends CertificateAuthorityMaterial {
  readonly certificatePem: string;
}

export interface GeneratedServerCertificate {
  readonly certificate: X509Certificate;
  readonly certificatePem: string;
  readonly privateKey: CryptoKey;
}

export interface IssuedDeviceCertificate {
  readonly certificate: X509Certificate;
  readonly certificatePem: string;
  readonly csrSha256: string;
}

export interface ServerCertificateNames {
  readonly dnsNames?: readonly string[];
  readonly ipAddresses?: readonly string[];
}

export async function generateCertificateAuthority(
  instanceId: string,
  now = new Date(),
): Promise<GeneratedCertificateAuthority> {
  validateIdentityId(instanceId, "instanceId");
  validateDate(now);
  const keys = await generateP256KeyPair();
  const name = commonName(`Pi Mobile Local CA ${instanceId}`);
  const subjectKeyId = await SubjectKeyIdentifierExtension.create(keys.publicKey);
  const notBefore = new Date(now.getTime() - CLOCK_SKEW_MS);
  const certificate = await X509CertificateGenerator.createSelfSigned({
    name,
    keys,
    serialNumber: serialNumber(),
    notBefore,
    notAfter: addUtcYears(notBefore, CA_LIFETIME_YEARS),
    signingAlgorithm: ecdsaSha256(),
    extensions: [
      new BasicConstraintsExtension(true, 0, true),
      new KeyUsagesExtension(KeyUsageFlags.keyCertSign | KeyUsageFlags.cRLSign, true),
      subjectKeyId,
      await AuthorityKeyIdentifierExtension.create(keys.publicKey),
    ],
  });
  return { certificate, certificatePem: certificate.toString("pem"), privateKey: keys.privateKey };
}

export async function generateServerCertificate(
  authority: CertificateAuthorityMaterial,
  instanceId: string,
  names: ServerCertificateNames = {},
  now = new Date(),
): Promise<GeneratedServerCertificate> {
  validateIdentityId(instanceId, "instanceId");
  validateDate(now);
  await validateAuthority(authority, now);
  const keys = await generateP256KeyPair();
  const extensions = await leafExtensions(
    keys.publicKey,
    authority.certificate,
    ExtendedKeyUsage.serverAuth,
    [
      { type: "url", value: macUriIdentity(instanceId) },
      ...unique(names.dnsNames ?? [], validateDnsName).map((value) => ({ type: "dns" as const, value })),
      ...unique(names.ipAddresses ?? [], validateIpAddress).map((value) => ({ type: "ip" as const, value })),
    ],
  );
  const certificate = await signLeaf(
    authority,
    commonName(instanceId),
    keys.publicKey,
    extensions,
    now,
  );
  return { certificate, certificatePem: certificate.toString("pem"), privateKey: keys.privateKey };
}

export async function issueDeviceCertificate(
  authority: CertificateAuthorityMaterial,
  deviceId: string,
  csrDer: Uint8Array,
  now = new Date(),
): Promise<IssuedDeviceCertificate> {
  validateIdentityId(deviceId, "deviceId");
  validateDate(now);
  await validateAuthority(authority, now);
  const verified = await verifyAndroidP256Csr(csrDer, deviceId);
  const extensions = await leafExtensions(
    verified.request.publicKey,
    authority.certificate,
    ExtendedKeyUsage.clientAuth,
    [{ type: "url", value: deviceUriIdentity(deviceId) }],
  );
  const certificate = await signLeaf(
    authority,
    commonName(deviceId),
    verified.request.publicKey,
    extensions,
    now,
  );
  return {
    certificate,
    certificatePem: certificate.toString("pem"),
    csrSha256: verified.sha256,
  };
}

export async function verifyAndroidP256Csr(
  csrDer: Uint8Array,
  expectedDeviceId: string,
): Promise<{ readonly request: Pkcs10CertificateRequest; readonly sha256: string }> {
  validateIdentityId(expectedDeviceId, "deviceId");
  if (!(csrDer instanceof Uint8Array) || csrDer.byteLength < 64 || csrDer.byteLength > MAX_ANDROID_CSR_BYTES) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR size is invalid");
  }
  assertCanonicalTopLevelDer(csrDer);

  let request: Pkcs10CertificateRequest;
  try {
    request = new Pkcs10CertificateRequest(copyExact(csrDer));
  } catch (error) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR encoding is invalid", { cause: error });
  }
  const signature = request.signatureAlgorithm;
  const publicKey = request.publicKey.algorithm;
  if (
    signature.name !== "ECDSA" ||
    signature.hash.name !== "SHA-256" ||
    publicKey.name !== "ECDSA" ||
    !("namedCurve" in publicKey) ||
    publicKey.namedCurve !== "P-256"
  ) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR must use P-256 ECDSA with SHA-256");
  }
  const commonNames = request.subjectName.getField("CN");
  if (commonNames.length !== 1 || commonNames[0] !== expectedDeviceId) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR common name does not match device identity");
  }
  let signatureValid: boolean;
  try {
    signatureValid = await request.verify();
  } catch (error) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR signature verification failed", { cause: error });
  }
  if (!signatureValid) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR signature is invalid");
  }
  const digest = await crypto.subtle.digest("SHA-256", copyExact(csrDer));
  return { request, sha256: Buffer.from(digest).toString("hex") };
}

async function signLeaf(
  authority: CertificateAuthorityMaterial,
  subject: Name,
  publicKey: CryptoKey | Pkcs10CertificateRequest["publicKey"],
  extensions: Extension[],
  now: Date,
): Promise<X509Certificate> {
  const notBefore = new Date(now.getTime() - CLOCK_SKEW_MS);
  const notAfter = new Date(notBefore.getTime() + LEAF_LIFETIME_DAYS * DAY_MS);
  if (notAfter.getTime() > authority.certificate.notAfter.getTime()) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "leaf lifetime exceeds CA lifetime");
  }
  return await X509CertificateGenerator.create({
    subject,
    issuer: authority.certificate.subjectName,
    publicKey,
    signingKey: authority.privateKey,
    serialNumber: serialNumber(),
    notBefore,
    notAfter,
    signingAlgorithm: ecdsaSha256(),
    extensions,
  });
}

async function leafExtensions(
  publicKey: CryptoKey | Pkcs10CertificateRequest["publicKey"],
  authority: X509Certificate,
  extendedKeyUsage: ExtendedKeyUsage,
  names: { readonly type: "dns" | "ip" | "url"; readonly value: string }[],
): Promise<Extension[]> {
  return [
    new BasicConstraintsExtension(false, undefined, true),
    new KeyUsagesExtension(KeyUsageFlags.digitalSignature, true),
    new ExtendedKeyUsageExtension([extendedKeyUsage], false),
    new SubjectAlternativeNameExtension(names, false),
    await SubjectKeyIdentifierExtension.create(publicKey),
    await AuthorityKeyIdentifierExtension.create(authority),
  ];
}

async function validateAuthority(authority: CertificateAuthorityMaterial, now: Date): Promise<void> {
  const algorithm = authority.privateKey.algorithm;
  const signatureAlgorithm = authority.certificate.signatureAlgorithm;
  const basicConstraints = authority.certificate.getExtension(BasicConstraintsExtension);
  const keyUsage = authority.certificate.getExtension(KeyUsagesExtension);
  const extendedKeyUsage = authority.certificate.getExtension(ExtendedKeyUsageExtension);
  if (
    authority.privateKey.type !== "private" ||
    !authority.privateKey.extractable ||
    algorithm.name !== "ECDSA" ||
    !("namedCurve" in algorithm) ||
    algorithm.namedCurve !== "P-256" ||
    signatureAlgorithm.name !== "ECDSA" ||
    signatureAlgorithm.hash.name !== "SHA-256" ||
    !basicConstraints?.ca ||
    basicConstraints.pathLength !== 0 ||
    !basicConstraints.critical ||
    !keyUsage?.critical ||
    !isAuthorityKeyUsage(keyUsage.usages) ||
    extendedKeyUsage !== null ||
    authority.certificate.notAfter > addUtcYears(authority.certificate.notBefore, CA_LIFETIME_YEARS) ||
    now < authority.certificate.notBefore ||
    now >= authority.certificate.notAfter ||
    !(await authority.certificate.isSelfSigned()) ||
    !(await privateKeyMatchesCertificate(authority.privateKey, authority.certificate))
  ) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "certificate authority material is invalid");
  }
}

async function privateKeyMatchesCertificate(privateKey: CryptoKey, certificate: X509Certificate): Promise<boolean> {
  try {
    const privateJwk = await crypto.subtle.exportKey("jwk", privateKey);
    if (privateJwk.kty !== "EC" || privateJwk.crv !== "P-256" || privateJwk.x === undefined || privateJwk.y === undefined) {
      return false;
    }
    const publicKey = await crypto.subtle.importKey(
      "jwk",
      { kty: "EC", crv: "P-256", x: privateJwk.x, y: privateJwk.y, ext: true, key_ops: ["verify"] },
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["verify"],
    );
    const publicSpki = Buffer.from(await crypto.subtle.exportKey("spki", publicKey));
    const certificateSpki = Buffer.from(certificate.publicKey.rawData);
    return publicSpki.length === certificateSpki.length && timingSafeEqual(publicSpki, certificateSpki);
  } catch {
    return false;
  }
}

function isAuthorityKeyUsage(value: KeyUsageFlags): boolean {
  const required = KeyUsageFlags.keyCertSign | KeyUsageFlags.cRLSign;
  return (value & required) === required && (value & ~required) === 0;
}

function commonName(value: string): Name {
  return new Name([{ CN: [value] }]);
}

function ecdsaSha256(): EcdsaParams {
  return { name: "ECDSA", hash: "SHA-256" };
}

async function generateP256KeyPair(): Promise<CryptoKeyPair> {
  return await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
}

function serialNumber(): string {
  const serial = randomBytes(20);
  serial[0] = (serial[0] ?? 0) & 0x7f;
  if (serial.every((value) => value === 0)) serial[serial.length - 1] = 1;
  return serial.toString("hex");
}

function addUtcYears(date: Date, years: number): Date {
  const result = new Date(date.getTime());
  result.setUTCFullYear(result.getUTCFullYear() + years);
  return result;
}

function validateDate(date: Date): void {
  if (!Number.isFinite(date.getTime())) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "certificate time is invalid");
  }
}

function unique(values: readonly string[], validate: (value: string) => string): string[] {
  if (values.length > 32) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "too many certificate SANs");
  }
  return [...new Set(values.map(validate))];
}

function copyExact(value: Uint8Array): Uint8Array<ArrayBuffer> {
  const copy = new Uint8Array(value.byteLength);
  copy.set(value);
  return copy;
}

function assertCanonicalTopLevelDer(der: Uint8Array): void {
  if (der[0] !== 0x30 || der.length < 2) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR is not a DER sequence");
  }
  const firstLength = der[1];
  if (firstLength === undefined) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR DER length is invalid");
  }
  let bodyLength: number;
  let headerLength: number;
  if ((firstLength & 0x80) === 0) {
    bodyLength = firstLength;
    headerLength = 2;
  } else {
    const lengthBytes = firstLength & 0x7f;
    if (lengthBytes === 0 || lengthBytes > 4 || der.length < 2 + lengthBytes || der[2] === 0) {
      throw new SecurityError("SECURITY_CSR_INVALID", "CSR DER length is invalid");
    }
    bodyLength = 0;
    for (let index = 0; index < lengthBytes; index += 1) {
      bodyLength = bodyLength * 256 + (der[2 + index] ?? 0);
    }
    if (bodyLength < 128) {
      throw new SecurityError("SECURITY_CSR_INVALID", "CSR DER length is not canonical");
    }
    headerLength = 2 + lengthBytes;
  }
  if (!Number.isSafeInteger(bodyLength) || headerLength + bodyLength !== der.length) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR DER length is invalid");
  }
}
