import { describe, expect, it } from "vitest";
import { TerminalRuntimeAdapter } from "../src/daemon/terminal-runtime.js";
import type { TerminalBackend, TerminalSession } from "../src/terminal/backend.js";
import type { TerminalChannel } from "../src/gateway/types.js";
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

async function open(adapter: TerminalRuntimeAdapter): Promise<TerminalChannel> {
  const opened = await adapter.open(
    { sessionId, columns: 80, rows: 24 },
    { write: () => Promise.resolve(), reset: () => Promise.resolve() },
    new AbortController().signal,
  );
  return opened.channel;
}

describe("terminal history gateway runtime", () => {
  const result: TerminalHistoryResult = {
    terminalGeneration: 3n,
    capturedAt: "2026-08-09T12:00:00.000Z",
    text: "line1\nline2",
    truncatedLines: true,
    truncatedBytes: false,
  };

  it("serves the documented bounded tmux capture", async () => {
    const { adapter, requests } = fixture(result);
    const channel = await open(adapter);
    const response = await channel.history?.(
      { sessionId, terminalGeneration: "3", maxLines: MAX_TERMINAL_HISTORY_LINES, maxBytes: MAX_TERMINAL_HISTORY_BYTES },
      new AbortController().signal,
    );
    if (response === undefined) throw new Error("history capability missing");
    expect(requests).toEqual([{ terminalGeneration: 3n, maxLines: MAX_TERMINAL_HISTORY_LINES, maxBytes: MAX_TERMINAL_HISTORY_BYTES }]);
    expect(response).toEqual({
      sessionId,
      terminalGeneration: "3",
      capturedAt: "2026-08-09T12:00:00.000Z",
      text: "line1\nline2",
      truncatedLines: true,
      truncatedBytes: false,
    });
  });

  it("reduces a capture to the PIMB JSON bound and marks byte truncation", async () => {
    const { adapter } = fixture({ ...result, text: "π".repeat(200_000), truncatedBytes: false });
    const channel = await open(adapter);
    const response = await channel.history?.(
      { sessionId, terminalGeneration: "3", maxLines: MAX_TERMINAL_HISTORY_LINES, maxBytes: MAX_TERMINAL_HISTORY_BYTES },
      new AbortController().signal,
    );
    if (response === undefined) throw new Error("history capability missing");
    expect(Buffer.byteLength(JSON.stringify(response), "utf8")).toBeLessThanOrEqual(256 * 1024);
    expect(response["truncatedBytes"]).toBe(true);
  });

  it("rejects missing, out-of-range, and noncanonical history request fields", async () => {
    const { adapter } = fixture(result);
    const channel = await open(adapter);
    const history = (request: Parameters<NonNullable<TerminalChannel["history"]>>[0], signal: AbortSignal) => {
      if (channel.history === undefined) throw new Error("history capability missing");
      return channel.history(request, signal);
    };
    const signal = new AbortController().signal;
    await expect(history({ sessionId, terminalGeneration: "3", maxLines: 0, maxBytes: 1 }, signal)).rejects.toThrow(/bound is invalid/);
    await expect(history({ sessionId, terminalGeneration: "3", maxLines: 1, maxBytes: 1_048_577 }, signal)).rejects.toThrow(/bound is invalid/);
    await expect(history({ sessionId, terminalGeneration: "01", maxLines: 1, maxBytes: 1 }, signal)).rejects.toThrow(/capability is stale/);
    await expect(history({ sessionId, terminalGeneration: "18446744073709551616", maxLines: 1, maxBytes: 1 }, signal)).rejects.toThrow(/capability is stale/);
    await expect(history({ sessionId, terminalGeneration: "3", maxLines: 1 }, signal)).rejects.toThrow(/bound is invalid/);
  });

  it("binds history to each connection session and generation capability", async () => {
    const { adapter } = fixture(result);
    const firstConnection = await open(adapter);
    const secondConnection = await open(adapter);
    const signal = new AbortController().signal;
    await expect(firstConnection.history?.({
      sessionId: "650e8400-e29b-41d4-a716-446655440060",
      terminalGeneration: "3",
      maxLines: 1,
      maxBytes: 1,
    }, signal)).rejects.toThrow(/capability is stale/);
    await expect(secondConnection.history?.({
      sessionId,
      terminalGeneration: "4",
      maxLines: 1,
      maxBytes: 1,
    }, signal)).rejects.toThrow(/capability is stale/);
  });
});
