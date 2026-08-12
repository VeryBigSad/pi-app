import { createHash, randomUUID } from "node:crypto";
import {
  FrameKind,
  assertEnvelope,
  commandPayloadHash,
  createEnvelope,
  decodeJsonPayload,
  decodeTerminalPayload,
  encodeFrame,
  encodeJsonPayload,
  encodeStreamPayload,
  encodeTerminalPayload,
  type Envelope,
  type JsonObject,
  type JsonValue,
} from "@pimobile/protocol";
import { describe, expect, it } from "vitest";
import { BoundedFrameReader } from "../src/gateway/framing.js";
import { createHostGateway } from "../src/gateway/host-gateway.js";
import type {
  BlobOutput,
  BlobRuntime,
  BlobStreamMetadata,
  BlobStreamUpload,
  ByteTransport,
  GatewayClock,
  GatewayConnection,
  HostGateway,
  HostGatewayOptions,
  OutboundMessage,
  TerminalOutput,
  VerifiedTransportAdmission,
} from "../src/gateway/types.js";
import type {
  CommandJournalStore,
  JournalInsertResult,
  JournalRecord,
  JournalRecoveryResult,
  JournalTransition,
  JournalTransitionResult,
} from "../src/journal/types.js";
import { JournalStoreError } from "../src/journal/types.js";

const deviceId = "550e8400-e29b-41d4-a716-446655440001";
const invitationId = "550e8400-e29b-41d4-a716-446655440002";
const sessionId = "550e8400-e29b-41d4-a716-446655440003";
const streamEpoch = "550e8400-e29b-41d4-a716-446655440004";
const streamId = "550e8400-e29b-41d4-a716-446655440005";
const blobId = "550e8400-e29b-41d4-a716-446655440006";
const sha256 = "a".repeat(64);

class MemoryEndpoint implements ByteTransport {
  peer: MemoryEndpoint | undefined;
  readonly closeCodes: string[] = [];
  failWrites = false;
  onWrite: ((bytes: Uint8Array) => void) | undefined;
  private readonly incoming: Uint8Array[] = [];
  private readonly readers: ((value: Uint8Array | null) => void)[] = [];
  private ended = false;

  async read(maxBytes: number, signal: AbortSignal): Promise<Uint8Array | null> {
    signal.throwIfAborted();
    const available = this.incoming.shift();
    if (available !== undefined) return splitForRead(available, maxBytes, this.incoming);
    if (this.ended) return null;
    return new Promise<Uint8Array | null>((resolve, reject) => {
      const abort = (): void => reject(new DOMException("Aborted", "AbortError"));
      signal.addEventListener("abort", abort, { once: true });
      this.readers.push((value) => {
        signal.removeEventListener("abort", abort);
        if (value !== null && value.length > maxBytes) resolve(splitForRead(value, maxBytes, this.incoming));
        else resolve(value);
      });
    });
  }

  write(bytes: Uint8Array, signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    if (this.failWrites) throw new Error("forced transport write failure");
    const peer = this.peer;
    if (peer === undefined || peer.ended) throw new Error("transport closed");
    peer.enqueue(bytes.slice());
    this.onWrite?.(bytes);
    return Promise.resolve();
  }

  close(code: string): Promise<void> {
    if (!this.ended) {
      this.closeCodes.push(code);
      this.end();
      this.peer?.end();
    }
    return Promise.resolve();
  }

  fragmentNext(parts: readonly number[]): void {
    const value = this.incoming.shift();
    if (value === undefined) return;
    let offset = 0;
    const fragments: Uint8Array[] = [];
    for (const size of parts) {
      fragments.push(value.slice(offset, offset + size));
      offset += size;
    }
    if (offset < value.length) fragments.push(value.slice(offset));
    this.incoming.unshift(...fragments);
  }

  private enqueue(bytes: Uint8Array): void {
    const reader = this.readers.shift();
    if (reader === undefined) this.incoming.push(bytes);
    else reader(bytes);
  }

  private end(): void {
    if (this.ended) return;
    this.ended = true;
    for (const reader of this.readers.splice(0)) reader(null);
  }
}

function splitForRead(value: Uint8Array, maximum: number, queue: Uint8Array[]): Uint8Array {
  if (value.length <= maximum) return value;
  queue.unshift(value.slice(maximum));
  return value.slice(0, maximum);
}

function transportPair(): { client: MemoryEndpoint; server: MemoryEndpoint } {
  const client = new MemoryEndpoint();
  const server = new MemoryEndpoint();
  client.peer = server;
  server.peer = client;
  return { client, server };
}

class TestClient {
  private readonly reader: BoundedFrameReader;

  constructor(readonly transport: MemoryEndpoint) {
    this.reader = new BoundedFrameReader(transport);
  }

  async send(type: string, body: JsonObject = {}): Promise<string> {
    const messageId = randomUUID();
    const envelope = createEnvelope(type, messageId, null, body);
    await this.transport.write(encodeFrame(FrameKind.Json, encodeJsonPayload(envelope)), new AbortController().signal);
    return messageId;
  }

  async sendBinary(kind: FrameKind, payload: Uint8Array): Promise<void> {
    await this.transport.write(encodeFrame(kind, payload), new AbortController().signal);
  }

  async receive(): Promise<Envelope> {
    const frame = await this.reader.next(new AbortController().signal);
    if (frame?.kind !== FrameKind.Json) throw new Error("expected JSON frame");
    const value = decodeJsonPayload(frame.payload);
    assertEnvelope(value);
    return value;
  }

  async receiveBinary(kind: FrameKind): Promise<Uint8Array> {
    const frame = await this.reader.next(new AbortController().signal);
    if (frame?.kind !== kind) throw new Error("expected binary frame");
    return frame.payload;
  }
}

class ManualClock implements GatewayClock {
  nowMs = 1;
  private nextId = 0;
  private readonly timers = new Map<number, { at: number; operation: () => void }>();

  now(): number {
    return this.nowMs;
  }

  setTimeout(operation: () => void, delayMs: number): unknown {
    const id = ++this.nextId;
    this.timers.set(id, { at: this.nowMs + delayMs, operation });
    return id;
  }

  clearTimeout(handle: unknown): void {
    if (typeof handle === "number") this.timers.delete(handle);
  }

  advance(milliseconds: number): void {
    this.nowMs += milliseconds;
    for (;;) {
      const due = [...this.timers].find(([, timer]) => timer.at <= this.nowMs);
      if (due === undefined) return;
      this.timers.delete(due[0]);
      due[1].operation();
    }
  }
}

class MemoryJournal implements CommandJournalStore {
  readonly records = new Map<string, JournalRecord>();

  get(commandId: string): Promise<JournalRecord | undefined> {
    return Promise.resolve(this.records.get(commandId));
  }

  insertReceived(record: JournalRecord): Promise<JournalInsertResult> {
    const existing = this.records.get(record.command.commandId);
    if (existing !== undefined) {
      if (existing.command.payloadHash !== record.command.payloadHash) throw new JournalStoreError("COMMAND_ID_REUSE");
      return Promise.resolve({ inserted: false, record: existing });
    }
    this.records.set(record.command.commandId, record);
    return Promise.resolve({ inserted: true, record });
  }

  transition(commandId: string, payloadHash: string, transition: JournalTransition): Promise<JournalTransitionResult> {
    const current = this.records.get(commandId);
    if (current === undefined) throw new JournalStoreError("command not found");
    if (current.command.payloadHash !== payloadHash) throw new JournalStoreError("COMMAND_ID_REUSE");
    const state = nextState(current.state, transition.kind);
    if (state === undefined) return Promise.resolve({ transitioned: false, record: current });
    const next: JournalRecord = {
      ...current,
      state,
      dormant: false,
      updatedAtMs: transition.atMs,
      revision: current.revision + 1,
      ...("result" in transition ? { result: transition.result } : {}),
      ...("errorCode" in transition ? { errorCode: transition.errorCode } : {}),
    };
    this.records.set(commandId, next);
    return Promise.resolve({ transitioned: true, record: next });
  }

  recover(): Promise<JournalRecoveryResult> {
    let dormantReceived = 0;
    let indeterminateArmed = 0;
    for (const [id, record] of this.records) {
      if (record.state === "RECEIVED") {
        this.records.set(id, { ...record, dormant: true });
        dormantReceived += 1;
      } else if (record.state === "ARMED") {
        this.records.set(id, { ...record, state: "INDETERMINATE", errorCode: "HOST_RECOVERED_AFTER_ARM" });
        indeterminateArmed += 1;
      }
    }
    return Promise.resolve({ dormantReceived, indeterminateArmed });
  }
}

function nextState(state: JournalRecord["state"], kind: JournalTransition["kind"]): JournalRecord["state"] | undefined {
  if (state === "RECEIVED" && kind === "arm") return "ARMED";
  if (state === "RECEIVED" && kind === "reject") return "REJECTED";
  if (state === "ARMED" && kind === "ack") return "ACKED";
  if (state === "ARMED" && kind === "reject") return "REJECTED";
  if (state === "ARMED" && kind === "indeterminate") return "INDETERMINATE";
  return undefined;
}

class RecordingBlobRuntime implements BlobRuntime {
  readonly bytes: Uint8Array[] = [];
  readonly cancellations: string[] = [];
  output: BlobOutput | undefined;
  metadata: BlobStreamMetadata | undefined;
  blockWrite = false;
  closeCalls = 0;
  enteredWrite = false;
  private releaseWrite: (() => void) | undefined;

  open(metadata: BlobStreamMetadata, output: BlobOutput): Promise<BlobStreamUpload> {
    this.metadata = metadata;
    this.output = output;
    return Promise.resolve({
      write: async (_sequence, _offset, data, signal) => {
        if (this.blockWrite) {
          this.enteredWrite = true;
          await new Promise<void>((resolve, reject) => {
            this.releaseWrite = resolve;
            signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
          });
        }
        this.bytes.push(data.slice());
      },
      close: (length, digest): Promise<OutboundMessage> => {
        this.closeCalls += 1;
        return Promise.resolve({
          type: "blob.ready",
          body: { blobId, size: length.toString(), sha256: digest, mimeType: metadata.mediaType, expiresAt: "2030-01-01T00:00:00.000Z" },
        });
      },
      cancel: (reason) => {
        this.cancellations.push(reason);
        return Promise.resolve();
      },
    });
  }

  unblock(): void {
    this.releaseWrite?.();
  }
}

function defaultOptions(overrides: Partial<HostGatewayOptions> = {}): HostGatewayOptions {
  const clock = overrides.clock ?? new ManualClock();
  return {
    hostVersion: "1.0.0",
    piVersion: "0.84.0",
    features: ["terminal", "blobs"],
    clock,
    pairing: {
      handle: () => Promise.resolve({}),
    },
    authentication: {
      assertionOptions: () => Promise.resolve({ challenge: "opaque-options" }),
      verifyAssertion: (_response, _binding, complete) => Promise.resolve(complete({ userId: "owner", credentialId: "credential" })),
    },
    sync: {
      catalog: () => Promise.resolve([hostCatalogEntry(sessionId)]),
      prepare: () => Promise.resolve({
        kind: "replay",
        sessionId,
        streamEpoch,
        fromSequence: 0n,
        throughSequence: 1n,
        events: [{ sessionId, streamEpoch, sequence: "1", piType: "agent_end" }],
      }),
      committed: () => Promise.resolve(),
    },
    journal: new MemoryJournal(),
    commandAuthorizer: {
      authorize: () => Promise.resolve({
        approvedAtMs: clock.now(),
        revalidate: (signal) => {
          signal.throwIfAborted();
          return Promise.resolve();
        },
      }),
    },
    commandPaths: { capture: () => ({ generation: 1, dispatch: () => Promise.resolve({ ok: true }) }) },
    blobs: new RecordingBlobRuntime(),
    terminal: {
      open: () => Promise.resolve({
        generation: 7n,
        channel: { write: () => Promise.resolve(), close: () => Promise.resolve() },
      }),
    },
    ...overrides,
  };
}

function canonicalEvent(
  sequence: string,
  piType: string,
  eventSessionId = sessionId,
  eventStreamEpoch = streamEpoch,
): JsonObject {
  const rawJson = JSON.stringify({ type: piType });
  return {
    sessionId: eventSessionId,
    streamEpoch: eventStreamEpoch,
    sequence,
    piType,
    projection: { type: piType },
    rawJson,
    rawSize: String(Buffer.byteLength(rawJson, "utf8")),
    rawSha256: createHash("sha256").update(rawJson, "utf8").digest("hex"),
  };
}

function finalCanonicalEvent(sequence: string, appendId: string): JsonObject {
  const rawJson = "{\"type\":\"message_end\"}";
  return {
    sessionId,
    streamEpoch,
    sequence,
    appendId,
    piType: "message_end",
    projection: {
      type: "message_end",
      message: { id: "m-final", role: "assistant", content: [{ type: "text", text: "final" }] },
    },
    rawJson,
    rawSize: String(Buffer.byteLength(rawJson, "utf8")),
    rawSha256: createHash("sha256").update(rawJson, "utf8").digest("hex"),
  };
}

function hostCatalogEntry(id: string): JsonObject {
  return {
    sessionId: id,
    provider: "anthropic",
    model: "claude",
    thinkingLevel: "high",
    repo: "/tmp/pi-app",
    worktree: null,
    cwd: "/tmp/pi-app",
    parentId: null,
    createdAt: "2026-08-01T00:00:00.000Z",
    updatedAt: "2026-08-12T00:00:00.000Z",
  };
}

function admitNormal(gateway: HostGateway, transport: ByteTransport, path: "direct" | "relay" = "direct"): GatewayConnection {
  return gateway.accept(gateway.transportVerification.mutualTlsVerified({
    transport,
    deviceId,
    certificateId: "cert-1",
    tlsExporter: new Uint8Array(32).fill(1),
    path,
  }));
}

async function attachReady(
  gateway: HostGateway,
  path: "direct" | "relay" = "direct",
): Promise<{
  connection: GatewayConnection;
  client: TestClient;
  pair: { client: MemoryEndpoint; server: MemoryEndpoint };
}> {
  const pair = transportPair();
  const client = new TestClient(pair.client);
  const connection = admitNormal(gateway, pair.server, path);
  await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: ["terminal", "unknown"] });
  expect((await client.receive()).type).toBe("server.hello");
  expect((await client.receive()).type).toBe("auth.assertion.options");
  await client.send("auth.assertion.response", { credential: "opaque-response" });
  expect((await client.receive()).type).toBe("auth.result");
  const resumeId = await client.send("sync.resume", { cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] });
  expect(await client.receive()).toMatchObject({ type: "session.catalog", replyTo: resumeId });
  expect(await client.receive()).toMatchObject({ type: "sync.replay", replyTo: resumeId });
  expect(await client.receive()).toMatchObject({ type: "event.batch", body: { events: [{ sequence: "1" }] } });
  await client.send("event.ack", { sessionId, streamEpoch, sequence: "1" });
  expect(await client.receive()).toMatchObject({ type: "sync.complete", body: {}, replyTo: resumeId });
  await waitUntil(() => connection.phase() === "READY");
  await client.send("ping", { foregroundLease: true });
  expect((await client.receive()).type).toBe("pong");
  expect(connection.phase()).toBe("READY");
  return { connection, client, pair };
}

async function readyConnection(options: HostGatewayOptions = defaultOptions()): Promise<{
  gateway: HostGateway;
  connection: GatewayConnection;
  client: TestClient;
  pair: { client: MemoryEndpoint; server: MemoryEndpoint };
}> {
  const gateway = createHostGateway(options);
  return { gateway, ...await attachReady(gateway) };
}

function commandBody(commandId = "550e8400-e29b-41d4-a716-446655440010"): JsonObject {
  const input = { sessionId, operation: "prompt", payload: { message: "hello" }, expectedLeafId: "deadbeef" };
  return { commandId, ...input, payloadHash: commandPayloadHash(input) };
}

function pause(): { readonly promise: Promise<void>; readonly release: () => void } {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => { release = resolve; });
  return { promise, release };
}

async function waitUntil(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error("condition did not settle");
}

describe("host gateway transport and authorization", () => {
  it("rejects structurally forged transport facts and keeps provisional pairing isolated", async () => {
    const gateway = createHostGateway(defaultOptions({
      pairing: {
        handle: (message, context) => Promise.resolve({
          replies: message.type === "pair.confirm"
            ? [{ type: "pair.result", body: { invitationId: context.invitationId, certificateChain: ["opaque"] } }]
            : [{ type: "pair.confirm", body: { invitationId: context.invitationId, state: "waiting" } }],
          certificateIssued: message.type === "pair.confirm",
        }),
      },
    }));
    const forgedPair = transportPair();
    const forged = { mode: "mutual-tls", facts: { transport: forgedPair.server } } as unknown as VerifiedTransportAdmission;
    expect(() => gateway.accept(forged)).toThrow("not verified");
    const foreignGateway = createHostGateway(defaultOptions());
    const scopedPair = transportPair();
    const scopedAdmission = gateway.transportVerification.mutualTlsVerified({
      transport: scopedPair.server,
      deviceId,
      certificateId: "scoped",
      tlsExporter: new Uint8Array(32),
      path: "direct",
    });
    expect(() => foreignGateway.accept(scopedAdmission)).toThrow("not verified");
    await foreignGateway.close();

    const pair = transportPair();
    const client = new TestClient(pair.client);
    const provisionalAdmission = gateway.transportVerification.provisionalVerified({
      transport: pair.server,
      invitationId,
      serverCertificateSha256: sha256,
    });
    const connection = gateway.accept(provisionalAdmission);
    expect(() => gateway.accept(provisionalAdmission)).toThrow("not verified");
    await client.send("client.hello", { minMinor: 0 });
    expect(await client.receive()).toMatchObject({ type: "error", body: { code: "PAIRING_PHASE_REQUIRED" } });
    expect(connection.phase()).toBe("PAIRING_PROVISIONAL");

    await client.send("pair.begin", { invitationId });
    expect(await client.receive()).toMatchObject({ type: "pair.confirm", body: { invitationId } });
    await client.send("pair.confirm", { state: "confirmed" });
    expect((await client.receive()).type).toBe("pair.result");
    await connection.closed();
    expect(connection.phase()).toBe("CLOSING");
    await gateway.close();
  });

  it("fails closed if a provisional runtime attempts application output", async () => {
    const gateway = createHostGateway(defaultOptions({
      pairing: {
        handle: () => Promise.resolve({ replies: [{ type: "command.result", body: { secret: "blocked" } }] }),
      },
    }));
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = gateway.accept(gateway.transportVerification.provisionalVerified({
      transport: pair.server,
      invitationId,
      serverCertificateSha256: sha256,
    }));
    await client.send("pair.begin", { invitationId });
    expect(await client.receive()).toMatchObject({ type: "error", body: { code: "PROTOCOL_VIOLATION" } });
    await connection.closed();
    await gateway.close();
  });

  it("negotiates hello, fails closed on forged user facts, and forbids pre-READY access", async () => {
    const forgedOptions = defaultOptions({
      authentication: {
        assertionOptions: () => Promise.resolve({ challenge: "opaque-options" }),
        verifyAssertion: (_response, binding) => Promise.resolve({ userId: "owner", credentialId: "forged", binding }) as never,
      },
    });
    const forgedGateway = createHostGateway(forgedOptions);
    const forgedPair = transportPair();
    const forgedClient = new TestClient(forgedPair.client);
    const forgedConnection = admitNormal(forgedGateway, forgedPair.server);
    await forgedClient.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await forgedClient.receive();
    await forgedClient.receive();
    await forgedClient.send("command.submit", commandBody());
    expect(await forgedClient.receive()).toMatchObject({ type: "error", body: { code: "AUTH_REQUIRED" } });
    await forgedClient.send("auth.assertion.response", { credential: "forged" });
    expect(await forgedClient.receive()).toMatchObject({ type: "error", body: { code: "AUTH_FAILED" } });
    await forgedConnection.closed();

    const options = defaultOptions();
    const gateway = createHostGateway(options);
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: ["terminal"] });
    const hello = await client.receive();
    expect(hello).toMatchObject({ type: "server.hello", body: { minor: 0, features: ["terminal"] } });
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    expect((await client.receive()).type).toBe("auth.result");
    expect(connection.phase()).toBe("USER_AUTHENTICATED");
    await client.send("command.submit", commandBody());
    expect(await client.receive()).toMatchObject({ type: "error", body: { code: "SYNC_REQUIRED" } });
    expect((options.journal as MemoryJournal).records.size).toBe(0);
    await gateway.close();
  });

  it("emits one empty-resume completion replying to the resume before serving READY traffic", async () => {
    let prepares = 0;
    const gateway = createHostGateway(defaultOptions({
      sync: {
        catalog: () => Promise.resolve([]),
        listAll: () => Promise.resolve([]),
        prepare: () => {
          prepares += 1;
          return Promise.reject(new Error("prepare must not run"));
        },
        committed: () => Promise.resolve(),
      },
    }));
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    await client.receive();

    const resumeId = await client.send("sync.resume", { cursors: [] });
    expect(await client.receive()).toMatchObject({ type: "session.catalog", body: { sessions: [] }, replyTo: resumeId });
    expect(await client.receive()).toMatchObject({ type: "agents.catalog", body: { sessions: [] }, replyTo: resumeId });
    expect(await client.receive()).toMatchObject({ type: "sync.complete", body: {}, replyTo: resumeId });
    await waitUntil(() => connection.phase() === "READY");
    await client.send("ping");
    expect((await client.receive()).type).toBe("pong");
    expect(prepares).toBe(0);
    await gateway.close();
  });

  it("buffers live canonical events until host completion is sent and then enters READY", async () => {
    const gateway = createHostGateway(defaultOptions());
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    await client.receive();
    const resumeId = await client.send("sync.resume", { cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] });
    await client.receive();
    await client.receive();
    await client.receive();

    // The replay has already emitted sequence 1; a concurrent duplicate must not
    // be re-published after the completion fence.
    gateway.publishToReady("event.batch", { events: [canonicalEvent("1", "agent_end")] });
    gateway.publishToReady("event.batch", { events: [canonicalEvent("2", "agent_end")] });
    expect(connection.phase()).toBe("SYNCING");
    await client.send("event.ack", { sessionId, streamEpoch, sequence: "1" });
    expect(await client.receive()).toMatchObject({ type: "event.batch", body: { events: [{ sequence: "2", piType: "agent_end" }] } });
    expect(await client.receive()).toMatchObject({ type: "sync.complete", replyTo: resumeId });
    await waitUntil(() => connection.phase() === "READY");
    await gateway.close();
  });

  it("delivers every post-sync canonical record once, preserving metadata and final ordering", async () => {
    const ready = await readyConnection();
    ready.gateway.publishToReady("event.batch", { events: [canonicalEvent("2", "agent_start")] });
    ready.gateway.publishToReady("event.batch", { events: [canonicalEvent("3", "message_update")] });
    ready.gateway.publishToReady("message.append", { ...finalCanonicalEvent("4", "2"), appendId: "2" });
    // Re-emitting an already persisted sequence cannot produce a duplicate final delivery.
    ready.gateway.publishToReady("message.append", { ...finalCanonicalEvent("4", "2"), appendId: "2" });

    expect(await ready.client.receive()).toMatchObject({ type: "event.batch", body: { events: [{ sequence: "2", piType: "agent_start" }] } });
    expect(await ready.client.receive()).toMatchObject({ type: "event.batch", body: { events: [{ sequence: "3", piType: "message_update" }] } });
    expect(await ready.client.receive()).toMatchObject({ type: "message.append", body: { sequence: "4", appendId: "2", piType: "message_end" } });
    await ready.gateway.close();
  });

  it("queues a live append published at the sync completion fence without closing", async () => {
    const gateway = createHostGateway(defaultOptions());
    const pair = transportPair();
    let completionAppendQueued = false;
    pair.server.onWrite = (frame) => {
      if (frame[5] !== FrameKind.Json) return;
      const envelope = decodeJsonPayload(frame.subarray(12));
      if (envelope["type"] !== "sync.complete" || completionAppendQueued) return;
      completionAppendQueued = true;
      gateway.publishToReady("message.append", {
        sessionId,
        streamEpoch,
        sequence: "2",
        appendId: "2",
        piType: "message_end",
        projection: { type: "message_end" },
        rawJson: "{\"type\":\"message_end\"}",
        rawSize: "22",
        rawSha256: sha256,
      });
    };
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    await client.receive();
    const resumeId = await client.send("sync.resume", { cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] });
    await client.receive();
    await client.receive();
    await client.receive();
    await client.send("event.ack", { sessionId, streamEpoch, sequence: "1" });
    expect(await client.receive()).toMatchObject({ type: "sync.complete", replyTo: resumeId });
    expect(await client.receive()).toMatchObject({ type: "message.append", body: { appendId: "2", sequence: "2" } });
    await waitUntil(() => connection.phase() === "READY");
    expect(pair.server.closeCodes).not.toContain("PROTOCOL_VIOLATION");
    await gateway.close();
  });

  it("refreshes inventory and sends a new-session catalog before buffered appends and the completion fence", async () => {
    const newSessionId = "650e8400-e29b-41d4-a716-446655440003";
    let catalog = [hostCatalogEntry(sessionId)];
    const gateway = createHostGateway(defaultOptions({
      sync: {
        catalog: () => Promise.resolve(catalog),
        prepare: () => Promise.resolve({
          kind: "replay",
          sessionId,
          streamEpoch,
          fromSequence: 0n,
          throughSequence: 1n,
          events: [{ sessionId, streamEpoch, sequence: "1", piType: "agent_end" }],
        }),
        committed: () => Promise.resolve(),
      },
    }));
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    await client.receive();
    const resumeId = await client.send("sync.resume", { cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] });
    await client.receive();
    await client.receive();
    await client.receive();
    catalog = [hostCatalogEntry(sessionId), hostCatalogEntry(newSessionId)];
    gateway.publishToReady("event.batch", {
      events: [canonicalEvent("1", "agent_end", newSessionId, "650e8400-e29b-41d4-a716-446655440004")],
    });
    await client.send("event.ack", { sessionId, streamEpoch, sequence: "1" });
    expect(await client.receive()).toMatchObject({ type: "session.catalog", body: { sessions: catalog } });
    expect(await client.receive()).toMatchObject({ type: "event.batch", body: { events: [{ sessionId: newSessionId }] } });
    expect(await client.receive()).toMatchObject({ type: "sync.complete", replyTo: resumeId });
    await waitUntil(() => connection.phase() === "READY");
    await gateway.close();
  });

  it("closes fail-closed when a READY live event cannot be sent", async () => {
    const gateway = createHostGateway(defaultOptions());
    const ready = await attachReady(gateway);
    ready.pair.server.failWrites = true;
    gateway.publishToReady("event.batch", { events: [canonicalEvent("2", "agent_end")] });
    await ready.connection.closed();
    expect(ready.connection.phase()).toBe("CLOSING");
    expect(ready.pair.server.closeCodes).toContain("PROTOCOL_VIOLATION");
    await gateway.close();
  });

  it("emits no completion when synchronization preparation fails", async () => {
    const gateway = createHostGateway(defaultOptions({
      sync: {
        catalog: () => Promise.resolve([hostCatalogEntry(sessionId)]),
        prepare: () => Promise.reject(new Error("snapshot failed")),
        committed: () => Promise.resolve(),
      },
    }));
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    await client.receive();

    const resumeId = await client.send("sync.resume", { cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] });
    expect(await client.receive()).toMatchObject({ type: "session.catalog", replyTo: resumeId });
    expect(await client.receive()).toMatchObject({ type: "error", replyTo: resumeId });
    await connection.closed();
    expect(connection.phase()).toBe("CLOSING");
    await gateway.close();
  });

  it("prevents an in-flight verified assertion from restoring authority after auth.lock", async () => {
    const verification = pause();
    let verifying = false;
    const gateway = createHostGateway(defaultOptions({
      authentication: {
        assertionOptions: () => Promise.resolve({ challenge: "opaque-options" }),
        verifyAssertion: async (_response, _binding, complete) => {
          const fact = complete({ userId: "owner", credentialId: "credential" });
          verifying = true;
          await verification.promise;
          return fact;
        },
      },
    }));
    const pair = transportPair();
    const client = new TestClient(pair.client);
    const connection = admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    await waitUntil(() => verifying);
    await client.send("auth.lock");
    verification.release();
    expect(await client.receive()).toMatchObject({ type: "error", body: { code: "AUTH_REQUIRED" } });
    expect(connection.phase()).toBe("DEVICE_AUTHENTICATED");
    await gateway.close();
  });

  it("integrates READY commands with one journal dispatch across repeated submissions", async () => {
    const journal = new MemoryJournal();
    let dispatches = 0;
    const ready = await readyConnection(defaultOptions({
      journal,
      commandPaths: {
        capture: () => ({
          generation: 4,
          dispatch: () => {
            dispatches += 1;
            return Promise.resolve({ accepted: true });
          },
        }),
      },
    }));
    for (let index = 0; index < 25; index += 1) await ready.client.send("command.submit", commandBody());
    for (let index = 0; index < 25; index += 1) {
      expect(await ready.client.receive()).toMatchObject({ type: "command.result", body: { state: "ACKED", result: { accepted: true } } });
    }
    expect(dispatches).toBe(1);
    expect(journal.records.get(commandBody()["commandId"] as string)).toMatchObject({ state: "ACKED" });
    await ready.gateway.close();
  });

  it("recovers journal state before serving query or dormant resubmission", async () => {
    const journal = new MemoryJournal();
    const submitted = commandBody();
    const id = submitted["commandId"] as string;
    journal.records.set(id, {
      command: {
        commandId: id,
        sessionId,
        operation: submitted["operation"] as string,
        payload: submitted["payload"] as JsonObject,
        payloadHash: submitted["payloadHash"] as string,
        expectedLeafId: submitted["expectedLeafId"] as string,
      },
      state: "RECEIVED",
      dormant: false,
      receivedAtMs: 0,
      updatedAtMs: 0,
      revision: 0,
    });
    let dispatches = 0;
    const ready = await readyConnection(defaultOptions({
      journal,
      commandPaths: {
        capture: () => ({
          generation: 1,
          dispatch: () => {
            dispatches += 1;
            return Promise.resolve({ recovered: true });
          },
        }),
      },
    }));
    await ready.client.send("command.query", { commandId: id });
    expect(await ready.client.receive()).toMatchObject({ type: "command.state", body: { state: "RECEIVED", dormant: true } });
    expect(dispatches).toBe(0);
    await ready.client.send("command.submit", submitted);
    expect(await ready.client.receive()).toMatchObject({ type: "command.result", body: { state: "ACKED" } });
    expect(dispatches).toBe(1);
    await ready.gateway.close();
  });

  it("never migrates an ARMED command when a newer direct or relay path wins", async () => {
    const journal = new MemoryJournal();
    let dispatches = 0;
    let enteredDispatch = false;
    const gateway = createHostGateway(defaultOptions({
      journal,
      commandPaths: {
        capture: () => ({
          generation: 8,
          dispatch: (_command, _authorization, signal) => {
            dispatches += 1;
            enteredDispatch = true;
            return new Promise<JsonValue>((_resolve, reject) => {
              signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
            });
          },
        }),
      },
    }));
    const oldPath = await attachReady(gateway, "direct");
    await oldPath.client.send("command.submit", commandBody());
    await waitUntil(() => enteredDispatch);
    const newPath = await attachReady(gateway, "relay");
    expect(newPath.connection.pathGeneration).toBe(2);
    await waitUntil(() => journal.records.get(commandBody()["commandId"] as string)?.state === "INDETERMINATE");
    expect(dispatches).toBe(1);
    expect(oldPath.connection.phase()).toBe("CLOSING");

    await newPath.client.send("command.submit", commandBody());
    expect(await newPath.client.receive()).toMatchObject({ type: "command.state", body: { state: "INDETERMINATE" } });
    expect(dispatches).toBe(1);
    await gateway.close();
  });

  it("answers auth.result with success and a 12-hour passkey session expiry", async () => {
    const clock = new ManualClock();
    const gateway = createHostGateway(defaultOptions({ clock }));
    const pair = transportPair();
    const client = new TestClient(pair.client);
    admitNormal(gateway, pair.server);
    await client.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await client.receive();
    await client.receive();
    await client.send("auth.assertion.response", { credential: "opaque" });
    const result = await client.receive();
    expect(result.type).toBe("auth.result");
    expect(result.body["success"]).toBe(true);
    expect(result.body["expiresAt"]).toBe(new Date(1 + 12 * 60 * 60 * 1000).toISOString());
    await gateway.close();

    const custom = createHostGateway(defaultOptions({ clock: new ManualClock(), passkeySessionTtlMs: 60_000 }));
    const customPair = transportPair();
    const customClient = new TestClient(customPair.client);
    admitNormal(custom, customPair.server);
    await customClient.send("client.hello", { minMinor: 0, maxMinor: 0, appVersion: "1", deviceId, features: [] });
    await customClient.receive();
    await customClient.receive();
    await customClient.send("auth.assertion.response", { credential: "opaque" });
    expect((await customClient.receive()).body["expiresAt"]).toBe(new Date(1 + 60_000).toISOString());
    await custom.close();
    expect(() => createHostGateway(defaultOptions({ passkeySessionTtlMs: 30_000 }))).toThrow(RangeError);
  });

  it("accepts contiguous AudioPcm frames and emits ordered cumulative transcripts", async () => {
    const submitted: { sessionId: string; chunkSequence: bigint; final: boolean; bytes: number }[] = [];
    const ready = await readyConnection(defaultOptions({
      voice: {
        submit: (chunk) => {
          submitted.push({ sessionId: chunk.sessionId, chunkSequence: chunk.chunkSequence, final: chunk.final, bytes: chunk.pcm16le.byteLength });
          return Promise.resolve(chunk.chunkSequence === 0n ? "hello world" : "world again");
        },
      },
    }));
    await ready.client.send("voice.start", { streamId: sessionId, sampleRate: 16_000, channels: 1, sampleFormat: "s16le" });
    await ready.client.sendBinary(FrameKind.AudioPcm, encodeStreamPayload({ streamId: sessionId, sequence: 0, offset: 0n, data: Buffer.alloc(64, 1) }));
    await ready.client.send("voice.audio", { sessionId, chunkSequence: "0", final: false });
    await ready.client.sendBinary(FrameKind.AudioPcm, encodeStreamPayload({ streamId: sessionId, sequence: 1, offset: 64n, data: Buffer.alloc(32, 2) }));
    await ready.client.send("voice.audio", { sessionId, chunkSequence: "1", final: true });

    expect(await ready.client.receive()).toMatchObject({ type: "voice.partial", body: { sessionId, chunkSequence: "0", revision: "1", text: "hello world" } });
    expect(await ready.client.receive()).toMatchObject({ type: "voice.partial", body: { sessionId, chunkSequence: "1", revision: "2", text: "hello world again" } });
    expect(await ready.client.receive()).toMatchObject({ type: "voice.finish", body: { sessionId, chunkSequence: "1", text: "hello world again" } });
    expect(submitted).toEqual([
      { sessionId, chunkSequence: 0n, final: false, bytes: 64 },
      { sessionId, chunkSequence: 1n, final: true, bytes: 32 },
    ]);
    await ready.gateway.close();
  });

  it("terminally fails AudioPcm gaps and rejects second streams and JSON PCM", async () => {
    const ready = await readyConnection(defaultOptions({ voice: { submit: () => Promise.resolve("unused") } }));
    await ready.client.send("voice.start", { streamId: sessionId, sampleRate: 16_000, channels: 1, sampleFormat: "s16le" });
    await ready.client.sendBinary(FrameKind.AudioPcm, encodeStreamPayload({ streamId: sessionId, sequence: 1, offset: 0n, data: Buffer.alloc(2) }));
    expect(await ready.client.receive()).toMatchObject({ type: "voice.error", body: { streamId: sessionId, code: "STREAM_INVALID" } });
    await ready.gateway.close();

    const duplicate = await readyConnection(defaultOptions({ voice: { submit: () => Promise.resolve("unused") } }));
    await duplicate.client.send("voice.start", { streamId: sessionId, sampleRate: 16_000, channels: 1, sampleFormat: "s16le" });
    await duplicate.client.send("voice.start", { streamId, sampleRate: 16_000, channels: 1, sampleFormat: "s16le" });
    expect(await duplicate.client.receive()).toMatchObject({ type: "error", body: { code: "RESOURCE_EXHAUSTED" } });
    await duplicate.gateway.close();

    const jsonPcm = await readyConnection(defaultOptions({ voice: { submit: () => Promise.resolve("unused") } }));
    await jsonPcm.client.send("voice.audio", { sessionId, chunkSequence: "0", final: true, audio: "AQI" });
    expect(await jsonPcm.client.receive()).toMatchObject({ type: "error", body: { code: "PROTOCOL_VIOLATION" } });
    await jsonPcm.gateway.close();
  });

  it("reports runtime failures as voice.error correlated by streamId", async () => {
    const ready = await readyConnection(defaultOptions({
      voice: {
        submit: () => {
          const error = new Error("VOICE_RPM_LIMIT") as Error & { code: string; resetAtMs: number };
          error.code = "VOICE_QUOTA";
          error.resetAtMs = 1_000;
          return Promise.reject(error);
        },
      },
    }));
    await ready.client.send("voice.start", { streamId: sessionId, sampleRate: 16_000, channels: 1, sampleFormat: "s16le" });
    await ready.client.sendBinary(FrameKind.AudioPcm, encodeStreamPayload({ streamId: sessionId, sequence: 0, offset: 0n, data: Buffer.alloc(64, 2) }));
    await ready.client.send("voice.audio", { sessionId, chunkSequence: "0", final: false });
    expect(await ready.client.receive()).toMatchObject({
      type: "voice.error",
      body: { streamId: sessionId, code: "VOICE_QUOTA", detailCode: "VOICE_RPM_LIMIT", resetAtEpochMilliseconds: "1000" },
    });
    await ready.gateway.close();
  });

  it("publishes conformed message.append and session.settled events only to READY connections", async () => {
    const ready = await readyConnection();
    ready.gateway.publishToReady("message.append", {
      ...canonicalEvent("7", "message_end"),
      appendId: "7",
    });
    expect(await ready.client.receive()).toMatchObject({
      type: "message.append",
      body: { appendId: "7", sessionId, streamEpoch, sequence: "7" },
    });
    ready.gateway.publishToReady("session.settled", {
      settlementId: "550e8400-e29b-41d4-a716-446655440021",
      sessionId,
      streamEpoch,
      sequence: "7",
      settledAtMs: 42,
    });
    expect(await ready.client.receive()).toMatchObject({
      type: "session.settled",
      body: { settlementId: "550e8400-e29b-41d4-a716-446655440021", sessionId, sequence: "7" },
    });
    await ready.gateway.close();

    const pending = createHostGateway(defaultOptions());
    const pair = transportPair();
    const client = new TestClient(pair.client);
    admitNormal(pending, pair.server);
    pending.publishToReady("event.batch", { events: [canonicalEvent("8", "agent_end")] });
    await client.send("ping");
    expect((await client.receive()).type).toBe("pong");
    await pending.close();
  });

  it("downgrades immediately on auth.lock and independently on foreground lease expiry", async () => {
    const clock = new ManualClock();
    const first = await readyConnection(defaultOptions({ clock }));
    await first.client.send("auth.lock");
    await first.client.send("ping", { foregroundLease: true });
    await first.client.receive();
    expect(first.connection.phase()).toBe("DEVICE_AUTHENTICATED");
    await first.client.send("command.submit", commandBody());
    expect(await first.client.receive()).toMatchObject({ type: "error", body: { code: "AUTH_REQUIRED" } });
    await first.gateway.close();

    const independentClock = new ManualClock();
    const third = await readyConnection(defaultOptions({ clock: independentClock }));
    independentClock.advance(299_999);
    expect(third.connection.phase()).toBe("READY");
    await third.client.send("ping", { foregroundLease: true });
    await third.client.receive();
    independentClock.advance(299_999);
    expect(third.connection.phase()).toBe("READY");
    independentClock.advance(1);
    await waitUntil(() => third.connection.phase() === "DEVICE_AUTHENTICATED");
    expect(third.connection.phase()).toBe("DEVICE_AUTHENTICATED");
    await third.gateway.close();
  });

  it("backpressures blob close behind the preceding chunk write", async () => {
    const blobs = new RecordingBlobRuntime();
    blobs.blockWrite = true;
    const ready = await readyConnection(defaultOptions({ blobs }));
    await ready.client.send("stream.open", {
      streamId,
      purpose: "prompt_image",
      mediaType: "image/png",
      expectedLength: "1",
      sha256,
      limit: "1",
    });
    await ready.client.sendBinary(FrameKind.BlobChunk, encodeStreamPayload({ streamId, sequence: 0, offset: 0n, data: Uint8Array.of(1) }));
    await ready.client.send("stream.close", { streamId, length: "1", sha256 });
    await waitUntil(() => blobs.enteredWrite);
    expect(blobs.closeCalls).toBe(0);
    expect(blobs.bytes).toEqual([]);
    blobs.unblock();
    expect(await ready.client.receive()).toMatchObject({ type: "blob.ready" });
    expect(blobs.closeCalls).toBe(1);
    await ready.gateway.close();
  });

  it("streams contiguous blobs and terminal bytes with exact generations", async () => {
    const blobs = new RecordingBlobRuntime();
    let terminalOutput: TerminalOutput | undefined;
    const terminalWrites: Uint8Array[] = [];
    const terminalResets: string[] = [];
    const historyRequests: JsonObject[] = [];
    const ready = await readyConnection(defaultOptions({
      blobs,
      terminal: {
        open: (_request, output) => {
          terminalOutput = output;
          return Promise.resolve({
            generation: 9n,
            channel: {
              write: (data) => {
                terminalWrites.push(data.slice());
                return Promise.resolve();
              },
              reset: (reason) => {
                terminalResets.push(reason);
                return Promise.resolve();
              },
              history: (request) => {
                historyRequests.push(request);
                return Promise.resolve({
                  sessionId,
                  terminalGeneration: "9",
                  capturedAt: "2026-08-09T12:00:00.000Z",
                  text: "tmux capture",
                  truncatedLines: false,
                  truncatedBytes: true,
                });
              },
              close: () => Promise.resolve(),
            },
          });
        },
      },
    }));

    await ready.client.send("stream.open", {
      streamId,
      purpose: "prompt_image",
      mediaType: "image/png",
      expectedLength: "3",
      sha256,
      limit: "3",
    });
    await ready.client.sendBinary(FrameKind.BlobChunk, encodeStreamPayload({ streamId, sequence: 0, offset: 0n, data: Uint8Array.of(1, 2, 3) }));
    await ready.client.send("stream.close", { streamId, length: "3", sha256 });
    expect(await ready.client.receive()).toMatchObject({ type: "blob.ready", body: { blobId, size: "3" } });
    expect(blobs.bytes).toEqual([Uint8Array.of(1, 2, 3)]);

    await ready.client.send("terminal.open", { sessionId });
    expect(await ready.client.receive()).toMatchObject({ type: "terminal.ready", body: { terminalGeneration: "9" } });
    await ready.client.send("terminal.history.request", {
      sessionId,
      terminalGeneration: "9",
      maxLines: 5_000,
      maxBytes: 1_024,
    });
    expect(await ready.client.receive()).toMatchObject({
      type: "terminal.history.response",
      body: { sessionId, terminalGeneration: "9", text: "tmux capture", truncatedLines: false, truncatedBytes: true },
    });
    expect(historyRequests).toEqual([{
      sessionId,
      terminalGeneration: "9",
      maxLines: 5_000,
      maxBytes: 1_024,
    }]);

    await ready.client.sendBinary(FrameKind.TerminalBytes, encodeTerminalPayload({ terminalGeneration: 9n, sequence: 0n, data: Uint8Array.of(4, 5) }));
    await waitUntil(() => terminalWrites.length === 1);
    expect(terminalWrites).toEqual([Uint8Array.of(4, 5)]);

    await terminalOutput?.write(Uint8Array.of(6, 7), new AbortController().signal);
    const outbound = decodeTerminalPayload(await ready.client.receiveBinary(FrameKind.TerminalBytes));
    expect(outbound).toEqual({ terminalGeneration: 9n, sequence: 0n, data: Uint8Array.of(6, 7) });
    const output = terminalOutput;
    if (output === undefined) throw new Error("terminal output missing");
    const concurrent = Promise.all([
      output.write(Uint8Array.of(8), new AbortController().signal),
      output.write(Uint8Array.of(9), new AbortController().signal),
    ]);
    const firstOutput = decodeTerminalPayload(await ready.client.receiveBinary(FrameKind.TerminalBytes));
    const secondOutput = decodeTerminalPayload(await ready.client.receiveBinary(FrameKind.TerminalBytes));
    await concurrent;
    expect([firstOutput.sequence, secondOutput.sequence]).toEqual([1n, 2n]);

    await ready.client.sendBinary(FrameKind.TerminalBytes, encodeTerminalPayload({ terminalGeneration: 8n, sequence: 1n, data: Uint8Array.of(8) }));
    expect(await ready.client.receive()).toMatchObject({ type: "terminal.reset", body: { reason: "sequence_gap" } });
    expect(await ready.client.receive()).toMatchObject({ type: "error", body: { code: "TERMINAL_RESET_REQUIRED" } });
    expect(terminalResets).toEqual(["sequence_gap"]);
    await ready.gateway.close();
  });

  it("cancels blocked content work and retains unknown READY messages without executing them", async () => {
    const blobs = new RecordingBlobRuntime();
    blobs.blockWrite = true;
    const retained: string[] = [];
    const ready = await readyConnection(defaultOptions({
      blobs,
      unknownMessages: {
        retain: (message) => {
          retained.push(message.type);
          return Promise.resolve();
        },
      },
    }));
    await ready.client.send("future.inspect", { opaque: true });
    await ready.client.send("ping");
    await ready.client.receive();
    expect(retained).toEqual(["future.inspect"]);

    await ready.client.send("stream.open", { streamId, purpose: "prompt_image", mediaType: "image/png", limit: "3" });
    await ready.client.sendBinary(FrameKind.BlobChunk, encodeStreamPayload({ streamId, sequence: 0, offset: 0n, data: Uint8Array.of(1) }));
    await waitUntil(() => blobs.enteredWrite);
    await ready.client.send("auth.lock");
    await waitUntil(() => blobs.cancellations.includes("AUTH_LOCK"));
    expect(ready.connection.phase()).toBe("DEVICE_AUTHENTICATED");
    expect(await ready.client.receive()).toMatchObject({ type: "error", body: { code: "AUTH_REQUIRED" } });
    await ready.connection.close("TEST_CANCEL");
    expect(ready.pair.server.closeCodes).toEqual(["TEST_CANCEL"]);
    await ready.gateway.close();
  });
});
