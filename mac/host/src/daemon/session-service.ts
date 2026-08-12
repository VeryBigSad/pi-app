import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { isJsonObject, type JsonObject, type JsonValue } from "@pimobile/protocol";
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
import type { EntriesResponse, SnapshotSource } from "../sync/canonical-snapshot.js";

const ALLOWED_OPERATIONS = new Set(["prompt", "abort", "get_state", "new_session"]);
const IDLE_POLL_MS = 100;
const IDLE_WAIT_MS = 15_000;

export interface SettlementNotice {
  readonly settlementId: string;
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly settledAtMs: number;
}

export interface SessionAppend {
  readonly appendId: string;
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly record: unknown;
}

export interface AgentUpdateNotice {
  readonly sessionId: string;
  readonly agent: TrackedAgent;
}

interface SessionActorOptions {
  readonly sessionId: string;
  readonly cwd: string;
  readonly onSettlement: (notice: SettlementNotice) => void;
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
  private readonly streamEpoch = randomUUID();
  private sequence = 0n;
  private generation = 0;
  private tail: Promise<unknown> = Promise.resolve();
  private child: RuntimeSession | undefined;

  constructor(
    private readonly supervisor: RuntimeSupervisor,
    private readonly options: SessionActorOptions,
  ) {}

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
    const existing = this.child;
    if (existing?.state() === "ready") return existing;
    const session = await this.supervisor.startSession({
      sessionId: this.options.sessionId,
      cwd: this.options.cwd,
    });
    this.generation += 1;
    this.child = session;
    session.on("record", (record: PiJsonRecord) => this.onRecord(record));
    session.on("fault", () => {
      if (this.child === session) this.child = undefined;
      this.generation += 1;
    });
    return session;
  }

  private onRecord(record: PiJsonRecord): void {
    this.sequence += 1n;
    this.options.onAppend?.({
      appendId: randomUUID(),
      sessionId: this.options.sessionId,
      streamEpoch: this.streamEpoch,
      sequence: this.sequence.toString(),
      record: record.value,
    });
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
    const run = this.tail.then(async () => operation(await this.ensureChild()));
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

  async stop(): Promise<void> {
    const child = this.child;
    this.child = undefined;
    if (child !== undefined) await child.stop().catch(() => undefined);
  }
}

class PiSnapshotSource implements SnapshotSource {
  private barrierChild: RuntimeSession | undefined;

  constructor(private readonly actor: SessionActor) {}

  withMutationBarrier<T>(operation: () => Promise<T>): Promise<T> {
    return this.actor.serialize(async (child) => {
      this.barrierChild = child;
      try {
        return await operation();
      } finally {
        this.barrierChild = undefined;
      }
    });
  }

  async waitUntilIdle(): Promise<void> {
    await this.actor.waitUntilIdle();
  }

  currentEventFence(): bigint {
    return 0n;
  }

  async getEntries(since?: string): Promise<EntriesResponse> {
    const held = this.barrierChild;
    if (held !== undefined) return await this.queryEntries(held, since);
    return await this.actor.serialize(async (child) => await this.queryEntries(child, since));
  }

  private async queryEntries(child: RuntimeSession, since?: string): Promise<EntriesResponse> {
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

  replayAfter(): Promise<readonly unknown[]> {
    return Promise.resolve([]);
  }
}

export interface SessionServiceOptions {
  readonly supervisor: RuntimeSupervisor;
  readonly sessionsDirectory: string;
  readonly onSettlement: (notice: SettlementNotice) => void;
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

  constructor(private readonly options: SessionServiceOptions) {}

  async actor(sessionId: string): Promise<SessionActor> {
    const existing = this.actors.get(sessionId);
    if (existing !== undefined) return existing;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(sessionId)) {
      throw new Error("INVALID_SESSION");
    }
    const cwd = join(this.options.sessionsDirectory, sessionId);
    await mkdir(cwd, { recursive: true, mode: 0o700 });
    const actor = new SessionActor(this.options.supervisor, {
      sessionId,
      cwd,
      onSettlement: this.options.onSettlement,
      ...(this.options.onAppend === undefined ? {} : { onAppend: this.options.onAppend }),
      ...(this.options.onAgentsUpdate === undefined ? {} : { onAgentsUpdate: this.options.onAgentsUpdate }),
    });
    this.actors.set(sessionId, actor);
    this.options.onCatalogChanged?.();
    return actor;
  }

  sessionIds(): readonly string[] {
    return [...this.actors.keys()];
  }

  /** Current session.catalog sessions array from every supervised actor. */
  async catalogSnapshot(signal: AbortSignal): Promise<JsonObject[]> {
    signal.throwIfAborted();
    const entries: JsonObject[] = [];
    for (const actor of this.actors.values()) {
      entries.push(await this.catalog(actor, actor.sessionId, signal));
    }
    return entries;
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
          return (await child.call(toRpcCommand(command))) as JsonValue;
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
    const catalog = await this.catalog(actor, sessionId, signal);
    return {
      kind: "snapshot",
      sessionId,
      streamEpoch: resume["streamEpoch"] as string,
      source: new PiSnapshotSource(actor),
      catalog,
      agentsCatalog: { sessionId, agents: actor.agentsCatalog() as unknown as JsonObject[] },
    };
  }

  /** Builds session.catalog from the live Pi RPC session state; only fields Pi actually provides are included. */
  private async catalog(actor: SessionActor, sessionId: string, signal: AbortSignal): Promise<JsonObject> {
    const base: JsonObject = { sessionId, cwd: actor.sessionCwd };
    let response: Readonly<Record<string, unknown>>;
    try {
      response = await actor.serialize(async (child) => await child.call({ type: "get_state" }));
    } catch {
      return base;
    }
    signal.throwIfAborted();
    if (response["success"] !== true) return base;
    const data = response["data"];
    if (!isJsonObject(data)) return base;
    const catalog: JsonObject = { ...base };
    for (const key of ["provider", "thinkingLevel", "repo", "worktree", "parentId", "createdAt", "updatedAt"] as const) {
      const value = data[key];
      if (typeof value === "string" && value.length > 0) catalog[key] = value;
    }
    const model = data["model"];
    if (typeof model === "string" && model.length > 0) catalog["model"] = model;
    else if (isJsonObject(model) && typeof model["id"] === "string" && model["id"].length > 0) catalog["model"] = model["id"];
    const piCwd = data["cwd"];
    if (typeof piCwd === "string" && piCwd.length > 0) catalog["cwd"] = piCwd;
    return catalog;
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
  }
}

function toRpcCommand(command: SemanticCommand): Record<string, unknown> {
  const payload: JsonValue = command.payload;
  const body: Record<string, unknown> = { type: command.operation };
  if (isJsonObject(payload)) {
    for (const [key, value] of Object.entries(payload)) body[key] = value;
  }
  return body;
}
