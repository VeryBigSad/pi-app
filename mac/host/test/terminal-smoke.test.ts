import { access, mkdtemp, rm, stat } from "node:fs/promises";
import { arch, platform, tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { createLocalTerminalBackend, type TerminalBackend } from "../src/terminal/index.js";

const roots: string[] = [];
const backends: TerminalBackend[] = [];

afterEach(async () => {
  await Promise.allSettled(backends.splice(0).map((backend) => backend.stop()));
  await Promise.allSettled(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("real local terminal smoke", () => {
  it.skipIf(platform() !== "darwin" || arch() !== "arm64")("runs node-pty through a private tmux socket when tmux is installed", async () => {
    const root = await mkdtemp(join(tmpdir(), "terminal-real-smoke-"));
    roots.push(root);
    const availability = await createLocalTerminalBackend();
    if (availability.state === "unsupported") {
      expect(availability.code).toBe("TMUX_NOT_FOUND");
      return;
    }
    const backend = availability.backend;
    backends.push(backend);
    const session = await backend.createSession({
      sessionId: "real-smoke",
      command: "/bin/cat",
      args: [],
      cwd: root,
      columns: 80,
      rows: 24,
    });
    const markers: string[] = [];
    let output = Buffer.alloc(0);
    const attachment = await session.attach({
      columns: 80,
      rows: 24,
      onMarker: (marker) => { markers.push(marker.type); },
      onOutput: (value) => {
        output = Buffer.concat([output, Buffer.from(value.bytes)]);
        if (output.byteLength > 256 * 1_024) output = Buffer.from(output.subarray(output.byteLength - 256 * 1_024));
      },
    });
    const input = Buffer.from("PI_INPUT_π\n");
    await attachment.send({ terminalGeneration: attachment.terminalGeneration, sequence: 0n, bytes: input });
    await waitFor(() => output.includes(Buffer.from("PI_INPUT_π")));

    let history = await session.history({ terminalGeneration: attachment.terminalGeneration, maxLines: 5_000, maxBytes: 1_024 * 1_024 });
    await waitFor(async () => {
      history = await session.history({ terminalGeneration: attachment.terminalGeneration, maxLines: 5_000, maxBytes: 1_024 * 1_024 });
      return history.text.includes("PI_INPUT_π");
    });
    expect(history.text).toContain("PI_INPUT_π");
    await attachment.detach();
    const reconnect = await session.attach({
      columns: 90,
      rows: 30,
      onMarker: (marker) => { markers.push(marker.type); },
      onOutput: () => undefined,
    });
    expect(reconnect.terminalGeneration).toBe(2n);
    expect(markers).toContain("reconnected");
    const runtime = backend.runtimeDirectory();
    if (runtime === undefined) throw new Error("terminal runtime missing");
    expect((await stat(runtime)).mode & 0o777).toBe(0o700);
    expect((await stat(backend.socketPath())).mode & 0o777).toBe(0o700);
    await session.cancel();

    const injectionMarker = join(root, "must-not-exist");
    const exited = await backend.createSession({
      sessionId: "real-exit-smoke",
      command: "/usr/bin/printf",
      args: [`literal;touch ${injectionMarker}`],
      cwd: root,
      columns: 80,
      rows: 24,
    });
    await expect(exited.waitForExit()).resolves.toMatchObject({ exitCode: 0, cancelled: false });
    await expect(access(injectionMarker)).rejects.toMatchObject({ code: "ENOENT" });
    await exited.cancel();
  }, 10_000);
});

async function waitFor(predicate: () => boolean | Promise<boolean>): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (!await predicate()) {
    if (Date.now() > deadline) throw new Error("terminal smoke timeout");
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 10));
  }
}
