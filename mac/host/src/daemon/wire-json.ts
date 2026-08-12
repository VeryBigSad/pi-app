import type { JsonObject } from "@pimobile/protocol";

/**
 * Library objects (e.g. SimpleWebAuthn options) may carry explicit `undefined`
 * fields, which the strict envelope validator rejects. JSON round-trip drops them.
 */
export function wireJsonObject(value: unknown): JsonObject {
  return JSON.parse(JSON.stringify(value)) as JsonObject;
}
