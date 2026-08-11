import { once } from "node:events";
import { describe, expect, it } from "vitest";
import { RelayTunnel } from "../src/relay/tunnel.js";
import {
  DATA_MESSAGE_MAX_BYTES,
  MAX_TUNNEL_WRITE_BYTES,
  type RelayError,
  type RelaySocketConnectOptions,
  type RelaySocketEvent,
  type RelaySocketListener,
  type RelayWebSocket,
} from "../src/relay/types.js";
import { dataSocketOptions, relayEndpoints } from "../src/relay/endpoint.js";

class FakeSocket implements RelayWebSocket {
  extensions = "";
  readyState = 1;
  readonly sent: { bytes: Buffer; binary: boolean; compress: boolean }[] = [];
  readonly closes: { code: number; reason: string }[] = [];
  paused = false;
  terminated = false;
  private readonly listeners = new Map<RelaySocketEvent, Set<RelaySocketListener>>();

  on(event: RelaySocketEvent, listener: RelaySocketListener): this {
    let listeners = this.listeners.get(event);
    if (listeners === undefined) {
      listeners = new Set();
      this.listeners.set(event, listeners);
    }
    listeners.add(listener);
    return this;
  }
  off(event: RelaySocketEvent, listener: RelaySocketListener): this {
    this.listeners.get(event)?.delete(listener);
    return this;
  }
  emit(event: RelaySocketEvent, ...arguments_: unknown[]): void {
    for (const listener of this.listeners.get(event) ?? []) listener(...arguments_);
  }
  send(data: string | Uint8Array, options: { readonly binary: boolean; readonly compress: false }, callback: (error?: Error) => void): void {
    this.sent.push({ bytes: Buffer.from(data), binary: options.binary, compress: options.compress });
    callback();
  }
  ping(callback: (error?: Error) => void): void { callback(); }
  pause(): void { this.paused = true; }
  resume(): void { this.paused = false; }
  close(code: number, reason: string): void { this.closes.push({ code, reason }); }
  terminate(): void { this.terminated = true; }
}

describe("opaque relay tunnel", () => {
  it("exposes exact opaque bytes as Duplex and disables compression", async () => {
    const socket = new FakeSocket();
    const tunnel = new RelayTunnel(socket);
    const inbound = Buffer.from([0, 255, 1, 128, 2]);
    const read = once(tunnel, "data");
    socket.emit("message", inbound, true);
    expect((await read)[0]).toEqual(inbound);
    const outbound = Buffer.alloc(DATA_MESSAGE_MAX_BYTES + 7, 0xa5);
    await new Promise<void>((resolve, reject) => tunnel.write(outbound, (error) => error === null || error === undefined ? resolve() : reject(error)));
    expect(socket.sent).toHaveLength(2);
    expect(socket.sent.map((item) => item.bytes.byteLength)).toEqual([DATA_MESSAGE_MAX_BYTES, 7]);
    expect(Buffer.concat(socket.sent.map((item) => item.bytes))).toEqual(outbound);
    expect(socket.sent.every((item) => item.binary && !item.compress)).toBe(true);
    expect(tunnel.byteChannel()).toMatchObject({ readable: tunnel, writable: tunnel });
    tunnel.destroy();
  });

  it("applies read backpressure instead of dropping authoritative bytes", () => {
    const socket = new FakeSocket();
    const tunnel = new RelayTunnel(socket);
    socket.emit("message", Buffer.alloc(DATA_MESSAGE_MAX_BYTES), true);
    expect(socket.paused).toBe(true);
    tunnel.read(1);
    expect(socket.paused).toBe(false);
    tunnel.destroy();
  });

  it("rejects an unbounded single write before sending", async () => {
    const socket = new FakeSocket();
    const tunnel = new RelayTunnel(socket);
    const fault = once(tunnel, "error");
    tunnel.write(Buffer.alloc(MAX_TUNNEL_WRITE_BYTES + 1), () => undefined);
    const [error] = await fault as [RelayError];
    expect(error.code).toBe("RELAY_RESOURCE_EXHAUSTED");
    expect(socket.sent).toEqual([]);
  });

  it("rejects text and oversized frames without including bytes in errors", async () => {
    const textSocket = new FakeSocket();
    const textTunnel = new RelayTunnel(textSocket);
    const textError = once(textTunnel, "error");
    textSocket.emit("message", "private-payload", false);
    const [textFault] = await textError as [RelayError];
    expect(textFault.code).toBe("RELAY_DATA_PROTOCOL");
    expect(textFault.message).not.toContain("private-payload");
    expect(textSocket.closes[0]?.code).toBe(1003);

    const largeSocket = new FakeSocket();
    const largeTunnel = new RelayTunnel(largeSocket);
    const largeError = once(largeTunnel, "error");
    largeSocket.emit("message", Buffer.alloc(DATA_MESSAGE_MAX_BYTES + 1, 0x73), true);
    const [largeFault] = await largeError as [RelayError];
    expect(largeFault.code).toBe("RELAY_RESOURCE_EXHAUSTED");
    expect(largeSocket.closes[0]?.code).toBe(1009);
  });
});

describe("relay endpoint security", () => {
  it("requires an origin-only WSS endpoint and TLS-authenticated compression-off options", () => {
    expect(relayEndpoints("wss://relay.example", "route-1")).toEqual({
      controlWss: "wss://relay.example/v1/routes/route-1/control",
      dataWss: "wss://relay.example/v1/routes/route-1/data",
      devicesHttps: "https://relay.example/v1/routes/route-1/devices",
      revokeHttps: "https://relay.example/v1/routes/route-1/revoke",
      pairingHttps: "https://relay.example/v1/routes/route-1/pairing",
    });
    for (const url of ["ws://relay.example", "wss://user@relay.example", "wss://relay.example/path", "wss://relay.example?x=1"]) {
      expect(() => relayEndpoints(url, "route-1")).toThrow(/WSS/);
    }
    const options: RelaySocketConnectOptions = dataSocketOptions("proof");
    expect(options).toMatchObject({
      perMessageDeflate: false,
      maxPayload: DATA_MESSAGE_MAX_BYTES,
      followRedirects: false,
      rejectUnauthorized: true,
      minVersion: "TLSv1.3",
      headers: { "X-Relay-Proof": "proof" },
    });
  });
});
