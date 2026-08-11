import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { connect as connectNet, createServer, type Socket } from "node:net";
import { connect as connectTls, type TLSSocket } from "node:tls";
import { afterEach, describe, expect, it } from "vitest";
import { createInnerTlsContext, terminateInnerTls } from "../src/daemon/inner-tls.js";
import { loadOrCreateTlsMaterial, type HostTlsMaterial } from "../src/daemon/tls-material.js";
import type { WrappingSecretProvider } from "../src/security/keychain.js";

const secrets: WrappingSecretProvider = {
  getSecret: () => Promise.resolve(Buffer.alloc(32, 11)),
};

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function material(): Promise<HostTlsMaterial> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-inner-tls-"));
  roots.push(root);
  return await loadOrCreateTlsMaterial({
    keyDirectory: join(root, "keys"),
    secrets,
    instanceId: "550e8400-e29b-41d4-a716-446655440000",
  });
}

/** Stands in for a relay tunnel: an opaque loopback byte pipe. */
async function tunnelPair(onServerSide: (socket: Socket) => void): Promise<{ clientSide: Socket; close: () => Promise<void> }> {
  const relay = createServer(onServerSide);
  await new Promise<void>((resolveListen) => relay.listen(0, "127.0.0.1", () => resolveListen()));
  const address = relay.address();
  if (address === null || typeof address === "string") throw new Error("relay fixture has no port");
  const clientSide = await new Promise<Socket>((resolveConnect, rejectConnect) => {
    const socket = connectNet(address.port, "127.0.0.1", () => resolveConnect(socket));
    socket.once("error", rejectConnect);
  });
  return {
    clientSide,
    close: async () => {
      clientSide.destroy();
      await new Promise<void>((resolveClose) => relay.close(() => resolveClose()));
    },
  };
}

describe("terminateInnerTls", () => {
  it("terminates nested TLS 1.3 inside a relay-like duplex and carries bytes", async () => {
    const host = await material();
    const context = await createInnerTlsContext(host);
    let serverSocketPromise: Promise<TLSSocket> | undefined;
    const tunnel = await tunnelPair((socket) => {
      serverSocketPromise = terminateInnerTls(socket, context, true);
      serverSocketPromise.catch(() => undefined);
    });
    const client = connectTls({
      socket: tunnel.clientSide,
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
      ca: host.caCertificatePem,
      checkServerIdentity: () => undefined,
    });
    await new Promise<void>((resolveSecure, rejectSecure) => {
      client.once("secure", () => resolveSecure());
      client.once("error", rejectSecure);
    });
    const serverSocket = await serverSocketPromise;
    if (serverSocket === undefined) throw new Error("inner TLS server socket missing");
    const echoed = new Promise<Buffer>((resolveData) => {
      client.once("data", (chunk: Buffer) => resolveData(chunk));
    });
    serverSocket.write(Buffer.from("ping", "utf8"));
    expect((await echoed).toString("utf8")).toBe("ping");
    client.destroy();
    serverSocket.destroy();
    await tunnel.close();
  });

  it("does not authorize clients that reject the host CA", async () => {
    const host = await material();
    const context = await createInnerTlsContext(host);
    const tunnel = await tunnelPair((socket) => {
      terminateInnerTls(socket, context, true).then(
        (serverSocket) => serverSocket.destroy(),
        () => undefined,
      );
    });
    const client = connectTls({
      socket: tunnel.clientSide,
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
      checkServerIdentity: () => undefined,
    });
    // TLS 1.3 emits "secure" with authorized=false instead of an error.
    const outcome = await new Promise<string>((resolveOutcome) => {
      client.once("secure", () => resolveOutcome(client.authorized ? "authorized" : "unauthorized"));
      client.once("error", () => resolveOutcome("error"));
    });
    expect(outcome).not.toBe("authorized");
    client.destroy();
    await tunnel.close();
  });
});
