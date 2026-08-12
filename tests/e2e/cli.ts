#!/usr/bin/env node
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { E2E_HOOKS, runInstalledStack, type E2eHook, type InstalledStackOptions } from "./orchestrator.js";

function option(argv: readonly string[], name: string): string | undefined {
  const index = argv.indexOf(name);
  if (index === -1) return undefined;
  const value = argv[index + 1];
  if (value === undefined || value.startsWith("--")) throw new Error("E2E_USAGE");
  return value;
}

function flag(argv: readonly string[], name: string): boolean {
  return argv.includes(name);
}

function required(value: string | undefined, code: string): string {
  if (value === undefined || value.length === 0) throw new Error(code);
  return value;
}

function parseHooks(value: string | undefined): readonly E2eHook[] {
  if (value === undefined || value.length === 0) return [];
  const hooks = value.split(",");
  if (new Set(hooks).size !== hooks.length || hooks.some((hook) => !E2E_HOOKS.includes(hook as E2eHook))) {
    throw new Error("E2E_HOOK_SELECTION_INVALID");
  }
  return hooks as E2eHook[];
}

function timestamp(): string {
  return new Date().toISOString().replaceAll(":", "-").replace(".", "-");
}

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  const repositoryRoot = resolve(import.meta.dirname, "../..");
  const serial = required(option(argv, "--serial") ?? process.env["PI_E2E_SERIAL"], "E2E_SERIAL_REQUIRED");
  const artifactRoot = resolve(
    option(argv, "--artifacts")
      ?? process.env["PI_E2E_ARTIFACTS"]
      ?? join(repositoryRoot, "artifacts", "e2e", timestamp()),
  );
  if (option(argv, "--session-id") !== undefined || process.env["PI_E2E_SESSION_ID"] !== undefined) {
    throw new Error("E2E_SESSION_ID_UNSUPPORTED");
  }
  const selectedHooks = parseHooks(option(argv, "--hooks") ?? process.env["PI_E2E_HOOKS"]);
  const options: InstalledStackOptions = {
    repositoryRoot,
    serial,
    dataDirectory: resolve(
      option(argv, "--data-dir")
        ?? process.env["PI_E2E_DATA_DIR"]
        ?? join(homedir(), "Library", "Application Support", "PiMobile"),
    ),
    artifactRoot,
    allowDestructiveSession: flag(argv, "--allow-destructive-session") || process.env["PI_E2E_ALLOW_DESTRUCTIVE_SESSION"] === "1",
    selectedHooks,
    knownContent: process.env["PI_E2E_KNOWN_CONTENT"] ?? "PONG",
    prompt: process.env["PI_E2E_PROMPT"] ?? "Reply with exactly PI_E2E_FINAL",
    expectedReply: process.env["PI_E2E_EXPECTED_REPLY"] ?? "PI_E2E_FINAL",
    terminalCanary: process.env["PI_E2E_TERMINAL_CANARY"] ?? "PI_E2E_TERMINAL_CANARY",
    isolateHostAuthentication: flag(argv, "--isolate-host-auth") || process.env["PI_E2E_ISOLATE_HOST_AUTH"] === "1",
    ...(process.env["PI_E2E_JAVA_HOME"] === undefined ? {} : { javaHome: process.env["PI_E2E_JAVA_HOME"] }),
  };
  const output = await runInstalledStack(options);
  process.stdout.write(`installed-stack E2E passed: ${output}\n`);
}

main().catch((error: unknown) => {
  const code = error instanceof Error ? error.message : "E2E_INTERNAL";
  process.stderr.write(`installed-stack E2E failed: ${code}\n`);
  process.exitCode = 1;
});
