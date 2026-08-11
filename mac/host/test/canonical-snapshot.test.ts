import { describe, expect, it } from "vitest";
import { captureCanonicalSnapshot, type EntriesResponse, type SnapshotSource } from "../src/sync/canonical-snapshot.js";

class FakeSource implements SnapshotSource {
  readonly calls: (string | undefined)[] = [];
  readonly responses: EntriesResponse[];
  barriers = 0;

  constructor(responses: EntriesResponse[]) {
    this.responses = [...responses];
  }

  async withMutationBarrier<T>(operation: () => Promise<T>): Promise<T> {
    this.barriers += 1;
    return await operation();
  }

  async waitUntilIdle(): Promise<void> {
    await Promise.resolve();
  }

  currentEventFence(): bigint {
    return 42n;
  }

  async getEntries(since?: string): Promise<EntriesResponse> {
    this.calls.push(since);
    const next = this.responses.shift();
    if (next === undefined) throw new Error("missing fake response");
    return await Promise.resolve(next);
  }

  async replayAfter(fence: bigint): Promise<readonly unknown[]> {
    return await Promise.resolve([{ after: String(fence) }]);
  }
}

describe("captureCanonicalSnapshot", () => {
  it("uses final append ID rather than an older active branch leaf", async () => {
    const source = new FakeSource([
      {
        entries: [
          { id: "aaaabbbb", parentId: null },
          { id: "ccccdddd", parentId: "aaaabbbb" },
          { id: "eeeeffff", parentId: "ccccdddd" },
        ],
        leafId: "aaaabbbb",
      },
      { entries: [], leafId: "aaaabbbb" },
    ]);

    const snapshot = await captureCanonicalSnapshot(source);
    expect(source.calls).toEqual([undefined, "eeeeffff"]);
    expect(snapshot).toMatchObject({ leafId: "aaaabbbb", lastAppendId: "eeeeffff", attempts: 1 });
  });

  it("retries the whole attempt after a concurrent append", async () => {
    const source = new FakeSource([
      { entries: [{ id: "aaaabbbb" }], leafId: "aaaabbbb" },
      { entries: [{ id: "ccccdddd" }], leafId: "ccccdddd" },
      { entries: [{ id: "aaaabbbb" }, { id: "ccccdddd" }], leafId: "ccccdddd" },
      { entries: [], leafId: "ccccdddd" },
    ]);

    const snapshot = await captureCanonicalSnapshot(source);
    expect(snapshot.attempts).toBe(2);
    expect(source.barriers).toBe(2);
    expect(source.calls).toEqual([undefined, "aaaabbbb", undefined, "ccccdddd"]);
  });

  it("repeats a full query to validate an empty session", async () => {
    const source = new FakeSource([
      { entries: [], leafId: null },
      { entries: [], leafId: null },
    ]);
    const snapshot = await captureCanonicalSnapshot(source);
    expect(source.calls).toEqual([undefined, undefined]);
    expect(snapshot.lastAppendId).toBeUndefined();
  });
});
