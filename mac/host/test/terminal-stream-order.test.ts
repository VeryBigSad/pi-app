import { describe, expect, it } from "vitest";
import { FrameKind, decodeTerminalPayload, type JsonObject } from "@pimobile/protocol";
import { ContentStreamManager } from "../src/gateway/streams.js";
import type { TerminalOutput, TerminalRuntime } from "../src/gateway/types.js";

const sessionId = "0f8fad5b-d9cb-469f-a165-70867728950e";

describe("terminal stream ordering", () => {
  it("serializes concurrent output without reordering bytes or wire sequences", async () => {
    let output: TerminalOutput | undefined;
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const sent: ReturnType<typeof decodeTerminalPayload>[] = [];
    let sendCalls = 0;
    const runtime: TerminalRuntime = {
      open: (_request, value) => {
        output = value;
        return Promise.resolve({
          generation: 4n,
          channel: { write: () => Promise.resolve(), close: () => Promise.resolve() },
        });
      },
    };
    const manager = new ContentStreamManager(
      { open: () => Promise.reject(new Error("unused")) },
      runtime,
      async (kind, payload) => {
        expect(kind).toBe(FrameKind.TerminalBytes);
        const call = sendCalls++;
        if (call === 0) await firstGate;
        sent.push(decodeTerminalPayload(payload));
      },
      () => Promise.resolve(),
      new AbortController().signal,
    );
    await manager.openTerminal({ sessionId });
    if (output === undefined) throw new Error("terminal output missing");

    const first = output.write(Uint8Array.of(1, 2), new AbortController().signal);
    const second = output.write(Uint8Array.of(3, 4), new AbortController().signal);
    await Promise.resolve();
    expect(sent).toEqual([]);
    releaseFirst?.();
    await Promise.all([first, second]);

    expect(sent).toEqual([
      { terminalGeneration: 4n, sequence: 0n, data: Uint8Array.of(1, 2) },
      { terminalGeneration: 4n, sequence: 1n, data: Uint8Array.of(3, 4) },
    ]);
  });

  it("keeps history bound to the active session and generation", async () => {
    const historyRequests: JsonObject[] = [];
    const manager = new ContentStreamManager(
      { open: () => Promise.reject(new Error("unused")) },
      {
        open: () => Promise.resolve({
          generation: 8n,
          channel: {
            write: () => Promise.resolve(),
            history: (request) => {
              historyRequests.push(request);
              return Promise.resolve({
                sessionId,
                terminalGeneration: "8",
                capturedAt: "2026-08-12T00:00:00.000Z",
                text: "strict history",
                truncatedLines: false,
                truncatedBytes: false,
              });
            },
            close: () => Promise.resolve(),
          },
        }),
      },
      () => Promise.resolve(),
      () => Promise.resolve(),
      new AbortController().signal,
    );
    await manager.openTerminal({ sessionId });
    await manager.terminalHistory({ sessionId, terminalGeneration: "8", maxLines: 5_000, maxBytes: 1_024 });
    await expect(manager.terminalHistory({ sessionId, terminalGeneration: "7", maxLines: 1, maxBytes: 1 })).rejects.toMatchObject({
      code: "TERMINAL_RESET_REQUIRED",
    });
    expect(historyRequests).toHaveLength(1);
  });
});
