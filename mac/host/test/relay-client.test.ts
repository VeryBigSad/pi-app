import { generateKeyPairSync } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { describe, expect, it } from "vitest";
import { MacRelayClient } from "../src/relay/client.js";
import { RouteKeyRing, type PersistedRouteKeyState, type RouteKeyPersistence } from "../src/relay/key-ring.js";
import { NodeP256RouteSigner, type P256RouteSigner } from "../src/relay/proof.js";
import type { RelayTunnel } from "../src/relay/tunnel.js";
import {
  CONTROL_MESSAGE_MAX_BYTES,
  type RelayClock,
  type RelaySocketConnectOptions,
  type RelaySocketEvent,
  type RelaySocketListener,
  type RelayWebSocket,
  type RelayWebSocketFactory,
} from "../src/relay/types.js";

const NOW = Date.UTC(2026, 7, 9, 12);
const NONCE = Buffer.alloc(32, 9).toString("base64url");

interface TimerEntry {
  readonly id: number;
  readonly due: number;
  readonly callback: () => void;
}

class ManualClock implements RelayClock {
  now = NOW;
  randomValue = 0.5;
  readonly scheduledDelays: number[] = [];
  private nextId = 1;
  private readonly timers = new Map<number, TimerEntry>();

  nowMs(): number { return this.now; }
  random(): number { return this.randomValue; }
  setTimeout(callback: () => void, milliseconds: number): unknown {
    const id = this.nextId++;
    this.scheduledDelays.push(milliseconds);
    this.timers.set(id, { id, due: this.now + milliseconds, callback });
    return id;
  }
  clearTimeout(handle: unknown): void {
    if (typeof handle === "number") this.timers.delete(handle);
  }
  advance(milliseconds: number): void {
    const target = this.now + milliseconds;
    for (;;) {
      const next = [...this.timers.values()].filter((item) => item.due <= target).sort((left, right) => left.due - right.due || left.id - right.id)[0];
      if (next === undefined) break;
      this.timers.delete(next.id);
      this.now = next.due;
      next.callback();
    }
    this.now = target;
  }
}

class FakeSocket implements RelayWebSocket {
  extensions = "";
  readyState = 0;
  readonly sent: { raw: Buffer; binary: boolean; compress: boolean }[] = [];
  pings = 0;
  paused = false;
  terminated = false;
  readonly closes: number[] = [];
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
    for (const listener of [...(this.listeners.get(event) ?? [])]) listener(...arguments_);
  }
  open(): void {
    this.readyState = 1;
    this.emit("open");
  }
  message(value: unknown, binary = false): void {
    const raw = typeof value === "string" || Buffer.isBuffer(value) ? value : JSON.stringify(value);
    this.emit("message", raw, binary);
  }
  send(data: string | Uint8Array, options: { readonly binary: boolean; readonly compress: false }, callback: (error?: Error) => void): void {
    this.sent.push({ raw: Buffer.from(data), binary: options.binary, compress: options.compress });
    callback();
  }
  ping(callback: (error?: Error) => void): void { this.pings += 1; callback(); }
  pause(): void { this.paused = true; }
  resume(): void { this.paused = false; }
  close(code: number): void { this.readyState = 3; this.closes.push(code); }
  terminate(): void { this.readyState = 3; this.terminated = true; }
}

class FakeFactory implements RelayWebSocketFactory {
  readonly connections: { url: string; options: RelaySocketConnectOptions; socket: FakeSocket }[] = [];
  connect(url: string, options: RelaySocketConnectOptions): RelayWebSocket {
    const socket = new FakeSocket();
    this.connections.push({ url, options, socket });
    return socket;
  }
}

class MemoryKeys implements RouteKeyPersistence {
  state: unknown;
  readonly signers = new Map<string, P256RouteSigner>();
  readState(): Promise<unknown> { return Promise.resolve(structuredClone(this.state)); }
  writeStateAtomically(state: PersistedRouteKeyState, expectedState: PersistedRouteKeyState | undefined): Promise<boolean> {
    if (!isDeepStrictEqual(this.state, expectedState)) return Promise.resolve(false);
    this.state = structuredClone(state);
    return Promise.resolve(true);
  }
  loadSigner(keyId: string): Promise<P256RouteSigner | undefined> { return Promise.resolve(this.signers.get(keyId)); }
}

function routeSigner(keyId: string): NodeP256RouteSigner {
  return new NodeP256RouteSigner(keyId, generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey);
}

async function flush(): Promise<void> {
  for (let index = 0; index < 32; index += 1) await Promise.resolve();
}

function sentKeyId(socket: FakeSocket | undefined): string {
  const value: unknown = JSON.parse(socket?.sent[0]?.raw.toString("utf8") ?? "null");
  if (typeof value !== "object" || value === null || !("keyId" in value) || typeof value.keyId !== "string") {
    throw new Error("missing key ID");
  }
  return value.keyId;
}

async function readyClient(overlap = false, onPairingRequest?: (pairingId: string) => void): Promise<{
  client: MacRelayClient;
  clock: ManualClock;
  factory: FakeFactory;
  control: FakeSocket;
  tunnels: { tunnel: RelayTunnel; mode: string }[];
  keys: MemoryKeys;
}> {
  const clock = new ManualClock();
  const factory = new FakeFactory();
  const keys = new MemoryKeys();
  keys.signers.set("mac-1", routeSigner("mac-1"));
  if (overlap) keys.signers.set("mac-old", routeSigner("mac-old"));
  keys.state = {
    version: 1,
    routeId: "route-1",
    activeKeyId: "mac-1",
    overlapKeys: overlap ? [{ keyId: "mac-old", retireAfterMs: NOW + 60_000 }] : [],
  };
  const ring = new RouteKeyRing(keys, clock);
  const tunnels: { tunnel: RelayTunnel; mode: string }[] = [];
  const client = new MacRelayClient({
    relayBaseUrl: "wss://relay.example",
    keyRing: ring,
    webSockets: factory,
    clock,
    onTunnel: (tunnel, notice) => tunnels.push({ tunnel, mode: notice.mode }),
    ...(onPairingRequest === undefined ? {} : { onPairingRequest }),
  });
  await client.start();
  const control = factory.connections[0]?.socket;
  if (control === undefined) throw new Error("missing control socket");
  control.open();
  expect(JSON.parse(control.sent[0]?.raw.toString("utf8") ?? "null")).toEqual({
    type: "route.control.begin",
    routeId: "route-1",
    keyId: "mac-1",
  });
  control.message({
    type: "route.challenge",
    signed: {
      audience: "control",
      routeId: "route-1",
      keyId: "mac-1",
      nonce: NONCE,
      expiresAt: new Date(clock.now + 20_000).toISOString(),
    },
  });
  await flush();
  const proof = JSON.parse(control.sent[1]?.raw.toString("utf8") ?? "null") as Record<string, unknown>;
  expect(proof["type"]).toBe("route.proof");
  expect(Buffer.from(String(proof["signature"]), "base64url")[0]).toBe(0x30);
  control.message({ type: "route.control.ready" });
  expect(client.state).toBe("ready");
  return { client, clock, factory, control, tunnels, keys };
}

describe("Mac relay control and data", () => {
  it("performs cold control auth and opens a nonce/rendezvous-bound opaque data WSS", async () => {
    const { client, clock, factory, control, tunnels } = await readyClient();
    expect(factory.connections[0]).toMatchObject({
      url: "wss://relay.example/v1/routes/route-1/control",
      options: {
        perMessageDeflate: false,
        maxPayload: CONTROL_MESSAGE_MAX_BYTES,
        rejectUnauthorized: true,
        minVersion: "TLSv1.3",
      },
    });
    const notice = {
      type: "route.notice",
      rendezvousId: "rv-1",
      nonce: Buffer.alloc(32, 4).toString("base64url"),
      expiresAt: new Date(clock.now + 20_000).toISOString(),
      mode: "normal",
    };
    control.message(notice);
    await flush();
    const dataConnection = factory.connections[1];
    expect(dataConnection?.url).toBe("wss://relay.example/v1/routes/route-1/data");
    expect(dataConnection?.options).toMatchObject({ perMessageDeflate: false, rejectUnauthorized: true, minVersion: "TLSv1.3" });
    const proof = JSON.parse(dataConnection?.options.headers?.["X-Relay-Proof"] ?? "null") as { type: string; signed: Record<string, unknown> };
    expect(proof.type).toBe("route.proof");
    expect(proof.signed).toEqual({
      audience: "mac-data",
      routeId: "route-1",
      keyId: "mac-1",
      rendezvousId: "rv-1",
      nonce: notice.nonce,
      expiresAt: notice.expiresAt,
    });
    dataConnection?.socket.open();
    await flush();
    expect(tunnels).toHaveLength(1);
    expect(tunnels[0]?.mode).toBe("normal");
    client.stop();
  });

  it("forwards provisional pairing notifications without opening a data tunnel", async () => {
    const received: string[] = [];
    const { client, factory, control } = await readyClient(false, (pairingId) => received.push(pairingId));
    control.message({ type: "pairing.request", pairingId: "pair-1" });
    await flush();
    expect(received).toEqual(["pair-1"]);
    expect(factory.connections).toHaveLength(1);
    expect(client.state).toBe("ready");

    control.message({ type: "pairing.request", pairingId: "bad/id" });
    await flush();
    expect(client.state).toBe("backoff");
    client.stop();
  });

  it("does not attach a stale notice after stop", async () => {
    const { client, clock, factory, control, tunnels } = await readyClient();
    control.message({
      type: "route.notice",
      rendezvousId: "rv-stop",
      nonce: Buffer.alloc(32, 5).toString("base64url"),
      expiresAt: new Date(clock.now + 20_000).toISOString(),
      mode: "normal",
    });
    client.stop();
    await flush();
    expect(factory.connections).toHaveLength(1);
    expect(tunnels).toEqual([]);
    expect(client.state).toBe("stopped");
  });

  it("rejects reused, malformed, expired, binary, and oversized control notices", async () => {
    const { client, clock, control } = await readyClient();
    const notice = {
      type: "route.notice",
      rendezvousId: "rv-1",
      nonce: Buffer.alloc(32, 4).toString("base64url"),
      expiresAt: new Date(clock.now + 20_000).toISOString(),
      mode: "normal",
    };
    control.message(notice);
    await flush();
    control.message(notice);
    expect(control.terminated).toBe(true);
    expect(client.state).toBe("backoff");
    expect(client.lastFault).toMatchObject({ code: "RELAY_BAD_NOTICE" });

    const malformedCases: { value: unknown; binary?: boolean }[] = [
      { value: { ...notice, rendezvousId: "bad/id" } },
      { value: { ...notice, expiresAt: new Date(clock.now).toISOString() } },
      { value: { ...notice, mode: "unknown" } },
      { value: notice, binary: true },
      { value: Buffer.alloc(CONTROL_MESSAGE_MAX_BYTES + 1) },
    ];
    for (const item of malformedCases) {
      const fixture = await readyClient();
      fixture.control.message(item.value, item.binary);
      expect(fixture.control.terminated).toBe(true);
      expect(fixture.client.state).toBe("backoff");
      fixture.client.stop();
    }
    client.stop();
  });

  it("sends 30-second pings and reconnects after 90 seconds without pong", async () => {
    const { client, clock, factory, control } = await readyClient();
    clock.advance(30_000);
    expect(control.pings).toBe(1);
    control.emit("pong", Buffer.alloc(0));
    clock.advance(60_000);
    expect(control.pings).toBe(3);
    clock.advance(30_000);
    expect(control.terminated).toBe(true);
    expect(client.lastFault).toMatchObject({ code: "RELAY_LIVENESS_TIMEOUT" });
    expect(client.state).toBe("backoff");
    clock.advance(250);
    await flush();
    expect(factory.connections).toHaveLength(2);
    client.stop();
  });

  it("uses exponential full-jitter reconnect and cold overlap-key fallback", async () => {
    const clock = new ManualClock();
    const factory = new FakeFactory();
    const keys = new MemoryKeys();
    keys.signers.set("new", routeSigner("new"));
    keys.signers.set("old", routeSigner("old"));
    keys.state = {
      version: 1,
      routeId: "route-1",
      activeKeyId: "new",
      overlapKeys: [{ keyId: "old", retireAfterMs: NOW + 60_000 }],
    };
    const client = new MacRelayClient({
      relayBaseUrl: "wss://relay.example",
      keyRing: new RouteKeyRing(keys, clock),
      webSockets: factory,
      clock,
      onTunnel: () => undefined,
    });
    await client.start();
    const first = factory.connections[0]?.socket;
    first?.open();
    expect(sentKeyId(first)).toBe("new");
    first?.emit("error", new Error("rejected"));
    expect(clock.scheduledDelays.at(-1)).toBe(250);
    clock.advance(250);
    await flush();
    const second = factory.connections[1]?.socket;
    second?.open();
    expect(sentKeyId(second)).toBe("old");
    second?.emit("error", new Error("rejected"));
    expect(clock.scheduledDelays.at(-1)).toBe(500);
    clock.advance(500);
    await flush();
    expect(factory.connections).toHaveLength(3);
    client.stop();
  });

  it("fails closed if compression is negotiated", async () => {
    const clock = new ManualClock();
    const factory = new FakeFactory();
    const keys = new MemoryKeys();
    keys.signers.set("mac-1", routeSigner("mac-1"));
    keys.state = { version: 1, routeId: "route-1", activeKeyId: "mac-1", overlapKeys: [] };
    const client = new MacRelayClient({
      relayBaseUrl: "wss://relay.example",
      keyRing: new RouteKeyRing(keys, clock),
      webSockets: factory,
      clock,
      onTunnel: () => undefined,
    });
    await client.start();
    const socket = factory.connections[0]?.socket;
    if (socket === undefined) throw new Error("missing socket");
    socket.extensions = "permessage-deflate";
    socket.open();
    expect(socket.terminated).toBe(true);
    expect(client.lastFault).toMatchObject({ code: "RELAY_COMPRESSION_NEGOTIATED" });
    client.stop();
  });
});
