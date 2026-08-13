import { COMPATIBILITY_MANIFEST } from "./manifest.js";
import type {
  CompatibilityManifest,
  ExpectedInvocationActivity,
  InvocationArgumentShape,
  InvocationManifestEntry,
  InvocationRoute,
  InvocationWatchdog,
  ParsedLeadingInvocation,
  SemanticInvocationRoute,
  TerminalInvocationRoute,
} from "./types.js";

const LEADING_INVOCATION = /^\/[A-Za-z0-9][A-Za-z0-9:_-]*(?:\/[A-Za-z0-9][A-Za-z0-9:_-]*)*(?=$|\s)/u;

const SEMANTIC_ACTIVITY = {
  rpc: "prompt_response_then_settled",
  terminal: "none",
} as const satisfies ExpectedInvocationActivity;

export const UNEXPECTED_LEADING_INVOCATION_WATCHDOG = {
  deadlineMs: 15_000,
  expectedActivity: "rpc_response_then_settled",
  timeoutAction: "restart_and_resync",
  retryInvocation: false,
  markSideEffectUnknown: true,
} as const satisfies InvocationWatchdog;

export function parseLeadingInvocation(input: string): ParsedLeadingInvocation | undefined {
  const match = LEADING_INVOCATION.exec(input);
  if (match === null) return undefined;
  const path = match[0];
  return { path, argumentText: input.slice(path.length) };
}

export function routeInvocation(input: unknown, manifest: CompatibilityManifest = COMPATIBILITY_MANIFEST): InvocationRoute {
  if (typeof input !== "string") throw new Error("COMPATIBILITY_ROUTER_INVALID_INPUT");
  const leadingInvocation = parseLeadingInvocation(input);
  if (leadingInvocation === undefined) return ordinarySemanticRoute(input);
  const invocation = findInvocation(manifest, leadingInvocation);
  if (invocation === undefined) return unexpectedSemanticRoute(input, leadingInvocation);
  if (invocation.requiresTerminal) return terminalRoute(input, leadingInvocation, invocation);
  return semanticRoute(input, leadingInvocation, invocation, invocation.watchdog);
}

export function isTerminalRoute(route: InvocationRoute): route is TerminalInvocationRoute {
  return route.target === "terminal";
}

function findInvocation(
  manifest: CompatibilityManifest,
  leadingInvocation: ParsedLeadingInvocation,
): InvocationManifestEntry | undefined {
  return manifest.invocations.find(
    (candidate) => candidate.path === leadingInvocation.path && matchesArgumentShape(candidate.argumentShape, leadingInvocation.argumentText),
  );
}

function matchesArgumentShape(shape: InvocationArgumentShape, argumentText: string): boolean {
  if (shape.kind === "any") return true;
  const trimmed = argumentText.trim();
  if (shape.kind === "empty") return trimmed.length === 0;
  const firstToken = trimmed.split(/\s+/u)[0];
  return firstToken !== undefined && shape.values.includes(firstToken);
}

function terminalRoute(
  input: string,
  leadingInvocation: ParsedLeadingInvocation,
  invocation: InvocationManifestEntry,
): TerminalInvocationRoute {
  return {
    target: "terminal",
    input,
    path: leadingInvocation.path,
    argumentText: leadingInvocation.argumentText,
    invocation,
    mustNotSendToRpc: true,
  };
}

function ordinarySemanticRoute(input: string): SemanticInvocationRoute {
  return {
    target: "semantic",
    input,
    leadingInvocation: null,
    invocation: null,
    sideEffectClass: "unknown",
    expectedActivity: SEMANTIC_ACTIVITY,
    watchdog: null,
  };
}

function unexpectedSemanticRoute(input: string, leadingInvocation: ParsedLeadingInvocation): SemanticInvocationRoute {
  return {
    target: "semantic",
    input,
    leadingInvocation,
    invocation: null,
    sideEffectClass: "unknown",
    expectedActivity: SEMANTIC_ACTIVITY,
    watchdog: UNEXPECTED_LEADING_INVOCATION_WATCHDOG,
  };
}

function semanticRoute(
  input: string,
  leadingInvocation: ParsedLeadingInvocation,
  invocation: InvocationManifestEntry,
  watchdog: InvocationWatchdog,
): SemanticInvocationRoute {
  return {
    target: "semantic",
    input,
    leadingInvocation,
    invocation,
    sideEffectClass: invocation.sideEffectClass,
    expectedActivity: invocation.expectedActivity,
    watchdog,
  };
}
