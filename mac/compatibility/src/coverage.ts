import { DOCUMENTED_RPC_COMMAND_GROUPS, DOCUMENTED_RPC_EVENTS, DOCUMENTED_UI_METHODS, REQUIRED_TERMINAL_PATHS } from "./contracts.js";
import type { CompatibilityManifest, InvocationArgumentShape } from "./types.js";

const INVOCATION_PATH = /^\/[A-Za-z0-9][A-Za-z0-9:_-]*(?:\/[A-Za-z0-9][A-Za-z0-9:_-]*)*$/u;

export function assertCapabilityCoverage(manifest: CompatibilityManifest): void {
  assertUnique(manifest.sources.map((source) => source.id), "SOURCE");
  assertCommandCoverage(manifest);
  assertEventCoverage(manifest);
  assertUiCoverage(manifest);
  assertInvocationCoverage(manifest);
}

function assertCommandCoverage(manifest: CompatibilityManifest): void {
  if (manifest.rpcCommandGroups.length !== DOCUMENTED_RPC_COMMAND_GROUPS.length) {
    fail("COMMAND_GROUP_COUNT");
  }
  assertUnique(manifest.rpcCommandGroups.map((group) => group.id), "COMMAND_GROUP");
  const allCommands: string[] = [];
  for (const expected of DOCUMENTED_RPC_COMMAND_GROUPS) {
    const group = manifest.rpcCommandGroups.find((candidate) => candidate.id === expected.id);
    if (group === undefined) fail(`UNMAPPED_COMMAND_GROUP:${expected.id}`);
    if (group.treatment !== expected.treatment) fail(`COMMAND_GROUP_TREATMENT:${expected.id}`);
    if (group.commands.length !== expected.commands.length) fail(`COMMAND_COUNT:${expected.id}`);
    assertUnique(group.commands.map((command) => command.name), `COMMAND:${expected.id}`);
    for (const expectedCommand of expected.commands) {
      const command = group.commands.find((candidate) => candidate.name === expectedCommand);
      if (command === undefined) fail(`UNMAPPED_COMMAND:${expectedCommand}`);
      if (command.treatment !== expected.treatment) fail(`COMMAND_TREATMENT:${expectedCommand}`);
      allCommands.push(command.name);
    }
  }
  assertUnique(allCommands, "COMMAND");
}

function assertEventCoverage(manifest: CompatibilityManifest): void {
  const knownEvents = manifest.events.filter((event) => !event.isCatchAll);
  if (knownEvents.length !== DOCUMENTED_RPC_EVENTS.length) fail("EVENT_COUNT");
  assertUnique(knownEvents.map((event) => event.name), "EVENT");
  for (const expected of DOCUMENTED_RPC_EVENTS) {
    const event = knownEvents.find((candidate) => candidate.name === expected.name);
    if (event === undefined) fail(`UNMAPPED_EVENT:${expected.name}`);
    if (event.treatment !== expected.treatment) fail(`EVENT_TREATMENT:${expected.name}`);
  }
  const catchAll = manifest.events.filter((event) => event.isCatchAll);
  if (catchAll.length !== 1 || catchAll[0]?.name !== "*" || catchAll[0].treatment !== "retained") {
    fail("EVENT_CATCH_ALL");
  }
}

function assertUiCoverage(manifest: CompatibilityManifest): void {
  const knownMethods = manifest.uiMethods.filter((method) => !method.isCatchAll);
  if (knownMethods.length !== DOCUMENTED_UI_METHODS.length) fail("UI_METHOD_COUNT");
  assertUnique(knownMethods.map((method) => method.method), "UI_METHOD");
  for (const expected of DOCUMENTED_UI_METHODS) {
    const method = knownMethods.find((candidate) => candidate.method === expected.method);
    if (method === undefined) fail(`UNMAPPED_UI_METHOD:${expected.method}`);
    if (
      method.treatment !== expected.treatment ||
      method.wireMethod !== expected.wireMethod ||
      method.category !== expected.category ||
      method.rpcBehavior !== expected.rpcBehavior
    ) {
      fail(`UI_METHOD_TREATMENT:${expected.method}`);
    }
  }
  const catchAll = manifest.uiMethods.filter((method) => method.isCatchAll);
  if (catchAll.length !== 1 || catchAll[0]?.method !== "*" || catchAll[0].treatment !== "retained") {
    fail("UI_METHOD_CATCH_ALL");
  }
}

function assertInvocationCoverage(manifest: CompatibilityManifest): void {
  const sourceIds = new Set(manifest.sources.map((source) => source.id));
  const invocationKeys: string[] = [];
  for (const invocation of manifest.invocations) {
    if (!INVOCATION_PATH.test(invocation.path)) fail(`INVOCATION_PATH:${invocation.id}`);
    if (invocation.requiresTerminal && invocation.treatment !== "terminal") fail(`INVOCATION_TREATMENT:${invocation.id}`);
    if (invocation.sourceIds.length === 0) fail(`INVOCATION_SOURCE:${invocation.id}`);
    for (const sourceId of invocation.sourceIds) {
      if (!sourceIds.has(sourceId)) fail(`UNKNOWN_INVOCATION_SOURCE:${invocation.id}`);
    }
    if (!Number.isSafeInteger(invocation.watchdog.deadlineMs) || invocation.watchdog.deadlineMs <= 0) {
      fail(`INVOCATION_WATCHDOG:${invocation.id}`);
    }
    if (invocation.requiresTerminal && invocation.expectedActivity.terminal !== "attached") {
      fail(`INVOCATION_ACTIVITY:${invocation.id}`);
    }
    invocationKeys.push(`${invocation.path}\u0000${argumentShapeKey(invocation.argumentShape)}`);
  }
  assertUnique(invocationKeys, "INVOCATION");
  for (const path of REQUIRED_TERMINAL_PATHS) {
    const matches = manifest.invocations.filter((invocation) => invocation.path === path && invocation.requiresTerminal);
    if (matches.length !== 1) fail(`UNMAPPED_TERMINAL_PATH:${path}`);
  }
}

function argumentShapeKey(shape: InvocationArgumentShape): string {
  if (shape.kind === "any" || shape.kind === "empty") return shape.kind;
  return `first-token:${shape.values.join("\u0001")}`;
}

function assertUnique(values: readonly string[], code: string): void {
  const seen = new Set<string>();
  for (const value of values) {
    if (seen.has(value)) fail(`DUPLICATE_${code}:${value}`);
    seen.add(value);
  }
}

function fail(detail: string): never {
  throw new Error(`COMPATIBILITY_COVERAGE_${detail}`);
}
