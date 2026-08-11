import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { connect, type TLSSocket } from "node:tls";
import { Name, Pkcs10CertificateRequestGenerator } from "@peculiar/x509";
import { afterEach, describe, expect, it } from "vitest";
import { DirectTlsListeners } from "../src/daemon/direct-tls.js";
import { loadOrCreateTlsMaterial, type HostTlsMaterial } from "../src/daemon/tls-material.js";
import { issueDeviceCertificate } from "../src/security/pki.js";
import type { WrappingSecretProvider } from "../src/security/keychain.js";
import type {
  GatewayConnection,
  HostGateway,
  MutualTlsTransportFacts,
  ProvisionalTransportFacts,
  VerifiedTransportAdmission,
} from "../src/gateway/types.js";

const secrets: WrappingSecretProvider = {
  getSecret: () => Promise.resolve(Buffer.alloc(32, 9)),
};

const roots: string[] = [];
const listeners: DirectTlsListeners[] = [];

afterEach(async () => {
  await Promise.allSettled(listeners.splice(0).map(async (listener) => listener.stop()));
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

interface Accepted {
  readonly mode: "provisional" | "mutual-tls";
  readonly deviceId?: string;
}

function fakeGateway(accepted: Accepted[]): HostGateway {
  const connection: GatewayConnection = {
    pathGeneration: 0,
    phase: () => "READY",
    closed: () => Promise.resolve(),
    close: () => Promise.resolve(),
  };
  return {
    transportVerification: {
      provisionalVerified: (facts: ProvisionalTransportFacts) => {
        accepted.push({ mode: "provisional" });
        return { facts, mode: "provisional" } as VerifiedTransportAdmission;
      },
      mutualTlsVerified: (facts: MutualTlsTransportFacts) => {
        accepted.push({ mode: "mutual-tls", deviceId: facts.deviceId });
        return { facts, mode: "mutual-tls" } as VerifiedTransportAdmission;
      },
    },
    accept: () => connection,
    publishToReady: () => undefined,
    close: () => Promise.resolve(),
  };
}

async function fixture(overrides: {
  readonly isRevoked?: (certificateId: string) => boolean;
  readonly activeInvitationId?: () => string | undefined;
} = {}): Promise<{ material: HostTlsMaterial; ports: { normalPort: number; provisionalPort: number }; accepted: Accepted[] }> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-tls-"));
  roots.push(root);
  const material = await loadOrCreateTlsMaterial({
    keyDirectory: join(root, "keys"),
    secrets,
    instanceId: "550e8400-e29b-41d4-a716-446655440000",
  });
  const accepted: Accepted[] = [];
  const listener = new DirectTlsListeners({
    material,
    gateway: fakeGateway(accepted),
    isRevoked: overrides.isRevoked ?? (() => false),
    ...(overrides.activeInvitationId === undefined ? {} : { activeInvitationId: overrides.activeInvitationId }),
  });
  listeners.push(listener);
  const ports = await listener.start(0, 0);
  return { material, ports, accepted };
}

async function deviceClientCertificate(material: HostTlsMaterial, deviceId: string): Promise<{ key: string; cert: string }> {
  const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const request = await Pkcs10CertificateRequestGenerator.create({
    name: new Name([{ CN: [deviceId] }]),
    keys,
    signingAlgorithm: { name: "ECDSA", hash: "SHA-256" },
  });
  const issued = await issueDeviceCertificate(material.authority, deviceId, new Uint8Array(request.rawData));
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", keys.privateKey));
  const body = pkcs8.toString("base64");
  pkcs8.fill(0);
  const pem = `-----BEGIN PRIVATE KEY-----\n${body.match(/.{1,64}/gu)?.join("\n") ?? ""}\n-----END PRIVATE KEY-----\n`;
  return { key: pem, cert: issued.certificatePem };
}

function connectTls(port: number, options: { key?: string; cert?: string; ca: string }): Promise<TLSSocket> {
  return new Promise((resolveConnect, rejectConnect) => {
    const socket = connect({
      port,
      host: "127.0.0.1",
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
      checkServerIdentity: () => undefined,
      ...(options.key === undefined ? {} : { key: options.key }),
      ...(options.cert === undefined ? {} : { cert: options.cert }),
      ca: options.ca,
    });
    socket.once("secure", () => resolveConnect(socket));
    socket.once("error", rejectConnect);
  });
}

describe("DirectTlsListeners", () => {
  it("admits a device certificate issued by the host CA over mutual TLS 1.3", async () => {
    const deviceId = "550e8400-e29b-41d4-a716-446655440001";
    const { material, ports, accepted } = await fixture();
    const client = await deviceClientCertificate(material, deviceId);
    const socket = await connectTls(ports.normalPort, { ...client, ca: material.caCertificatePem });
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
    expect(accepted).toEqual([{ mode: "mutual-tls", deviceId }]);
    socket.destroy();
  });

  it("rejects a missing client certificate on the mutual listener", async () => {
    const { material, ports, accepted } = await fixture();
    // TLS 1.3: the client may report "secure" before the server processes the
    // empty Certificate message, so wait for the server-side alert to arrive.
    const socket = await connectTls(ports.normalPort, { ca: material.caCertificatePem }).catch(() => undefined);
    if (socket !== undefined) {
      const failed = await new Promise<boolean>((resolveFailure) => {
        socket.once("error", () => resolveFailure(true));
        socket.once("close", () => resolveFailure(true));
        setTimeout(() => resolveFailure(false), 2_000);
      });
      expect(failed).toBe(true);
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
    expect(accepted).toEqual([]);
  });

  it("rejects provisional connections when no invitation is active", async () => {
    const { material, ports, accepted } = await fixture({ activeInvitationId: () => undefined });
    const socket = await connectTls(ports.provisionalPort, { ca: material.caCertificatePem });
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
    expect(accepted).toEqual([]);
    socket.destroy();
  });

  it("admits provisional connections while an invitation is active", async () => {
    const { material, ports, accepted } = await fixture({ activeInvitationId: () => "invitation-1" });
    const socket = await connectTls(ports.provisionalPort, { ca: material.caCertificatePem });
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
    expect(accepted).toEqual([{ mode: "provisional" }]);
    socket.destroy();
  });

  it("drops revoked device certificates", async () => {
    const deviceId = "550e8400-e29b-41d4-a716-446655440002";
    const probe = await fixture();
    const client = await deviceClientCertificate(probe.material, deviceId);
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-tls-"));
    roots.push(root);
    const accepted: Accepted[] = [];
    const listener = new DirectTlsListeners({
      material: probe.material,
      gateway: fakeGateway(accepted),
      isRevoked: () => true,
    });
    listeners.push(listener);
    const ports = await listener.start(0, 0);
    const socket = await connectTls(ports.normalPort, { ...client, ca: probe.material.caCertificatePem });
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
    expect(accepted).toEqual([]);
    expect(socket.destroyed).toBe(true);
  });
});
