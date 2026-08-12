import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { assertWireMessage } from "@pimobile/protocol";
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
    private readonly success = true,
  ) {}

  state(): "ready" {
    return "ready";
  }

  call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>> {
    this.calls.push(command);
    const response = { type: "response", command: command["type"], success: this.success };
    return Promise.resolve(command["type"] === "prompt" ? response : { ...response, data: this.stateData });
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

  constructor(
    private readonly stateData: Record<string, unknown> = {},
    private readonly success = true,
  ) {}

  startSession(options: { sessionId: string }): Promise<RuntimeSession> {
    const session = new FakeSession(options.sessionId, this.stateData, this.success);
    this.sessions.push(session);
    return Promise.resolve(session as unknown as RuntimeSession);
  }
}

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function fixture(stateData: Record<string, unknown> = {}, success = true): Promise<{
  service: SessionService;
  supervisor: FakeSupervisor;
  appends: SessionAppend[];
  settlements: SettlementNotice[];
}> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-sessions-"));
  roots.push(root);
  const supervisor = new FakeSupervisor(stateData, success);
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
  it("does not publish finalized append identities without a durable canonical store", async () => {
    const { service, supervisor, appends, settlements } = await fixture();
    const actor = await service.actor(sessionId);
    await actor.serialize(() => Promise.resolve());
    const session = supervisor.sessions[0];
    expect(session).toBeDefined();

    session?.emitRecord({ type: "agent_start" });
    session?.emitRecord({ type: "message_end", id: "m-1" });
    session?.emitRecord({ type: "agent_settled" });
    expect(appends).toHaveLength(0);

    expect(settlements).toHaveLength(1);
    expect(settlements[0]).toMatchObject({ sessionId, sequence: "3" });
    expect(settlements[0]?.settlementId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    await service.stop();
  });

  it("accepts the documented prompt success response without data", async () => {
    const { service } = await fixture();
    const path = service.capture(sessionId);
    await expect(path.dispatch({
      commandId: "550e8400-e29b-41d4-a716-446655440053",
      sessionId,
      operation: "prompt",
      payload: { message: "sanitized" },
      payloadHash: "0".repeat(64),
    }, {
      approvedAtMs: 1,
      revalidate: () => Promise.resolve(),
    }, new AbortController().signal)).resolves.toMatchObject({
      type: "response",
      command: "prompt",
      success: true,
    });
    await service.stop();
  });

  it("rejects an explicit Pi RPC operation failure with a stable code", async () => {
    const { service, supervisor } = await fixture({}, false);
    const path = service.capture(sessionId);
    await expect(path.dispatch({
      commandId: "550e8400-e29b-41d4-a716-446655440052",
      sessionId,
      operation: "prompt",
      payload: { message: "sanitized" },
      payloadHash: "0".repeat(64),
    }, {
      approvedAtMs: 1,
      revalidate: () => Promise.resolve(),
    }, new AbortController().signal)).rejects.toMatchObject({ code: "PI_RPC_REJECTED" });
    expect(supervisor.sessions[0]?.calls[0]).toMatchObject({ type: "prompt", message: "sanitized" });
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
    await service.actor(sessionId);
    const catalog = await service.catalog(new AbortController().signal);
    expect(catalog).toHaveLength(1);
    expect(catalog[0]).toMatchObject({
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
    expect(catalog[0]).not.toHaveProperty("extraIgnored");
    await service.stop();
  });

  it("normalizes the observed six-session Pi state and preserves inventory identity", async () => {
    const { service } = await fixture({
      model: { id: "gpt-5.4", provider: "openai", api: "responses", contextWindow: 1_000_000 },
      thinkingLevel: "high",
      isStreaming: false,
      sessionId: "a Pi-owned identity that must not cross the boundary",
    });
    const ids = Array.from({ length: 6 }, (_, index) => `550e8400-e29b-41d4-a716-44665544004${String(index)}`);
    for (const id of ids) await service.actor(id);
    const signal = new AbortController().signal;
    const expectedResumes = await service.listAll(signal);

    const inventory = await service.inventory(signal);
    expect(inventory.catalog.map((entry) => entry["sessionId"])).toEqual(ids);
    expect(inventory.resumes).toEqual(expectedResumes);
    expect(inventory.catalog).toHaveLength(6);
    for (const entry of inventory.catalog) {
      expect(() => assertWireMessage("session.catalog", { sessions: [entry] })).not.toThrow();
      expect(entry).toMatchObject({
        provider: "openai",
        model: "gpt-5.4",
        thinkingLevel: "high",
        worktree: null,
        parentId: null,
      });
      expect(entry["repo"]).toBe(entry["cwd"]);
      expect(Date.parse(entry["createdAt"] as string)).not.toBeNaN();
      expect(Date.parse(entry["updatedAt"] as string)).not.toBeNaN();
    }
    await service.stop();
  });

  it("uses bounded actor-state defaults when Pi returns no catalog metadata", async () => {
    const { service } = await fixture({ ok: true });
    await service.actor(sessionId);
    const catalog = await service.catalog(new AbortController().signal);
    const entry = catalog[0];
    expect(entry).toBeDefined();
    expect(entry).toMatchObject({
      sessionId,
      provider: "unavailable",
      model: "unavailable",
      thinkingLevel: "unavailable",
      worktree: null,
      parentId: null,
    });
    expect(entry?.["repo"]).toBe(entry?.["cwd"]);
    expect(() => assertWireMessage("session.catalog", { sessions: catalog })).not.toThrow();
    await service.stop();
  });

  const malformedCatalogFields: readonly {
    readonly field: string;
    readonly values: readonly unknown[];
  }[] = [
    { field: "provider", values: [undefined, "", "p".repeat(65), 42] },
    { field: "model", values: [undefined, "", "m".repeat(129), { id: "" }] },
    { field: "thinkingLevel", values: [undefined, "", "t".repeat(33), false] },
    { field: "repo", values: [undefined, "", "r".repeat(4_097), false] },
    { field: "worktree", values: [undefined, "", "w".repeat(4_097), false] },
    { field: "cwd", values: [undefined, "", "c".repeat(4_097), false] },
    { field: "parentId", values: [undefined, "", "not-a-uuid", sessionId] },
    { field: "createdAt", values: [undefined, "", "not-a-date", "d".repeat(65)] },
    { field: "updatedAt", values: [undefined, "", "not-a-date", "d".repeat(65)] },
  ];

  for (const malformed of malformedCatalogFields) {
    it(`normalizes missing and malformed ${malformed.field}`, async () => {
      for (const value of malformed.values) {
        const state: Record<string, unknown> = {
          provider: "openai",
          model: "gpt-5.4",
          thinkingLevel: "high",
          repo: "/state/repo",
          worktree: "/state/worktree",
          cwd: "/state/cwd",
          parentId: "550e8400-e29b-41d4-a716-446655440051",
          createdAt: "2026-08-01T00:00:00.000Z",
          updatedAt: "2026-08-12T00:00:00.000Z",
          [malformed.field]: value,
        };
        const { service } = await fixture(state);
        try {
          await service.actor(sessionId);
          const catalog = await service.catalog(new AbortController().signal);
          const entry = catalog[0];
          expect(entry).toBeDefined();
          expect(() => assertWireMessage("session.catalog", { sessions: catalog })).not.toThrow();
          if (malformed.field === "provider") expect(entry?.["provider"]).toBe("unavailable");
          if (malformed.field === "model") expect(entry?.["model"]).toBe("unavailable");
          if (malformed.field === "thinkingLevel") expect(entry?.["thinkingLevel"]).toBe("unavailable");
          if (malformed.field === "repo") expect(entry?.["repo"]).toBe("/state/cwd");
          if (malformed.field === "worktree") expect(entry?.["worktree"]).toBeNull();
          if (malformed.field === "cwd") expect(entry?.["cwd"]).not.toBe(value);
          if (malformed.field === "parentId") expect(entry?.["parentId"]).toBeNull();
          if (malformed.field === "createdAt") expect(entry?.["createdAt"]).not.toBe(value);
          if (malformed.field === "updatedAt") expect(entry?.["updatedAt"]).not.toBe(value);
        } finally {
          await service.stop();
        }
      }
    });
  }

  it("uses nested model metadata only when each value is independently bounded", async () => {
    const { service } = await fixture({
      model: { id: "m".repeat(129), provider: "p".repeat(65) },
      thinkingLevel: "high",
    });
    await service.actor(sessionId);
    const [entry] = await service.catalog(new AbortController().signal);
    expect(entry).toMatchObject({ provider: "unavailable", model: "unavailable" });
    await service.stop();
  });
});
