import { generateKeyPairSync } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { describe, expect, it } from "vitest";
import { RouteKeyRing, type PersistedRouteKeyState, type RouteKeyPersistence } from "../src/relay/key-ring.js";
import { RelayPairingClient, type RelayPairingFetch } from "../src/relay/pairing-client.js";
import { NodeP256RouteSigner, type P256RouteSigner } from "../src/relay/proof.js";
import { RelayError, type RelayClock } from "../src/relay/types.js";

const NOW = Date.UTC(2026, 7, 9, 12);

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

const manualClock: RelayClock = {
  nowMs: () => NOW,
  random: () => 0.5,
  setTimeout: () => 0,
  clearTimeout: () => undefined,
};

async function expectRelayError(promise: Promise<unknown>, code: RelayError["code"]): Promise<void> {
  await expect(promise).rejects.toSatisfy((error: unknown) => error instanceof RelayError && error.code === code);
}

function routeSigner(keyId: string): NodeP256RouteSigner {
  return new NodeP256RouteSigner(keyId, generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey);
}

interface RecordedCall {
  readonly url: string;
  readonly method: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly body?: string;
}

function harness(handler: (call: RecordedCall) => { status: number; body?: unknown }): {
  client: RelayPairingClient;
  calls: RecordedCall[];
} {
  const keys = new MemoryKeys();
  keys.signers.set("mac-1", routeSigner("mac-1"));
  keys.signers.set("mac-old", routeSigner("mac-old"));
  keys.state = { version: 1, routeId: "route-1", activeKeyId: "mac-1", overlapKeys: [{ keyId: "mac-old", retireAfterMs: NOW + 60_000 }] };
  const calls: RecordedCall[] = [];
  const fetch: RelayPairingFetch = (url, init) => {
    const call: RecordedCall = { url, method: init.method, headers: init.headers, ...(init.body === undefined ? {} : { body: init.body }) };
    calls.push(call);
    const result = handler(call);
    return Promise.resolve({
      status: result.status,
      body: new TextEncoder().encode(result.body === undefined ? "" : JSON.stringify(result.body)),
    });
  };
  const client = new RelayPairingClient({
    relayBaseUrl: "wss://relay.example",
    keyRing: new RouteKeyRing(keys, manualClock),
    clock: manualClock,
    fetch,
  });
  return { client, calls };
}

describe("relay provisional pairing client", () => {
  it("opens an exchange with a route-admin proof against the relay pairing endpoint", async () => {
    const { client, calls } = harness(() => ({
      status: 201,
      body: { pairingId: "pair-1", secret: Buffer.alloc(32, 7).toString("base64url"), expiresAt: new Date(NOW + 300_000).toISOString() },
    }));
    const exchange = await client.openExchange();
    expect(exchange.pairingId).toBe("pair-1");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe("POST");
    expect(calls[0]?.url).toBe("https://relay.example/v1/routes/route-1/pairing");
    const proof = JSON.parse(calls[0]?.headers["X-Relay-Proof"] ?? "null") as { type: string; signed: Record<string, unknown> };
    expect(proof.type).toBe("route.proof");
    expect(proof.signed).toMatchObject({ audience: "route-admin", routeId: "route-1", keyId: "mac-1" });
  });

  it("falls back to the overlap key on 401 and rejects exhausted authentication", async () => {
    const fallback = harness((call) => {
      const proof = JSON.parse(call.headers["X-Relay-Proof"] ?? "null") as { signed: { keyId: string } };
      return proof.signed.keyId === "mac-old" ? { status: 201, body: { pairingId: "p", secret: "s".repeat(43), expiresAt: new Date(NOW + 1_000).toISOString() } } : { status: 401 };
    });
    await expect(fallback.client.openExchange()).resolves.toMatchObject({ pairingId: "p" });
    expect(fallback.calls).toHaveLength(2);

    const rejected = harness(() => ({ status: 401 }));
    await expectRelayError(rejected.client.openExchange(), "RELAY_ADMIN_REJECTED");
  });

  it("maps capacity failures to RELAY_RESOURCE_EXHAUSTED", async () => {
    const busy = harness(() => ({ status: 409 }));
    await expectRelayError(busy.client.openExchange(), "RELAY_RESOURCE_EXHAUSTED");
  });

  it("fetches the deposited request, returning undefined while none arrived", async () => {
    const payload = Buffer.from("csr-bytes", "utf8");
    const { client, calls } = harness(() => ({ status: 200, body: { message: payload.toString("base64url") } }));
    const message = await client.fetchRequest("pair-1");
    expect(Buffer.from(message ?? []).toString("utf8")).toBe("csr-bytes");
    expect(calls[0]?.method).toBe("GET");
    expect(calls[0]?.url).toBe("https://relay.example/v1/routes/route-1/pairing/pair-1");

    const empty = harness(() => ({ status: 404 }));
    await expect(empty.client.fetchRequest("pair-1")).resolves.toBeUndefined();
  });

  it("submits the opaque reply and reports closed exchanges as not ready", async () => {
    const { client, calls } = harness(() => ({ status: 202 }));
    await client.submitReply("pair-1", new TextEncoder().encode("cert-bytes"));
    expect(calls[0]?.url).toBe("https://relay.example/v1/routes/route-1/pairing/pair-1/reply");
    expect(JSON.parse(calls[0]?.body ?? "{}")).toEqual({ message: Buffer.from("cert-bytes", "utf8").toString("base64url") });

    const closed = harness(() => ({ status: 404 }));
    await expectRelayError(closed.client.submitReply("pair-1", new TextEncoder().encode("x")), "RELAY_NOT_READY");
    await expect(client.submitReply("pair-1", new Uint8Array(0))).rejects.toThrow(/exceeds bounds/);
    await expect(client.submitReply("pair-1", new Uint8Array(17 << 10))).rejects.toThrow(/exceeds bounds/);
  });

  it("rejects malformed exchange handles and messages", async () => {
    const malformed = harness(() => ({ status: 201, body: { pairingId: "bad/id", secret: "s", expiresAt: "soon" } }));
    await expect(malformed.client.openExchange()).rejects.toThrow(/malformed/);
    const badMessage = harness(() => ({ status: 200, body: { message: "!!!" } }));
    await expect(badMessage.client.fetchRequest("pair-1")).rejects.toThrow(/malformed/);
  });
});
