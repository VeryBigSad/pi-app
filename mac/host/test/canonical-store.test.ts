import { access, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { SessionService, type SessionAppend, type SettlementNotice } from "../src/daemon/session-service.js";
import type { PiJsonRecord } from "../src/pi/lf-json-framer.js";
import type { RuntimeSession, RuntimeSupervisor } from "../src/runtime/supervisor.js";
import { captureCanonicalSnapshot } from "../src/sync/canonical-snapshot.js";
import { CanonicalStore, CanonicalStoreError } from "../src/sync/canonical-store.js";
import { SqliteCommandJournal } from "../src/journal/sqlite-journal.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440050";

class FakeSession {
  stopCalls = 0;
  private recordListener: ((record: PiJsonRecord) => void) | undefined;
  private faultListener: (() => void) | undefined;
  private ready = true;

  constructor(readonly sessionId: string) {}

  state(): "ready" | "faulted" {
    return this.ready ? "ready" : "faulted";
  }

  call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>> {
    return Promise.resolve({ type: "response", id: command["id"], command: command["type"], success: true, data: {} });
  }

  stop(): Promise<void> {
    this.stopCalls += 1;
    return Promise.resolve();
  }

  on(event: string, listener: never): this {
    if (event === "record") this.recordListener = listener;
    if (event === "fault") this.faultListener = listener;
    return this;
  }

  emitRecord(value: unknown): void {
    this.recordListener?.({ sequence: 0, value } as unknown as PiJsonRecord);
  }

  fault(): void {
    this.ready = false;
    this.faultListener?.();
  }
}

class FakeSupervisor {
  readonly sessions: FakeSession[] = [];

  startSession(options: { sessionId: string }): Promise<RuntimeSession> {
    const session = new FakeSession(options.sessionId);
    this.sessions.push(session);
    return Promise.resolve(session as unknown as RuntimeSession);
  }
}

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

interface Fixture {
  root: string;
  service: SessionService;
  supervisor: FakeSupervisor;
  appends: SessionAppend[];
  settlements: SettlementNotice[];
  storePath: string;
}

async function fixture(root?: string): Promise<Fixture> {
  const dir = root ?? (await mkdtemp(join(tmpdir(), "pi-mobile-canonical-")));
  if (root === undefined) roots.push(dir);
  const supervisor = new FakeSupervisor();
  const appends: SessionAppend[] = [];
  const settlements: SettlementNotice[] = [];
  const storePath = join(dir, "canonical.sqlite");
  const service = new SessionService({
    supervisor: supervisor as unknown as RuntimeSupervisor,
    sessionsDirectory: join(dir, "sessions"),
    canonicalStorePath: storePath,
    onSettlement: (notice) => settlements.push(notice),
    onAppend: (append) => appends.push(append),
  });
  return { root: dir, service, supervisor, appends, settlements, storePath };
}

describe("CanonicalStore", () => {
  it("persists records with monotonic sequences and dedups replayed record keys", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-store-"));
    roots.push(root);
    const store = new CanonicalStore(join(root, "canonical.sqlite"));
    const state = store.ensureSession(sessionId, "epoch-a");
    expect(state).toMatchObject({ sessionId, streamEpoch: "epoch-a", nextSequence: "1" });
    expect(store.ensureSession(sessionId, "epoch-b").streamEpoch).toBe("epoch-a");

    const first = store.append({
      sessionId,
      streamEpoch: "epoch-a",
      sourceId: "550e8400-e29b-41d4-a716-446655440051",
      sourcePosition: "1",
      rawJson: JSON.stringify({ type: "message_end", id: "m-1" }),
      rawSha256: "a".repeat(64),
      piType: "message_end",
      projectionJson: JSON.stringify({ type: "message_end" }),
    });
    expect(first).toEqual({ inserted: true, sequence: "1", appendId: "1" });
    const replay = store.append({
      sessionId,
      streamEpoch: "epoch-a",
      sourceId: "550e8400-e29b-41d4-a716-446655440051",
      sourcePosition: "1",
      rawJson: JSON.stringify({ type: "message_end", id: "m-1" }),
      rawSha256: "a".repeat(64),
      piType: "message_end",
      projectionJson: JSON.stringify({ type: "message_end" }),
    });
    expect(replay).toEqual({ inserted: false, sequence: "1", appendId: "1" });
    expect(store.sessionState(sessionId)?.nextSequence).toBe("2");
    expect(store.records(sessionId)).toHaveLength(1);
    store.close();
  });

  it("preserves byte-identical id-less records at distinct durable source positions", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-store-"));
    roots.push(root);
    const store = new CanonicalStore(join(root, "canonical.sqlite"));
    store.ensureSession(sessionId, "epoch-a");
    const input = {
      sessionId,
      streamEpoch: "epoch-a",
      sourceId: "550e8400-e29b-41d4-a716-446655440052",
      rawJson: JSON.stringify({ type: "agent_start" }),
      rawSha256: "b".repeat(64),
      piType: "agent_start",
      projectionJson: JSON.stringify({ type: "agent_start" }),
    };
    expect(store.append({ ...input, sourcePosition: "1" })).toMatchObject({ inserted: true, sequence: "1" });
    expect(store.append({ ...input, sourcePosition: "2" })).toMatchObject({ inserted: true, sequence: "2" });
    expect(store.records(sessionId).map((record) => record.sourcePosition)).toEqual(["1", "2"]);
    store.close();
  });

  it("fails closed on a corrupt database file", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-corrupt-"));
    roots.push(root);
    const path = join(root, "canonical.sqlite");
    await writeFile(path, Buffer.from("this is not a sqlite database at all, definitely not", "utf8"));
    expect(() => new CanonicalStore(path)).toThrow(CanonicalStoreError);
  });

  it("owns, transactionally clears, and tombstones only its E2E session", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-e2e-"));
    roots.push(root);
    const store = new CanonicalStore(join(root, "canonical.sqlite"));
    const owned = "550e8400-e29b-41d4-a716-446655440060";
    const other = "550e8400-e29b-41d4-a716-446655440061";
    const token = "a".repeat(43);
    store.createE2eSession(owned, "550e8400-e29b-41d4-a716-446655440062", token);
    store.ensureSession(other, "550e8400-e29b-41d4-a716-446655440063");
    store.append({
      sessionId: owned,
      streamEpoch: "550e8400-e29b-41d4-a716-446655440062",
      sourceId: "550e8400-e29b-41d4-a716-446655440064",
      sourcePosition: "1",
      rawJson: "{\"type\":\"message_end\"}",
      rawSha256: "a".repeat(64),
      piType: "message_end",
      projectionJson: "{\"type\":\"message_end\"}",
    });
    expect(() => store.e2eSessionState(other, token)).toThrow("E2E_SESSION_OWNERSHIP_REQUIRED");
    expect(store.beginE2eDisposal(owned, token)).toBe("deleting");
    expect(store.records(owned)).toEqual([]);
    expect(store.sessionState(owned)).toBeUndefined();
    expect(store.sessionState(other)).toBeDefined();
    store.completeE2eDisposal(owned, token);
    expect(store.e2eSessionState(owned, token)).toBe("disposed");
    expect(store.beginE2eDisposal(owned, token)).toBe("disposed");
    store.close();
  });
});

describe("SessionService durable canonical log", () => {
  it("restores streamEpoch, sequence and records across a daemon restart", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-"));
    roots.push(root);
    const first = await fixture(root);
    const actor1 = await first.service.actor(sessionId);
    await actor1.serialize(() => Promise.resolve());
    const epoch = actor1.currentStreamEpoch;
    first.supervisor.sessions[0]?.emitRecord({ type: "message_end", id: "m-1" });
    first.supervisor.sessions[0]?.emitRecord({ type: "message_end", id: "m-2" });
    expect(first.appends.map((append) => append.sequence)).toEqual(["1", "2"]);
    await first.service.stop();

    const second = await fixture(root);
    await second.service.rehydrate(new AbortController().signal);
    const actor2 = await second.service.actor(sessionId);
    expect(actor2.currentStreamEpoch).toBe(epoch);
    await actor2.serialize(() => Promise.resolve());
    second.supervisor.sessions[0]?.emitRecord({ type: "message_end", id: "m-3" });
    expect(second.appends).toHaveLength(1);
    expect(second.appends[0]).toMatchObject({ sessionId, streamEpoch: epoch, sequence: "3" });

    const stored = new CanonicalStore(second.storePath);
    expect(stored.records(sessionId, epoch).map((record) => record.sequence)).toEqual(["1", "2", "3"]);
    stored.close();
    await second.service.stop();
  });

  it("serves canonical snapshots from the durable log after a restart", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-"));
    roots.push(root);
    const first = await fixture(root);
    const actor1 = await first.service.actor(sessionId);
    await actor1.serialize(() => Promise.resolve());
    const epoch = actor1.currentStreamEpoch;
    first.supervisor.sessions[0]?.emitRecord({
      type: "message_start",
      message: { role: "user", content: [{ type: "text", text: "one" }] },
    });
    first.supervisor.sessions[0]?.emitRecord({
      type: "message_end",
      id: "m-1",
      message: { role: "user", content: [{ type: "text", text: "one" }] },
    });
    first.supervisor.sessions[0]?.emitRecord({
      type: "message_end",
      id: "m-2",
      message: { role: "assistant", content: [{ type: "text", text: "two" }] },
    });
    await first.service.stop();

    const second = await fixture(root);
    await second.service.rehydrate(new AbortController().signal);
    const plan = await second.service.prepare({
      sessionId,
      streamEpoch: "750e8400-e29b-41d4-a716-446655440050",
    }, new AbortController().signal);
    if (plan.kind !== "snapshot") throw new Error("expected snapshot plan");
    expect(plan.streamEpoch).toBe(epoch);
    const snapshot = await captureCanonicalSnapshot(plan.source);
    expect(snapshot.entries.map((entry) => entry.id)).toEqual(["m-1", "m-2"]);
    expect(snapshot.lastAppendId).toBe("2");
    await second.service.stop();
  });

  it("keeps later snapshots available after one oversized finalized record", async () => {
    const { service, supervisor } = await fixture();
    const actor = await service.actor(sessionId);
    await actor.serialize(() => Promise.resolve());
    const session = supervisor.sessions[0];
    session?.emitRecord({
      type: "message_end",
      id: "m-too-large",
      message: { role: "assistant", content: [{ type: "text", text: "x".repeat(64 * 1024) }] },
    });
    session?.emitRecord({
      type: "message_end",
      id: "m-safe",
      message: { role: "assistant", content: [{ type: "text", text: "safe" }] },
    });
    const plan = await service.prepare({ sessionId, streamEpoch: actor.currentStreamEpoch }, new AbortController().signal);
    if (plan.kind !== "snapshot") throw new Error("expected snapshot plan");
    const snapshot = await captureCanonicalSnapshot(plan.source);
    expect(snapshot.entries.map((entry) => entry.id)).toEqual(["m-too-large", "m-safe"]);
    expect(snapshot.entries[0]?.["content"]).toEqual([]);
    expect(snapshot.entries[1]?.["content"]).toEqual([{ type: "text", text: "safe" }]);
    await service.stop();
  });

  it("dedups a Pi child respawn history replay and continues the sequence", async () => {
    const { service, supervisor, appends, settlements } = await fixture();
    const actor = await service.actor(sessionId);
    await actor.serialize(() => Promise.resolve());
    const epoch = actor.currentStreamEpoch;
    const first = supervisor.sessions[0];
    first?.emitRecord({ type: "message_end", id: "m-1" });
    first?.emitRecord({ type: "agent_settled", id: "s-1" });
    expect(appends.map((append) => append.sequence)).toEqual(["1", "2"]);
    expect(settlements).toHaveLength(1);

    first?.fault();
    await actor.serialize(() => Promise.resolve());
    const respawned = supervisor.sessions[1];
    expect(respawned).toBeDefined();
    respawned?.emitRecord({ type: "message_end", id: "m-1" });
    respawned?.emitRecord({ type: "agent_settled", id: "s-1" });
    expect(appends).toHaveLength(2);
    expect(settlements).toHaveLength(1);

    respawned?.emitRecord({ type: "message_end", id: "m-2" });
    expect(appends).toHaveLength(3);
    expect(appends[2]).toMatchObject({ streamEpoch: epoch, sequence: "3", appendId: "2" });
    await service.stop();
  });

  it("removes only an ordered respawn replay prefix while preserving repeated id-less records", async () => {
    const { service, supervisor, appends } = await fixture();
    const actor = await service.actor(sessionId);
    await actor.serialize(() => Promise.resolve());
    const first = supervisor.sessions[0];
    const repeated = { type: "message_end", message: { role: "assistant", content: [{ type: "text", text: "same" }] } };
    first?.emitRecord(repeated);
    first?.emitRecord(repeated);
    expect(appends.map((append) => append.appendId)).toEqual(["1", "2"]);

    first?.fault();
    await actor.serialize(() => Promise.resolve());
    const respawned = supervisor.sessions[1];
    respawned?.emitRecord(repeated);
    respawned?.emitRecord(repeated);
    respawned?.emitRecord(repeated);
    expect(appends.map((append) => append.appendId)).toEqual(["1", "2", "3"]);
    await service.stop();
  });

  it("retains an owned E2E session and its delete capability across a restart", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-e2e-restart-"));
    roots.push(root);
    const first = await fixture(root);
    const owned = await first.service.createE2eSession();
    await first.service.stop();

    const second = await fixture(root);
    await second.service.rehydrate(new AbortController().signal);
    expect(second.service.sessionIds()).toContain(owned.sessionId);
    await second.service.disposeE2eSession(owned.sessionId, owned.deleteToken, () => Promise.resolve(undefined));
    expect(second.service.sessionIds()).not.toContain(owned.sessionId);
    await expect(access(join(root, "sessions", owned.sessionId))).rejects.toThrow();
    await second.service.stop();
  });

  it("lists sessions with their durable epoch in listAll after a restart", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-"));
    roots.push(root);
    const first = await fixture(root);
    const actor1 = await first.service.actor(sessionId);
    const epoch = actor1.currentStreamEpoch;
    await actor1.serialize(() => Promise.resolve());
    first.supervisor.sessions[0]?.emitRecord({ type: "message_end", id: "m-1" });
    await first.service.stop();

    const second = await fixture(root);
    await second.service.rehydrate(new AbortController().signal);
    await expect(second.service.listAll(new AbortController().signal)).resolves.toEqual([{ sessionId, streamEpoch: epoch }]);
    await second.service.stop();
  });

  it("stops and removes only a host-owned E2E actor, canonical rows, journal rows, and contained directory", async () => {
    const { root, service, supervisor, storePath } = await fixture();
    const owned = await service.createE2eSession();
    const otherSessionId = "550e8400-e29b-41d4-a716-446655440070";
    const actor = await service.actor(owned.sessionId);
    await actor.serialize(async (child) => await child.call({ type: "get_state" }));
    supervisor.sessions[0]?.emitRecord({
      type: "message_end",
      id: "owned-message",
      message: { role: "user", content: [{ type: "text", text: "PONG" }] },
    });
    expect(service.e2eCanonicalContains(owned.sessionId, owned.deleteToken, "PONG")).toBe(true);
    expect(() => service.e2eCanonicalContains(owned.sessionId, "b".repeat(43), "PONG"))
      .toThrow("E2E_SESSION_OWNERSHIP_REQUIRED");
    await service.actor(otherSessionId);
    const journal = new SqliteCommandJournal(join(root, "journal.sqlite"));
    const addCommand = async (commandId: string, targetSessionId: string): Promise<void> => {
      await journal.insertReceived({
        command: { commandId, sessionId: targetSessionId, operation: "prompt", payload: { message: "PONG" }, payloadHash: "a".repeat(64) },
        state: "RECEIVED",
        dormant: false,
        receivedAtMs: 1,
        updatedAtMs: 1,
        revision: 0,
      });
    };
    const ownedCommandId = "550e8400-e29b-41d4-a716-446655440071";
    const otherCommandId = "550e8400-e29b-41d4-a716-446655440072";
    await addCommand(ownedCommandId, owned.sessionId);
    await addCommand(otherCommandId, otherSessionId);
    const sessionsRoot = join(root, "sessions");
    const ownedDirectory = join(sessionsRoot, owned.sessionId);
    const external = join(root, "external");
    await mkdir(external, { mode: 0o700 });
    await rm(ownedDirectory, { recursive: true, force: true });
    await symlink(external, ownedDirectory);

    await expect(service.disposeE2eSession(owned.sessionId, owned.deleteToken, async () => await journal.deleteSession(owned.sessionId)))
      .rejects.toThrow("E2E_SESSION_DIRECTORY_UNSAFE");
    expect(supervisor.sessions[0]?.stopCalls).toBe(1);
    await expect(service.actor(owned.sessionId)).rejects.toThrow("E2E_SESSION_DISPOSING");
    const store = new CanonicalStore(storePath);
    expect(store.sessionState(owned.sessionId)).toBeUndefined();
    expect(store.records(owned.sessionId)).toEqual([]);
    expect(store.sessionState(otherSessionId)).toBeDefined();
    store.close();
    await expect(journal.get(ownedCommandId)).resolves.toBeUndefined();
    await expect(journal.get(otherCommandId)).resolves.toBeDefined();
    await expect(service.disposeE2eSession("../not-a-session", owned.deleteToken, () => Promise.resolve(undefined)))
      .rejects.toThrow("E2E_SESSION_INVALID");

    await rm(ownedDirectory, { force: true });
    await service.disposeE2eSession(owned.sessionId, owned.deleteToken, async () => await journal.deleteSession(owned.sessionId));
    await expect(access(ownedDirectory)).rejects.toThrow();
    expect(service.sessionIds()).not.toContain(owned.sessionId);
    expect(service.sessionIds()).toContain(otherSessionId);
    journal.close();
    await service.stop();
  });

  it("fails closed and retries teardown only with the owned capability after journal removal fails", async () => {
    const { root, service, supervisor, storePath } = await fixture();
    const owned = await service.createE2eSession();
    const actor = await service.actor(owned.sessionId);
    await actor.serialize(async (child) => await child.call({ type: "get_state" }));
    const directory = join(root, "sessions", owned.sessionId);
    await expect(service.disposeE2eSession(
      owned.sessionId,
      owned.deleteToken,
      () => Promise.reject(new Error("journal unavailable")),
    )).rejects.toThrow("journal unavailable");
    expect(supervisor.sessions[0]?.stopCalls).toBe(1);
    await expect(access(directory)).resolves.toBeUndefined();
    const store = new CanonicalStore(storePath);
    expect(store.e2eSessionState(owned.sessionId, owned.deleteToken)).toBe("deleting");
    store.close();
    await expect(service.actor(owned.sessionId)).rejects.toThrow("E2E_SESSION_DISPOSING");
    await service.disposeE2eSession(owned.sessionId, owned.deleteToken, () => Promise.resolve(undefined));
    await expect(access(directory)).rejects.toThrow();
    await service.stop();
  });

  it("fails closed when the canonical database is corrupt", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-"));
    roots.push(root);
    await writeFile(join(root, "canonical.sqlite"), Buffer.alloc(4096, 0x7f));
    expect(() =>
      new SessionService({
        supervisor: new FakeSupervisor() as unknown as RuntimeSupervisor,
        sessionsDirectory: join(root, "sessions"),
        canonicalStorePath: join(root, "canonical.sqlite"),
        onSettlement: () => undefined,
      }),
    ).toThrow(CanonicalStoreError);
  });
});
