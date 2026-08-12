export interface SessionEntry {
  readonly id: string;
  readonly parentId?: null | string;
  readonly [key: string]: unknown;
}

export interface EntriesResponse {
  readonly entries: readonly SessionEntry[];
  readonly leafId: null | string;
}

export interface SnapshotSource {
  withMutationBarrier<T>(operation: () => Promise<T>): Promise<T>;
  waitUntilIdle(): Promise<void>;
  currentEventFence(): bigint;
  getEntries(since?: string): Promise<EntriesResponse>;
  replayAfter(fence: bigint): Promise<readonly unknown[]>;
}

export interface CanonicalSnapshot {
  readonly fence: bigint;
  readonly entries: readonly SessionEntry[];
  readonly leafId: null | string;
  readonly lastAppendId: string | undefined;
  readonly postFenceEvents: readonly unknown[];
  readonly attempts: number;
}

export class SnapshotChangedError extends Error {
  constructor() {
    super("canonical snapshot changed during validation");
    this.name = "SnapshotChangedError";
  }
}

export async function captureCanonicalSnapshot(
  source: SnapshotSource,
  options: { readonly maxAttempts?: number } = {},
): Promise<CanonicalSnapshot> {
  const maxAttempts = options.maxAttempts ?? 10;
  if (!Number.isSafeInteger(maxAttempts) || maxAttempts < 1) throw new RangeError("maxAttempts must be positive");

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const snapshot = await source.withMutationBarrier(async () => {
      await source.waitUntilIdle();
      const fence = source.currentEventFence();
      const captured = await source.getEntries();
      validateLeaf(captured.leafId);
      const lastAppendId = captured.entries.at(-1)?.["appendId"];
      if (lastAppendId !== undefined && typeof lastAppendId !== "string") throw new TypeError("appendId is invalid");
      const validation = lastAppendId === undefined
        ? await source.getEntries()
        : await source.getEntries(lastAppendId);
      validateLeaf(validation.leafId);

      if (validation.entries.length !== 0 || validation.leafId !== captured.leafId) return undefined;

      return {
        fence,
        entries: structuredClone(captured.entries),
        leafId: captured.leafId,
        lastAppendId,
        postFenceEvents: structuredClone(await source.replayAfter(fence)),
        attempts: attempt,
      };
    });
    if (snapshot !== undefined) return snapshot;
  }

  throw new SnapshotChangedError();
}

function validateLeaf(value: null | string): void {
  if (value !== null && !/^[0-9a-f]{8}$/.test(value)) throw new TypeError("leafId is invalid");
}
