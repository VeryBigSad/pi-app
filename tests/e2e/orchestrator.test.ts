import { createHash } from "node:crypto";
import { access, chmod, mkdir, mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  CleanupStack,
  acquireRunLock,
  installedStackInstrumentationArgs,
  instrumentationFailureCode,
  preflight,
  registerHostAuthenticationRecovery,
  requireDestructiveSessionOptIn,
  instrumentationPassed,
  parseHookResults,
  releaseContainsE2eClasses,
  runInstalledStack,
  stageOneUseChannel,
  uninstallPackage,
  type AdminClient,
  type ProcessOptions,
  type ProcessResult,
  type ProcessRunner,
} from "./orchestrator.js";

class SequenceRunner implements ProcessRunner {
  readonly calls: { readonly command: string; readonly args: readonly string[] }[] = [];

  constructor(private readonly results: readonly ProcessResult[]) {}

  run(command: string, args: readonly string[]): Promise<ProcessResult> {
    this.calls.push({ command, args });
    const result = this.results[this.calls.length - 1];
    if (result === undefined) throw new Error("unexpected process call");
    return Promise.resolve(result);
  }
}

class FailingAdminClient implements AdminClient {
  readonly calls: string[] = [];

  call(method: string): Promise<unknown> {
    this.calls.push(method);
    return Promise.reject(new Error(`unexpected admin call: ${method}`));
  }
}

class RecordingRunner implements ProcessRunner {
  readonly calls: { readonly command: string; readonly args: readonly string[]; readonly options?: ProcessOptions }[] = [];
  pushedBytes?: Uint8Array;
  pushedMode?: number;

  constructor(
    private readonly failAt = -1,
    private readonly remote?: { readonly bytes: number; readonly digest: string },
  ) {}

  async run(command: string, args: readonly string[], options?: ProcessOptions): Promise<ProcessResult> {
    this.calls.push({ command, args, ...(options === undefined ? {} : { options }) });
    if (args.includes("push")) {
      const localPath = args.at(-2);
      if (localPath !== undefined) {
        const metadata = await stat(localPath);
        this.pushedMode = metadata.mode & 0o777;
        this.pushedBytes = await readFile(localPath);
      }
    }
    const failed = this.calls.length === this.failAt;
    const remotePath = args.at(-1) ?? "";
    const stdout = args.includes("stat")
      ? `600:${String(this.remote?.bytes ?? 0)}\n`
      : args.includes("sha256sum")
        ? `${this.remote?.digest ?? "0".repeat(64)}  ${remotePath}\n`
        : "";
    return {
      code: failed ? 1 : 0,
      stdout,
      stderr: "",
    };
  }
}

describe("installed-stack orchestration", () => {
  it("serializes destructive runs per emulator and releases idempotently", async () => {
    const serial = `test-${String(process.pid)}-${String(Date.now())}`;
    const first = await acquireRunLock(serial);
    await expect(acquireRunLock(serial)).rejects.toThrow("E2E_RUN_ALREADY_ACTIVE");
    await first.release();
    await first.release();
    const second = await acquireRunLock(serial);
    await second.release();
  });

  it("runs teardown in reverse order, continues after failure, and is idempotent", async () => {
    const order: string[] = [];
    const cleanup = new CleanupStack();
    cleanup.defer("first", () => {
      order.push("first");
      return Promise.resolve();
    });
    cleanup.defer("second", () => {
      order.push("second");
      return Promise.reject(new Error("sensitive detail"));
    });
    cleanup.defer("third", () => {
      order.push("third");
      return Promise.resolve();
    });

    expect(await cleanup.close()).toEqual([{ name: "second", code: "E2E_INTERNAL" }]);
    expect(order).toEqual(["third", "second", "first"]);
    expect(await cleanup.close()).toEqual([]);
  });

  it("restores host state and daemon when isolation fails after daemon stop", async () => {
    const original = {
      ownerCredentialCount: 1,
      deviceCount: 2,
      instanceId: "00000000-0000-4000-8000-000000000001",
      routeId: "route",
    };
    const snapshot = { directory: "/private/backup", summary: original };
    const state = { snapshot };
    const order: string[] = [];
    const cleanup = new CleanupStack();
    registerHostAuthenticationRecovery(cleanup, original, state, {
      stopIfRunning: () => {
        order.push("stop");
        return Promise.resolve();
      },
      restore: (received) => {
        expect(received).toBe(snapshot);
        order.push("restore");
        return Promise.resolve();
      },
      startOrVerify: () => {
        order.push("start");
        return Promise.resolve();
      },
      awaitReady: () => {
        order.push("ready");
        return Promise.resolve({ relay: { state: "ready" } });
      },
      inspect: () => {
        order.push("inspect");
        return Promise.resolve(original);
      },
    });

    expect(await cleanup.close()).toEqual([]);
    expect(state.snapshot).toBeUndefined();
    expect(order).toEqual(["stop", "restore", "start", "ready", "inspect"]);
  });

  it("atomically stages and verifies a mode-0600 one-use channel without exposing its payload", async () => {
    const invitation = "pimobile://pair?v=1&d=secret-invitation";
    const payload = { invitationUri: invitation };
    const expectedBytes = Buffer.from(JSON.stringify(payload), "utf8");
    const expectedDigest = createHash("sha256").update(expectedBytes).digest("hex");
    const runner = new RecordingRunner(-1, { bytes: expectedBytes.byteLength, digest: expectedDigest });
    const cleanup = new CleanupStack();
    const remotePath = await stageOneUseChannel(
      runner,
      cleanup,
      { repositoryRoot: "/repo", serial: "emulator-5554" },
      payload,
    );

    expect(remotePath).toMatch(/^\/data\/local\/tmp\/pi-mobile-e2e-[A-Za-z0-9_-]{43}\/config$/u);
    expect(runner.calls.map((call) => call.args.join(" ")).join("\n")).not.toContain(invitation);
    expect(runner.calls.flatMap((call) => call.args)).not.toContain("sh");
    expect(runner.pushedMode).toBe(0o600);
    expect(runner.pushedBytes).toEqual(expectedBytes);
    const push = runner.calls.find((call) => call.args.includes("push"));
    expect(push).toBeDefined();
    const localPath = push?.args.at(-2);
    expect(localPath).toBeDefined();
    await expect(access(localPath ?? "")).rejects.toThrow();
    expect(runner.calls.some((call) => call.args.includes("stat"))).toBe(true);
    expect(runner.calls.some((call) => call.args.includes("sha256sum"))).toBe(true);

    expect(await cleanup.close()).toEqual([]);
    expect(runner.calls.at(-1)?.args).toEqual([
      "-s", "emulator-5554", "shell", "rm", "-rf", remotePath.slice(0, remotePath.lastIndexOf("/")),
    ]);
  });

  it("keeps remote cleanup registered when channel upload fails", async () => {
    const runner = new RecordingRunner(2);
    const cleanup = new CleanupStack();
    await expect(stageOneUseChannel(
      runner,
      cleanup,
      { repositoryRoot: "/repo", serial: "emulator-5554" },
      { invitationUri: "opaque" },
    )).rejects.toThrow("E2E_CHANNEL_PUSH_FAILED");

    expect(await cleanup.close()).toEqual([]);
    expect(runner.calls.at(-1)?.args.slice(0, 5)).toEqual(["-s", "emulator-5554", "shell", "rm", "-rf"]);
  });

  it("fails closed when the remote channel byte count or digest differs", async () => {
    const payload = { invitationUri: "opaque" };
    const bytes = Buffer.from(JSON.stringify(payload), "utf8");
    const digest = createHash("sha256").update(bytes).digest("hex");
    const sizeCleanup = new CleanupStack();
    await expect(stageOneUseChannel(
      new RecordingRunner(-1, { bytes: bytes.byteLength + 1, digest }),
      sizeCleanup,
      { repositoryRoot: "/repo", serial: "emulator-5554" },
      payload,
    )).rejects.toThrow("E2E_CHANNEL_REMOTE_SIZE_MISMATCH");
    expect(await sizeCleanup.close()).toEqual([]);

    const digestCleanup = new CleanupStack();
    await expect(stageOneUseChannel(
      new RecordingRunner(-1, { bytes: bytes.byteLength, digest: "0".repeat(64) }),
      digestCleanup,
      { repositoryRoot: "/repo", serial: "emulator-5554" },
      payload,
    )).rejects.toThrow("E2E_CHANNEL_REMOTE_DIGEST_MISMATCH");
    expect(await digestCleanup.close()).toEqual([]);
  });

  it("rejects an oversized channel before transfer", async () => {
    const runner = new RecordingRunner();
    const cleanup = new CleanupStack();
    await expect(stageOneUseChannel(
      runner,
      cleanup,
      { repositoryRoot: "/repo", serial: "emulator-5554" },
      { invitationUri: "x".repeat((64 << 10) + 1) },
    )).rejects.toThrow("E2E_CHANNEL_PAYLOAD_TOO_LARGE");
    expect(runner.calls).toEqual([]);
    expect(await cleanup.close()).toEqual([]);
  });

  it("skips uninstall when pm confirms the package is absent", async () => {
    const runner = new SequenceRunner([{ code: 0, stdout: "", stderr: "" }]);

    await uninstallPackage(runner, "emulator-5590", "io.github.verybigsad.pimobile.debug.test", "/repo");

    expect(runner.calls).toHaveLength(1);
    expect(runner.calls[0]?.args).toEqual([
      "-s", "emulator-5590", "shell", "pm", "path", "io.github.verybigsad.pimobile.debug.test",
    ]);
  });

  it("accepts an uninstall failure only when a postflight confirms absence", async () => {
    const packagePath = "package:/data/app/test/base.apk\n";
    const runner = new SequenceRunner([
      { code: 0, stdout: packagePath, stderr: "" },
      { code: 1, stdout: "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", stderr: "" },
      { code: 0, stdout: "", stderr: "" },
    ]);

    await uninstallPackage(runner, "emulator-5590", "io.github.verybigsad.pimobile.debug.test", "/repo");
    expect(runner.calls).toHaveLength(3);
  });

  it("rejects a failed uninstall when the package remains installed", async () => {
    const packagePath = "package:/data/app/test/base.apk\n";
    const runner = new SequenceRunner([
      { code: 0, stdout: packagePath, stderr: "" },
      { code: 1, stdout: "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", stderr: "" },
      { code: 0, stdout: packagePath, stderr: "" },
    ]);

    await expect(uninstallPackage(
      runner,
      "emulator-5590",
      "io.github.verybigsad.pimobile.debug.test",
      "/repo",
    )).rejects.toThrow("E2E_UNINSTALL_FAILED");
  });

  it("binds installed-stack execution to the random one-use channel token and selected hooks", () => {
    const token = "a".repeat(43);
    const sessionId = "00000000-0000-4000-8000-000000000001";
    const channel = `/data/local/tmp/pi-mobile-e2e-${token}/config`;
    const args = installedStackInstrumentationArgs("emulator-5590", channel, sessionId, ["voice", "push"]);

    expect(args).toContain(channel);
    expect(args.slice(args.indexOf("e2eRunToken") - 1, args.indexOf("e2eRunToken") + 2)).toEqual([
      "-e", "e2eRunToken", token,
    ]);
    expect(args.slice(args.indexOf("e2eSessionId") - 1, args.indexOf("e2eSessionId") + 2)).toEqual([
      "-e", "e2eSessionId", sessionId,
    ]);
    expect(args.slice(args.indexOf("e2eHooks") - 1, args.indexOf("e2eHooks") + 2)).toEqual([
      "-e", "e2eHooks", "voice,push",
    ]);
    expect(() => installedStackInstrumentationArgs("emulator-5590", "/data/local/tmp/config", sessionId, [])).toThrow(
      "E2E_CHANNEL_PATH_INVALID",
    );
    expect(() => installedStackInstrumentationArgs("emulator-5590", channel, sessionId, ["voice", "voice"])).toThrow(
      "E2E_HOOK_SELECTION_INVALID",
    );
  });

  it("accepts only a clean instrumentation completion and preserves a sanitized root failure", () => {
    expect(instrumentationPassed({ code: 0, stdout: "OK (3 tests)\n", stderr: "" })).toBe(true);
    expect(instrumentationFailureCode({ code: 0, stdout: "OK (3 tests)\n", stderr: "" })).toBeUndefined();
    expect(instrumentationPassed({ code: 0, stdout: "FAILURES!!!\nOK (2 tests)\n", stderr: "" })).toBe(false);
    expect(instrumentationFailureCode({
      code: 0,
      stdout: "java.lang.AssertionError: E2E_CHANNEL_EMPTY\nFAILURES!!!\n",
      stderr: "sensitive detail",
    })).toBe("E2E_CHANNEL_EMPTY");
    expect(instrumentationPassed({ code: 1, stdout: "OK (3 tests)\n", stderr: "" })).toBe(false);
    expect(instrumentationFailureCode({ code: 1, stdout: "", stderr: "Process crashed." })).toBe(
      "E2E_INSTRUMENTATION_PROCESS_CRASHED",
    );
    expect(instrumentationFailureCode({ code: 1, stdout: "", stderr: "sensitive detail" })).toBe("E2E_INSTRUMENTATION_FAILED");
  });

  it("records only selected hook outcomes with bounded stable failure codes", () => {
    expect(parseHookResults({
      version: 1,
      hooks: [
        { hook: "push", outcome: "passed" },
        { hook: "voice", outcome: "failed", failureCode: "E2E_VOICE_TRANSCRIPT_NOT_DELIVERED" },
      ],
    }, ["voice", "push"])).toEqual([
      { hook: "voice", outcome: "failed", failureCode: "E2E_VOICE_TRANSCRIPT_NOT_DELIVERED" },
      { hook: "push", outcome: "passed" },
      { hook: "external-push", outcome: "not-selected" },
    ]);
    expect(() => parseHookResults({
      version: 1,
      hooks: [{ hook: "voice", outcome: "failed", failureCode: "prompt leaked" }],
    }, ["voice"])).toThrow("E2E_HOOK_EVIDENCE_INVALID");
    expect(() => parseHookResults({
      version: 1,
      hooks: [{ hook: "push", outcome: "passed" }],
    }, [])).toThrow("E2E_HOOK_EVIDENCE_INVALID");
  });

  it("requires explicit opt-in before creating a host-owned disposable session", () => {
    expect(() => requireDestructiveSessionOptIn(false)).toThrow("E2E_DESTRUCTIVE_SESSION_OPT_IN_REQUIRED");
    expect(() => requireDestructiveSessionOptIn(true)).not.toThrow();
  });

  it("fails closed unless adb proves an allowlisted emulator identity", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-e2e-preflight-"));
    const dataDirectory = join(root, "data");
    const socketPath = join(dataDirectory, "run", "host-admin.sock");
    await mkdir(join(dataDirectory, "run"), { recursive: true, mode: 0o700 });
    const server = createServer();
    await new Promise<void>((resolveListen, rejectListen) => {
      server.once("error", rejectListen);
      server.listen(socketPath, resolveListen);
    });
    await chmod(socketPath, 0o600);
    const base = {
      repositoryRoot: "/repo",
      serial: "emulator-5590",
      dataDirectory,
      artifactRoot: "/artifacts",
      knownContent: "PONG",
      prompt: "prompt",
      expectedReply: "reply",
      terminalCanary: "canary",
      isolateHostAuthentication: true,
    };
    try {
      const runner = new SequenceRunner([
        { code: 0, stdout: "device\n", stderr: "" },
        { code: 0, stdout: "PiApp_API_29\r\nOK\r\n", stderr: "" },
        { code: 0, stdout: "1\n", stderr: "" },
        { code: 0, stdout: "\n", stderr: "" },
      ]);
      await expect(preflight(base, runner)).resolves.toBeUndefined();
      expect(runner.calls.map((call) => call.args.slice(2))).toEqual([
        ["get-state"],
        ["emu", "avd", "name"],
        ["shell", "getprop", "ro.kernel.qemu"],
        ["shell", "getprop", "ro.boot.qemu.avd_name"],
      ]);
      await expect(preflight({ ...base, serial: "R5CR123" }, new SequenceRunner([]))).rejects.toThrow("E2E_PHYSICAL_DEVICE_REJECTED");
      await expect(preflight(base, new SequenceRunner([
        { code: 0, stdout: "device\n", stderr: "" },
        { code: 0, stdout: "Untrusted_AVD\r\nOK\r\n", stderr: "" },
      ]))).rejects.toThrow("E2E_AVD_NOT_ALLOWLISTED");
      await expect(preflight(base, new SequenceRunner([
        { code: 0, stdout: "device\n", stderr: "" },
        { code: 0, stdout: "PiApp_API_29\r\nERROR\r\n", stderr: "" },
      ]))).rejects.toThrow("E2E_AVD_NOT_ALLOWLISTED");
      await expect(preflight(base, new SequenceRunner([
        { code: 0, stdout: "device\n", stderr: "" },
        { code: 0, stdout: "PiApp_API_29\r\nOK\r\nunexpected\r\n", stderr: "" },
      ]))).rejects.toThrow("E2E_AVD_NOT_ALLOWLISTED");
      await expect(preflight(base, new SequenceRunner([
        { code: 0, stdout: "device\n", stderr: "" },
        { code: 0, stdout: "PiApp_API_29\r\nOK\r\n", stderr: "" },
        { code: 0, stdout: "1\n", stderr: "" },
        { code: 0, stdout: "PiApp_API_34_AOSP\n", stderr: "" },
      ]))).rejects.toThrow("E2E_EMULATOR_IDENTITY_INVALID");
    } finally {
      await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
      await rm(root, { recursive: true, force: true });
    }
  });

  it("does not query or revoke devices after preflight fails before baseline capture", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-e2e-preflight-cleanup-"));
    const admin = new FailingAdminClient();
    try {
      await expect(runInstalledStack({
        repositoryRoot: "/repo",
        serial: "invalid serial",
        dataDirectory: join(root, "data"),
        artifactRoot: join(root, "artifacts"),
        knownContent: "PONG",
        prompt: "prompt",
        expectedReply: "reply",
        terminalCanary: "canary",
      }, new SequenceRunner([]), admin)).rejects.toThrow("E2E_SERIAL_INVALID");
      expect(admin.calls).toEqual([]);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it("does not query or revoke devices after an APK build fails before baseline capture", async () => {
    const root = await mkdtemp(join(tmpdir(), "e2e-"));
    const dataDirectory = join(root, "data");
    const socketPath = join(dataDirectory, "run", "host-admin.sock");
    await mkdir(join(dataDirectory, "run"), { recursive: true, mode: 0o700 });
    const server = createServer();
    await new Promise<void>((resolveListen, rejectListen) => {
      server.once("error", rejectListen);
      server.listen(socketPath, resolveListen);
    });
    await chmod(socketPath, 0o600);
    const admin = new FailingAdminClient();
    try {
      await expect(runInstalledStack({
        repositoryRoot: "/repo",
        serial: "emulator-5590",
        dataDirectory,
        artifactRoot: join(root, "artifacts"),
        knownContent: "PONG",
        prompt: "prompt",
        expectedReply: "reply",
        terminalCanary: "canary",
        allowDestructiveSession: true,
        isolateHostAuthentication: true,
      }, new SequenceRunner([
        { code: 0, stdout: "device\n", stderr: "" },
        { code: 0, stdout: "PiApp_API_29\r\nOK\r\n", stderr: "" },
        { code: 0, stdout: "1\n", stderr: "" },
        { code: 0, stdout: "\n", stderr: "" },
        { code: 1, stdout: "", stderr: "build failed" },
      ]), admin)).rejects.toThrow("E2E_ANDROID_BUILD_FAILED");
      expect(admin.calls).toEqual([]);
    } finally {
      await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
      await rm(root, { recursive: true, force: true });
    }
  });

  it("detects E2E packages and bridge classes in release package listings", () => {
    expect(releaseContainsE2eClasses("C io.github.verybigsad.pimobile.MainActivity")).toBe(false);
    expect(releaseContainsE2eClasses("C io.github.verybigsad.pimobile.e2e.InstalledStackE2eBridge")).toBe(true);
    expect(releaseContainsE2eClasses("C io.github.verybigsad.pimobile.InstalledStackE2eBridge")).toBe(true);
    expect(releaseContainsE2eClasses("C io.github.verybigsad.pimobile.testing.AppLaunchTestBridge")).toBe(true);
  });
});
