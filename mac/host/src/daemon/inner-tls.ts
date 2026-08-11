import { createPrivateKey } from "node:crypto";
import { TLSSocket } from "node:tls";
import type { Duplex } from "node:stream";
import type { HostTlsMaterial } from "./tls-material.js";

export interface InnerTlsContext {
  readonly serverKeyPem: string;
  readonly serverCertificatePem: string;
  readonly caCertificatePem: string;
}

export async function createInnerTlsContext(material: HostTlsMaterial): Promise<InnerTlsContext> {
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", material.serverKey));
  try {
    return {
      serverKeyPem: createPrivateKey({ key: pkcs8, format: "der", type: "pkcs8" })
        .export({ format: "pem", type: "pkcs8" })
        .toString(),
      serverCertificatePem: material.serverCertificatePem,
      caCertificatePem: material.caCertificatePem,
    };
  } finally {
    pkcs8.fill(0);
  }
}

/**
 * Terminates the inner nested TLS 1.3 session that runs inside a relay tunnel.
 * The relay only carries opaque bytes; authentication still happens here, at the
 * inner layer, exactly as on the direct LAN listener.
 */
export function terminateInnerTls(
  channel: Duplex,
  context: InnerTlsContext,
  provisional: boolean,
): Promise<TLSSocket> {
  return new Promise<TLSSocket>((resolveHandshake, rejectHandshake) => {
    let settled = false;
    const socket = new TLSSocket(channel, {
      isServer: true,
      key: context.serverKeyPem,
      cert: context.serverCertificatePem,
      ...(provisional
        ? { requestCert: false, rejectUnauthorized: false }
        : { requestCert: true, rejectUnauthorized: true, ca: context.caCertificatePem }),
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
    });
    const fail = (): void => {
      if (settled) return;
      settled = true;
      socket.destroy();
      rejectHandshake(new Error("inner TLS handshake failed"));
    };
    socket.once("error", fail);
    socket.once("close", () => {
      if (!settled) fail();
    });
    socket.once("secure", () => {
      if (settled) return;
      settled = true;
      socket.removeListener("error", fail);
      if (!provisional && !socket.authorized) {
        socket.destroy();
        rejectHandshake(new Error("inner TLS peer is not authorized"));
        return;
      }
      resolveHandshake(socket);
    });
    const timeout = setTimeout(() => {
      if (!settled) fail();
    }, 15_000);
    timeout.unref();
    socket.once("secure", () => clearTimeout(timeout));
  });
}
