import { describe, expect, it } from "vitest";
import { TerminalRuntimeAdapter } from "../src/daemon/terminal-runtime.js";
import type { TerminalBackend, TerminalSession } from "../src/terminal/backend.js";
import type { TerminalHistoryRequest, TerminalHistoryResult } from "../src/terminal/types.js";
import { MAX_TERMINAL_HISTORY_BYTES, MAX_TERMINAL_HISTORY_LINES } from "../src/terminal/types.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440060";

function fixture(result: TerminalHistoryResult): {
  adapter: TerminalRuntimeAdapter;
  requests: TerminalHistoryRequest[];
} {
  const requests: TerminalHistoryRequest[] = [];
  const fakeSession = {
    attach: () => Promise.resolve({
      terminalGeneration: 3n,
      send: () => Promise.resolve(),
      resize: () => Promise.resolve(),
      detach: () => Promise.resolve(),
    }),
    history: (request: TerminalHistoryRequest): Promise<TerminalHistoryResult> => {
      requests.push(request);
      return Promise.resolve(result);
    },
  };
  const fakeBackend = {
    session: (id: string) => (id === sessionId ? fakeSession : undefined),
    createSession: () => Promise.reject(new Error("unexpected session creation")),
  };
  const adapter = new TerminalRuntimeAdapter({
    backend: fakeBackend as unknown as TerminalBackend,
    command: "cli.js",
    args: [],
    cwdForSession: () => "/tmp",
  });
  void (fakeSession as unknown as TerminalSession);
  return { adapter, requests };
}

async function open(adapter: TerminalRuntimeAdapter): Promise<void> {
  await adapter.open(
    { sessionId, columns: 80, rows: 24 },
    { write: () => Promise.resolve(), reset: () => Promise.resolve() },
    new AbortController().signal,
  );
}

describe("terminal history gateway runtime", () => {
  const result: TerminalHistoryResult = {
    terminalGeneration: 3n,
    capturedAt: "2026-08-09T12:00:00.000Z",
    text: "line1\nline2",
    truncatedLines: true,
    truncatedBytes: false,
  };

  it("serves bounded history with a combined truncated flag", async () => {
    const { adapter, requests } = fixture(result);
    await open(adapter);
    const response = await adapter.history({ sessionId, terminalGeneration: "3" }, new AbortController().signal);
    expect(requests).toEqual([{ terminalGeneration: 3n, maxLines: MAX_TERMINAL_HISTORY_LINES, maxBytes: MAX_TERMINAL_HISTORY_BYTES }]);
    expect(response).toMatchObject({
      terminalGeneration: "3",
      capturedAt: "2026-08-09T12:00:00.000Z",
      text: "line1\nline2",
      truncated: true,
      truncatedLines: true,
      truncatedBytes: false,
    });
  });

  it("clamps device-requested history bounds to 5000 lines and 1 MiB", async () => {
    const { adapter, requests } = fixture({ ...result, truncatedLines: false });
    await open(adapter);
    const response = await adapter.history(
      { sessionId, terminalGeneration: "3", maxLines: 999_999, maxBytes: 128 },
      new AbortController().signal,
    );
    expect(requests[0]?.maxLines).toBe(MAX_TERMINAL_HISTORY_LINES);
    expect(requests[0]?.maxBytes).toBe(128);
    expect(response["truncated"]).toBe(false);

    await adapter.history({ sessionId, terminalGeneration: "3", maxLines: 0 }, new AbortController().signal);
    expect(requests[1]?.maxLines).toBe(1);
    await expect(
      adapter.history({ sessionId, terminalGeneration: "3", maxBytes: 1.5 }, new AbortController().signal),
    ).rejects.toThrow(/invalid/);
  });

  it("requires an attached terminal for history", async () => {
    const { adapter } = fixture(result);
    await expect(
      adapter.history({ sessionId, terminalGeneration: "3" }, new AbortController().signal),
    ).rejects.toThrow(/not attached/);
  });
});
