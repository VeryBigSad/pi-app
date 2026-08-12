import { assertJsonValue, isJsonObject, type JsonObject } from "@pimobile/protocol";
import { captureCanonicalSnapshot } from "../sync/canonical-snapshot.js";
import type { GatewaySyncPlan, ReplaySyncPlan, SnapshotSyncPlan, SyncRuntime } from "./types.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UINT64 = /^(0|[1-9][0-9]{0,19})$/;
const LEAF_ID = /^[0-9a-f]{8}$/;
const MAX_UINT64 = 18_446_744_073_709_551_615n;
const MAX_CURSORS = 512;

export class SyncSequenceError extends Error {
  readonly code = "PROTOCOL_VIOLATION";

  constructor(message: string) {
    super(message);
    this.name = "SyncSequenceError";
  }
}

export type SyncSend = (type: string, body: JsonObject, replyTo?: string | null) => Promise<void>;

interface PendingCommit {
  readonly plan: GatewaySyncPlan;
  readonly expectedSequence: bigint;
  readonly afterCommit: readonly JsonObject[];
}

export class CanonicalSyncSequencer {
  private pending: PendingCommit | undefined;
  private queue: JsonObject[] = [];
  /** Set when a resume carried no per-session work: catalogs + sync.complete were sent, no ack fence. */
  private completedEmpty = false;

  constructor(private readonly runtime: SyncRuntime, private readonly send: SyncSend) {}

  /** True when start() completed an empty resume without any pending commit. */
  get completedWithoutWork(): boolean {
    return this.completedEmpty;
  }

  async start(resume: JsonObject, replyTo: string, signal: AbortSignal): Promise<void> {
    if (this.pending !== undefined) throw new SyncSequenceError("Synchronization is already active");
    this.completedEmpty = false;
    this.queue = validateResume(resume);
    if (this.queue.length === 0) {
      // Fresh device: no cursors means "snapshot everything currently supervised".
      this.queue = (await this.runtime.listAll?.(signal)) ?? [];
    }
    if (this.queue.length === 0) {
      // No sessions exist at all: publish the empty catalogs and complete the fence.
      await this.send("session.catalog", { sessions: [] }, replyTo);
      await this.send("agents.catalog", { sessions: [] }, replyTo);
      await this.send("sync.complete", {}, replyTo);
      this.completedEmpty = true;
      return;
    }
    try {
      await this.startNext(replyTo, signal);
    } catch (error) {
      this.queue = [];
      throw error;
    }
  }

  /** Returns true once every cursor from sync.resume has committed; false while more plans follow. */
  async acknowledge(body: JsonObject, signal: AbortSignal): Promise<boolean> {
    const pending = this.pending;
    if (pending === undefined) throw new SyncSequenceError("No synchronization commit is pending");
    const sequence = parseUint64(body["sequence"], "event.ack sequence");
    const sessionId = body["sessionId"];
    const streamEpoch = body["streamEpoch"];
    if (sessionId !== pending.plan.sessionId || streamEpoch !== pending.plan.streamEpoch || sequence !== pending.expectedSequence) {
      throw new SyncSequenceError("Synchronization acknowledgment does not match the published fence");
    }
    await this.runtime.committed(pending.plan, sequence, signal);
    for (const event of pending.afterCommit) await this.send("event.batch", { events: [event] });
    this.pending = undefined;
    if (this.queue.length > 0) {
      await this.startNext(null, signal);
      return false;
    }
    return true;
  }

  cancel(): void {
    this.pending = undefined;
    this.queue = [];
  }

  private async startNext(replyTo: string | null, signal: AbortSignal): Promise<void> {
    const cursor = this.queue.shift();
    if (cursor === undefined) throw new SyncSequenceError("Synchronization cursor queue is empty");
    const plan = await this.runtime.prepare(cursor, signal);
    validatePlan(plan, cursor);
    if (plan.kind === "replay") {
      await this.publishReplay(plan, replyTo);
      this.pending = { plan, expectedSequence: plan.throughSequence, afterCommit: [] };
      return;
    }
    const published = await this.publishSnapshot(plan, replyTo);
    this.pending = { plan, expectedSequence: published.sequence, afterCommit: published.postFenceEvents };
  }

  private async publishReplay(plan: ReplaySyncPlan, replyTo: string | null): Promise<void> {
    validateEvents(plan.events, plan.sessionId, plan.streamEpoch, plan.fromSequence, plan.throughSequence);
    await this.send("sync.replay", {
      sessionId: plan.sessionId,
      streamEpoch: plan.streamEpoch,
      fromSequence: plan.fromSequence.toString(),
      throughSequence: plan.throughSequence.toString(),
    }, replyTo);
    for (const event of plan.events) await this.send("event.batch", { events: [event] });
  }

  private async publishSnapshot(plan: SnapshotSyncPlan, replyTo: string | null): Promise<{ sequence: bigint; postFenceEvents: readonly JsonObject[] }> {
    const snapshot = await captureCanonicalSnapshot(plan.source);
    const sequence = snapshot.fence;
    if (sequence < 0n || sequence > MAX_UINT64) throw new SyncSequenceError("Snapshot fence is outside uint64");
    const entries = snapshot.entries.map((entry) => checkedObject(entry, "snapshot entry"));
    const postFenceEvents = snapshot.postFenceEvents.map((event) => checkedObject(event, "post-fence event"));
    validateEvents(postFenceEvents, plan.sessionId, plan.streamEpoch, sequence, undefined);
    const lastAppendId = snapshot.lastAppendId ?? null;
    await this.send("sync.reset", {
      sessionId: plan.sessionId,
      streamEpoch: plan.streamEpoch,
      reason: "canonical_snapshot",
    }, replyTo);
    if (plan.catalog !== undefined) {
      await this.send("session.catalog", checkedObject(plan.catalog, "session catalog"));
    }
    if (plan.agentsCatalog !== undefined) {
      await this.send("agents.catalog", { sessions: [checkedObject(plan.agentsCatalog, "agents catalog")] });
    }
    await this.send("snapshot.begin", {
      sessionId: plan.sessionId,
      streamEpoch: plan.streamEpoch,
      sequence: sequence.toString(),
      messageCount: entries.length,
      lastAppendId,
    });
    let page = 0;
    for (const entry of entries) {
      await this.send("snapshot.page", {
        sessionId: plan.sessionId,
        streamEpoch: plan.streamEpoch,
        sequence: sequence.toString(),
        page,
        entries: [entry],
      });
      page += 1;
    }
    for (const adjunct of plan.adjunctPages ?? []) {
      await this.send("snapshot.page", {
        sessionId: plan.sessionId,
        streamEpoch: plan.streamEpoch,
        sequence: sequence.toString(),
        page,
        adjunct,
      });
      page += 1;
    }
    await this.send("snapshot.end", {
      sessionId: plan.sessionId,
      streamEpoch: plan.streamEpoch,
      sequence: sequence.toString(),
      leafId: snapshot.leafId,
      pages: page,
      messageCount: entries.length,
      lastAppendId,
    });
    return { sequence, postFenceEvents };
  }
}

function validateResume(resume: JsonObject): JsonObject[] {
  const cursors = resume["cursors"];
  if (!Array.isArray(cursors) || cursors.length > MAX_CURSORS) {
    throw new SyncSequenceError("sync.resume cursors are invalid");
  }
  return cursors.map((cursor) => {
    if (!isJsonObject(cursor)) throw new SyncSequenceError("sync.resume cursor is invalid");
    const sessionId = cursor["sessionId"];
    const streamEpoch = cursor["streamEpoch"];
    const leafId = cursor["leafId"];
    if (typeof sessionId !== "string" || !UUID_V4.test(sessionId) || typeof streamEpoch !== "string" || !UUID_V4.test(streamEpoch)) {
      throw new SyncSequenceError("sync.resume cursor identity is invalid");
    }
    parseUint64(cursor["sequence"], "sync.resume cursor sequence");
    if (leafId !== null && (typeof leafId !== "string" || !LEAF_ID.test(leafId))) {
      throw new SyncSequenceError("sync.resume cursor leaf is invalid");
    }
    return cursor;
  });
}

function validatePlan(plan: GatewaySyncPlan, cursor: JsonObject): void {
  if (!UUID_V4.test(plan.sessionId) || !UUID_V4.test(plan.streamEpoch)) {
    throw new SyncSequenceError("Synchronization identity is invalid");
  }
  if (plan.sessionId !== cursor["sessionId"]) {
    throw new SyncSequenceError("Synchronization plan does not match its cursor");
  }
  if (plan.kind === "replay") {
    if (plan.fromSequence < 0n || plan.throughSequence < plan.fromSequence || plan.throughSequence > MAX_UINT64) {
      throw new SyncSequenceError("Replay sequence range is invalid");
    }
  }
}

function validateEvents(
  events: readonly JsonObject[],
  sessionId: string,
  streamEpoch: string,
  preceding: bigint,
  final: bigint | undefined,
): void {
  let sequence = preceding;
  for (const event of events) {
    assertJsonValue(event);
    if (event["sessionId"] !== sessionId || event["streamEpoch"] !== streamEpoch) {
      throw new SyncSequenceError("Event identity does not match synchronization plan");
    }
    const next = parseUint64(event["sequence"], "event sequence");
    if (next !== sequence + 1n) throw new SyncSequenceError("Event replay is not contiguous");
    sequence = next;
  }
  if (final !== undefined && sequence !== final) {
    throw new SyncSequenceError("Replay events do not reach the declared sequence");
  }
}

function parseUint64(value: unknown, label: string): bigint {
  if (typeof value !== "string" || !UINT64.test(value)) throw new SyncSequenceError(`${label} is invalid`);
  const parsed = BigInt(value);
  if (parsed > MAX_UINT64) throw new SyncSequenceError(`${label} is outside uint64`);
  return parsed;
}

function checkedObject(value: unknown, label: string): JsonObject {
  assertJsonValue(value);
  if (!isJsonObject(value)) throw new SyncSequenceError(`${label} is not an object`);
  return value;
}
