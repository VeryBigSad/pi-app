import { generateKeyPairSync, verify } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { describe, expect, it, vi } from "vitest";
import { RelayRouteAdmin } from "../src/relay/admin.js";
import { RouteKeyRing, type PersistedRouteKeyState, type RouteKeyPersistence } from "../src/relay/key-ring.js";
import {
  canonicalRouteSigned,
  createRouteProof,
  NodeP256RouteSigner,
  parseControlChallenge,
  parseControlReady,
  parseRouteNotice,
  type P256RouteSigner,
  type RouteSigned,
} from "../src/relay/proof.js";
import { RelayError, ROTATION_OVERLAP_MS, type RelayClock } from "../src/relay/types.js";

const NOW = Date.UTC(2026, 7, 9, 12);
const NONCE = Buffer.alloc(32, 7).toString("base64url");

class TestClock implements RelayClock {
  now = NOW;
  random(): number { return 0.5; }
  nowMs(): number { return this.now; }
  setTimeout(): unknown { return 1; }
  clearTimeout(handle: unknown): void { void handle; }
}

class MemoryKeyPersistence implements RouteKeyPersistence {
  state: unknown;
  readonly writes: PersistedRouteKeyState[] = [];
  readonly signers = new Map<string, P256RouteSigner>();

  readState(): Promise<unknown> { return Promise.resolve(structuredClone(this.state)); }
  writeStateAtomically(
    state: PersistedRouteKeyState,
    expectedState: PersistedRouteKeyState | undefined,
  ): Promise<boolean> {
    if (!isDeepStrictEqual(this.state, expectedState)) return Promise.resolve(false);
    this.state = structuredClone(state);
    this.writes.push(structuredClone(state));
    return Promise.resolve(true);
  }
  loadSigner(keyId: string): Promise<P256RouteSigner | undefined> { return Promise.resolve(this.signers.get(keyId)); }
}

class DelayedWritePersistence extends MemoryKeyPersistence {
  readonly firstWriteEntered: Promise<void>;
  private enterFirstWrite: () => void = () => undefined;
  private releaseFirstWrite: () => void = () => undefined;
  private readonly firstWriteGate: Promise<void>;
  private first = true;

  constructor() {
    super();
    this.firstWriteEntered = new Promise((resolve) => { this.enterFirstWrite = resolve; });
    this.firstWriteGate = new Promise((resolve) => { this.releaseFirstWrite = resolve; });
  }

  override async writeStateAtomically(
    state: PersistedRouteKeyState,
    expectedState: PersistedRouteKeyState | undefined,
  ): Promise<boolean> {
    if (this.first) {
      this.first = false;
      this.enterFirstWrite();
      await this.firstWriteGate;
    }
    return super.writeStateAtomically(state, expectedState);
  }

  release(): void { this.releaseFirstWrite(); }
}

function signer(keyId: string): NodeP256RouteSigner {
  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  return new NodeP256RouteSigner(keyId, privateKey);
}

function signed(overrides: Partial<RouteSigned> = {}): RouteSigned {
  return {
    audience: "control",
    routeId: "route-1",
    keyId: "mac-1",
    nonce: NONCE,
    expiresAt: new Date(NOW + 20_000).toISOString(),
    ...overrides,
  };
}

function parseRequestBody(init: RequestInit | undefined): unknown {
  if (typeof init?.body !== "string") throw new Error("missing request body");
  return JSON.parse(init.body) as unknown;
}

function proofHeaderKeyId(init: RequestInit): string {
  const headers = init.headers;
  if (headers === undefined || Array.isArray(headers) || headers instanceof Headers) throw new Error("missing proof header");
  const raw = headers["X-Relay-Proof"];
  if (typeof raw !== "string") throw new Error("missing proof header");
  const value: unknown = JSON.parse(raw);
  if (typeof value !== "object" || value === null || !("signed" in value)) throw new Error("missing signed proof");
  const proofSigned = value.signed;
  if (typeof proofSigned !== "object" || proofSigned === null || !("keyId" in proofSigned) || typeof proofSigned.keyId !== "string") {
    throw new Error("missing proof key ID");
  }
  return proofSigned.keyId;
}

describe("relay P-256 proofs", () => {
  it("signs exact JCS bytes with ASN.1 DER ECDSA-SHA256", async () => {
    const routeSigner = signer("mac-1");
    const value = signed({
      rendezvousId: "rv-1",
      audience: "mac-data",
    });
    const proof = await createRouteProof(value, routeSigner, true);
    expect(proof.type).toBe("route.proof");
    expect(proof.signed).toBe(value);
    const publicKey = await routeSigner.publicKeySpki();
    expect(verify(
      "sha256",
      Buffer.from(canonicalRouteSigned(value)),
      { key: Buffer.from(publicKey), format: "der", type: "spki" },
      Buffer.from(proof.signature, "base64url"),
    )).toBe(true);
    expect(Buffer.from(proof.signature, "base64url")[0]).toBe(0x30);
  });

  it("parses the current Go challenge shape and rejects boundary drift", () => {
    const challenge = JSON.stringify({ type: "route.challenge", signed: signed() });
    expect(parseControlChallenge(Buffer.from(challenge), false, NOW)).toEqual(signed());
    expect(() => parseControlChallenge(Buffer.from(challenge), true, NOW)).toThrow(RelayError);
    expect(() => parseControlChallenge(Buffer.alloc((16 << 10) + 1), false, NOW)).toThrow(/bounds/);
    expect(parseControlChallenge(JSON.stringify({ type: "route.challenge", signed: signed(), extra: true }), false, NOW)).toEqual(signed());
    expect(() => parseControlChallenge(JSON.stringify({ type: "route.challenge", signed: signed({ nonce: "x" }) }), false, NOW)).toThrow(/nonce/);
    expect(() => parseControlChallenge(JSON.stringify({ type: "route.challenge", signed: signed({ expiresAt: new Date(NOW + 30_001).toISOString() }) }), false, NOW)).toThrow(/expiry/);
  });

  it("accepts current Go and schema control-ready shapes but rejects wrong bindings", () => {
    expect(() => parseControlReady(JSON.stringify({ type: "route.control.ready" }), false, "route-1", "mac-1")).not.toThrow();
    expect(() => parseControlReady(JSON.stringify({ type: "route.control.ready", routeId: "route-1", keyId: "mac-1" }), false, "route-1", "mac-1")).not.toThrow();
    expect(() => parseControlReady(JSON.stringify({ type: "route.control.ready", routeId: "route-2", keyId: "mac-1" }), false, "route-1", "mac-1")).toThrow(/binding/);
  });

  it("validates one-use notice fields and 20-second lifetime", () => {
    const notice = {
      type: "route.notice",
      rendezvousId: "rv-1",
      nonce: NONCE,
      expiresAt: new Date(NOW + 20_000).toISOString(),
      mode: "normal",
    };
    expect(parseRouteNotice(JSON.stringify(notice), false, NOW)).toMatchObject({ ...notice, expiresAtMs: NOW + 20_000 });
    expect(() => parseRouteNotice(JSON.stringify({ ...notice, mode: "other" }), false, NOW)).toThrow(/mode/);
    expect(() => parseRouteNotice(JSON.stringify({ ...notice, expiresAt: new Date(NOW + 20_001).toISOString() }), false, NOW)).toThrow(/expiry/);
    expect(parseRouteNotice(JSON.stringify({ ...notice, additive: true }), false, NOW)).toMatchObject({ rendezvousId: "rv-1" });
  });
});

describe("route key persistence and overlap", () => {
  it("persists active and overlap state, restores cold, then prunes after 24 hours", async () => {
    const clock = new TestClock();
    const persistence = new MemoryKeyPersistence();
    persistence.signers.set("old", signer("old"));
    persistence.signers.set("new", signer("new"));
    const ring = new RouteKeyRing(persistence, clock);
    await ring.installInitial("route-1", "old");
    await ring.rotateTo("new");
    expect(await ring.snapshot()).toEqual({
      version: 1,
      routeId: "route-1",
      activeKeyId: "new",
      overlapKeys: [{ keyId: "old", retireAfterMs: NOW + ROTATION_OVERLAP_MS }],
    });
    const cold = new RouteKeyRing(persistence, clock);
    expect((await cold.authenticationCandidates()).map((item) => item.keyId)).toEqual(["new", "old"]);
    clock.now += ROTATION_OVERLAP_MS;
    expect((await cold.authenticationCandidates()).map((item) => item.keyId)).toEqual(["new"]);
    expect(persistence.writes.at(-1)?.overlapKeys).toEqual([]);
  });

  it("serializes expiry pruning with concurrent rotation writes", async () => {
    const clock = new TestClock();
    const persistence = new DelayedWritePersistence();
    persistence.signers.set("old", signer("old"));
    persistence.signers.set("new", signer("new"));
    persistence.state = {
      version: 1,
      routeId: "route-1",
      activeKeyId: "old",
      overlapKeys: [{ keyId: "expired", retireAfterMs: NOW }],
    };
    const ring = new RouteKeyRing(persistence, clock);
    const candidates = ring.authenticationCandidates();
    await persistence.firstWriteEntered;
    const rotation = ring.rotateTo("new");
    persistence.release();
    await Promise.all([candidates, rotation]);
    expect(persistence.state).toEqual({
      version: 1,
      routeId: "route-1",
      activeKeyId: "new",
      overlapKeys: [{ keyId: "old", retireAfterMs: NOW + ROTATION_OVERLAP_MS }],
    });
  });

  it("fails a stale prune across key-ring instances instead of overwriting rotation", async () => {
    const clock = new TestClock();
    const persistence = new DelayedWritePersistence();
    persistence.signers.set("old", signer("old"));
    persistence.signers.set("new", signer("new"));
    persistence.state = {
      version: 1,
      routeId: "route-1",
      activeKeyId: "old",
      overlapKeys: [{ keyId: "expired", retireAfterMs: NOW }],
    };
    const stalePrune = new RouteKeyRing(persistence, clock).authenticationCandidates();
    await persistence.firstWriteEntered;
    await new RouteKeyRing(persistence, clock).rotateTo("new");
    persistence.release();
    await expect(stalePrune).rejects.toMatchObject({ code: "RELAY_PERSISTENCE" });
    expect(persistence.state).toEqual({
      version: 1,
      routeId: "route-1",
      activeKeyId: "new",
      overlapKeys: [{ keyId: "old", retireAfterMs: NOW + ROTATION_OVERLAP_MS }],
    });
  });

  it("fails closed on malformed state, missing signer, and oversized overlap", async () => {
    const clock = new TestClock();
    const persistence = new MemoryKeyPersistence();
    persistence.state = { version: 1, routeId: "route-1", activeKeyId: "missing", overlapKeys: [], extra: true };
    await expect(new RouteKeyRing(persistence, clock).initialize()).rejects.toMatchObject({ code: "RELAY_PERSISTENCE" });
    persistence.state = { version: 1, routeId: "route-1", activeKeyId: "missing", overlapKeys: [] };
    await expect(new RouteKeyRing(persistence, clock).authenticationCandidates()).rejects.toMatchObject({ code: "RELAY_BAD_KEY" });
  });
});

describe("route-admin API", () => {
  it("adds and revokes device keys with fresh bounded route-admin proofs", async () => {
    const clock = new TestClock();
    const persistence = new MemoryKeyPersistence();
    const mac = signer("mac-1");
    const device = signer("device-1");
    persistence.signers.set(mac.keyId, mac);
    const ring = new RouteKeyRing(persistence, clock);
    await ring.installInitial("route-1", mac.keyId);
    const requests: { url: string; init: RequestInit }[] = [];
    const fetch = vi.fn((url: string, init: RequestInit) => {
      requests.push({ url, init });
      return Promise.resolve(new Response(null, { status: requests.length === 1 ? 201 : 204 }));
    });
    const admin = new RelayRouteAdmin({
      relayBaseUrl: "wss://relay.example",
      keyRing: ring,
      clock,
      fetch,
      entropy: { randomBytes: () => Buffer.alloc(32, requests.length + 1) },
    });
    await admin.addDeviceKey("device-1", await device.publicKeySpki());
    clock.now += 1;
    await admin.revokeKey("device-1");
    expect(requests.map((item) => item.url)).toEqual([
      "https://relay.example/v1/routes/route-1/devices",
      "https://relay.example/v1/routes/route-1/revoke",
    ]);
    const firstProof = JSON.parse(String((requests[0]?.init.headers as Record<string, string>)["X-Relay-Proof"])) as { type: string; signed: RouteSigned; signature: string };
    expect(firstProof.type).toBe("route.proof");
    expect(firstProof.signed).toMatchObject({ audience: "route-admin", routeId: "route-1", keyId: "mac-1" });
    expect(Buffer.from(firstProof.signed.nonce, "base64url")).toHaveLength(32);
    expect(verify(
      "sha256",
      Buffer.from(canonicalRouteSigned(firstProof.signed)),
      { key: Buffer.from(await mac.publicKeySpki()), format: "der", type: "spki" },
      Buffer.from(firstProof.signature, "base64url"),
    )).toBe(true);
    expect(parseRequestBody(requests[0]?.init)).toEqual({
      keyId: "device-1",
      publicKeySpki: Buffer.from(await device.publicKeySpki()).toString("base64url"),
    });
    expect(parseRequestBody(requests[1]?.init)).toEqual({ keyId: "device-1" });
  });

  it("falls back to an overlap Mac key when the active key is not registered", async () => {
    const clock = new TestClock();
    const persistence = new MemoryKeyPersistence();
    const active = signer("new");
    const overlap = signer("old");
    const device = signer("device-1");
    persistence.signers.set(active.keyId, active);
    persistence.signers.set(overlap.keyId, overlap);
    persistence.state = {
      version: 1,
      routeId: "route-1",
      activeKeyId: active.keyId,
      overlapKeys: [{ keyId: overlap.keyId, retireAfterMs: NOW + 60_000 }],
    };
    const requests: RequestInit[] = [];
    const admin = new RelayRouteAdmin({
      relayBaseUrl: "wss://relay.example",
      keyRing: new RouteKeyRing(persistence, clock),
      clock,
      fetch: (_url, init) => {
        requests.push(init);
        return Promise.resolve(new Response(null, { status: requests.length === 1 ? 401 : 201 }));
      },
      entropy: { randomBytes: () => Buffer.alloc(32, requests.length + 1) },
    });
    await admin.addDeviceKey("device-1", await device.publicKeySpki());
    expect(requests.map((request) => proofHeaderKeyId(request))).toEqual(["new", "old"]);
  });

  it("does not expose a rejected response body", async () => {
    const clock = new TestClock();
    const persistence = new MemoryKeyPersistence();
    const mac = signer("mac-1");
    const device = signer("device-1");
    persistence.signers.set(mac.keyId, mac);
    const ring = new RouteKeyRing(persistence, clock);
    await ring.installInitial("route-1", mac.keyId);
    const secret = "payload-secret-that-must-not-escape";
    const admin = new RelayRouteAdmin({
      relayBaseUrl: "wss://relay.example",
      keyRing: ring,
      clock,
      fetch: () => Promise.resolve(new Response(secret, { status: 401 })),
    });
    await expect(admin.addDeviceKey("device-1", await device.publicKeySpki())).rejects.toSatisfy((error: unknown) =>
      error instanceof RelayError && error.code === "RELAY_ADMIN_REJECTED" && !error.message.includes(secret),
    );
  });
});
