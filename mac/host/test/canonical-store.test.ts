import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { SessionService, type SessionAppend, type SettlementNotice } from "../src/daemon/session-service.js";
import type { PiJsonRecord } from "../src/pi/lf-json-framer.js";
import type { RuntimeSession, RuntimeSupervisor } from "../src/runtime/supervisor.js";
import { captureCanonicalSnapshot } from "../src/sync/canonical-snapshot.js";
import { CanonicalStore, CanonicalStoreError, canonicalRecordKey } from "../src/sync/canonical-store.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440050";

class FakeSession {
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
  return { service, supervisor, appends, settlements, storePath };
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
      recordKey: canonicalRecordKey({ type: "message_end", id: "m-1" }, "a".repeat(64)),
      rawJson: JSON.stringify({ type: "message_end", id: "m-1" }),
      rawSha256: "a".repeat(64),
      piType: "message_end",
      projectionJson: JSON.stringify({ type: "message_end" }),
    });
    expect(first).toEqual({ inserted: true, sequence: "1" });
    const replay = store.append({
      sessionId,
      streamEpoch: "epoch-a",
      recordKey: canonicalRecordKey({ type: "message_end", id: "m-1" }, "a".repeat(64)),
      rawJson: JSON.stringify({ type: "message_end", id: "m-1" }),
      rawSha256: "a".repeat(64),
      piType: "message_end",
      projectionJson: JSON.stringify({ type: "message_end" }),
    });
    expect(replay).toEqual({ inserted: false, sequence: "1" });
    expect(store.sessionState(sessionId)?.nextSequence).toBe("2");
    expect(store.records(sessionId)).toHaveLength(1);
    store.close();
  });

  it("fails closed on a corrupt database file", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-canonical-corrupt-"));
    roots.push(root);
    const path = join(root, "canonical.sqlite");
    await writeFile(path, Buffer.from("this is not a sqlite database at all, definitely not", "utf8"));
    expect(() => new CanonicalStore(path)).toThrow(CanonicalStoreError);
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
    first.supervisor.sessions[0]?.emitRecord({ type: "message_end", id: "m-1" });
    first.supervisor.sessions[0]?.emitRecord({ type: "message_end", id: "m-2" });
    await first.service.stop();

    const second = await fixture(root);
    await second.service.rehydrate(new AbortController().signal);
    const plan = await second.service.prepare({ sessionId, streamEpoch: epoch }, new AbortController().signal);
    if (plan.kind !== "snapshot") throw new Error("expected snapshot plan");
    const snapshot = await captureCanonicalSnapshot(plan.source);
    expect(snapshot.entries.map((entry) => entry.id)).toEqual(["m-1", "m-2"]);
    expect(snapshot.lastAppendId).toBe("m-2");
    await second.service.stop();
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
    expect(appends[2]).toMatchObject({ streamEpoch: epoch, sequence: "3" });
    await service.stop();
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
