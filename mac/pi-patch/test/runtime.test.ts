import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { afterEach, describe, expect, it } from "vitest";
import { ApprovalSocketServer, type ApprovalOffer } from "@pimobile/approval";
import { defaultSourceRoot, installPinnedPi, verifyPinnedPi } from "../src/index.js";

const children: ChildProcessWithoutNullStreams[] = [];
const servers: ApprovalSocketServer[] = [];
const httpServers: Server[] = [];

afterEach(async () => {
  for (const child of children.splice(0)) child.kill("SIGKILL");
  await Promise.all(servers.splice(0).map((server) => server.close()));
  await Promise.all(httpServers.splice(0).map((server) => new Promise<void>((done) => server.close(() => done()))));
});

describe("patched Pi integrity", () => {
  it("fails closed on any dist tree drift", async () => {
    const root = mkdtempSync(join(tmpdir(), "pi-mobile-integrity-"));
    const target = join(root, "pi");
    await installPinnedPi(await defaultSourceRoot(), target);
    await expect(verifyPinnedPi(target)).resolves.toMatchObject({ piVersion: "0.84.0" });

    const drifted = join(target, "dist/cli.js");
    const original = readFileSync(drifted);
    writeFileSync(drifted, `${original.toString("utf8")}// tampered\n`);
    await expect(verifyPinnedPi(target)).rejects.toThrow("drifted");
    writeFileSync(drifted, original);
    await expect(verifyPinnedPi(target)).resolves.toMatchObject({ piVersion: "0.84.0" });

    const extra = join(target, "dist/extra-injected.js");
    writeFileSync(extra, "globalThis.pwned = true;\n");
    await expect(verifyPinnedPi(target)).rejects.toThrow("drifted");
    const { rm } = await import("node:fs/promises");
    await rm(extra);
    await rm(join(target, "dist/utils/shell.js"));
    await expect(verifyPinnedPi(target)).rejects.toThrow();
  }, 120_000);
});

describe("patched Pi runtime", () => {
  it("intercepts the final prefixed RPC bash argument exactly once", async () => {
    const root = mkdtempSync(join(tmpdir(), "pi-mobile-patched-runtime-"));
    const target = join(root, "pi");
    await installPinnedPi(await defaultSourceRoot(), target);
    await expect(verifyPinnedPi(target)).resolves.toMatchObject({ piVersion: "0.84.0" });
    const agentDirectory = join(root, "agent");
    const workingDirectory = join(root, "work");
    writeFileSync(join(root, "placeholder"), "");
    await import("node:fs/promises").then(({ mkdir }) => Promise.all([
      mkdir(agentDirectory, { recursive: true }),
      mkdir(workingDirectory, { recursive: true }),
    ]));
    const prefix = "export PI_MOBILE_PREFIX_MARKER=present";
    writeFileSync(join(agentDirectory, "settings.json"), `${JSON.stringify({ shellCommandPrefix: prefix })}\n`, { mode: 0o600 });
    const socketPath = join(root, "approval.sock");
    const offers: ApprovalOffer[] = [];
    const server = new ApprovalSocketServer({
      socketPath,
      onOffer: (offer) => {
        offers.push(offer);
        server.decide({
          offerId: offer.offerId,
          operationId: offer.operationId,
          argumentHash: offer.argumentHash,
          connectionId: offer.connectionId,
          decision: "allow_once",
        });
      },
    });
    servers.push(server);
    await server.start();
    const preload = resolve(dirname(fileURLToPath(import.meta.url)), "../../preload/dist/index.js");
    const child = spawnPi(target, agentDirectory, workingDirectory, {
      NODE_OPTIONS: `--import=${preload}`,
      PI_MOBILE_APPROVAL_SOCKET: socketPath,
      PI_MOBILE_CONNECTION_ID: "runtime-test",
    });
    const response = waitForResponse(child, "bash-1");
    child.stdin.write(`${JSON.stringify({ id: "bash-1", type: "bash", command: `printf '%s' "$PI_MOBILE_PREFIX_MARKER"` })}\n`);
    await expect(response).resolves.toMatchObject({
      success: true,
      data: { output: "present", exitCode: 0 },
    });
    expect(offers).toHaveLength(1);
    expect(offers[0]).toMatchObject({
      operationId: "bash-1",
      connectionId: "runtime-test",
      operation: `${prefix}\nprintf '%s' "$PI_MOBILE_PREFIX_MARKER"`,
    });

    child.kill("SIGTERM");
    const unhooked = spawnPi(target, agentDirectory, workingDirectory, { NODE_OPTIONS: "" });
    const rejected = waitForResponse(unhooked, "bash-2");
    unhooked.stdin.write(`${JSON.stringify({ id: "bash-2", type: "bash", command: "pwd" })}\n`);
    await expect(rejected).resolves.toMatchObject({ success: false });
  }, 120_000);

  it("gates a model-issued bash tool call on exactly one bound offer and executes only after allow_once", async () => {
    const root = mkdtempSync(join(tmpdir(), "pi-mobile-model-runtime-"));
    const target = join(root, "pi");
    await installPinnedPi(await defaultSourceRoot(), target);
    const agentDirectory = join(root, "agent");
    const workingDirectory = join(root, "work");
    await import("node:fs/promises").then(({ mkdir }) => Promise.all([
      mkdir(agentDirectory, { recursive: true }),
      mkdir(workingDirectory, { recursive: true }),
    ]));
    const prefix = "export PI_MOBILE_PREFIX_MARKER=present";
    writeFileSync(join(agentDirectory, "settings.json"), `${JSON.stringify({ shellCommandPrefix: prefix })}\n`, { mode: 0o600 });

    const modelPort = await startMockModelServer("touch executed.marker");
    writeFileSync(join(agentDirectory, "models.json"), `${JSON.stringify({
      providers: {
        mock: {
          name: "Mock",
          baseUrl: `http://127.0.0.1:${String(modelPort)}/v1`,
          apiKey: "mock-key",
          api: "openai-completions",
          models: [{
            id: "mock-bash",
            name: "Mock Bash",
            reasoning: false,
            input: ["text"],
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
            contextWindow: 128_000,
            maxTokens: 4096,
          }],
        },
      },
    })}\n`, { mode: 0o600 });

    const socketPath = join(root, "approval.sock");
    const offers: ApprovalOffer[] = [];
    let deliver: (offer: ApprovalOffer) => void = () => undefined;
    const broker = new ApprovalSocketServer({ socketPath, onOffer: (offer) => { offers.push(offer); deliver(offer); } });
    servers.push(broker);
    await broker.start();

    const preload = resolve(dirname(fileURLToPath(import.meta.url)), "../../preload/dist/index.js");
    const child = spawnPi(target, agentDirectory, workingDirectory, {
      NODE_OPTIONS: `--import=${preload}`,
      PI_MOBILE_APPROVAL_SOCKET: socketPath,
      PI_MOBILE_CONNECTION_ID: "model-runtime-test",
    });

    const modelResponse = waitForResponse(child, "model-1");
    child.stdin.write(`${JSON.stringify({ id: "model-1", type: "set_model", provider: "mock", modelId: "mock-bash" })}\n`);
    await expect(modelResponse).resolves.toMatchObject({ success: true });

    const firstOffer = nextOffer();
    const promptResponse = waitForResponse(child, "prompt-1");
    child.stdin.write(`${JSON.stringify({ id: "prompt-1", type: "prompt", message: "Run the bash tool now." })}\n`);
    await expect(promptResponse).resolves.toMatchObject({ success: true });

    const offer = await firstOffer;
    expect(offers).toHaveLength(1);
    expect(offer).toMatchObject({
      connectionId: "model-runtime-test",
      operation: `${prefix}\ntouch executed.marker`,
    });
    expect(offer.reasons.length).toBeGreaterThan(0);
    await new Promise((resolveWait) => setTimeout(resolveWait, 500));
    expect(existsSync(join(workingDirectory, "executed.marker"))).toBe(false);

    const settled = waitForEvent(child, "agent_settled");
    expect(broker.decide({
      offerId: offer.offerId,
      operationId: offer.operationId,
      argumentHash: offer.argumentHash,
      connectionId: offer.connectionId,
      decision: "allow_once",
    })).toBe(true);
    await settled;
    expect(existsSync(join(workingDirectory, "executed.marker"))).toBe(true);
    expect(offers).toHaveLength(1);

    function nextOffer(): Promise<ApprovalOffer> {
      return new Promise((resolveOffer) => { deliver = resolveOffer; });
    }
  }, 120_000);

  it("blocks a model-issued bash tool call when the offer is denied", async () => {
    const root = mkdtempSync(join(tmpdir(), "pi-mobile-model-deny-"));
    const target = join(root, "pi");
    await installPinnedPi(await defaultSourceRoot(), target);
    const agentDirectory = join(root, "agent");
    const workingDirectory = join(root, "work");
    await import("node:fs/promises").then(({ mkdir }) => Promise.all([
      mkdir(agentDirectory, { recursive: true }),
      mkdir(workingDirectory, { recursive: true }),
    ]));
    const prefix = "export PI_MOBILE_PREFIX_MARKER=present";
    writeFileSync(join(agentDirectory, "settings.json"), `${JSON.stringify({ shellCommandPrefix: prefix })}\n`, { mode: 0o600 });

    const modelPort = await startMockModelServer("touch denied.marker");
    writeFileSync(join(agentDirectory, "models.json"), `${JSON.stringify({
      providers: {
        mock: {
          name: "Mock",
          baseUrl: `http://127.0.0.1:${String(modelPort)}/v1`,
          apiKey: "mock-key",
          api: "openai-completions",
          models: [{
            id: "mock-bash",
            name: "Mock Bash",
            reasoning: false,
            input: ["text"],
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
            contextWindow: 128_000,
            maxTokens: 4096,
          }],
        },
      },
    })}\n`, { mode: 0o600 });

    const socketPath = join(root, "approval.sock");
    const offers: ApprovalOffer[] = [];
    let deliver: (offer: ApprovalOffer) => void = () => undefined;
    const broker = new ApprovalSocketServer({ socketPath, onOffer: (offer) => { offers.push(offer); deliver(offer); } });
    servers.push(broker);
    await broker.start();

    const preload = resolve(dirname(fileURLToPath(import.meta.url)), "../../preload/dist/index.js");
    const child = spawnPi(target, agentDirectory, workingDirectory, {
      NODE_OPTIONS: `--import=${preload}`,
      PI_MOBILE_APPROVAL_SOCKET: socketPath,
      PI_MOBILE_CONNECTION_ID: "model-deny-test",
    });

    const modelResponse = waitForResponse(child, "model-1");
    child.stdin.write(`${JSON.stringify({ id: "model-1", type: "set_model", provider: "mock", modelId: "mock-bash" })}\n`);
    await expect(modelResponse).resolves.toMatchObject({ success: true });

    const firstOffer = new Promise<ApprovalOffer>((resolveOffer) => { deliver = resolveOffer; });
    const promptResponse = waitForResponse(child, "prompt-1");
    child.stdin.write(`${JSON.stringify({ id: "prompt-1", type: "prompt", message: "Run the bash tool now." })}\n`);
    await expect(promptResponse).resolves.toMatchObject({ success: true });

    const offer = await firstOffer;
    expect(offer.operation).toBe(`${prefix}\ntouch denied.marker`);
    const settled = waitForEvent(child, "agent_settled");
    expect(broker.decide({
      offerId: offer.offerId,
      operationId: offer.operationId,
      argumentHash: offer.argumentHash,
      connectionId: offer.connectionId,
      decision: "deny",
    })).toBe(true);
    await settled;
    expect(offers).toHaveLength(1);
    expect(existsSync(join(workingDirectory, "denied.marker"))).toBe(false);
  }, 120_000);
});

function spawnPi(target: string, agentDirectory: string, workingDirectory: string, extra: Record<string, string>): ChildProcessWithoutNullStreams {
  const child = spawn(process.execPath, [
    join(target, "dist/cli.js"),
    "--mode", "rpc",
    "--no-session",
    "--no-extensions",
    "--no-skills",
    "--no-prompt-templates",
    "--no-themes",
    "--no-context-files",
    "--offline",
  ], {
    cwd: workingDirectory,
    env: {
      ...process.env,
      PI_CODING_AGENT_DIR: agentDirectory,
      PI_OFFLINE: "1",
      ...extra,
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  children.push(child);
  return child;
}

function startMockModelServer(command: string): Promise<number> {
  const server = createServer((request, response) => {
    const url = request.url;
    if (request.method !== "POST" || typeof url !== "string" || !url.endsWith("/chat/completions")) {
      response.writeHead(404);
      response.end();
      return;
    }
    let body = "";
    request.on("data", (chunk: Buffer) => { body += chunk.toString("utf8"); });
    request.on("end", () => {
      const parsed = JSON.parse(body) as { messages?: { role?: string }[] };
      const lastRole = parsed.messages?.at(-1)?.role;
      response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
      const chunks = lastRole === "tool" ? finalChunks() : toolCallChunks(command);
      for (const chunk of chunks) response.write(`data: ${JSON.stringify(chunk)}\n\n`);
      response.write("data: [DONE]\n\n");
      response.end();
    });
  });
  httpServers.push(server);
  return new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address === null || typeof address === "string") {
        rejectListen(new Error("mock model server has no port"));
        return;
      }
      resolveListen(address.port);
    });
  });
}

function toolCallChunks(command: string): Record<string, unknown>[] {
  return [
    {
      id: "chatcmpl-mock-tool",
      object: "chat.completion.chunk",
      created: 0,
      model: "mock-bash",
      choices: [{
        index: 0,
        delta: {
          role: "assistant",
          tool_calls: [{
            index: 0,
            id: "call_mock_bash",
            type: "function",
            function: { name: "bash", arguments: JSON.stringify({ command }) },
          }],
        },
        finish_reason: null,
      }],
    },
    {
      id: "chatcmpl-mock-tool",
      object: "chat.completion.chunk",
      created: 0,
      model: "mock-bash",
      choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    },
  ];
}

function finalChunks(): Record<string, unknown>[] {
  return [
    {
      id: "chatcmpl-mock-final",
      object: "chat.completion.chunk",
      created: 0,
      model: "mock-bash",
      choices: [{ index: 0, delta: { role: "assistant", content: "done" }, finish_reason: null }],
    },
    {
      id: "chatcmpl-mock-final",
      object: "chat.completion.chunk",
      created: 0,
      model: "mock-bash",
      choices: [{ index: 0, delta: {}, finish_reason: "stop" }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    },
  ];
}

function waitForResponse(child: ChildProcessWithoutNullStreams, id: string): Promise<Record<string, unknown>> {
  return waitForMessage(child, (value) => value["type"] === "response" && value["id"] === id);
}

function waitForEvent(child: ChildProcessWithoutNullStreams, type: string): Promise<Record<string, unknown>> {
  return waitForMessage(child, (value) => value["type"] === type);
}

function waitForMessage(child: ChildProcessWithoutNullStreams, predicate: (value: Record<string, unknown>) => boolean): Promise<Record<string, unknown>> {
  return new Promise((resolvePromise, reject) => {
    let pending = "";
    let stderr = "";
    const timeout = setTimeout(() => reject(new Error(`Pi RPC timeout: ${stderr}`)), 60_000);
    child.stderr.on("data", (chunk: Buffer) => { stderr = `${stderr}${chunk.toString("utf8")}`.slice(-16_384); });
    child.once("exit", (code) => reject(new Error(`Pi exited ${String(code)}: ${stderr}`)));
    child.stdout.on("data", (chunk: Buffer) => {
      pending += chunk.toString("utf8");
      for (;;) {
        const newline = pending.indexOf("\n");
        if (newline < 0) return;
        const line = pending.slice(0, newline);
        pending = pending.slice(newline + 1);
        const value = JSON.parse(line) as Record<string, unknown>;
        if (predicate(value)) {
          clearTimeout(timeout);
          resolvePromise(value);
          return;
        }
      }
    });
  });
}
