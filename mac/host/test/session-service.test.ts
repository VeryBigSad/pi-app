import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import { SessionService, type SessionAppend, type SettlementNotice } from "../src/daemon/session-service.js";
import type { PiJsonRecord } from "../src/pi/lf-json-framer.js";
import type { RuntimeSession, RuntimeSupervisor } from "../src/runtime/supervisor.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440050";

class FakeSession {
  readonly calls: Readonly<Record<string, unknown>>[] = [];
  private recordListener: ((record: PiJsonRecord) => void) | undefined;

  constructor(
    readonly sessionId: string,
    private readonly stateData: Record<string, unknown>,
  ) {}

  state(): "ready" {
    return "ready";
  }

  call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>> {
    this.calls.push(command);
    return Promise.resolve({ type: "response", id: command["id"], command: command["type"], success: true, data: this.stateData });
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

  constructor(private readonly stateData: Record<string, unknown> = {}) {}

  startSession(options: { sessionId: string }): Promise<RuntimeSession> {
    const session = new FakeSession(options.sessionId, this.stateData);
    this.sessions.push(session);
    return Promise.resolve(session as unknown as RuntimeSession);
  }
}

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function fixture(stateData: Record<string, unknown> = {}): Promise<{
  service: SessionService;
  supervisor: FakeSupervisor;
  appends: SessionAppend[];
  settlements: SettlementNotice[];
}> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-sessions-"));
  roots.push(root);
  const supervisor = new FakeSupervisor(stateData);
  const appends: SessionAppend[] = [];
  const settlements: SettlementNotice[] = [];
  const service = new SessionService({
    supervisor: supervisor as unknown as RuntimeSupervisor,
    sessionsDirectory: join(root, "sessions"),
    onSettlement: (notice) => settlements.push(notice),
    onAppend: (append) => appends.push(append),
  });
  return { service, supervisor, appends, settlements };
}

describe("SessionService live events and catalog", () => {
  it("emits message.append data with a fresh appendId per Pi record and settlementId per settlement", async () => {
    const { service, supervisor, appends, settlements } = await fixture();
    const actor = await service.actor(sessionId);
    await actor.serialize(() => Promise.resolve());
    const session = supervisor.sessions[0];
    expect(session).toBeDefined();

    session?.emitRecord({ type: "agent_start" });
    session?.emitRecord({ type: "message_end", id: "m-1" });
    session?.emitRecord({ type: "agent_settled" });
    expect(appends).toHaveLength(3);
    expect(appends[0]).toMatchObject({ sessionId, sequence: "1", record: { type: "agent_start" } });
    expect(appends[1]).toMatchObject({ sessionId, sequence: "2", record: { type: "message_end", id: "m-1" } });
    const appendIds = new Set(appends.map((append) => append.appendId));
    expect(appendIds.size).toBe(3);
    for (const append of appends) {
      expect(append.appendId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    }

    expect(settlements).toHaveLength(1);
    expect(settlements[0]).toMatchObject({ sessionId, sequence: "3" });
    expect(settlements[0]?.settlementId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    await service.stop();
  });

  it("builds session.catalog from the Pi RPC session state without unknown placeholders", async () => {
    const { service } = await fixture({
      provider: "anthropic",
      model: { id: "claude-opus-4-1" },
      thinkingLevel: "high",
      repo: "pi-app",
      worktree: "/wt/main",
      cwd: "/wt/main",
      parentId: "550e8400-e29b-41d4-a716-446655440051",
      createdAt: "2026-08-01T00:00:00.000Z",
      updatedAt: "2026-08-09T12:00:00.000Z",
      extraIgnored: 42,
    });
    const plan = await service.prepare({ sessionId, streamEpoch: randomUUID() }, new AbortController().signal);
    expect(plan.kind).toBe("snapshot");
    if (plan.kind !== "snapshot") return;
    expect(plan.catalog).toMatchObject({
      sessionId,
      provider: "anthropic",
      model: "claude-opus-4-1",
      thinkingLevel: "high",
      repo: "pi-app",
      worktree: "/wt/main",
      cwd: "/wt/main",
      parentId: "550e8400-e29b-41d4-a716-446655440051",
      createdAt: "2026-08-01T00:00:00.000Z",
      updatedAt: "2026-08-09T12:00:00.000Z",
    });
    expect(plan.catalog).not.toHaveProperty("extraIgnored");
    await service.stop();
  });

  it("omits catalog fields the Pi session state does not provide", async () => {
    const { service } = await fixture({ ok: true });
    const plan = await service.prepare({ sessionId, streamEpoch: randomUUID() }, new AbortController().signal);
    if (plan.kind !== "snapshot") throw new Error("expected snapshot plan");
    expect(plan.catalog?.["sessionId"]).toBe(sessionId);
    expect(typeof plan.catalog?.["cwd"]).toBe("string");
    expect(plan.catalog).not.toHaveProperty("provider");
    expect(plan.catalog).not.toHaveProperty("model");
    expect(plan.catalog).not.toHaveProperty("repo");
    await service.stop();
  });
});
