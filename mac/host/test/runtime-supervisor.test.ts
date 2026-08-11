import { access, mkdir, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdtemp } from "node:fs/promises";
import { afterEach, describe, expect, it } from "vitest";
import type { ApprovalOffer } from "@pimobile/approval";
import type { PatchManifest } from "@pimobile/pi-patch";
import {
  RuntimeSupervisor,
  type PiRuntimeProvisioner,
} from "../src/runtime/supervisor.js";

const manifest: PatchManifest = {
  piVersion: "0.84.0",
  originalAgentSessionSha256: "a".repeat(64),
  patchedAgentSessionSha256: "b".repeat(64),
  policyHookKey: "io.github.verybigsad.pimobile.policy.v1",
};
const preloadPath = resolve(dirname(fileURLToPath(import.meta.url)), "../../preload/dist/index.js");
const roots: string[] = [];
const supervisors: RuntimeSupervisor[] = [];

const fakePi = `
let input = "";
const hookSymbol = Symbol.for("io.github.verybigsad.pimobile.policy.v1");
const reply = (id, success, data) => process.stdout.write(JSON.stringify({type:"response", id, success, data}) + "\\n");
process.stdin.on("data", chunk => {
  input += chunk.toString("utf8");
  for (;;) {
    const newline = input.indexOf("\\n");
    if (newline < 0) return;
    const line = input.slice(0, newline);
    input = input.slice(newline + 1);
    const command = JSON.parse(line);
    void (async () => {
      if (command.type === "inspect") {
        const args = process.argv.slice(2);
        reply(command.id, true, {
          args,
          cwd: process.cwd(),
          configuredAgent: process.env.PI_CODING_AGENT_DIR === "/Users/test/.pi/agent",
          packageConfig: process.env.PI_PACKAGE_DIR === "/Users/test/.pi/packages",
          credentialPresent: process.env.TEST_PROVIDER_CREDENTIAL === "local-only-secret",
          existingNodeOptionsPreserved: process.env.NODE_OPTIONS.includes("--trace-warnings"),
          preloadPresent: process.env.NODE_OPTIONS.includes("preload/dist/index.js"),
          socketPath: process.env.PI_MOBILE_APPROVAL_SOCKET,
          connectionId: process.env.PI_MOBILE_CONNECTION_ID,
        });
        return;
      }
      if (command.type === "dangerous") {
        try {
          await globalThis[hookSymbol]({
            version: 1,
            kind: "bash",
            operationId: command.id,
            command: "rm -rf target",
            cwd: process.cwd(),
            signal: new AbortController().signal,
          });
          reply(command.id, true, {allowed:true});
        } catch {
          reply(command.id, false, {allowed:false});
        }
        return;
      }
      if (command.type === "crash") {
        process.stderr.write("local-only-secret\\n");
        process.exit(19);
      }
      reply(command.id, true, {ok:true});
    })();
  }
});
`;

afterEach(async () => {
  await Promise.allSettled(supervisors.splice(0).map((supervisor) => supervisor.shutdown()));
  await Promise.allSettled(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("RuntimeSupervisor", () => {
  it("verifies before use and installs then re-verifies an invalid stage", async () => {
    const fixture = await createFixture(false);
    const supervisor = fixture.supervisor;
    const first = supervisor.start();
    const second = supervisor.start();
    await expect(first).resolves.toMatchObject({ root: fixture.target, manifest, installed: true });
    await expect(second).resolves.toMatchObject({ root: fixture.target, manifest, installed: true });
    expect(fixture.calls()).toEqual([
      `verify:${fixture.target}`,
      `source:${fixture.source}`,
      `install:${fixture.source}:${fixture.target}`,
      `verify:${fixture.target}`,
    ]);
    expect(supervisor.state()).toBe("ready");
    expect(await supervisor.start()).toEqual(supervisor.pinnedRuntime());
  });

  it("uses an already exact stage without resolving or copying the source", async () => {
    const fixture = await createFixture(true);
    const runtime = await fixture.supervisor.start();
    expect(runtime.installed).toBe(false);
    expect(fixture.calls()).toEqual([`verify:${fixture.target}`]);
  });

  it("rejects an install whose returned manifest does not match re-verification", async () => {
    const root = await temporaryRoot();
    const target = join(root, "pi");
    const provisioner: PiRuntimeProvisioner = {
      targetRoot: () => target,
      sourceRoot: () => Promise.resolve(join(root, "source")),
      install: async () => {
        await writeFakePi(target);
        return { ...manifest, piVersion: "unexpected" };
      },
      verify: (() => {
        let first = true;
        return () => {
          if (first) {
            first = false;
            return Promise.reject(new Error("invalid"));
          }
          return Promise.resolve(manifest);
        };
      })(),
    };
    const supervisor = register(new RuntimeSupervisor({
      approvalSocketPath: join(root, "approval.sock"),
      onApprovalOffer: () => undefined,
      preloadPath,
      provisioner,
    }));
    await expect(supervisor.start()).rejects.toMatchObject({ code: "RUNTIME_PROVISION_FAILED", message: "RUNTIME_PROVISION_FAILED" });
    expect(supervisor.state()).toBe("faulted");
  });

  it("runs independent RPC sessions with normal Mac configuration and one approval broker", async () => {
    const fixture = await createFixture(true);
    const offers: ApprovalOffer[] = [];
    const supervisor = fixture.replaceSupervisor((offer) => offers.push(offer));
    await supervisor.start();
    const workOne = join(fixture.root, "repo-one");
    const workTwo = join(fixture.root, "repo-two");
    await Promise.all([mkdir(workOne), mkdir(workTwo)]);
    const first = await supervisor.startSession({
      sessionId: "one",
      cwd: workOne,
      env: configuredEnvironment(),
    });
    const sessionFile = join(fixture.root, "session-two.jsonl");
    const second = await supervisor.startSession({
      sessionId: "two",
      cwd: workTwo,
      sessionFile,
      env: configuredEnvironment(),
    });

    const firstState = responseData(await first.call({ type: "inspect" }));
    const secondState = responseData(await second.call({ type: "inspect" }));
    expect(firstState).toMatchObject({
      cwd: await realpath(workOne),
      configuredAgent: true,
      packageConfig: true,
      credentialPresent: true,
      existingNodeOptionsPreserved: true,
      preloadPresent: true,
      socketPath: supervisor.approvalSocketPath(),
      connectionId: first.connectionId,
    });
    expect(secondState).toMatchObject({ cwd: await realpath(workTwo), socketPath: supervisor.approvalSocketPath(), connectionId: second.connectionId });
    expect(first.connectionId).not.toBe(second.connectionId);
    expect(firstState["args"]).toEqual(["--mode", "rpc", "--no-session"]);
    expect(secondState["args"]).toEqual(["--mode", "rpc", "--session", sessionFile]);
    expect((firstState["args"] as string[]).some((argument) => argument.includes("extensions") || argument.includes("packages"))).toBe(false);
    expect(supervisor.sessionCount()).toBe(2);

    const firstApproval = first.call({ type: "dangerous" });
    await waitFor(() => offers.length === 1);
    const secondApproval = second.call({ type: "dangerous" });
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 20));
    expect(offers).toHaveLength(1);
    const firstOffer = requiredOffer(offers, 0);
    expect(supervisor.activeApprovalOffer()).toEqual(firstOffer);
    expect(supervisor.decideApproval(decision(firstOffer, "allow_once"))).toBe(true);
    await expect(firstApproval).resolves.toMatchObject({ success: true, data: { allowed: true } });
    await waitFor(() => offers.length === 2);
    const secondOffer = requiredOffer(offers, 1);
    expect(supervisor.decideApproval(decision(secondOffer, "allow_once"))).toBe(true);
    await expect(secondApproval).resolves.toMatchObject({ success: true, data: { allowed: true } });
    expect(supervisor.decideApproval(decision(secondOffer, "allow_once"))).toBe(false);
  });

  it("fails closed without faulting the runtime when approval delivery throws", async () => {
    const fixture = await createFixture(true);
    const supervisor = fixture.replaceSupervisor(() => { throw new Error("UI failure with local-only-secret"); });
    await supervisor.start();
    const session = await supervisor.startSession({ sessionId: "approval-fault", cwd: fixture.root });
    await expect(session.call({ type: "dangerous" })).resolves.toMatchObject({ success: false, data: { allowed: false } });
    expect(supervisor.state()).toBe("ready");
    expect(supervisor.activeApprovalOffer()).toBeUndefined();
  });

  it("rejects duplicate or non-local session paths", async () => {
    const fixture = await createFixture(true);
    await fixture.supervisor.start();
    const first = await fixture.supervisor.startSession({ sessionId: "same", cwd: fixture.root });
    await expect(fixture.supervisor.startSession({ sessionId: "same", cwd: fixture.root })).rejects.toMatchObject({ code: "DUPLICATE_SESSION" });
    await expect(fixture.supervisor.startSession({ sessionId: "bad", cwd: "relative" })).rejects.toMatchObject({ code: "INVALID_SESSION" });
    await expect(fixture.supervisor.startSession({ sessionId: "bad", cwd: fixture.root, sessionFile: "relative" })).rejects.toMatchObject({ code: "INVALID_SESSION" });
    await first.stop();
    expect(fixture.supervisor.sessionCount()).toBe(0);
  });

  it("reports child faults with stable metadata and never surfaces stderr secrets", async () => {
    const fixture = await createFixture(true);
    await fixture.supervisor.start();
    const session = await fixture.supervisor.startSession({ sessionId: "faulting", cwd: fixture.root });
    const fault = new Promise<unknown>((resolvePromise) => fixture.supervisor.once("session_fault", resolvePromise));
    const result = session.call({ type: "crash" });
    await expect(result).rejects.toMatchObject({ code: "RUNTIME_SESSION_FAILED", message: "RUNTIME_SESSION_FAILED" });
    const event = await fault;
    expect(event).toEqual({ sessionId: "faulting", connectionId: session.connectionId, code: "PI_RPC_FAULT" });
    expect(JSON.stringify(event)).not.toContain("local-only-secret");
    await waitFor(() => fixture.supervisor.sessionCount() === 0);
  });

  it("awaits concurrent shutdown, terminates children, and removes the socket", async () => {
    const fixture = await createFixture(true);
    await fixture.supervisor.start();
    const session = await fixture.supervisor.startSession({ sessionId: "running", cwd: fixture.root });
    const socketPath = fixture.supervisor.approvalSocketPath();
    await expect(access(socketPath)).resolves.toBeUndefined();
    const sessionStop = session.stop();
    expect(session.stop()).toBe(sessionStop);
    const first = fixture.supervisor.shutdown();
    const second = fixture.supervisor.shutdown();
    await Promise.all([sessionStop, first, second]);
    expect(fixture.supervisor.state()).toBe("stopped");
    expect(fixture.supervisor.pinnedRuntime()).toBeUndefined();
    expect(fixture.supervisor.sessionCount()).toBe(0);
    expect(session.state()).toBe("stopped");
    await expect(access(socketPath)).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("cannot become ready or leak a socket when shutdown wins startup", async () => {
    const root = await temporaryRoot();
    const target = join(root, "pi");
    await writeFakePi(target);
    let releaseVerify: (() => void) | undefined;
    const verifyGate = new Promise<void>((resolvePromise) => { releaseVerify = resolvePromise; });
    let verificationStarted: (() => void) | undefined;
    const started = new Promise<void>((resolvePromise) => { verificationStarted = resolvePromise; });
    const provisioner: PiRuntimeProvisioner = {
      targetRoot: () => target,
      sourceRoot: () => Promise.resolve(join(root, "source")),
      install: () => Promise.resolve(manifest),
      verify: async () => {
        verificationStarted?.();
        await verifyGate;
        return manifest;
      },
    };
    const supervisor = register(new RuntimeSupervisor({
      approvalSocketPath: join(root, "approval.sock"),
      onApprovalOffer: () => undefined,
      preloadPath,
      provisioner,
    }));
    const start = supervisor.start();
    await started;
    const shutdown = supervisor.shutdown();
    releaseVerify?.();
    await expect(start).rejects.toMatchObject({ code: "RUNTIME_STOPPING" });
    await shutdown;
    expect(supervisor.state()).toBe("stopped");
    await expect(access(supervisor.approvalSocketPath())).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("does not unlink an approval socket owned by another live supervisor", async () => {
    const root = await temporaryRoot();
    const target = join(root, "pi");
    await writeFakePi(target);
    const provisioner: PiRuntimeProvisioner = {
      targetRoot: () => target,
      sourceRoot: () => Promise.resolve(join(root, "source")),
      install: () => Promise.resolve(manifest),
      verify: () => Promise.resolve(manifest),
    };
    const socketPath = join(root, "shared.sock");
    const first = register(new RuntimeSupervisor({ approvalSocketPath: socketPath, onApprovalOffer: () => undefined, preloadPath, provisioner }));
    const second = register(new RuntimeSupervisor({ approvalSocketPath: socketPath, onApprovalOffer: () => undefined, preloadPath, provisioner }));
    await first.start();
    await expect(second.start()).rejects.toMatchObject({ code: "RUNTIME_PROVISION_FAILED" });
    await expect(access(socketPath)).resolves.toBeUndefined();
    const session = await first.startSession({ sessionId: "still-running", cwd: root });
    await expect(session.call({ type: "inspect" })).resolves.toMatchObject({ success: true });
  });

  it("rejects relative and overlong approval socket paths", () => {
    expect(() => new RuntimeSupervisor({ approvalSocketPath: "relative.sock", onApprovalOffer: () => undefined, preloadPath })).toThrow("approval socket path is invalid");
    expect(() => new RuntimeSupervisor({ approvalSocketPath: `/${"x".repeat(101)}`, onApprovalOffer: () => undefined, preloadPath })).toThrow("approval socket path is invalid");
  });
});

interface Fixture {
  readonly root: string;
  readonly target: string;
  readonly source: string;
  readonly supervisor: RuntimeSupervisor;
  readonly calls: () => string[];
  readonly replaceSupervisor: (onOffer: (offer: ApprovalOffer) => void) => RuntimeSupervisor;
}

async function createFixture(installed: boolean): Promise<Fixture> {
  const root = await temporaryRoot();
  const target = join(root, "pi");
  const source = join(root, "source");
  const calls: string[] = [];
  let valid = installed;
  if (installed) await writeFakePi(target);
  const provisioner: PiRuntimeProvisioner = {
    targetRoot: () => target,
    sourceRoot: () => {
      calls.push(`source:${source}`);
      return Promise.resolve(source);
    },
    install: async (sourceRoot, targetRoot) => {
      calls.push(`install:${sourceRoot}:${targetRoot}`);
      await writeFakePi(targetRoot);
      valid = true;
      return manifest;
    },
    verify: (targetRoot) => {
      calls.push(`verify:${targetRoot}`);
      return valid ? Promise.resolve(manifest) : Promise.reject(new Error("invalid stage with local-only-secret"));
    },
  };
  const create = (onOffer: (offer: ApprovalOffer) => void): RuntimeSupervisor => register(new RuntimeSupervisor({
    approvalSocketPath: join(root, `approval-${String(supervisors.length)}.sock`),
    onApprovalOffer: onOffer,
    preloadPath,
    provisioner,
    responseTimeoutMs: 2_000,
  }));
  const supervisor = create(() => undefined);
  return {
    root,
    target,
    source,
    supervisor,
    calls: () => [...calls],
    replaceSupervisor: (onOffer) => create(onOffer),
  };
}

async function temporaryRoot(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-supervisor-"));
  roots.push(root);
  return root;
}

async function writeFakePi(target: string): Promise<void> {
  await mkdir(join(target, "dist"), { recursive: true });
  await writeFile(join(target, "dist", "cli.js"), fakePi, { mode: 0o700 });
}

function register(supervisor: RuntimeSupervisor): RuntimeSupervisor {
  supervisors.push(supervisor);
  return supervisor;
}

function configuredEnvironment(): NodeJS.ProcessEnv {
  return {
    PI_CODING_AGENT_DIR: "/Users/test/.pi/agent",
    PI_PACKAGE_DIR: "/Users/test/.pi/packages",
    TEST_PROVIDER_CREDENTIAL: "local-only-secret",
    NODE_OPTIONS: "--trace-warnings",
  };
}

function responseData(response: Readonly<Record<string, unknown>>): Record<string, unknown> {
  const data = response["data"];
  if (typeof data !== "object" || data === null) throw new TypeError("response data missing");
  return data as Record<string, unknown>;
}

function requiredOffer(offers: readonly ApprovalOffer[], index: number): ApprovalOffer {
  const offer = offers[index];
  if (offer === undefined) throw new TypeError("approval offer missing");
  return offer;
}

function decision(offer: ApprovalOffer, value: "allow_once" | "deny") {
  return {
    offerId: offer.offerId,
    operationId: offer.operationId,
    argumentHash: offer.argumentHash,
    connectionId: offer.connectionId,
    decision: value,
  } as const;
}

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("condition timed out");
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 5));
  }
}
