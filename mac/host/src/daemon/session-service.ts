import { createHash, randomBytes, randomUUID } from "node:crypto";
import { lstat, mkdir, readdir, realpath, rm, stat } from "node:fs/promises";
import { projectPiRecord, type ProjectedPiRecord } from "../pi/raw-projector.js";
import { join, relative, resolve, sep } from "node:path";
import { assertWireMessage, isJsonObject, type JsonObject, type JsonValue } from "@pimobile/protocol";
import { CommandDispatchRejectedError } from "../gateway/command-dispatch.js";
import type {
  CommandAuthorization,
  CommandAuthorizer,
  CommandDispatchPath,
  CommandGuardContext,
  CommandPathRouter,
  SyncRuntime,
  GatewaySyncPlan,
} from "../gateway/types.js";
import type { SemanticCommand } from "../journal/types.js";
import { AgentTracker, type TrackedAgent } from "../pi/agent-tracker.js";
import { LifecycleTracker } from "../pi/lifecycle.js";
import type { PiJsonRecord } from "../pi/lf-json-framer.js";
import type { RuntimeSession, RuntimeSupervisor } from "../runtime/supervisor.js";
import type { EntriesResponse, SessionEntry, SnapshotSource } from "../sync/canonical-snapshot.js";
import { CanonicalStore, type CanonicalStoredRecord } from "../sync/canonical-store.js";

const ALLOWED_OPERATIONS = new Set(["prompt", "steer", "follow_up", "abort", "get_state", "new_session"]);
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const CATALOG_UNAVAILABLE = "unavailable";
const CATALOG_PATH_BYTES = 4_096;
const IDLE_POLL_MS = 100;
const IDLE_WAIT_MS = 15_000;
const MAX_SNAPSHOT_MESSAGE_BYTES = 48 * 1024;
const E2E_DELETE_TOKEN = /^[A-Za-z0-9_-]{43}$/u;

export interface E2eSessionHandle {
  readonly sessionId: string;
  readonly deleteToken: string;
}

export interface SettlementNotice {
  readonly settlementId: string;
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly settledAtMs: number;
}

export interface SessionAppend {
  /** Present only for a finalized message_end record. */
  readonly appendId?: string;
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly record: unknown;
  /** Canonical projected form (piType + bounded projection + raw hashes) for wire publishing. */
  readonly projected: ProjectedPiRecord;
}

export interface AgentUpdateNotice {
  readonly sessionId: string;
  readonly agent: TrackedAgent;
}

interface SourceSegment {
  readonly id: string;
  position: bigint;
  readonly replay: readonly CanonicalStoredRecord[];
  replayIndex: number;
  matchingReplay: boolean;
}

interface SessionActorOptions {
  readonly sessionId: string;
  readonly cwd: string;
  /** Durable canonical log; when present the actor restores epoch/sequence from it and persists before publishing. */
  readonly canonicalStore?: CanonicalStore;
  readonly onSettlement: (notice: SettlementNotice) => void;
  /** Receives every newly persisted canonical record, in durable sequence order. */
  readonly onAppend?: (append: SessionAppend) => void;
  readonly onAgentsUpdate?: (notice: AgentUpdateNotice) => void;
  /** Fires when a new session actor appears; the daemon republishes session.catalog. */
  readonly onCatalogChanged?: () => void;
}

class SessionActor {
  get sessionId(): string {
    return this.options.sessionId;
  }

  private readonly lifecycle = new LifecycleTracker();
  private readonly agents = new AgentTracker();
  private readonly streamEpoch: string;
  private sequence: bigint;
  private generation = 0;
  private tail: Promise<unknown> = Promise.resolve();
  private child: RuntimeSession | undefined;
  private disposing = false;

  constructor(
    private readonly supervisor: RuntimeSupervisor,
    private readonly options: SessionActorOptions,
  ) {
    const store = options.canonicalStore;
    if (store !== undefined) {
      // Actor identity is the sessionId: restore the durable epoch and high-water
      // sequence; a daemon restart never rotates the stream epoch.
      const state = store.ensureSession(options.sessionId, randomUUID());
      this.streamEpoch = state.streamEpoch;
      this.sequence = BigInt(state.nextSequence) - 1n;
    } else {
      this.streamEpoch = randomUUID();
      this.sequence = 0n;
    }
  }

  get currentGeneration(): number {
    return this.generation;
  }

  get currentStreamEpoch(): string {
    return this.streamEpoch;
  }

  get sessionCwd(): string {
    return this.options.cwd;
  }

  private async ensureChild(): Promise<RuntimeSession> {
    if (this.disposing) throw new Error("E2E_SESSION_DISPOSING");
    const existing = this.child;
    if (existing?.state() === "ready") return existing;
    const session = await this.supervisor.startSession({
      sessionId: this.options.sessionId,
      cwd: this.options.cwd,
    });
    this.generation += 1;
    this.child = session;
    const source = this.newSourceSegment();
    session.on("record", (record: PiJsonRecord) => this.onRecord(record, source));
    session.on("fault", () => {
      if (this.child === session) this.child = undefined;
      this.generation += 1;
    });
    return session;
  }

  private newSourceSegment(): SourceSegment {
    const records = this.options.canonicalStore?.records(this.options.sessionId, this.streamEpoch) ?? [];
    return {
      id: randomUUID(),
      position: 0n,
      replay: records,
      replayIndex: 0,
      matchingReplay: true,
    };
  }

  private onRecord(record: PiJsonRecord, source: SourceSegment): void {
    // Synthetic runtimes may omit rawBytes/rawJson despite the type; derive them.
    const runtime = record as { rawBytes?: Buffer; rawJson?: string; value: Readonly<Record<string, unknown>> };
    const normalized: PiJsonRecord = runtime.rawBytes === undefined || runtime.rawJson === undefined
      ? { rawBytes: Buffer.from(JSON.stringify(record.value), "utf8"), rawJson: JSON.stringify(record.value), value: record.value }
      : record;
    const projected = projectPiRecord(normalized);
    source.position += 1n;
    if (source.matchingReplay) {
      const replay = source.replay[source.replayIndex];
      if (replay?.rawSha256 === projected.rawSha256 && replay.piType === projected.piType) {
        source.replayIndex += 1;
        return;
      }
      source.matchingReplay = false;
    }
    const store = this.options.canonicalStore;
    let appendId: string | undefined;
    if (store !== undefined) {
      // Persist-first ordering: a crash here loses the publish, never the record.
      // A Pi child respawn replays history; already-persisted records are skipped
      // without advancing the sequence or re-publishing.
      const result = store.append({
        sessionId: this.options.sessionId,
        streamEpoch: this.streamEpoch,
        sourceId: source.id,
        sourcePosition: source.position.toString(),
        rawJson: projected.rawJson,
        rawSha256: projected.rawSha256,
        piType: projected.piType,
        projectionJson: JSON.stringify(projected.projection.value),
        ...(projected.piType === "message_end" ? { snapshotJson: snapshotFinalJson(normalized.value) } : {}),
      });
      if (!result.inserted) return;
      this.sequence = BigInt(result.sequence);
      appendId = result.appendId;
    } else {
      this.sequence += 1n;
    }
    if (store !== undefined) {
      this.options.onAppend?.({
        ...(appendId === undefined ? {} : { appendId }),
        sessionId: this.options.sessionId,
        streamEpoch: this.streamEpoch,
        sequence: this.sequence.toString(),
        record: normalized.value,
        projected,
      });
    }
    const changedAgent = this.agents.apply(record.value);
    if (changedAgent !== undefined) {
      this.options.onAgentsUpdate?.({ sessionId: this.options.sessionId, agent: changedAgent });
    }
    const trigger = this.lifecycle.apply(record.value, {
      streamEpoch: this.streamEpoch,
      sequence: this.sequence.toString(),
    });
    if (trigger !== undefined) {
      this.options.onSettlement({
        settlementId: randomUUID(),
        sessionId: this.options.sessionId,
        streamEpoch: trigger.streamEpoch,
        sequence: trigger.sequence,
        settledAtMs: trigger.settledAtMs,
      });
    }
  }

  agentsCatalog(): readonly TrackedAgent[] {
    return this.agents.catalog();
  }

  serialize<T>(operation: (child: RuntimeSession) => Promise<T>): Promise<T> {
    if (this.disposing) return Promise.reject(new Error("E2E_SESSION_DISPOSING"));
    const run = this.tail.then(async () => {
      if (this.disposing) throw new Error("E2E_SESSION_DISPOSING");
      return await operation(await this.ensureChild());
    });
    this.tail = run.then(() => undefined, () => undefined);
    return run;
  }

  async waitUntilIdle(): Promise<void> {
    const deadline = Date.now() + IDLE_WAIT_MS;
    for (;;) {
      if (this.lifecycle.snapshot().phase === "idle" && this.child !== undefined) return;
      if (Date.now() >= deadline) return;
      await new Promise((resolveWait) => setTimeout(resolveWait, IDLE_POLL_MS));
    }
  }

  async dispose(): Promise<void> {
    this.disposing = true;
    await this.tail;
    const child = this.child;
    this.child = undefined;
    if (child !== undefined) await child.stop();
  }

  async stop(): Promise<void> {
    this.disposing = true;
    await this.tail.catch(() => undefined);
    const child = this.child;
    this.child = undefined;
    if (child !== undefined) await child.stop().catch(() => undefined);
  }
}

function parseJsonObject(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function recordProjection(value: string): unknown {
  return parseJsonObject(value);
}

function parseSnapshotFinal(value: string | undefined): { role: string; content: JsonValue } | undefined {
  const parsed = value === undefined ? undefined : parseJsonObject(value);
  if (!isJsonObject(parsed) || typeof parsed["role"] !== "string" || parsed["content"] === undefined) return undefined;
  const role = safeSnapshotRole(parsed["role"]);
  const content = boundedSnapshotValue(parsed["content"]);
  return content === undefined ? undefined : { role, content };
}

function snapshotContainsText(value: JsonValue, expected: string): boolean {
  if (typeof value === "string") return value.includes(expected);
  if (Array.isArray(value)) return value.some((entry) => snapshotContainsText(entry, expected));
  if (!isJsonObject(value)) return false;
  return Object.values(value).some((entry) => snapshotContainsText(entry, expected));
}

function snapshotFinal(value: unknown): { role: string; content: JsonValue } {
  const message = isJsonObject(value) && isJsonObject(value["message"]) ? value["message"] : undefined;
  const content = boundedSnapshotValue(message?.["content"]);
  return { role: safeSnapshotRole(message?.["role"]), content: content ?? [] };
}

function snapshotFinalJson(value: Readonly<Record<string, unknown>>): string {
  return JSON.stringify(snapshotFinal(isJsonObject(value["message"]) ? { message: value["message"] } : undefined));
}

function safeSnapshotRole(value: unknown): string {
  return typeof value === "string" && Buffer.byteLength(value, "utf8") <= 32 ? value : "unknown";
}

function boundedSnapshotValue(value: unknown): JsonValue | undefined {
  try {
    const serialized = JSON.stringify(value) as string | undefined;
    if (serialized === undefined || Buffer.byteLength(serialized, "utf8") > MAX_SNAPSHOT_MESSAGE_BYTES) return undefined;
    const parsed: unknown = JSON.parse(serialized);
    return parsed as JsonValue;
  } catch {
    return undefined;
  }
}

function storedRecordToWireEvent(record: CanonicalStoredRecord): JsonObject {
  const projection: unknown = JSON.parse(record.projectionJson);
  if (!isJsonObject(projection)) throw new Error("CANONICAL_PROJECTION_INVALID");
  return {
    sessionId: record.sessionId,
    streamEpoch: record.streamEpoch,
    sequence: record.sequence,
    ...(record.appendId === undefined ? {} : { appendId: record.appendId }),
    piType: record.piType,
    projection,
    rawJson: record.rawJson,
    rawSize: Buffer.byteLength(record.rawJson, "utf8").toString(),
    rawSha256: record.rawSha256,
  };
}

function storedRecordToFinalEntry(record: CanonicalStoredRecord): SessionEntry | undefined {
  if (record.piType !== "message_end" || record.appendId === undefined) return undefined;
  // A malformed or oversized final must be isolated to this entry. snapshotJson is
  // persisted at ingestion; legacy rows derive the same conservative fallback.
  const snapshot = parseSnapshotFinal(record.snapshotJson) ?? snapshotFinal(recordProjection(record.projectionJson));
  const raw: unknown = parseJsonObject(record.rawJson);
  const messageId = isJsonObject(raw) && typeof raw["id"] === "string" && raw["id"].length > 0 && Buffer.byteLength(raw["id"], "utf8") <= 256
    ? raw["id"]
    : `msg-${record.streamEpoch}-${record.sequence}`;
  const safeRawJson = JSON.stringify({ type: "message_end", message: snapshot });
  return {
    id: messageId,
    messageId,
    appendId: record.appendId,
    type: record.piType,
    role: snapshot.role,
    content: snapshot.content,
    rawJson: safeRawJson,
    rawSize: String(Buffer.byteLength(safeRawJson, "utf8")),
    rawSha256: createHash("sha256").update(safeRawJson, "utf8").digest("hex"),
    projection: { type: "message_end", message: snapshot, snapshotSafe: true },
  };
}

class PiSnapshotSource implements SnapshotSource {
  private barrierChild: RuntimeSession | undefined;
  private frozenFence: bigint | undefined;

  constructor(
    private readonly actor: SessionActor,
    private readonly canonicalStore?: CanonicalStore,
  ) {}

  withMutationBarrier<T>(operation: () => Promise<T>): Promise<T> {
    return this.actor.serialize(async (child) => {
      this.barrierChild = child;
      try {
        return await operation();
      } finally {
        this.barrierChild = undefined;
        this.frozenFence = undefined;
      }
    });
  }

  async waitUntilIdle(): Promise<void> {
    await this.actor.waitUntilIdle();
  }

  currentEventFence(): bigint {
    const store = this.canonicalStore;
    if (store === undefined) return 0n;
    const state = store.sessionState(this.actor.sessionId);
    if (state === undefined) return 0n;
    // next_sequence is one past the last appended record; the fence is the last.
    const next = BigInt(state.nextSequence);
    const fence = next > 0n ? next - 1n : 0n;
    this.frozenFence = fence;
    return fence;
  }

  async getEntries(since?: string): Promise<EntriesResponse> {
    const held = this.barrierChild;
    if (held !== undefined) return await this.queryEntries(held, since);
    return await this.actor.serialize(async (child) => await this.queryEntries(child, since));
  }

  private async queryEntries(child: RuntimeSession, since?: string): Promise<EntriesResponse> {
    const store = this.canonicalStore;
    if (store !== undefined) {
      // Durable canonical log is authoritative; it survives daemon restarts and
      // does not depend on what the (possibly freshly respawned) Pi child holds.
      const fence = this.frozenFence;
      const entries = store.records(this.actor.sessionId, this.actor.currentStreamEpoch)
        .filter((record) => fence === undefined || BigInt(record.sequence) <= fence)
        .map(storedRecordToFinalEntry)
        .filter((entry): entry is SessionEntry => entry !== undefined);
      if (since === undefined) return { entries, leafId: null };
      if (!/^(0|[1-9][0-9]{0,19})$/u.test(since)) throw new Error("PI_SNAPSHOT_APPEND_ID_INVALID");
      return {
        entries: entries.filter((entry) => typeof entry["appendId"] === "string" && BigInt(entry["appendId"]) > BigInt(since)),
        leafId: null,
      };
    }
    const command: Record<string, unknown> = { type: "get_entries" };
    if (since !== undefined) command["since"] = since;
    const response = await child.call(command);
    if (response["success"] !== true) throw new Error("PI_SNAPSHOT_UNAVAILABLE");
    const data = response["data"];
    if (!isJsonObject(data) || !Array.isArray(data["entries"])) throw new Error("PI_SNAPSHOT_UNAVAILABLE");
    const leaf = data["leafId"];
    return {
      entries: data["entries"].filter(isJsonObject).map((entry) => {
        if (typeof entry["id"] !== "string") throw new Error("PI_SNAPSHOT_UNAVAILABLE");
        return entry as EntriesResponse["entries"][number];
      }),
      leafId: typeof leaf === "string" ? leaf : null,
    };
  }

  replayAfter(fence: bigint): Promise<readonly unknown[]> {
    const store = this.canonicalStore;
    if (store === undefined) return Promise.resolve([]);
    return Promise.resolve(
      store.records(this.actor.sessionId, this.actor.currentStreamEpoch)
        .filter((record) => BigInt(record.sequence) > fence)
        .map(storedRecordToWireEvent),
    );
  }
}

export interface SessionServiceOptions {
  readonly supervisor: RuntimeSupervisor;
  readonly sessionsDirectory: string;
  /** Path to the durable canonical record log (SQLite); when set, epochs/sequences/records survive daemon restarts. */
  readonly canonicalStorePath?: string;
  readonly onSettlement: (notice: SettlementNotice) => void;
  /** Receives every newly persisted canonical record, in durable sequence order. */
  readonly onAppend?: (append: SessionAppend) => void;
  readonly onAgentsUpdate?: (notice: AgentUpdateNotice) => void;
  /** Fires when a new session actor appears; the daemon republishes session.catalog. */
  readonly onCatalogChanged?: () => void;
}

/**
 * Owns one serialized actor per semantic session: the only component that talks
 * to that session's Pi RPC child, assigns its stream sequence, and runs its
 * canonical snapshots.
 */
export class SessionService implements CommandPathRouter, CommandAuthorizer, SyncRuntime {
  private readonly actors = new Map<string, SessionActor>();
  private readonly canonicalStore: CanonicalStore | undefined;
  private catalogNotificationsSuspended = false;

  constructor(private readonly options: SessionServiceOptions) {
    // Fail closed: a corrupt canonical log aborts construction rather than
    // silently restarting history with a fresh epoch.
    this.canonicalStore = options.canonicalStorePath === undefined
      ? undefined
      : new CanonicalStore(options.canonicalStorePath);
  }

  /** Rehydrates actors for session directories left by previous daemon runs (lazy Pi spawn). */
  async rehydrate(signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    const known = new Set<string>();
    const entries = await readdir(this.options.sessionsDirectory, { withFileTypes: true }).catch(() => [] as never[]);
    for (const entry of entries) {
      if (entry.isDirectory() && UUID_V4.test(entry.name)) known.add(entry.name);
    }
    for (const state of this.canonicalStore?.listSessions() ?? []) known.add(state.sessionId);
    this.catalogNotificationsSuspended = true;
    try {
      for (const sessionId of known) await this.actor(sessionId);
    } finally {
      this.catalogNotificationsSuspended = false;
    }
    if (known.size > 0) this.options.onCatalogChanged?.();
  }

  async actor(sessionId: string): Promise<SessionActor> {
    const existing = this.actors.get(sessionId);
    if (existing !== undefined) return existing;
    if (!UUID_V4.test(sessionId)) throw new Error("INVALID_SESSION");
    if (this.canonicalStore?.e2eSessionUnavailable(sessionId)) throw new Error("E2E_SESSION_DISPOSING");
    const cwd = join(this.options.sessionsDirectory, sessionId);
    await mkdir(cwd, { recursive: true, mode: 0o700 });
    const actor = new SessionActor(this.options.supervisor, {
      sessionId,
      cwd,
      ...(this.canonicalStore === undefined ? {} : { canonicalStore: this.canonicalStore }),
      onSettlement: this.options.onSettlement,
      ...(this.options.onAppend === undefined ? {} : { onAppend: this.options.onAppend }),
      ...(this.options.onAgentsUpdate === undefined ? {} : { onAgentsUpdate: this.options.onAgentsUpdate }),
    });
    this.actors.set(sessionId, actor);
    if (!this.catalogNotificationsSuspended) this.options.onCatalogChanged?.();
    return actor;
  }

  sessionIds(): readonly string[] {
    return [...this.actors.keys()];
  }

  async createE2eSession(): Promise<E2eSessionHandle> {
    const store = this.canonicalStore;
    if (store === undefined) throw new Error("E2E_SESSION_UNAVAILABLE");
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const sessionId = randomUUID();
      const deleteToken = randomBytes(32).toString("base64url");
      try {
        store.createE2eSession(sessionId, randomUUID(), deleteToken);
        try {
          await this.actor(sessionId);
        } catch (error) {
          store.beginE2eDisposal(sessionId, deleteToken);
          throw error;
        }
        return { sessionId, deleteToken };
      } catch (error) {
        if (error instanceof Error && error.message === "E2E_SESSION_COLLISION") continue;
        throw error;
      }
    }
    throw new Error("E2E_SESSION_COLLISION");
  }

  async disposeE2eSession(
    sessionId: string,
    deleteToken: string,
    deleteJournalRecords: () => Promise<unknown>,
  ): Promise<void> {
    if (!UUID_V4.test(sessionId) || !E2E_DELETE_TOKEN.test(deleteToken)) throw new Error("E2E_SESSION_INVALID");
    const store = this.canonicalStore;
    if (store === undefined) throw new Error("E2E_SESSION_UNAVAILABLE");
    const state = store.e2eSessionState(sessionId, deleteToken);
    if (state === "active") {
      const actor = this.actors.get(sessionId);
      if (actor !== undefined) {
        await actor.dispose();
        this.actors.delete(sessionId);
      }
      store.beginE2eDisposal(sessionId, deleteToken);
      this.options.onCatalogChanged?.();
    }
    await deleteJournalRecords();
    await removeE2eSessionDirectory(this.options.sessionsDirectory, sessionId);
    store.completeE2eDisposal(sessionId, deleteToken);
  }

  e2eCanonicalContains(sessionId: string, deleteToken: string, content: string): boolean {
    if (!E2E_DELETE_TOKEN.test(deleteToken) || content.length === 0 || Buffer.byteLength(content, "utf8") > 512) {
      throw new Error("E2E_SESSION_INVALID");
    }
    if (this.canonicalStore?.e2eSessionState(sessionId, deleteToken) !== "active") throw new Error("E2E_SESSION_DISPOSING");
    return this.canonicalStore.records(sessionId).some((record) => {
      if (record.piType !== "message_end" || record.snapshotJson === undefined) return false;
      const snapshot = parseSnapshotFinal(record.snapshotJson);
      return snapshot?.role === "user" && snapshotContainsText(snapshot.content, content);
    });
  }

  /** Captures one registry view for the catalog and fresh-device synchronization queue. */
  async inventory(signal: AbortSignal): Promise<{ catalog: readonly JsonObject[]; resumes: readonly JsonObject[] }> {
    signal.throwIfAborted();
    const actors = [...this.actors.values()];
    const catalog = await Promise.all(actors.map(async (actor) => await this.catalogEntry(actor, actor.sessionId, signal)));
    assertWireMessage("session.catalog", { sessions: catalog });
    return {
      catalog,
      resumes: actors.map((actor) => ({ sessionId: actor.sessionId, streamEpoch: actor.currentStreamEpoch })),
    };
  }

  async catalog(signal: AbortSignal): Promise<JsonObject[]> {
    const inventory = await this.inventory(signal);
    return [...inventory.catalog];
  }

  catalogSnapshot(signal: AbortSignal): Promise<JsonObject[]> {
    return this.catalog(signal);
  }

  capture(sessionId: string): CommandDispatchPath {
    let captured: { actor: SessionActor; generation: number } | undefined;
    return {
      get generation(): number {
        return captured?.generation ?? 0;
      },
      dispatch: async (command, authorization, signal) => {
        await authorization.revalidate(signal);
        const actor = await this.actor(sessionId);
        return await actor.serialize(async (child) => {
          captured ??= { actor, generation: actor.currentGeneration };
          if (captured.actor !== actor || captured.generation !== actor.currentGeneration) {
            throw new Error("SESSION_LEASE_CONFLICT");
          }
          return validateOperationResponse(await child.call(toRpcCommand(command)), command.operation);
        });
      },
    };
  }

  authorize(command: SemanticCommand, context: CommandGuardContext, signal: AbortSignal): Promise<CommandAuthorization> {
    signal.throwIfAborted();
    if (context.pathGeneration < 0) throw new Error("COMMAND_REJECTED");
    if (!ALLOWED_OPERATIONS.has(command.operation)) throw new Error("COMMAND_REJECTED");
    return Promise.resolve({
      approvedAtMs: Date.now(),
      revalidate: (revalidateSignal) => {
        revalidateSignal.throwIfAborted();
        return Promise.resolve();
      },
    });
  }

  /** Resume-shaped entries for every supervised session (fresh-device full sync). */
  listAll(signal: AbortSignal): Promise<JsonObject[]> {
    signal.throwIfAborted();
    return Promise.resolve(
      [...this.actors.values()].map((actor) => ({
        sessionId: actor.sessionId,
        streamEpoch: actor.currentStreamEpoch,
      })),
    );
  }

  async prepare(resume: JsonObject, signal: AbortSignal): Promise<GatewaySyncPlan> {
    signal.throwIfAborted();
    const sessionId = resume["sessionId"];
    if (typeof sessionId !== "string") throw new Error("SYNC_SESSION_INVALID");
    const actor = await this.actor(sessionId);
    return {
      kind: "snapshot",
      sessionId,
      streamEpoch: actor.currentStreamEpoch,
      source: new PiSnapshotSource(actor, this.canonicalStore),
      agentsCatalog: { sessionId, agents: actor.agentsCatalog() as unknown as JsonObject[] },
    };
  }

  /** Builds one schema-valid session.catalog entry without trusting Pi's session identity. */
  private async catalogEntry(actor: SessionActor, sessionId: string, signal: AbortSignal): Promise<JsonObject> {
    signal.throwIfAborted();
    const actorCwd = boundedUtf8(actor.sessionCwd, CATALOG_PATH_BYTES);
    if (actorCwd === undefined) throw new Error("SESSION_CATALOG_ACTOR_PATH_INVALID");
    const metadata = await stat(actorCwd);
    const createdFallback = fileDateTime(metadata.birthtimeMs, metadata.ctimeMs, metadata.mtimeMs);
    const updatedFallback = fileDateTime(metadata.mtimeMs, metadata.ctimeMs, metadata.birthtimeMs);
    let data: JsonObject = {};
    try {
      const response = await actor.serialize(async (child) => await child.call({ type: "get_state" }));
      signal.throwIfAborted();
      if (response["success"] === true && isJsonObject(response["data"])) data = response["data"];
    } catch {
      signal.throwIfAborted();
    }
    const modelState = isJsonObject(data["model"]) ? data["model"] : undefined;
    const cwd = boundedUtf8(data["cwd"], CATALOG_PATH_BYTES) ?? actorCwd;
    const parent = typeof data["parentId"] === "string" && UUID_V4.test(data["parentId"]) && data["parentId"] !== sessionId
      ? data["parentId"]
      : null;
    return {
      sessionId,
      provider: boundedUtf8(data["provider"], 64) ?? boundedUtf8(modelState?.["provider"], 64) ?? CATALOG_UNAVAILABLE,
      model: boundedUtf8(data["model"], 128) ?? boundedUtf8(modelState?.["id"], 128) ?? CATALOG_UNAVAILABLE,
      thinkingLevel: boundedUtf8(data["thinkingLevel"], 32) ?? CATALOG_UNAVAILABLE,
      repo: boundedUtf8(data["repo"], CATALOG_PATH_BYTES) ?? cwd,
      worktree: boundedUtf8(data["worktree"], CATALOG_PATH_BYTES) ?? null,
      cwd,
      parentId: parent,
      createdAt: normalizedDateTime(data["createdAt"]) ?? createdFallback,
      updatedAt: normalizedDateTime(data["updatedAt"]) ?? updatedFallback,
    };
  }

  committed(plan: GatewaySyncPlan, sequence: bigint, signal: AbortSignal): Promise<void> {
    void plan;
    void sequence;
    signal.throwIfAborted();
    return Promise.resolve();
  }

  async stop(): Promise<void> {
    const actors = [...this.actors.values()];
    this.actors.clear();
    await Promise.allSettled(actors.map(async (actor) => actor.stop()));
    this.canonicalStore?.close();
  }
}

export async function removeE2eSessionDirectory(sessionsDirectory: string, sessionId: string): Promise<void> {
  if (!UUID_V4.test(sessionId)) throw new Error("E2E_SESSION_INVALID");
  const configuredRoot = resolve(sessionsDirectory);
  const rootMetadata = await lstat(configuredRoot).catch((error: unknown) => {
    if (hasErrorCode(error, "ENOENT")) throw new Error("E2E_SESSION_DIRECTORY_UNSAFE");
    throw error;
  });
  if (!rootMetadata.isDirectory() || rootMetadata.isSymbolicLink()) throw new Error("E2E_SESSION_DIRECTORY_UNSAFE");
  const root = await realpath(configuredRoot);
  const target = resolve(root, sessionId);
  if (relative(root, target) !== sessionId || !target.startsWith(`${root}${sep}`)) throw new Error("E2E_SESSION_DIRECTORY_UNSAFE");
  const metadata = await lstat(target).catch((error: unknown) => {
    if (hasErrorCode(error, "ENOENT")) return undefined;
    throw error;
  });
  if (metadata === undefined) return;
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) throw new Error("E2E_SESSION_DIRECTORY_UNSAFE");
  await rm(target, { recursive: true, force: false, maxRetries: 0 });
}

function hasErrorCode(error: unknown, code: string): boolean {
  return error instanceof Error && "code" in error && error.code === code;
}

function boundedUtf8(value: unknown, maxBytes: number): string | undefined {
  if (typeof value !== "string" || value.length === 0 || value.length > maxBytes) return undefined;
  return Buffer.byteLength(value, "utf8") <= maxBytes ? value : undefined;
}

function normalizedDateTime(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length === 0 || value.length > 64) return undefined;
  const milliseconds = Date.parse(value);
  if (!Number.isFinite(milliseconds)) return undefined;
  try {
    return new Date(milliseconds).toISOString();
  } catch {
    return undefined;
  }
}

function fileDateTime(...candidates: number[]): string {
  for (const milliseconds of candidates) {
    if (Number.isFinite(milliseconds) && milliseconds >= 0) return new Date(milliseconds).toISOString();
  }
  throw new Error("SESSION_CATALOG_TIMESTAMP_INVALID");
}

function validateOperationResponse(response: Readonly<Record<string, unknown>>, operation: string): JsonValue {
  if (response["type"] !== "response" || response["command"] !== operation || typeof response["success"] !== "boolean") {
    throw new Error("PI_RPC_RESPONSE_INVALID");
  }
  if (!response["success"]) throw new CommandDispatchRejectedError("PI_RPC_REJECTED");
  const data = response["data"];
  if ((data !== undefined && !isJsonValue(data)) || !isJsonValue(response)) throw new Error("PI_RPC_RESPONSE_INVALID");
  return response;
}

function isJsonValue(value: unknown): value is JsonValue {
  if (value === null || typeof value === "string" || typeof value === "boolean") return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value)) return value.every(isJsonValue);
  return isJsonObject(value) && Object.values(value).every(isJsonValue);
}

function toRpcCommand(command: SemanticCommand): Record<string, unknown> {
  const payload: JsonValue = command.payload;
  const body: Record<string, unknown> = { type: command.operation };
  if (isJsonObject(payload)) {
    for (const [key, value] of Object.entries(payload)) body[key] = value;
  }
  return body;
}
