import { createHash, createPrivateKey } from "node:crypto";
import { logError } from "./log.js";
import { createServer, type Server, type TLSSocket } from "node:tls";
import { X509Certificate } from "@peculiar/x509";
import type { HostGateway } from "../gateway/types.js";
import type { HostTlsMaterial } from "./tls-material.js";
import { DuplexByteTransport } from "./socket-transport.js";

export const TLS_EXPORTER_LABEL = "EXPORTER-Pi-Mobile-Pairing-v1";
const EXPORTER_BYTES = 32;

export interface DeviceCertificateFacts {
  readonly deviceId: string;
  readonly certificateId: string;
}

export interface DirectTlsServerOptions {
  readonly material: HostTlsMaterial;
  readonly gateway: HostGateway;
  readonly path: "direct" | "relay";
  readonly isRevoked: (certificateId: string) => boolean;
  readonly activeInvitationId?: () => string | undefined;
}

export interface TlsAdmissionResult {
  readonly kind: "mutual" | "provisional" | "rejected";
  readonly deviceId?: string;
  readonly certificateId?: string;
  readonly reason?: string;
}

export function exportTlsKeyingMaterial(socket: TLSSocket): Uint8Array {
  const exported = socket.exportKeyingMaterial(EXPORTER_BYTES, TLS_EXPORTER_LABEL, Buffer.alloc(0));
  return new Uint8Array(exported.buffer, exported.byteOffset, exported.byteLength);
}

export function deviceFactsFromPeerCertificate(socket: TLSSocket, caPem: string): DeviceCertificateFacts {
  const peer = socket.getPeerCertificate(true);
  const raw = peer.raw as Buffer | undefined;
  if (raw === undefined || raw.length === 0) throw new Error("peer certificate is missing");
  const certificate = new X509Certificate(new Uint8Array(raw));
  const issuerChain = new X509Certificate(caPem);
  if (certificate.issuer !== issuerChain.subject) throw new Error("peer certificate issuer mismatch");
  const identity = certificate.subjectName.getField("CN");
  if (identity.length !== 1) throw new Error("peer certificate identity is invalid");
  const deviceId = identity[0];
  if (deviceId === undefined || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(deviceId)) {
    throw new Error("peer certificate identity is invalid");
  }
  const certificateId = createHash("sha256").update(raw).digest("hex");
  return { deviceId, certificateId };
}

/** Admits one already-established inner/direct TLS socket into the gateway. */
export async function admitTlsSocket(
  socket: TLSSocket,
  options: DirectTlsServerOptions,
  provisional: boolean,
): Promise<TlsAdmissionResult> {
  await Promise.resolve();
  const verification = options.gateway.transportVerification;
  if (provisional) {
    const invitationId = options.activeInvitationId?.();
    if (invitationId === undefined) {
      return { kind: "rejected", reason: "no active invitation" };
    }
    const admission = verification.provisionalVerified({
      transport: new DuplexByteTransport(socket),
      invitationId,
      serverCertificateSha256: options.material.serverCertificateSha256,
    });
    options.gateway.accept(admission);
    return { kind: "provisional" };
  }
  let facts: DeviceCertificateFacts;
  try {
    facts = deviceFactsFromPeerCertificate(socket, options.material.caCertificatePem);
  } catch {
    return { kind: "rejected", reason: "peer certificate invalid" };
  }
  if (options.isRevoked(facts.certificateId)) {
    socket.destroy();
    return { kind: "rejected", reason: "certificate revoked" };
  }
  const admission = verification.mutualTlsVerified({
    transport: new DuplexByteTransport(socket),
    deviceId: facts.deviceId,
    certificateId: facts.certificateId,
    tlsExporter: exportTlsKeyingMaterial(socket),
    path: options.path,
  });
  options.gateway.accept(admission);
  return { kind: "mutual", deviceId: facts.deviceId, certificateId: facts.certificateId };
}

interface ListenerHandle {
  readonly port: number;
  close(): Promise<void>;
}

async function cryptoKeyToPem(key: CryptoKey): Promise<string> {
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", key));
  try {
    return createPrivateKey({ key: pkcs8, format: "der", type: "pkcs8" })
      .export({ format: "pem", type: "pkcs8" })
      .toString();
  } finally {
    pkcs8.fill(0);
  }
}

export class DirectTlsListeners {
  private normal: Server | undefined;
  private provisional: Server | undefined;
  private readonly sockets = new Set<TLSSocket>();

  constructor(private readonly options: Omit<DirectTlsServerOptions, "path">) {}

  async start(normalPort: number, provisionalPort: number): Promise<{ normalPort: number; provisionalPort: number }> {
    const serverKey = await cryptoKeyToPem(this.options.material.serverKey);
    const cert = this.options.material.serverCertificatePem;
    const ca = this.options.material.caCertificatePem;
    this.normal = await this.listen(normalPort, {
      key: serverKey,
      cert,
      ca,
      requestCert: true,
      rejectUnauthorized: true,
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
    }, false);
    try {
      this.provisional = await this.listen(provisionalPort, {
        key: serverKey,
        cert,
        requestCert: false,
        rejectUnauthorized: false,
        minVersion: "TLSv1.3",
        maxVersion: "TLSv1.3",
      }, true);
    } catch (error) {
      await this.closeListener(this.normal);
      this.normal = undefined;
      throw error;
    }
    return {
      normalPort: this.boundPort(this.normal),
      provisionalPort: this.boundPort(this.provisional),
    };
  }

  async stop(): Promise<void> {
    const normal = this.normal;
    const provisional = this.provisional;
    this.normal = undefined;
    this.provisional = undefined;
    await Promise.all([
      normal === undefined ? Promise.resolve() : this.closeListener(normal),
      provisional === undefined ? Promise.resolve() : this.closeListener(provisional),
    ]);
  }

  private listen(
    port: number,
    tlsOptions: Parameters<typeof createServer>[0],
    provisional: boolean,
  ): Promise<Server> {
    const server = createServer(tlsOptions, (socket) => {
      this.sockets.add(socket);
      socket.once("close", () => this.sockets.delete(socket));
      void admitTlsSocket(socket, { ...this.options, path: "direct" }, provisional).catch((error: unknown) => {
        logError("direct-tls", provisional ? "provisional admission" : "mutual admission", error);
        socket.destroy();
      });
    });
    server.on("tlsClientError", (error) => logError("direct-tls", "client handshake", error));
    return new Promise<Server>((resolveListen, rejectListen) => {
      server.once("error", rejectListen);
      server.listen(port, () => {
        server.removeListener("error", rejectListen);
        resolveListen(server);
      });
    });
  }

  private boundPort(server: Server | undefined): number {
    const address = server?.address();
    if (address === null || address === undefined || typeof address === "string") return 0;
    return address.port;
  }

  private closeListener(server: Server): Promise<void> {
    return new Promise((resolveClose) => {
      server.close(() => resolveClose());
      for (const socket of this.sockets) socket.destroy();
    });
  }
}

export type { ListenerHandle };
