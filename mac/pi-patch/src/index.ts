#!/usr/bin/env node
import { createHash } from "node:crypto";
import { cp, mkdir, readFile, readdir, realpath, rename, rm, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

export const PINNED_PI_VERSION = "0.84.0";
export const PINNED_AGENT_SESSION_SHA256 = "91e72d5497f665e731cbd79da6a6e826d8cae7d2ce156a7dee39f8ca205e32c8";
export const PINNED_PACKAGE_SHA256 = "e26badbbd2b95079aa346edfc69efc78b5bc7cc843b4740594872509a959a6f9";
export const PINNED_BASH_TOOL_SHA256 = "e01d870f368a62050ce91a04752ee3e87d241fa5afded395f0add690037a4b01";
export const PINNED_PATCHED_AGENT_SESSION_SHA256 = "fb3bde5777ca3f57bc079825ac91d32cd814e22480758b9ecf772eafb088e4c4";
export const PINNED_PATCHED_BASH_TOOL_SHA256 = "854c06a5f49fa6dad55b731368c3589af78c776c789f3b8ce51b20c10c38aa4f";
export const POLICY_HOOK_KEY = "io.github.verybigsad.pimobile.policy.v1";
export const AGENT_SESSION_FILE = "dist/core/agent-session.js";
export const BASH_TOOL_FILE = "dist/core/tools/bash.js";

const marker = `const piMobilePolicyHookSymbol = Symbol.for("${POLICY_HOOK_KEY}");`;
const needle = `        const resolvedCommand = prefix ? \`\${prefix}\\n\${command}\` : command;
        try {
            const result = await executeBashWithOperations(resolvedCommand, this.sessionManager.getCwd(), options?.operations ?? createLocalBashOperations({ shellPath }), {`;
const replacement = `        const resolvedCommand = prefix ? \`\${prefix}\\n\${command}\` : command;
        const cwd = this.sessionManager.getCwd();
        try {
            ${marker}
            const piMobilePolicyHook = globalThis[piMobilePolicyHookSymbol];
            if (typeof piMobilePolicyHook !== "function") {
                throw new Error("Pi Mobile policy hook unavailable");
            }
            await piMobilePolicyHook(Object.freeze({ version: 1, kind: "bash", operationId: options?.id, command: resolvedCommand, cwd, signal: abortController.signal }));
            const result = await executeBashWithOperations(resolvedCommand, cwd, options?.operations ?? createLocalBashOperations({ shellPath }), {`;
const toolMarker = "const piMobileFinalToolArguments = true;";
const toolNeedle = `        this.agent.beforeToolCall = async ({ toolCall, args }) => {
            const runner = this._extensionRunner;
            if (!runner.hasHandlers("tool_call")) {
                return undefined;
            }
            try {
                return await runner.emitToolCall({
                    type: "tool_call",
                    toolName: toolCall.name,
                    toolCallId: toolCall.id,
                    input: args,
                });
            }
            catch (err) {
                if (err instanceof Error) {
                    throw err;
                }
                throw new Error(\`Extension failed, blocking execution: \${String(err)}\`);
            }
        };`;
const toolReplacement = `        this.agent.beforeToolCall = async ({ toolCall, args }, signal) => {
            ${toolMarker}
            const runner = this._extensionRunner;
            let extensionResult;
            if (runner.hasHandlers("tool_call")) {
                try {
                    extensionResult = await runner.emitToolCall({
                        type: "tool_call",
                        toolName: toolCall.name,
                        toolCallId: toolCall.id,
                        input: args,
                    });
                }
                catch (err) {
                    if (err instanceof Error) {
                        throw err;
                    }
                    throw new Error(\`Extension failed, blocking execution: \${String(err)}\`);
                }
            }
            if (extensionResult?.block || toolCall.name === "bash") {
                return extensionResult;
            }
            const piMobilePolicyHook = globalThis[Symbol.for("${POLICY_HOOK_KEY}")];
            if (typeof piMobilePolicyHook !== "function") {
                throw new Error("Pi Mobile policy hook unavailable");
            }
            await piMobilePolicyHook(Object.freeze({ version: 1, kind: "tool", operationId: toolCall.id, name: toolCall.name, arguments: args, cwd: this.sessionManager.getCwd(), signal: signal ?? new AbortController().signal }));
            return extensionResult;
        };`;
const bashToolMarker = "const piMobileBashToolPolicyHook = true;";
const bashToolNeedle = `        async execute(_toolCallId, { command, timeout }, signal, onUpdate, ctx) {
            const resolvedCommand = commandPrefix ? \`\${commandPrefix}\\n\${command}\` : command;
            const spawnContext = resolveSpawnContext(resolvedCommand, cwd, spawnHook, exposeSessionEnvironment, ctx);`;
const bashToolReplacement = `        async execute(_toolCallId, { command, timeout }, signal, onUpdate, ctx) {
            const resolvedCommand = commandPrefix ? \`\${commandPrefix}\\n\${command}\` : command;
            ${bashToolMarker}
            const piMobilePolicyHook = globalThis[Symbol.for("${POLICY_HOOK_KEY}")];
            if (typeof piMobilePolicyHook !== "function") {
                throw new Error("Pi Mobile policy hook unavailable");
            }
            await piMobilePolicyHook(Object.freeze({ version: 1, kind: "bash", operationId: _toolCallId, command: resolvedCommand, cwd, signal: signal ?? new AbortController().signal }));
            const spawnContext = resolveSpawnContext(resolvedCommand, cwd, spawnHook, exposeSessionEnvironment, ctx);`;

export interface DistManifest {
  readonly piVersion: string;
  readonly policyHookKey: string;
  readonly files: Readonly<Record<string, string>>;
  readonly patched: Readonly<Record<string, string>>;
}

export interface PatchManifest {
  readonly piVersion: string;
  readonly originalAgentSessionSha256: string;
  readonly patchedAgentSessionSha256: string;
  readonly policyHookKey: string;
}

export interface FullPatchManifest extends PatchManifest {
  readonly originalBashToolSha256: string;
  readonly patchedBashToolSha256: string;
  readonly files: Readonly<Record<string, string>>;
  readonly patchedFiles: Readonly<Record<string, string>>;
}

export function patchAgentSession(source: string): string {
  if (source.includes(marker) && source.includes(toolMarker)) return source;
  if (source.includes(marker) || source.includes(toolMarker)) throw new Error("Pinned Pi patch is incomplete");
  if (occurrences(source, needle) !== 1) throw new Error("Pinned Pi executeBash structure does not match");
  if (occurrences(source, toolNeedle) !== 1) throw new Error("Pinned Pi tool hook structure does not match");
  return source.replace(toolNeedle, toolReplacement).replace(needle, replacement);
}

export function patchBashTool(source: string): string {
  if (source.includes(bashToolMarker)) return source;
  if (occurrences(source, bashToolNeedle) !== 1) throw new Error("Pinned Pi bash tool structure does not match");
  return source.replace(bashToolNeedle, bashToolReplacement);
}

export async function generateDistManifest(sourceRoot: string): Promise<DistManifest> {
  const source = await realpath(sourceRoot);
  const files = await hashDistTree(source);
  const packageValue = JSON.parse(files["package.json"] === undefined ? "{}" : await readFile(resolve(source, "package.json"), "utf8")) as { version?: unknown };
  if (packageValue.version !== PINNED_PI_VERSION) throw new Error("Pi package identity does not match 0.84.0");
  const patched: Record<string, string> = {};
  const agentSource = await readFile(resolve(source, AGENT_SESSION_FILE), "utf8");
  patched[AGENT_SESSION_FILE] = sha256(patchAgentSession(agentSource));
  const bashSource = await readFile(resolve(source, BASH_TOOL_FILE), "utf8");
  patched[BASH_TOOL_FILE] = sha256(patchBashTool(bashSource));
  return { piVersion: PINNED_PI_VERSION, policyHookKey: POLICY_HOOK_KEY, files, patched: sortRecord(patched) };
}

export async function pinnedDistManifest(): Promise<DistManifest> {
  const manifestPath = resolve(dirname(fileURLToPath(import.meta.url)), "../manifest", `pi-${PINNED_PI_VERSION}.json`);
  const manifest = JSON.parse(await readFile(manifestPath, "utf8")) as DistManifest;
  if (
    manifest.piVersion !== PINNED_PI_VERSION ||
    manifest.policyHookKey !== POLICY_HOOK_KEY ||
    manifest.files["package.json"] !== PINNED_PACKAGE_SHA256 ||
    manifest.files[AGENT_SESSION_FILE] !== PINNED_AGENT_SESSION_SHA256 ||
    manifest.files[BASH_TOOL_FILE] !== PINNED_BASH_TOOL_SHA256 ||
    manifest.patched[AGENT_SESSION_FILE] !== PINNED_PATCHED_AGENT_SESSION_SHA256 ||
    manifest.patched[BASH_TOOL_FILE] !== PINNED_PATCHED_BASH_TOOL_SHA256 ||
    Object.keys(manifest.patched).length !== 2 ||
    !isSorted(manifest.files) ||
    !isSorted(manifest.patched)
  ) throw new Error("Pinned Pi dist manifest is inconsistent");
  return manifest;
}

export async function installPinnedPi(sourceRoot: string, targetRoot: string): Promise<FullPatchManifest> {
  const source = await realpath(sourceRoot);
  const manifest = await pinnedDistManifest();
  const sourceFiles = await hashDistTree(source);
  assertTreeMatches(sourceFiles, manifest.files, "Pi package tree does not match the pinned 0.84.0 manifest");
  const patchedAgent = patchAgentSession(await readFile(resolve(source, AGENT_SESSION_FILE), "utf8"));
  if (sha256(patchedAgent) !== PINNED_PATCHED_AGENT_SESSION_SHA256) throw new Error("Patched Pi output is not deterministic");
  const patchedBash = patchBashTool(await readFile(resolve(source, BASH_TOOL_FILE), "utf8"));
  if (sha256(patchedBash) !== PINNED_PATCHED_BASH_TOOL_SHA256) throw new Error("Patched Pi bash tool output is not deterministic");
  const patchManifest: FullPatchManifest = {
    piVersion: PINNED_PI_VERSION,
    originalAgentSessionSha256: PINNED_AGENT_SESSION_SHA256,
    patchedAgentSessionSha256: PINNED_PATCHED_AGENT_SESSION_SHA256,
    originalBashToolSha256: PINNED_BASH_TOOL_SHA256,
    patchedBashToolSha256: PINNED_PATCHED_BASH_TOOL_SHA256,
    policyHookKey: POLICY_HOOK_KEY,
    files: manifest.files,
    patchedFiles: manifest.patched,
  };

  const target = resolve(targetRoot);
  const temporary = `${target}.tmp-${String(process.pid)}`;
  await mkdir(dirname(target), { recursive: true, mode: 0o700 });
  await rm(temporary, { recursive: true, force: true });
  await cp(source, temporary, { recursive: true, force: false, errorOnExist: true });
  const targetAgent = resolve(temporary, AGENT_SESSION_FILE);
  const targetBash = resolve(temporary, BASH_TOOL_FILE);
  const agentMode = (await stat(targetAgent)).mode;
  const bashMode = (await stat(targetBash)).mode;
  await writeFile(targetAgent, patchedAgent, { mode: agentMode });
  await writeFile(targetBash, patchedBash, { mode: bashMode });
  await writeFile(resolve(temporary, "pi-mobile-patch.json"), `${JSON.stringify(patchManifest, null, 2)}\n`, { mode: 0o600 });
  await rm(target, { recursive: true, force: true });
  await rename(temporary, target);
  return patchManifest;
}

export async function verifyPinnedPi(targetRoot: string): Promise<FullPatchManifest> {
  const target = resolve(targetRoot);
  const manifest = JSON.parse(await readFile(resolve(target, "pi-mobile-patch.json"), "utf8")) as FullPatchManifest;
  const pinned = await pinnedDistManifest();
  if (
    manifest.piVersion !== PINNED_PI_VERSION ||
    manifest.originalAgentSessionSha256 !== PINNED_AGENT_SESSION_SHA256 ||
    manifest.policyHookKey !== POLICY_HOOK_KEY ||
    manifest.patchedAgentSessionSha256 !== PINNED_PATCHED_AGENT_SESSION_SHA256 ||
    manifest.originalBashToolSha256 !== PINNED_BASH_TOOL_SHA256 ||
    manifest.patchedBashToolSha256 !== PINNED_PATCHED_BASH_TOOL_SHA256 ||
    JSON.stringify(manifest.files) !== JSON.stringify(pinned.files) ||
    JSON.stringify(manifest.patchedFiles) !== JSON.stringify(pinned.patched)
  ) throw new Error("Patched Pi verification failed");
  const targetFiles = await hashDistTree(target);
  const expected: Record<string, string> = { ...manifest.files, ...manifest.patchedFiles };
  assertTreeMatches(targetFiles, expected, "Patched Pi tree drifted from the pinned 0.84.0 manifest");
  const agentText = await readFile(resolve(target, AGENT_SESSION_FILE), "utf8");
  const bashText = await readFile(resolve(target, BASH_TOOL_FILE), "utf8");
  if (
    !agentText.includes(marker) ||
    !agentText.includes(toolMarker) ||
    !bashText.includes(bashToolMarker)
  ) throw new Error("Patched Pi verification failed");
  return manifest;
}

export async function defaultSourceRoot(): Promise<string> {
  try {
    const entry = createRequire(import.meta.url).resolve("@earendil-works/pi-coding-agent");
    return dirname(dirname(await realpath(entry)));
  } catch {
    const executable = execFileSync("which", ["pi"], { encoding: "utf8" }).trim();
    return dirname(dirname(await realpath(executable)));
  }
}

export function defaultTargetRoot(): string {
  return resolve(homedir(), "Library/Application Support/PiMobile/pi", PINNED_PI_VERSION);
}

async function hashDistTree(root: string): Promise<Record<string, string>> {
  const entries: Record<string, string> = {};
  const walk = async (directory: string, prefix: string): Promise<void> => {
    const children = await readdir(directory, { withFileTypes: true });
    for (const child of children) {
      const relative = prefix === "" ? child.name : `${prefix}/${child.name}`;
      if (child.isDirectory()) {
        await walk(resolve(directory, child.name), relative);
      } else if (child.isFile() && child.name.endsWith(".js")) {
        entries[relative] = sha256(await readFile(resolve(directory, child.name)));
      }
    }
  };
  await walk(resolve(root, "dist"), "dist");
  entries["package.json"] = sha256(await readFile(resolve(root, "package.json")));
  return sortRecord(entries);
}

function assertTreeMatches(actual: Readonly<Record<string, string>>, expected: Readonly<Record<string, string>>, message: string): void {
  const actualKeys = Object.keys(actual);
  const expectedKeys = Object.keys(expected);
  if (actualKeys.length !== expectedKeys.length) throw new Error(message);
  for (const key of expectedKeys) {
    if (actual[key] !== expected[key]) throw new Error(message);
  }
}

function sortRecord(value: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)));
}

function isSorted(value: Readonly<Record<string, string>>): boolean {
  const keys = Object.keys(value);
  for (let index = 1; index < keys.length; index += 1) {
    const previous = keys[index - 1];
    const current = keys[index];
    if (previous === undefined || current === undefined || previous.localeCompare(current) >= 0) return false;
  }
  return true;
}

function sha256(value: string | Uint8Array): string {
  return createHash("sha256").update(value).digest("hex");
}

function occurrences(value: string, search: string): number {
  return value.split(search).length - 1;
}

async function main(): Promise<void> {
  const argumentsValue = process.argv.slice(2);
  const sourceIndex = argumentsValue.indexOf("--source");
  const targetIndex = argumentsValue.indexOf("--target");
  const generateIndex = argumentsValue.indexOf("--generate-manifest");
  if (generateIndex >= 0) {
    const source = sourceIndex >= 0 ? argumentsValue[sourceIndex + 1] : await defaultSourceRoot();
    if (source === undefined) throw new Error("--source requires a path");
    const manifest = await generateDistManifest(source);
    const output = `${JSON.stringify(manifest, null, 2)}\n`;
    const destination = argumentsValue[generateIndex + 1];
    if (destination === undefined || destination.startsWith("--")) {
      process.stdout.write(output);
    } else {
      await writeFile(destination, output, { mode: 0o600 });
    }
    return;
  }
  const target = targetIndex >= 0 ? argumentsValue[targetIndex + 1] : defaultTargetRoot();
  if (target === undefined) throw new Error("--target requires a path");
  if (argumentsValue.includes("--verify")) {
    const manifest = await verifyPinnedPi(target);
    process.stdout.write(`${manifest.patchedAgentSessionSha256}\n`);
    return;
  }
  const source = sourceIndex >= 0 ? argumentsValue[sourceIndex + 1] : await defaultSourceRoot();
  if (source === undefined) throw new Error("--source requires a path");
  const manifest = await installPinnedPi(source, target);
  process.stdout.write(`${manifest.patchedAgentSessionSha256}\n`);
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
