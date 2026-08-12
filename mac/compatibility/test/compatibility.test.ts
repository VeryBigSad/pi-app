import { describe, expect, it } from "vitest";

import {
  COMPATIBILITY_MANIFEST,
  DOCUMENTED_RPC_COMMAND_GROUPS,
  DOCUMENTED_RPC_EVENTS,
  DOCUMENTED_UI_METHODS,
  PINNED_PI_RUNTIME,
  REQUIRED_TERMINAL_PATHS,
  assertCapabilityCoverage,
  createCompatibilityManifest,
  isTerminalRoute,
  parseLeadingInvocation,
  routeInvocation,
  serializeCompatibilityManifest,
  verifyManifestIntegrity,
} from "../src/index.js";

describe("capability coverage", () => {
  it("maps every documented RPC command group, event, and UI method", () => {
    assertCapabilityCoverage(COMPATIBILITY_MANIFEST);
    expect(COMPATIBILITY_MANIFEST.rpcCommandGroups.map((group) => group.id)).toEqual(
      DOCUMENTED_RPC_COMMAND_GROUPS.map((group) => group.id),
    );
    expect(COMPATIBILITY_MANIFEST.events.filter((event) => !event.isCatchAll).map((event) => event.name)).toEqual(
      DOCUMENTED_RPC_EVENTS.map((event) => event.name),
    );
    expect(COMPATIBILITY_MANIFEST.uiMethods.filter((method) => !method.isCatchAll).map((method) => method.method)).toEqual(
      DOCUMENTED_UI_METHODS.map((method) => method.method),
    );
  });

  it("fails when a documented RPC command is unmapped", () => {
    const broken = {
      ...COMPATIBILITY_MANIFEST,
      rpcCommandGroups: COMPATIBILITY_MANIFEST.rpcCommandGroups.map((group) =>
        group.id === "state"
          ? {
              ...group,
              commands: group.commands.map((command) =>
                command.name === "get_state" ? { ...command, name: "unmapped_command" } : command,
              ),
            }
          : group,
      ),
    };
    expect(() => assertCapabilityCoverage(broken)).toThrow("COMPATIBILITY_COVERAGE_UNMAPPED_COMMAND:get_state");
  });

  it("fails when a documented event is unmapped", () => {
    const broken = {
      ...COMPATIBILITY_MANIFEST,
      events: COMPATIBILITY_MANIFEST.events.filter((event) => event.name !== "agent_settled"),
    };
    expect(() => assertCapabilityCoverage(broken)).toThrow("COMPATIBILITY_COVERAGE_EVENT_COUNT");
  });

  it("fails when a documented UI method is unmapped", () => {
    const broken = {
      ...COMPATIBILITY_MANIFEST,
      uiMethods: COMPATIBILITY_MANIFEST.uiMethods.filter((method) => method.method !== "setEditorText"),
    };
    expect(() => assertCapabilityCoverage(broken)).toThrow("COMPATIBILITY_COVERAGE_UI_METHOD_COUNT");
  });
});

describe("manifest generation", () => {
  it("is deterministic and binds supplied sanitized integrity inputs", () => {
    const first = createCompatibilityManifest({
      runtime: PINNED_PI_RUNTIME,
      sourceIntegrityInputs: [
        { sourceId: "pi-mcp-adapter", version: "2.21.1", sha256: "b".repeat(64) },
        { sourceId: "pi-runtime", sha256: "a".repeat(64) },
      ],
    });
    const second = createCompatibilityManifest({
      runtime: PINNED_PI_RUNTIME,
      sourceIntegrityInputs: [
        { sourceId: "pi-runtime", sha256: "a".repeat(64) },
        { sourceId: "pi-mcp-adapter", version: "2.21.1", sha256: "b".repeat(64) },
      ],
    });
    expect(first).toEqual(second);
    expect(serializeCompatibilityManifest(first)).toBe(serializeCompatibilityManifest(second));
    expect(first.integrity.sourceInputs.map((input) => input.sourceId)).toEqual(["pi-mcp-adapter", "pi-runtime"]);
    expect(verifyManifestIntegrity(first)).toBe(true);
    expect(verifyManifestIntegrity({ ...first, events: first.events.filter((event) => event.name !== "agent_settled") })).toBe(false);
  });

  it("changes integrity when an input changes and rejects another Pi runtime", () => {
    const first = createCompatibilityManifest({
      runtime: PINNED_PI_RUNTIME,
      sourceIntegrityInputs: [{ sourceId: "pi-runtime", sha256: "a".repeat(64) }],
    });
    const second = createCompatibilityManifest({
      runtime: PINNED_PI_RUNTIME,
      sourceIntegrityInputs: [{ sourceId: "pi-runtime", sha256: "b".repeat(64) }],
    });
    expect(first.integrity.digest).not.toBe(second.integrity.digest);
    expect(() =>
      createCompatibilityManifest({
        runtime: { packageName: PINNED_PI_RUNTIME.packageName, version: "0.85.0" },
        sourceIntegrityInputs: [],
      }),
    ).toThrow("COMPATIBILITY_MANIFEST_RUNTIME_MISMATCH");
  });
});

describe("invocation routing", () => {
  it.each([
    ["/mcp", "unknown"],
    ["/usage", "read_only"],
    ["/agents", "mutation"],
    ["/btw", "mutation"],
    ["/llama", "external_mutation"],
  ] as const)("pre-routes %s to terminal", (path, sideEffectClass) => {
    const route = routeInvocation(`${path} argument`);
    expect(isTerminalRoute(route)).toBe(true);
    if (!isTerminalRoute(route)) throw new Error("expected terminal route");
    expect(route.path).toBe(path);
    expect(route.argumentText).toBe(" argument");
    expect(route.mustNotSendToRpc).toBe(true);
    expect(route.invocation.sideEffectClass).toBe(sideEffectClass);
    expect(route.invocation.expectedActivity.rpc).toBe("none");
    expect(route.invocation.expectedActivity.terminal).toBe("attached");
    expect(route.invocation.watchdog.retryInvocation).toBe(false);
  });

  it("routes only exact leading terminal invocations", () => {
    const inputs = [
      "please inspect /mcp first",
      "prefix /usage",
      "ordinary /agents text",
      "mention /btw in prose",
      "a /llama mention",
      "/mcpish",
      "/usage-report",
      "/agents/setup",
      " /btw",
      "text\n/llama",
    ];
    for (const input of inputs) {
      const route = routeInvocation(input);
      expect(route.target).toBe("semantic");
      expect(route.target === "semantic" ? route.invocation : null).toBeNull();
    }
  });

  it("keeps unknown leading commands semantic under the no-retry watchdog", () => {
    const route = routeInvocation("/future-extension run");
    expect(route.target).toBe("semantic");
    if (route.target !== "semantic") throw new Error("expected semantic route");
    expect(route.leadingInvocation).toEqual({ path: "/future-extension", argumentText: " run" });
    expect(route.watchdog?.timeoutAction).toBe("restart_and_resync");
    expect(route.watchdog?.retryInvocation).toBe(false);
  });

  it("parses a path only from the first character of the prompt", () => {
    expect(parseLeadingInvocation("/mcp\tlist")).toEqual({ path: "/mcp", argumentText: "\tlist" });
    expect(parseLeadingInvocation("say /mcp")).toBeUndefined();
    expect(parseLeadingInvocation(" /mcp")).toBeUndefined();
    expect(REQUIRED_TERMINAL_PATHS).toEqual(["/mcp", "/usage", "/agents", "/btw", "/llama"]);
  });
});
