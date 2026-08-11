import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  TerminalBackend,
  TerminalError,
  createLocalTerminalBackend,
  encodeKittyKey,
  type PtySpawnOptions,
  type TerminalPty,
  type TerminalPtyFactory,
  type TmuxChildProcess,
  type TmuxProcessFactory,
  type TmuxRunOptions,
  type TmuxRunResult,
} from "../src/terminal/index.js";

const roots: string[] = [];
const backends: TerminalBackend[] = [];

class FakePty implements TerminalPty {
  readonly pid = 100;
  readonly writes: Buffer[] = [];
  readonly resizes: [number, number][] = [];
  readonly kills: NodeJS.Signals[] = [];
  pauseCount = 0;
  resumeCount = 0;
  private readonly dataListeners = new Set<(bytes: Uint8Array) => void>();
  private readonly exitListeners = new Set<(event: { readonly exitCode: number; readonly signal?: number }) => void>();

  write(bytes: Uint8Array): void { this.writes.push(Buffer.from(bytes)); }
  resize(columns: number, rows: number): void { this.resizes.push([columns, rows]); }
  pause(): void { this.pauseCount += 1; }
  resume(): void { this.resumeCount += 1; }
  kill(signal: NodeJS.Signals = "SIGHUP"): void { this.kills.push(signal); }
  onData(listener: (bytes: Uint8Array) => void): { dispose(): void } {
    this.dataListeners.add(listener);
    return { dispose: () => this.dataListeners.delete(listener) };
  }
  onExit(listener: (event: { readonly exitCode: number; readonly signal?: number }) => void): { dispose(): void } {
    this.exitListeners.add(listener);
    return { dispose: () => this.exitListeners.delete(listener) };
  }
  emitData(bytes: Uint8Array): void { for (const listener of this.dataListeners) listener(Buffer.from(bytes)); }
  emitExit(exitCode = 0, signal?: number): void {
    for (const listener of this.exitListeners) listener(signal === undefined ? { exitCode } : { exitCode, signal });
  }
}

class FakePtyFactory implements TerminalPtyFactory {
  readonly spawns: { executable: string; args: readonly string[]; options: PtySpawnOptions; pty: FakePty }[] = [];

  spawn(executable: string, args: readonly string[], options: PtySpawnOptions): TerminalPty {
    const pty = new FakePty();
    this.spawns.push({ executable, args: [...args], options, pty });
    return pty;
  }
}

class FakeChild implements TmuxChildProcess {
  readonly writes: string[] = [];
  autoAck = true;
  killed = false;
  private readonly dataListeners = new Set<(bytes: Uint8Array) => void>();
  private readonly exitListeners = new Set<(event: { readonly exitCode: number | null; readonly signal: NodeJS.Signals | null }) => void>();

  write(bytes: Uint8Array): Promise<void> {
    this.writes.push(Buffer.from(bytes).toString("utf8"));
    if (this.autoAck) queueMicrotask(() => this.ack());
    return Promise.resolve();
  }
  kill(): void { this.killed = true; }
  onData(listener: (bytes: Uint8Array) => void): { dispose(): void } {
    this.dataListeners.add(listener);
    return { dispose: () => this.dataListeners.delete(listener) };
  }
  onExit(listener: (event: { readonly exitCode: number | null; readonly signal: NodeJS.Signals | null }) => void): { dispose(): void } {
    this.exitListeners.add(listener);
    return { dispose: () => this.exitListeners.delete(listener) };
  }
  ready(): void { this.emit("%begin 1 1 0\n%end 1 1 0\n"); }
  ack(): void { this.emit("%begin 1 2 0\n%end 1 2 0\n"); }
  fail(): void { this.emit("%begin 1 2 0\n%error 1 2 0\n"); }
  paneDied(): void { this.emit("%subscription-changed pimobile_exit $0 - - - : 1:0:\n"); }
  exit(exitCode: number | null, signal: NodeJS.Signals | null = null): void {
    for (const listener of this.exitListeners) listener({ exitCode, signal });
  }
  private emit(text: string): void { for (const listener of this.dataListeners) listener(Buffer.from(text)); }
}

class FakeTmux implements TmuxProcessFactory {
  readonly runs: { args: readonly string[]; options: TmuxRunOptions }[] = [];
  readonly spawns: { args: readonly string[]; child: FakeChild }[] = [];
  history = Buffer.from("");
  historyBytes: number | undefined;
  historyLines: number | undefined;
  deadStatus = "0::";

  async run(args: readonly string[], options: TmuxRunOptions = {}): Promise<TmuxRunResult> {
    this.runs.push({ args: [...args], options });
    if (args.includes("new-session")) {
      const socketFlag = args.indexOf("-S");
      const socket = args[socketFlag + 1];
      if (socket !== undefined) await writeFile(socket, "");
    }
    let stdout = Buffer.alloc(0);
    let stdoutBytes = 0;
    let stdoutLines = 0;
    if (args.includes("capture-pane")) {
      const limit = options.captureBytes ?? this.history.byteLength;
      stdout = options.captureMode === "tail"
        ? Buffer.from(this.history.subarray(Math.max(0, this.history.byteLength - limit)))
        : Buffer.from(this.history.subarray(0, limit));
      stdoutBytes = this.historyBytes ?? this.history.byteLength;
      stdoutLines = this.historyLines ?? countLines(this.history);
    }
    if (args.includes("display-message")) {
      stdout = Buffer.from(`${this.deadStatus}\n`);
      stdoutBytes = stdout.byteLength;
      stdoutLines = 1;
    }
    return {
      exitCode: 0,
      signal: null,
      stdout,
      stdoutBytes,
      stdoutLines,
      stdoutTruncated: stdoutBytes > stdout.byteLength,
    };
  }

  spawn(args: readonly string[]): TmuxChildProcess {
    const child = new FakeChild();
    this.spawns.push({ args: [...args], child });
    if (args.includes("-C")) queueMicrotask(() => child.ready());
    return child;
  }

  control(): FakeChild {
    const child = this.spawns.find((spawn) => spawn.args.includes("-C"))?.child;
    if (child === undefined) throw new Error("missing control child");
    return child;
  }

}

afterEach(async () => {
  await Promise.allSettled(backends.splice(0).map((backend) => backend.stop()));
  await Promise.allSettled(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("terminal backend lifecycle", () => {
  it("uses a private socket/config and preserves exact command argv without a shell", async () => {
    const fixture = await createFixture();
    const cwd = join(fixture.root, "work dir");
    const commandArgs = ["a;touch injected", "$(uname)", "quote'arg", "", "line\narg"];
    const session = await fixture.backend.createSession({
      sessionId: "opaque-session",
      command: "/usr/bin/printf",
      args: commandArgs,
      cwd,
      env: { EXACT_VALUE: "x;$(ignored)" },
      columns: 80,
      rows: 24,
    });
    const create = required(fixture.tmux.runs.find((run) => run.args.includes("respawn-pane")));
    const separator = create.args.indexOf("--");
    expect(create.args.slice(separator)).toEqual(["--", "/usr/bin/env", "--", "/usr/bin/printf", ...commandArgs]);
    expect(create.args).toContain("EXACT_VALUE=x;$(ignored)");
    expect(create.args).toContain(cwd);

    const runtime = required(fixture.backend.runtimeDirectory());
    expect((await stat(runtime)).mode & 0o777).toBe(0o700);
    expect((await stat(fixture.backend.socketPath())).mode & 0o777).toBe(0o700);
    expect((await stat(join(runtime, "tmux.conf"))).mode & 0o777).toBe(0o600);
    const config = await readFile(join(runtime, "tmux.conf"), "utf8");
    expect(config).toContain("history-limit 5000");
    expect(config).toContain("extended-keys-format csi-u");
    expect(config).toContain("remain-on-exit on");

    const attachment = await session.attach({ columns: 81, rows: 25, onOutput: () => undefined });
    const spawn = required(fixture.pty.spawns[0]);
    expect(spawn.executable).toBe("/opt/homebrew/bin/tmux");
    expect(spawn.args).toContain(fixture.backend.socketPath());
    await attachment.detach();
    expect(fixture.tmux.runs.some((run) => run.args.includes("kill-session"))).toBe(false);
    await session.cancel();
    expect(fixture.tmux.runs.some((run) => run.args.includes("kill-session"))).toBe(true);
  });

  it("creates one fresh generation per attachment and backpressures exact binary output", async () => {
    const fixture = await createFixture();
    const session = await createSession(fixture);
    const outputs: { sequence: bigint; bytes: Buffer }[] = [];
    const markers: string[] = [];
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolvePromise) => { releaseFirst = resolvePromise; });
    const first = await session.attach({
      columns: 80,
      rows: 24,
      onMarker: (marker) => { markers.push(`${marker.type}:${String(marker.terminalGeneration)}`); },
      onOutput: async (output) => {
        outputs.push({ sequence: output.sequence, bytes: Buffer.from(output.bytes) });
        if (output.sequence === 0n) await firstGate;
      },
    });
    const firstPty = required(fixture.pty.spawns[0]).pty;
    firstPty.emitData(Buffer.concat([Buffer.from([0, 255, 0xc3, 0x28]), Buffer.alloc(70_000, 0xa5)]));
    await waitFor(() => outputs.length === 1);
    expect(firstPty.pauseCount).toBeGreaterThanOrEqual(2);
    releaseFirst?.();
    await waitFor(() => outputs.length === 2);
    expect(outputs.map((output) => output.sequence)).toEqual([0n, 1n]);
    expect(Buffer.concat(outputs.map((output) => output.bytes))).toEqual(Buffer.concat([Buffer.from([0, 255, 0xc3, 0x28]), Buffer.alloc(70_000, 0xa5)]));
    expect(firstPty.resumeCount).toBeGreaterThanOrEqual(2);

    await first.detach();
    const second = await session.attach({
      columns: 90,
      rows: 30,
      onMarker: (marker) => { markers.push(`${marker.type}:${String(marker.terminalGeneration)}`); },
      onOutput: () => undefined,
    });
    expect(first.terminalGeneration).toBe(1n);
    expect(second.terminalGeneration).toBe(2n);
    expect(markers).toContain("attached:1");
    expect(markers).toContain("reconnected:2");
    firstPty.emitData(Buffer.from("stale"));
    expect(outputs.some((output) => output.bytes.equals(Buffer.from("stale")))).toBe(false);
  });

  it("bounds pending output and detaches with an explicit resource marker", async () => {
    const fixture = await createFixture();
    const session = await createSession(fixture);
    const markers: string[] = [];
    await session.attach({
      columns: 80,
      rows: 24,
      onOutput: () => new Promise<void>(() => undefined),
      onMarker: (marker) => { markers.push(`${marker.type}:${marker.reason}`); },
    });
    const pty = required(fixture.pty.spawns[0]).pty;
    pty.emitData(Buffer.alloc(1_024 * 1_024 + 1));
    await waitFor(() => pty.kills.length > 0);
    expect(markers).toContain("reset_required:resource_exhausted");
  });

  it("serializes raw/key input, injects Kitty bytes through control, resizes, and resets gaps", async () => {
    const fixture = await createFixture();
    const session = await createSession(fixture);
    const markers: string[] = [];
    const attachment = await session.attach({
      columns: 80,
      rows: 24,
      onOutput: () => undefined,
      onMarker: (marker) => { markers.push(`${marker.type}:${marker.reason}`); },
    });
    const pty = required(fixture.pty.spawns[0]).pty;
    await attachment.send({ terminalGeneration: 1n, sequence: 0n, bytes: Buffer.from([0, 255, 1]) });
    expect(pty.writes[0]).toEqual(Buffer.from([0, 255, 1]));

    const control = fixture.tmux.control();
    control.autoAck = false;
    const firstKey = attachment.sendKey({
      terminalGeneration: 1n,
      sequence: 1n,
      key: "ArrowUp",
      action: "up",
      modifiers: ["control"],
    });
    await waitFor(() => control.writes.some((write) => write.includes("send-keys")));
    const writesBeforeSecond = control.writes.length;
    const secondKey = attachment.sendKey({
      terminalGeneration: 1n,
      sequence: 2n,
      key: "π",
      action: "text",
      modifiers: [],
    });
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 10));
    expect(control.writes).toHaveLength(writesBeforeSecond);
    control.ack();
    await Promise.all([firstKey, secondKey]);
    expect(control.writes.at(-1)).toContain("send-keys -H -t pmABC:0.0 1b 5b");
    expect(pty.writes.at(-1)).toEqual(Buffer.from("π"));

    control.autoAck = true;
    await attachment.resize(1n, 120, 40);
    expect(pty.resizes.at(-1)).toEqual([120, 40]);
    expect(fixture.tmux.runs.some((run) => run.args.includes("resize-window") && run.args.includes("120") && run.args.includes("40"))).toBe(true);

    await expect(attachment.send({ terminalGeneration: 1n, sequence: 4n, bytes: Buffer.from("gap") })).rejects.toMatchObject({ code: "TERMINAL_RESET_REQUIRED" });
    await waitFor(() => pty.kills.length > 0);
    expect(markers).toContain("reset_required:sequence_gap");
  });

  it("reports tmux pane exit and cancellation without surfacing process output", async () => {
    const fixture = await createFixture();
    const session = await createSession(fixture);
    const markers: string[] = [];
    await session.attach({
      columns: 80,
      rows: 24,
      onOutput: () => undefined,
      onMarker: (marker) => { markers.push(marker.type); },
    });
    fixture.tmux.deadStatus = "1:17:9";
    fixture.tmux.control().paneDied();
    await expect(session.waitForExit()).resolves.toEqual({ exitCode: 17, signal: 9, cancelled: false });
    expect(markers).toContain("process_exit");

    const second = await fixture.backend.createSession({
      sessionId: "cancelled",
      command: "/usr/bin/true",
      cwd: fixture.root,
      columns: 80,
      rows: 24,
    });
    await second.cancel();
    await expect(second.waitForExit()).resolves.toEqual({ exitCode: null, signal: null, cancelled: true });
  });
});

describe("terminal key encoding", () => {
  it("matches Kitty CSI-u and legacy functional event encodings", () => {
    expect(Buffer.from(encodeKittyKey({ terminalGeneration: 1n, sequence: 0n, key: "ArrowUp", action: "down", modifiers: [] })).toString("ascii")).toBe("\u001b[A");
    expect(Buffer.from(encodeKittyKey({ terminalGeneration: 1n, sequence: 1n, key: "ArrowUp", action: "up", modifiers: ["control"] })).toString("ascii")).toBe("\u001b[1;5:3A");
    expect(Buffer.from(encodeKittyKey({ terminalGeneration: 1n, sequence: 2n, key: "F13", action: "up", modifiers: [] })).toString("ascii")).toBe("\u001b[57376;1:3u");
    expect(Buffer.from(encodeKittyKey({ terminalGeneration: 1n, sequence: 3n, key: "A", action: "repeat", modifiers: ["shift"] })).toString("ascii")).toBe("\u001b[65;2:2u");
  });
});

describe("terminal history and availability", () => {
  it("returns only the newest bounded line/UTF-8 byte tail with truncation markers", async () => {
    const fixture = await createFixture();
    const session = await createSession(fixture);
    const attachment = await session.attach({ columns: 80, rows: 24, onOutput: () => undefined });
    fixture.tmux.history = Buffer.from("old\nππ\nnewest\n", "utf8");
    fixture.tmux.historyBytes = fixture.tmux.history.byteLength;
    fixture.tmux.historyLines = 3;
    const result = await session.history({ terminalGeneration: attachment.terminalGeneration, maxLines: 2, maxBytes: 10 });
    expect(Buffer.byteLength(result.text, "utf8")).toBeLessThanOrEqual(10);
    expect(result.text).toContain("newest");
    expect(result.truncatedLines).toBe(true);
    expect(result.truncatedBytes).toBe(true);
    expect(result.capturedAt).toBe("2026-08-09T00:00:00.000Z");
    const capture = fixture.tmux.runs.find((run) => run.args.includes("capture-pane"));
    expect(capture?.args).toContain("-3");
    await expect(session.history({ terminalGeneration: 99n, maxLines: 1, maxBytes: 1 })).rejects.toMatchObject({ code: "TERMINAL_NOT_ATTACHED" });
  });

  it("exposes explicit unsupported host and missing-tmux states", async () => {
    await expect(createLocalTerminalBackend({ platform: "linux", arch: "arm64", release: "23.0.0" })).resolves.toEqual({
      state: "unsupported",
      code: "HOST_UNSUPPORTED",
    });
    await expect(createLocalTerminalBackend({
      platform: "darwin",
      arch: "arm64",
      release: "23.0.0",
      tmuxExecutable: join(tmpdir(), `missing-tmux-${String(Date.now())}`),
    })).resolves.toEqual({ state: "unsupported", code: "TMUX_NOT_FOUND" });
  });
});

async function createFixture(): Promise<{ root: string; tmux: FakeTmux; pty: FakePtyFactory; backend: TerminalBackend }> {
  const root = await mkdtemp(join(tmpdir(), "terminal-backend-test-"));
  roots.push(root);
  const tmux = new FakeTmux();
  const pty = new FakePtyFactory();
  const backend = new TerminalBackend({
    tmux,
    pty,
    tmuxVersion: "tmux 3.5a",
    tmuxExecutable: "/opt/homebrew/bin/tmux",
    runtimeParent: "/tmp",
    now: () => new Date("2026-08-09T00:00:00Z"),
    randomId: () => "ABC",
  });
  backends.push(backend);
  return { root, tmux, pty, backend };
}

async function createSession(fixture: Awaited<ReturnType<typeof createFixture>>) {
  return await fixture.backend.createSession({
    sessionId: "session",
    command: "/usr/bin/true",
    cwd: fixture.root,
    columns: 80,
    rows: 24,
  });
}

function required<T>(value: T | undefined): T {
  if (value === undefined) throw new Error("required test value missing");
  return value;
}

function countLines(bytes: Buffer): number {
  if (bytes.byteLength === 0) return 0;
  let lines = 0;
  for (const byte of bytes) if (byte === 0x0a) lines += 1;
  return lines + (bytes[bytes.byteLength - 1] === 0x0a ? 0 : 1);
}

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new TerminalError("TERMINAL_PROCESS_FAILED");
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1));
  }
}
