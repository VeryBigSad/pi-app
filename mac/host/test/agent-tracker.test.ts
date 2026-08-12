import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { assertWireMessage } from "@pimobile/protocol";
import type { JsonObject } from "@pimobile/protocol";
import { AgentTracker, MAX_AGENTS_PER_SESSION, type TrackedAgent } from "../src/pi/agent-tracker.js";
import { SessionService, type AgentUpdateNotice } from "../src/daemon/session-service.js";
import { CanonicalSyncSequencer } from "../src/gateway/sync-sequencer.js";
import type { GatewaySyncPlan, SyncRuntime } from "../src/gateway/types.js";
import type { PiJsonRecord } from "../src/pi/lf-json-framer.js";
import type { RuntimeSession, RuntimeSupervisor } from "../src/runtime/supervisor.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440060";
const streamEpoch = "550e8400-e29b-41d4-a716-446655440061";
const baseMs = Date.parse("2026-01-01T00:00:00.000Z");

function spawn(id: string, args: Record<string, unknown> = {}): Record<string, unknown> {
  return { type: "tool_execution_start", toolCallId: id, toolName: "Agent", args: { description: `do ${id}`, ...args } };
}

function spawnEnd(id: string, isError = false): Record<string, unknown> {
  return { type: "tool_execution_end", toolCallId: id, toolName: "Agent", isError, result: { content: [] } };
}

function refStart(callId: string, toolName: string, agentId: string): Record<string, unknown> {
  return { type: "tool_execution_start", toolCallId: callId, toolName, args: { agentId } };
}

function refEnd(callId: string, toolName: string, result: unknown, isError = false): Record<string, unknown> {
  return { type: "tool_execution_end", toolCallId: callId, toolName, isError, result };
}

describe("AgentTracker lifecycle", () => {
  it("tracks spawn, steering, and completion from synthetic RPC events", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);

    const spawned = tracker.apply(spawn("call-1", { subagent_type: "explore", model: "test-model" }));
    expect(spawned).toMatchObject({
      agentId: "call-1",
      description: "do call-1",
      agentType: "explore",
      status: "running",
      model: "test-model",
      toolUses: 0,
      startedAt: "2026-01-01T00:00:00.000Z",
    });
    expect(spawned?.endedAt).toBeUndefined();
    assertWireMessage("agents.update", { sessionId, agent: spawned as unknown as JsonObject });

    const polled = tracker.apply(refStart("poll-1", "get_subagent_result", "call-1"));
    expect(polled).toMatchObject({ agentId: "call-1", status: "running", toolUses: 1 });

    const waiting = tracker.apply(refEnd("poll-1", "get_subagent_result", { content: [{ type: "text", text: '{"status":"waiting"}' }] }));
    expect(waiting).toMatchObject({ agentId: "call-1", status: "waiting" });
    expect(waiting?.endedAt).toBeUndefined();

    tracker.apply(refStart("steer-1", "steer_subagent", "call-1"));
    const steered = tracker.apply(refEnd("steer-1", "steer_subagent", { content: [] }));
    expect(steered).toMatchObject({ agentId: "call-1", status: "running", toolUses: 2 });

    const done = tracker.apply(refStart("poll-2", "get_subagent_result", "call-1"));
    expect(done?.toolUses).toBe(3);
    const finished = tracker.apply(refEnd("poll-2", "get_subagent_result", { content: [{ type: "text", text: "agent completed: all done" }] }));
    expect(finished).toMatchObject({ agentId: "call-1", status: "completed", endedAt: "2026-01-01T00:00:00.000Z" });
    assertWireMessage("agents.update", { sessionId, agent: finished as unknown as JsonObject });

    const catalog = tracker.catalog();
    expect(catalog).toHaveLength(1);
    assertWireMessage("agents.catalog", { sessions: [{ sessionId, agents: catalog as unknown as JsonObject[] }] });
  });

  it("marks synchronous Agent tool calls completed or failed at tool_execution_end", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    tracker.apply(spawn("ok-1"));
    expect(tracker.apply(spawnEnd("ok-1"))).toMatchObject({ status: "completed", endedAt: "2026-01-01T00:00:00.000Z" });
    tracker.apply(spawn("bad-1"));
    expect(tracker.apply(spawnEnd("bad-1", true))).toMatchObject({ status: "failed" });
  });

  it("maps failed and stopped statuses from get_subagent_result payloads", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    tracker.apply(spawn("a-1"));
    tracker.apply(refStart("p-1", "get_subagent_result", "a-1"));
    expect(tracker.apply(refEnd("p-1", "get_subagent_result", { content: [{ type: "text", text: "status: stopped by user" }] })))
      .toMatchObject({ status: "stopped" });
    tracker.apply(spawn("a-2"));
    tracker.apply(refStart("p-2", "get_subagent_result", "a-2"));
    expect(tracker.apply(refEnd("p-2", "get_subagent_result", { content: [{ type: "text", text: "boom" }] }, true)))
      .toMatchObject({ status: "failed" });
  });

  it("captures parentAgentId when derivable from the event payload", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    const child = tracker.apply(spawn("child-1", { parentAgentId: "parent-9" }));
    expect(child?.parentAgentId).toBe("parent-9");
    assertWireMessage("agents.update", { sessionId, agent: child as unknown as JsonObject });
  });

  it("discovers unknown agents from referencing tool calls with a neutral description", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    const discovered = tracker.apply(refStart("s-1", "steer_subagent", "ext-agent-7"));
    expect(discovered).toMatchObject({ agentId: "ext-agent-7", status: "running", description: "agent ext-agent-7", toolUses: 1 });
    assertWireMessage("agents.update", { sessionId, agent: discovered as unknown as JsonObject });
  });

  it("ignores unrelated records and tools", () => {
    const tracker = new AgentTracker();
    expect(tracker.apply({ type: "message_start" })).toBeUndefined();
    expect(tracker.apply({ type: "tool_execution_start", toolCallId: "x", toolName: "bash", args: {} })).toBeUndefined();
    expect(tracker.apply({ type: "tool_execution_end", toolCallId: "x", toolName: "bash", isError: false })).toBeUndefined();
    expect(tracker.catalog()).toHaveLength(0);
  });
});

describe("AgentTracker bounds and privacy", () => {
  it("enforces the 256-agent per-session bound, evicting oldest terminal agents first", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    for (let index = 0; index < 300; index += 1) {
      tracker.apply(spawn(`agent-${String(index)}`));
      if (index % 2 === 0) tracker.apply(spawnEnd(`agent-${String(index)}`));
    }
    const catalog = tracker.catalog();
    expect(catalog).toHaveLength(MAX_AGENTS_PER_SESSION);
    expect(catalog.some((agent) => agent.agentId === "agent-299")).toBe(true);
    expect(catalog.some((agent) => agent.agentId === "agent-0")).toBe(false);
    assertWireMessage("agents.catalog", { sessions: [{ sessionId, agents: catalog as unknown as JsonObject[] }] });
  });

  it("truncates descriptions at 256 characters and bounds identifiers", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    const longId = `id with spaces ${"x".repeat(300)}`;
    const agent = tracker.apply(spawn(longId, { description: "d".repeat(1000) }));
    expect(agent?.description).toHaveLength(256);
    expect(agent?.agentId.length).toBeLessThanOrEqual(128);
    expect(agent?.agentId).toMatch(/^[A-Za-z0-9._:-]+$/);
    assertWireMessage("agents.update", { sessionId, agent: agent as unknown as JsonObject });
  });

  it("never leaks tool arguments or tool output beyond the bounded description", () => {
    const tracker = new AgentTracker();
    tracker.setClock(() => baseMs);
    tracker.apply(spawn("leak-1", { prompt: "SECRET-PROMPT-TOKEN", description: "short safe summary" }));
    tracker.apply(refStart("p-1", "get_subagent_result", "leak-1"));
    const final = tracker.apply(refEnd("p-1", "get_subagent_result", {
      content: [{ type: "text", text: "SECRET-OUTPUT-TOKEN status: completed" }],
    }));
    const serialized = JSON.stringify({ catalog: tracker.catalog(), final });
    expect(serialized).not.toContain("SECRET-PROMPT-TOKEN");
    expect(serialized).not.toContain("SECRET-OUTPUT-TOKEN");
    expect(final?.description).toBe("short safe summary");
    for (const agent of tracker.catalog()) {
      expect(Object.keys(agent).sort()).toEqual(
        ["agentId", "agentType", "description", "endedAt", "startedAt", "status", "toolUses"].sort(),
      );
    }
  });
});

class FakeSession {
  private recordListener: ((record: PiJsonRecord) => void) | undefined;

  constructor(readonly sessionId: string) {}

  state(): "ready" {
    return "ready";
  }

  call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>> {
    const data: Record<string, unknown> = command["type"] === "get_entries" ? { entries: [], leafId: null } : {};
    return Promise.resolve({ type: "response", id: command["id"], command: command["type"], success: true, data });
  }

  stop(): Promise<void> {
    return Promise.resolve();
  }

  on(event: string, listener: (record: PiJsonRecord) => void): this {
    if (event === "record") this.recordListener = listener;
    return this;
  }

  emitRecord(value: unknown): void {
    this.recordListener?.({ sequence: 0, value } as unknown as PiJsonRecord);
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

describe("host agents emission wiring", () => {
  it("emits agents.update per change and agents.catalog inside the sync snapshot plan", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-agents-"));
    roots.push(root);
    const supervisor = new FakeSupervisor();
    const updates: AgentUpdateNotice[] = [];
    const service = new SessionService({
      supervisor: supervisor as unknown as RuntimeSupervisor,
      sessionsDirectory: join(root, "sessions"),
      onSettlement: () => undefined,
      onAgentsUpdate: (notice) => updates.push(notice),
    });

    const actor = await service.actor(sessionId);
    await actor.serialize(() => Promise.resolve());
    const child = supervisor.sessions[0];
    if (child === undefined) throw new Error("session not started");
    child.emitRecord(spawn("wire-1", { description: "wire test agent" }));
    child.emitRecord(spawnEnd("wire-1"));

    expect(updates.map((update) => [update.sessionId, update.agent.status])).toEqual([
      [sessionId, "running"],
      [sessionId, "completed"],
    ]);
    for (const update of updates) {
      assertWireMessage("agents.update", { sessionId: update.sessionId, agent: update.agent as unknown as JsonObject });
    }

    const plan = await service.prepare({ sessionId, streamEpoch, sequence: "0", leafId: null }, new AbortController().signal);
    expect(plan.kind).toBe("snapshot");
    if (plan.kind !== "snapshot") throw new Error("snapshot plan expected");
    expect(plan.agentsCatalog).toMatchObject({ sessionId });
    const agents = (plan.agentsCatalog as unknown as { agents: TrackedAgent[] }).agents;
    expect(agents).toHaveLength(1);
    expect(agents[0]).toMatchObject({ agentId: "wire-1", status: "completed", description: "wire test agent" });

    const sent: { type: string; body: JsonObject }[] = [];
    const runtime: SyncRuntime = {
      catalog: () => Promise.resolve([]),
      prepare: () => Promise.resolve(plan as GatewaySyncPlan),
      committed: () => Promise.resolve(),
    };
    const sequencer = new CanonicalSyncSequencer(runtime, (type, body) => {
      sent.push({ type, body });
      return Promise.resolve();
    });
    const signal = new AbortController().signal;
    await sequencer.start({ cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] }, "550e8400-e29b-41d4-a716-446655440062", signal);
    expect(sent.map((frame) => frame.type)).toEqual([
      "session.catalog",
      "sync.reset",
      "agents.catalog",
      "snapshot.begin",
      "snapshot.end",
    ]);
    const catalogFrame = sent[2];
    if (catalogFrame === undefined) throw new Error("agents.catalog frame missing");
    const catalogBody = catalogFrame.body as unknown as { sessions: { sessionId: string; agents: TrackedAgent[] }[] };
    expect(catalogBody.sessions).toHaveLength(1);
    expect(catalogBody.sessions[0]?.agents[0]).toMatchObject({ agentId: "wire-1", status: "completed" });
    assertWireMessage("agents.catalog", catalogBody as unknown as JsonObject);
    expect(JSON.stringify(catalogBody)).not.toContain("args");
    expect(JSON.stringify(catalogBody)).not.toContain("result");
    await sequencer.acknowledge({ sessionId, streamEpoch: plan.streamEpoch, sequence: "0" }, signal);
  });
});
