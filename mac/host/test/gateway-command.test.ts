import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { commandPayloadHash, type JsonObject } from "@pimobile/protocol";
import { describe, expect, it } from "vitest";
import {
  AtMostOnceCommandDispatcher,
  type CommandExecutionContext,
} from "../src/gateway/command-dispatch.js";
import type { GatewayClock } from "../src/gateway/types.js";
import { SqliteCommandJournal } from "../src/journal/sqlite-journal.js";
import type {
  CommandJournalStore,
  JournalInsertResult,
  JournalRecord,
  JournalRecoveryResult,
  JournalTransitionResult,
  SemanticCommand,
} from "../src/journal/types.js";
import { JournalStoreError } from "../src/journal/types.js";

const commandId = "550e8400-e29b-41d4-a716-446655440020";
const sessionId = "550e8400-e29b-41d4-a716-446655440021";

const clock: GatewayClock = {
  now: () => 100,
  setTimeout: (operation, delayMs) => setTimeout(operation, delayMs),
  clearTimeout: (handle) => clearTimeout(handle as NodeJS.Timeout),
};

function body(id = commandId, message = "hello"): JsonObject {
  const input = { sessionId, operation: "prompt", payload: { message }, expectedLeafId: "deadbeef" };
  return { commandId: id, ...input, payloadHash: commandPayloadHash(input) };
}

function authorization(approvedAtMs = 100): { readonly approvedAtMs: number; revalidate(signal: AbortSignal): Promise<void> } {
  return {
    approvedAtMs,
    revalidate: (signal) => {
      signal.throwIfAborted();
      return Promise.resolve();
    },
  };
}

function context(authorized = (): boolean => true): CommandExecutionContext {
  return {
    deviceId: "550e8400-e29b-41d4-a716-446655440022",
    certificateId: "cert",
    userId: "owner",
    path: "direct",
    pathGeneration: 1,
    authorizationGeneration: 1,
    authorized,
  };
}

function journal(): SqliteCommandJournal {
  return new SqliteCommandJournal(join(mkdtempSync(join(tmpdir(), "gateway-command-")), "journal.sqlite"));
}

function received(command: SemanticCommand, dormant = false): JournalRecord {
  return {
    command,
    state: "RECEIVED",
    dormant,
    receivedAtMs: 1,
    updatedAtMs: 1,
    revision: 0,
  };
}

function semantic(value: JsonObject): SemanticCommand {
  return {
    commandId: value["commandId"] as string,
    sessionId: value["sessionId"] as string,
    operation: value["operation"] as string,
    payload: value["payload"] as JsonObject,
    payloadHash: value["payloadHash"] as string,
    expectedLeafId: value["expectedLeafId"] as string,
  };
}

function gate(): { readonly promise: Promise<void>; readonly release: () => void } {
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

class FailingJournal implements CommandJournalStore {
  get(): Promise<JournalRecord | undefined> {
    return Promise.resolve(undefined);
  }

  insertReceived(): Promise<JournalInsertResult> {
    return Promise.reject(new JournalStoreError("disk fault"));
  }

  transition(): Promise<JournalTransitionResult> {
    return Promise.reject(new JournalStoreError("disk fault"));
  }

  recover(): Promise<JournalRecoveryResult> {
    return Promise.resolve({ dormantReceived: 0, indeterminateArmed: 0 });
  }
}

describe("gateway at-most-once command dispatch", () => {
  it("dispatches one Pi path for one hundred concurrent duplicates", async () => {
    const store = journal();
    const dispatchGate = gate();
    let dispatches = 0;
    const dispatcher = new AtMostOnceCommandDispatcher(
      store,
      { authorize: () => Promise.resolve(authorization()) },
      {
        capture: () => ({
          generation: 1,
          dispatch: async () => {
            dispatches += 1;
            await dispatchGate.promise;
            return { ok: true };
          },
        }),
      },
      clock,
    );

    const operations = Array.from({ length: 100 }, async () => dispatcher.submit(body(), context(), new AbortController().signal));
    await waitUntil(() => dispatches === 1);
    dispatchGate.release();
    const outcomes = await Promise.all(operations);
    expect(dispatches).toBe(1);
    expect(new Set(outcomes.map((outcome) => outcome.record.revision))).toEqual(new Set([2]));
    expect((await store.get(commandId))?.state).toBe("ACKED");
    store.close();
  });

  it("closes hash reuse even while the original command is active", async () => {
    const store = journal();
    const dispatchGate = gate();
    const dispatcher = new AtMostOnceCommandDispatcher(
      store,
      { authorize: () => Promise.resolve(authorization()) },
      { capture: () => ({ generation: 1, dispatch: async () => { await dispatchGate.promise; return { ok: true }; } }) },
      clock,
    );
    const first = dispatcher.submit(body(), context(), new AbortController().signal);
    await expect(dispatcher.submit(body(commandId, "different"), context(), new AbortController().signal)).rejects.toMatchObject({ code: "COMMAND_ID_REUSE" });
    dispatchGate.release();
    await first;
    store.close();
  });

  it("keeps recovered RECEIVED dormant on query and wakes only via current authorized resubmission", async () => {
    const store = journal();
    const command = semantic(body());
    await store.insertReceived(received(command));
    await store.recover(2);
    let dispatches = 0;
    const dispatcher = new AtMostOnceCommandDispatcher(
      store,
      { authorize: () => Promise.resolve(authorization()) },
      { capture: () => ({ generation: 1, dispatch: () => { dispatches += 1; return Promise.resolve({ ok: true }); } }) },
      clock,
    );

    expect(await dispatcher.query({ commandId })).toMatchObject({ state: "RECEIVED", dormant: true });
    expect(dispatches).toBe(0);
    expect(await dispatcher.submit(body(), context(), new AbortController().signal)).toMatchObject({ record: { state: "ACKED", dormant: false }, dispatched: true });
    expect(dispatches).toBe(1);
    store.close();
  });

  it("never revives recovered ARMED and fails closed on journal faults", async () => {
    const store = journal();
    const command = semantic(body());
    await store.insertReceived(received(command));
    await store.transition(commandId, command.payloadHash, { kind: "arm", atMs: 2 });
    await store.recover(3);
    let dispatches = 0;
    const dispatcher = new AtMostOnceCommandDispatcher(
      store,
      { authorize: () => Promise.resolve(authorization()) },
      { capture: () => ({ generation: 1, dispatch: () => { dispatches += 1; return Promise.resolve({}); } }) },
      clock,
    );
    expect(await dispatcher.submit(body(), context(), new AbortController().signal)).toMatchObject({ record: { state: "INDETERMINATE" }, dispatched: false });
    expect(dispatches).toBe(0);
    store.close();

    const faultDispatcher = new AtMostOnceCommandDispatcher(
      new FailingJournal(),
      { authorize: () => Promise.resolve(authorization()) },
      { capture: () => ({ generation: 1, dispatch: () => { dispatches += 1; return Promise.resolve({}); } }) },
      clock,
    );
    await expect(faultDispatcher.submit(body(), context(), new AbortController().signal)).rejects.toMatchObject({ code: "JOURNAL_UNAVAILABLE" });
    expect(dispatches).toBe(0);
  });

  it("revalidates current authority after ARMED and before the dispatch path", async () => {
    const store = journal();
    let dispatches = 0;
    const dispatcher = new AtMostOnceCommandDispatcher(
      store,
      {
        authorize: () => Promise.resolve({
          approvedAtMs: 100,
          revalidate: () => Promise.reject(new Error("SESSION_LEASE_CONFLICT")),
        }),
      },
      {
        capture: () => ({
          generation: 1,
          dispatch: () => {
            dispatches += 1;
            return Promise.resolve({});
          },
        }),
      },
      clock,
    );
    expect(await dispatcher.submit(body(), context(), new AbortController().signal)).toMatchObject({
      record: { state: "REJECTED", errorCode: "SESSION_LEASE_CONFLICT" },
      dispatched: false,
    });
    expect(dispatches).toBe(0);
    store.close();
  });

  it("captures one path generation and never migrates during a path race", async () => {
    const store = journal();
    const authorizationGate = gate();
    let generation = 1;
    let captured = 0;
    let oldDispatches = 0;
    let newDispatches = 0;
    const dispatcher = new AtMostOnceCommandDispatcher(
      store,
      { authorize: async () => { await authorizationGate.promise; return authorization(); } },
      {
        capture: () => {
          const selected = generation;
          captured = selected;
          return {
            generation: selected,
            dispatch: () => {
              if (selected === 1) oldDispatches += 1;
              else newDispatches += 1;
              return Promise.resolve({ generation: selected });
            },
          };
        },
      },
      clock,
    );

    const operation = dispatcher.submit(body(), context(), new AbortController().signal);
    await waitUntil(() => captured === 1);
    generation = 2;
    authorizationGate.release();
    const outcome = await operation;
    expect(outcome.record).toMatchObject({ state: "ACKED", result: { generation: 1 } });
    expect({ oldDispatches, newDispatches }).toEqual({ oldDispatches: 1, newDispatches: 0 });
    store.close();
  });

  it("rejects cancellation before ARMED and marks cancellation after ARMED indeterminate", async () => {
    const beforeStore = journal();
    const guardGate = gate();
    const beforeController = new AbortController();
    let authorized = true;
    const before = new AtMostOnceCommandDispatcher(
      beforeStore,
      { authorize: async () => { await guardGate.promise; return authorization(); } },
      { capture: () => ({ generation: 1, dispatch: () => Promise.resolve({}) }) },
      clock,
    );
    const beforeOperation = before.submit(body(), context(() => authorized), beforeController.signal);
    beforeController.abort("lock");
    authorized = false;
    guardGate.release();
    expect(await beforeOperation).toMatchObject({ record: { state: "REJECTED", errorCode: "AUTH_REQUIRED" }, dispatched: false });
    beforeStore.close();

    const afterStore = journal();
    const afterController = new AbortController();
    let enteredDispatch = false;
    const after = new AtMostOnceCommandDispatcher(
      afterStore,
      { authorize: () => Promise.resolve(authorization()) },
      {
        capture: () => ({
          generation: 1,
          dispatch: (_command, _authorization, signal) => {
            enteredDispatch = true;
            return new Promise<JsonObject>((_resolve, reject) => signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true }));
          },
        }),
      },
      clock,
    );
    const afterOperation = after.submit(body(), context(), afterController.signal);
    await waitUntil(() => enteredDispatch);
    afterController.abort("path_replaced");
    expect(await afterOperation).toMatchObject({ record: { state: "INDETERMINATE", errorCode: "DISPATCH_OUTCOME_UNKNOWN" }, dispatched: true });
    afterStore.close();
  });
});
