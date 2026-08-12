import { createHash } from "node:crypto";

import { MANIFEST_VERSION, PINNED_PI_RUNTIME } from "./contracts.js";
import { assertCapabilityCoverage } from "./coverage.js";
import { CAPABILITY_SOURCES, INVOCATIONS, RPC_COMMAND_GROUPS, RPC_EVENTS, UI_METHODS } from "./static-manifest.js";
import type {
  CapabilitySource,
  CompatibilityManifest,
  ManifestBuildInput,
  PinnedRuntimeIdentity,
  SourceIntegrityInput,
} from "./types.js";

interface NormalizedManifestBuildInput {
  readonly runtime: PinnedRuntimeIdentity;
  readonly sourceIntegrityInputs: readonly SourceIntegrityInput[];
}

interface IntegrityPayload {
  readonly manifestVersion: number;
  readonly runtime: PinnedRuntimeIdentity;
  readonly sources: CompatibilityManifest["sources"];
  readonly rpcCommandGroups: CompatibilityManifest["rpcCommandGroups"];
  readonly events: CompatibilityManifest["events"];
  readonly uiMethods: CompatibilityManifest["uiMethods"];
  readonly invocations: CompatibilityManifest["invocations"];
  readonly sourceIntegrityInputs: readonly SourceIntegrityInput[];
}

export function createCompatibilityManifest(input: ManifestBuildInput): CompatibilityManifest {
  const normalized = normalizeBuildInput(input);
  const payload = integrityPayload(normalized.runtime, normalized.sourceIntegrityInputs);
  const manifest: CompatibilityManifest = {
    manifestVersion: MANIFEST_VERSION,
    runtime: payload.runtime,
    sources: payload.sources,
    rpcCommandGroups: payload.rpcCommandGroups,
    events: payload.events,
    uiMethods: payload.uiMethods,
    invocations: payload.invocations,
    integrity: {
      algorithm: "sha256",
      digest: digest(payload),
      sourceInputs: normalized.sourceIntegrityInputs,
    },
  };
  assertCapabilityCoverage(manifest);
  return manifest;
}

export const COMPATIBILITY_MANIFEST = createCompatibilityManifest({
  runtime: PINNED_PI_RUNTIME,
  sourceIntegrityInputs: [],
});

export function verifyManifestIntegrity(manifest: CompatibilityManifest): boolean {
  return manifest.integrity.digest === digest({
    manifestVersion: manifest.manifestVersion,
    runtime: manifest.runtime,
    sources: manifest.sources,
    rpcCommandGroups: manifest.rpcCommandGroups,
    events: manifest.events,
    uiMethods: manifest.uiMethods,
    invocations: manifest.invocations,
    sourceIntegrityInputs: manifest.integrity.sourceInputs,
  });
}

export function serializeCompatibilityManifest(manifest: CompatibilityManifest): string {
  return `${JSON.stringify(manifest)}\n`;
}

function integrityPayload(runtime: PinnedRuntimeIdentity, sourceIntegrityInputs: readonly SourceIntegrityInput[]): IntegrityPayload {
  return {
    manifestVersion: MANIFEST_VERSION,
    runtime,
    sources: CAPABILITY_SOURCES,
    rpcCommandGroups: RPC_COMMAND_GROUPS,
    events: RPC_EVENTS,
    uiMethods: UI_METHODS,
    invocations: INVOCATIONS,
    sourceIntegrityInputs,
  };
}

function normalizeBuildInput(input: unknown): NormalizedManifestBuildInput {
  if (!isRecord(input)) fail("INVALID_INPUT");
  const runtime = input["runtime"];
  const sourceIntegrityInputs = input["sourceIntegrityInputs"];
  if (!isPinnedRuntimeIdentity(runtime) || !Array.isArray(sourceIntegrityInputs)) fail("INVALID_INPUT");
  if (runtime.packageName !== PINNED_PI_RUNTIME.packageName || runtime.version !== PINNED_PI_RUNTIME.version) {
    fail("RUNTIME_MISMATCH");
  }
  const knownSources = new Map<string, CapabilitySource>();
  for (const source of CAPABILITY_SOURCES) {
    knownSources.set(source.id, source);
  }
  const normalizedInputs: SourceIntegrityInput[] = [];
  for (const value of sourceIntegrityInputs) {
    const normalizedInput = normalizeSourceIntegrityInput(value);
    const source = knownSources.get(normalizedInput.sourceId);
    if (source === undefined) fail("UNKNOWN_INTEGRITY_SOURCE");
    if (normalizedInput.version !== undefined && normalizedInput.version !== source.version) {
      fail("INTEGRITY_SOURCE_VERSION_MISMATCH");
    }
    normalizedInputs.push(normalizedInput);
  }
  normalizedInputs.sort(compareSourceIntegrityInputs);
  for (let index = 1; index < normalizedInputs.length; index += 1) {
    if (normalizedInputs[index - 1]?.sourceId === normalizedInputs[index]?.sourceId) fail("DUPLICATE_INTEGRITY_SOURCE");
  }
  return {
    runtime: { packageName: runtime.packageName, version: runtime.version },
    sourceIntegrityInputs: normalizedInputs,
  };
}

function normalizeSourceIntegrityInput(value: unknown): SourceIntegrityInput {
  if (!isRecord(value)) fail("INVALID_INTEGRITY_SOURCE");
  const sourceId = value["sourceId"];
  const sha256 = value["sha256"];
  const version = value["version"];
  if (
    typeof sourceId !== "string" ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u.test(sourceId) ||
    typeof sha256 !== "string" ||
    !/^[a-f0-9]{64}$/u.test(sha256) ||
    (version !== undefined && typeof version !== "string")
  ) {
    fail("INVALID_INTEGRITY_SOURCE");
  }
  if (version === undefined) return { sourceId, sha256 };
  return { sourceId, sha256, version };
}

function isPinnedRuntimeIdentity(value: unknown): value is PinnedRuntimeIdentity {
  if (!isRecord(value)) return false;
  return typeof value["packageName"] === "string" && typeof value["version"] === "string";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function compareSourceIntegrityInputs(left: SourceIntegrityInput, right: SourceIntegrityInput): number {
  return compareStrings(left.sourceId, right.sourceId) || compareStrings(left.version ?? "", right.version ?? "") || compareStrings(left.sha256, right.sha256);
}

function compareStrings(left: string, right: string): number {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

function digest(payload: IntegrityPayload): string {
  return createHash("sha256").update(JSON.stringify(payload), "utf8").digest("hex");
}

function fail(detail: string): never {
  throw new Error(`COMPATIBILITY_MANIFEST_${detail}`);
}
