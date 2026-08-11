import { describe, expect, it } from "vitest";
import { CanonicalSyncSequencer } from "../src/gateway/sync-sequencer.js";
import type { GatewaySyncPlan, SyncRuntime } from "../src/gateway/types.js";
import type { EntriesResponse, SnapshotSource } from "../src/sync/canonical-snapshot.js";
import type { JsonObject } from "@pimobile/protocol";

const sessionId = "550e8400-e29b-41d4-a716-446655440030";
const streamEpoch = "550e8400-e29b-41d4-a716-446655440031";

interface Sent {
  readonly type: string;
  readonly body: JsonObject;
  readonly replyTo?: string | null;
}

class PlanRuntime implements SyncRuntime {
  readonly commits: { plan: GatewaySyncPlan; sequence: bigint }[] = [];

  constructor(readonly plan: GatewaySyncPlan) {}

  prepare(): Promise<GatewaySyncPlan> {
    return Promise.resolve(this.plan);
  }

  committed(plan: GatewaySyncPlan, sequence: bigint): Promise<void> {
    this.commits.push({ plan, sequence });
    return Promise.resolve();
  }
}

class SnapshotFixture implements SnapshotSource {
  readonly calls: (string | undefined)[] = [];
  barriers = 0;

  withMutationBarrier<T>(operation: () => Promise<T>): Promise<T> {
    this.barriers += 1;
    return operation();
  }

  waitUntilIdle(): Promise<void> {
    return Promise.resolve();
  }

  currentEventFence(): bigint {
    return 5n;
  }

  getEntries(since?: string): Promise<EntriesResponse> {
    this.calls.push(since);
    if (since === undefined) {
      return Promise.resolve({
        entries: [
          { id: "aaaabbbb", parentId: null, role: "user" },
          { id: "ccccdddd", parentId: "aaaabbbb", role: "assistant" },
        ],
        leafId: "aaaabbbb",
      });
    }
    return Promise.resolve({ entries: [], leafId: "aaaabbbb" });
  }

  replayAfter(fence: bigint): Promise<readonly unknown[]> {
    return Promise.resolve([{ sessionId, streamEpoch, sequence: (fence + 1n).toString(), piType: "message_end" }]);
  }
}

function event(sequence: bigint): JsonObject {
  return { sessionId, streamEpoch, sequence: sequence.toString(), piType: "message_end" };
}

function resume(): JsonObject {
  return { cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: null }] };
}

function harness(runtime: SyncRuntime): { sequencer: CanonicalSyncSequencer; sent: Sent[] } {
  const sent: Sent[] = [];
  return {
    sent,
    sequencer: new CanonicalSyncSequencer(runtime, (type, body, replyTo) => {
      sent.push({ type, body, ...(replyTo === undefined ? {} : { replyTo }) });
      return Promise.resolve();
    }),
  };
}

describe("gateway canonical synchronization sequencing", () => {
  it("publishes retained replay contiguously and requires its exact commit acknowledgment", async () => {
    const runtime = new PlanRuntime({
      kind: "replay",
      sessionId,
      streamEpoch,
      fromSequence: 10n,
      throughSequence: 12n,
      events: [event(11n), event(12n)],
    });
    const { sequencer, sent } = harness(runtime);
    const signal = new AbortController().signal;
    await sequencer.start(resume(), "550e8400-e29b-41d4-a716-446655440032", signal);
    expect(sent.map((item) => item.type)).toEqual(["sync.replay", "event.batch", "event.batch"]);
    expect(sent.map((item) => item.body["events"] ?? item.body["throughSequence"])).toEqual([
      "12",
      [event(11n)],
      [event(12n)],
    ]);
    await expect(sequencer.acknowledge({ sessionId, streamEpoch, sequence: "11" }, signal)).rejects.toThrow("does not match");
    expect(runtime.commits).toHaveLength(0);
    await expect(sequencer.acknowledge({ sessionId, streamEpoch, sequence: "12" }, signal)).resolves.toBe(true);
    expect(runtime.commits).toEqual([{ plan: runtime.plan, sequence: 12n }]);
  });

  it("captures one canonical snapshot, validates with last append ID, commits, then replays post-fence events", async () => {
    const source = new SnapshotFixture();
    const runtime = new PlanRuntime({
      kind: "snapshot",
      sessionId,
      streamEpoch,
      source,
      adjunctPages: [{ kind: "runtime", fence: "5", model: "test" }],
    });
    const { sequencer, sent } = harness(runtime);
    const signal = new AbortController().signal;
    await sequencer.start(resume(), "550e8400-e29b-41d4-a716-446655440033", signal);

    expect(source.calls).toEqual([undefined, "ccccdddd"]);
    expect(source.barriers).toBe(1);
    expect(sent.map((item) => item.type)).toEqual([
      "sync.reset",
      "snapshot.begin",
      "snapshot.page",
      "snapshot.page",
      "snapshot.page",
      "snapshot.end",
    ]);
    expect(sent[1]?.body).toMatchObject({ sessionId, streamEpoch, sequence: "5", messageCount: 2, lastAppendId: "ccccdddd" });
    expect(sent.at(-1)?.body).toMatchObject({ sessionId, streamEpoch, sequence: "5", leafId: "aaaabbbb", pages: 3, messageCount: 2, lastAppendId: "ccccdddd" });
    expect(sent.some((item) => item.type === "event.batch")).toBe(false);

    await sequencer.acknowledge({ sessionId, streamEpoch, sequence: "5" }, signal);
    expect(runtime.commits).toEqual([{ plan: runtime.plan, sequence: 5n }]);
    expect(sent.at(-1)).toMatchObject({ type: "event.batch", body: { events: [event(6n)] } });
  });

  it("publishes session.catalog before the snapshot when the plan provides one", async () => {
    const source = new SnapshotFixture();
    const runtime = new PlanRuntime({
      kind: "snapshot",
      sessionId,
      streamEpoch,
      source,
      catalog: { sessionId, provider: "anthropic", model: "claude", thinkingLevel: "high", cwd: "/tmp/work" },
    });
    const { sequencer, sent } = harness(runtime);
    const signal = new AbortController().signal;
    await sequencer.start(resume(), randomReply(), signal);
    expect(sent.map((item) => item.type).slice(0, 3)).toEqual(["sync.reset", "session.catalog", "snapshot.begin"]);
    expect(sent[1]?.body).toMatchObject({ sessionId, provider: "anthropic", model: "claude", thinkingLevel: "high", cwd: "/tmp/work" });
    await sequencer.acknowledge({ sessionId, streamEpoch, sequence: "5" }, signal);
  });

  it("sequences multiple sync.resume cursors, one committed plan per acknowledgment", async () => {
    const otherSession = "550e8400-e29b-41d4-a716-446655440035";
    const otherEpoch = "550e8400-e29b-41d4-a716-446655440036";
    const plans: Record<string, GatewaySyncPlan> = {
      [sessionId]: { kind: "replay", sessionId, streamEpoch, fromSequence: 0n, throughSequence: 1n, events: [event(1n)] },
      [otherSession]: { kind: "replay", sessionId: otherSession, streamEpoch: otherEpoch, fromSequence: 4n, throughSequence: 4n, events: [] },
    };
    class MultiRuntime implements SyncRuntime {
      readonly commits: string[] = [];
      prepare(cursor: JsonObject): Promise<GatewaySyncPlan> {
        const plan = plans[cursor["sessionId"] as string];
        if (plan === undefined) return Promise.reject(new Error("unexpected cursor"));
        return Promise.resolve(plan);
      }
      committed(plan: GatewaySyncPlan): Promise<void> {
        this.commits.push(plan.sessionId);
        return Promise.resolve();
      }
    }
    const runtime = new MultiRuntime();
    const { sequencer, sent } = harness(runtime);
    const signal = new AbortController().signal;
    await sequencer.start({
      cursors: [
        { sessionId, streamEpoch, sequence: "0", leafId: null },
        { sessionId: otherSession, streamEpoch: otherEpoch, sequence: "4", leafId: null },
      ],
    }, randomReply(), signal);
    expect(sent.map((item) => item.type)).toEqual(["sync.replay", "event.batch"]);

    const more = await sequencer.acknowledge({ sessionId, streamEpoch, sequence: "1" }, signal);
    expect(more).toBe(false);
    expect(runtime.commits).toEqual([sessionId]);
    expect(sent.at(-1)?.body).toMatchObject({ sessionId: otherSession, streamEpoch: otherEpoch, fromSequence: "4", throughSequence: "4" });

    const drained = await sequencer.acknowledge({ sessionId: otherSession, streamEpoch: otherEpoch, sequence: "4" }, signal);
    expect(drained).toBe(true);
    expect(runtime.commits).toEqual([sessionId, otherSession]);
  });

  it("rejects sync.resume without a cursors array and plans that mismatch their cursor", async () => {
    const runtime = new PlanRuntime({ kind: "replay", sessionId, streamEpoch, fromSequence: 0n, throughSequence: 0n, events: [] });
    const { sequencer, sent } = harness(runtime);
    const signal = new AbortController().signal;
    await expect(sequencer.start({ sessionId, streamEpoch, sequence: "0", leafId: null }, randomReply(), signal)).rejects.toThrow(/cursors/);
    await expect(sequencer.start({ cursors: [] }, randomReply(), signal)).rejects.toThrow(/cursors/);
    await expect(sequencer.start({ cursors: [{ sessionId: "nope", streamEpoch, sequence: "0", leafId: null }] }, randomReply(), signal)).rejects.toThrow(/identity/);
    await expect(sequencer.start({ cursors: [{ sessionId, streamEpoch, sequence: "0", leafId: "xyz" }] }, randomReply(), signal)).rejects.toThrow(/leaf/);
    expect(sent).toEqual([]);

    const mismatch = new PlanRuntime({
      kind: "replay",
      sessionId: "550e8400-e29b-41d4-a716-446655440037",
      streamEpoch,
      fromSequence: 0n,
      throughSequence: 0n,
      events: [],
    });
    const second = harness(mismatch);
    await expect(second.sequencer.start(resume(), randomReply(), signal)).rejects.toThrow(/does not match its cursor/);
  });

  it("rejects event gaps and cancellation without publishing or committing fabricated state", async () => {
    const runtime = new PlanRuntime({
      kind: "replay",
      sessionId,
      streamEpoch,
      fromSequence: 1n,
      throughSequence: 3n,
      events: [event(3n)],
    });
    const failed = harness(runtime);
    await expect(failed.sequencer.start(resume(), randomReply(), new AbortController().signal)).rejects.toThrow("not contiguous");
    expect(failed.sent).toEqual([]);
    expect(runtime.commits).toEqual([]);

    const cancellableRuntime = new PlanRuntime({
      kind: "replay",
      sessionId,
      streamEpoch,
      fromSequence: 0n,
      throughSequence: 0n,
      events: [],
    });
    const cancellable = harness(cancellableRuntime);
    await cancellable.sequencer.start(resume(), randomReply(), new AbortController().signal);
    cancellable.sequencer.cancel();
    await expect(cancellable.sequencer.acknowledge({ sessionId, streamEpoch, sequence: "0" }, new AbortController().signal)).rejects.toThrow("No synchronization");
    expect(cancellableRuntime.commits).toEqual([]);
  });
});

function randomReply(): string {
  return "550e8400-e29b-41d4-a716-446655440034";
}
