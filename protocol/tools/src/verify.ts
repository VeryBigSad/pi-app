import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsModule from "ajv-formats";

const here = dirname(fileURLToPath(import.meta.url));
const protocolDirectory = resolve(here, "../..");
const fixture = JSON.parse(await readFile(resolve(protocolDirectory, "fixtures/pimb-v1.json"), "utf8")) as {
  version: number;
  hardBounds: Record<string, number>;
  frames: unknown[];
  hashes: unknown[];
  rawRecords: unknown[];
  streamOrderCases: unknown[];
  pairingBindingCases: unknown[];
  unlockBindingCases: unknown[];
  pairingTokenCases: unknown[];
  approvalCases: unknown[];
  approvalLifecycleCases: unknown[];
  terminalHistoryCases: unknown[];
  promptImageCases: unknown[];
  recoveryCursorCases: unknown[];
  snapshotRecoveryCases: unknown[];
  assistantCases: unknown[];
  schemaCases: { name: string; schema: "messages" | "route" | "pairing"; valid: boolean; value: unknown }[];
  wireMessageCases: { name: string; type: string; body: unknown; valid: boolean; expectedError?: string }[];
  jcsNumberCases: { name: string; lexeme: string; canonical: string }[];
  envelopeCases: { name: string; json: string; valid: boolean; expectedError?: string }[];
};
const requiredFixtureSections = [
  "frames", "hashes", "rawRecords", "streamOrderCases", "pairingBindingCases", "unlockBindingCases", "pairingTokenCases", "approvalCases",
  "approvalLifecycleCases", "terminalHistoryCases", "promptImageCases", "recoveryCursorCases", "snapshotRecoveryCases", "assistantCases", "schemaCases",
  "jcsNumberCases", "envelopeCases", "wireMessageCases",
] as const;
if (fixture.version !== 1 || Object.keys(fixture.hardBounds).length === 0 || requiredFixtureSections.some((key) => fixture[key].length === 0)) {
  throw new Error("Fixture corpus is incomplete");
}

const schemaNames = ["envelope.schema.json", "messages.schema.json", "route.schema.json", "pairing-invitation.schema.json"] as const;
const schemas = await Promise.all(schemaNames.map(async (name) => JSON.parse(await readFile(resolve(protocolDirectory, "schema", name), "utf8")) as Schema));
const ajv = new Ajv2020({ allErrors: true, strict: true, strictTypes: false, strictRequired: false, allowUnionTypes: true });
const addFormats = addFormatsModule as unknown as (instance: Ajv2020) => Ajv2020;
addFormats(ajv);
ajv.addFormat("uint64", {
  type: "string",
  validate: (value: string) => /^(0|[1-9][0-9]{0,19})$/u.test(value) && BigInt(value) <= 18_446_744_073_709_551_615n,
});
ajv.addKeyword({
  keyword: "x-maxUtf8Bytes",
  type: "string",
  schemaType: "number",
  validate: (limit: number, value: string) => Buffer.byteLength(value, "utf8") <= limit,
});
ajv.addKeyword({
  keyword: "x-maxJsonBytes",
  schemaType: "number",
  validate: (limit: number, value: unknown) => Buffer.byteLength(JSON.stringify(value), "utf8") <= limit,
});
ajv.addKeyword({
  keyword: "x-maxUint64",
  type: "string",
  schemaType: "string",
  validate: (limit: string, value: string) => /^(0|[1-9][0-9]{0,19})$/u.test(value) && BigInt(value) <= BigInt(limit),
});
ajv.addKeyword({
  keyword: "x-maxDecodedBytes",
  type: "string",
  schemaType: "number",
  validate: (limit: number, value: string) => {
    if (!/^[A-Za-z0-9_-]+$/u.test(value)) return false;
    try {
      return Buffer.from(value, "base64url").length <= limit;
    } catch {
      return false;
    }
  },
});
for (const schema of schemas) {
  if (!ajv.validateSchema(schema)) throw new Error(`Invalid schema ${schema.$id}: ${ajv.errorsText(ajv.errors)}`);
  ajv.addSchema(schema);
}

const validators = {
  messages: requireValidator("https://verybigsad.github.io/pi-mobile/schema/messages-v1.json"),
  route: requireValidator("https://verybigsad.github.io/pi-mobile/schema/route-v1.json"),
  pairing: requireValidator("https://verybigsad.github.io/pi-mobile/schema/pairing-invitation-v1.json"),
};
for (const fixtureCase of fixture.schemaCases) {
  const result = validators[fixtureCase.schema](fixtureCase.value);
  if (result !== fixtureCase.valid) {
    throw new Error(`${fixtureCase.name} expected valid=${fixtureCase.valid}: ${ajv.errorsText(validators[fixtureCase.schema].errors)}`);
  }
}

for (const { lexeme, canonical } of fixture.jcsNumberCases) {
  const reference = JSON.stringify(JSON.parse(lexeme));
  if (reference !== canonical) throw new Error(`JCS number fixture mismatch for ${lexeme}: fixture=${canonical}, reference=${reference}`);
}

const messageCatalog = JSON.parse(await readFile(resolve(protocolDirectory, "catalog/message-types.json"), "utf8")) as { types: { type: string }[] };
const messagesSchema = schemas.find((schema) => schema.$id.endsWith("messages-v1.json"));
if (messagesSchema === undefined) throw new Error("Messages schema missing");
const mappedTypes = new Set<string>();
for (const entry of messagesSchema.allOf ?? []) {
  const typeSchema = entry.if?.properties?.type;
  if (typeof typeSchema?.const === "string") mappedTypes.add(typeSchema.const);
  for (const type of typeSchema?.enum ?? []) mappedTypes.add(type);
}
const catalogTypes = new Set(messageCatalog.types.map(({ type }) => type));
const unmapped = [...catalogTypes].filter((type) => !mappedTypes.has(type));
const undocumented = [...mappedTypes].filter((type) => !catalogTypes.has(type));
if (unmapped.length !== 0 || undocumented.length !== 0) throw new Error(`Schema/catalog mismatch; unmapped=${unmapped.join(",")}; undocumented=${undocumented.join(",")}`);

const constants = JSON.parse(await readFile(resolve(protocolDirectory, "catalog/constants.json"), "utf8")) as { limits: Record<string, number> };
if (Object.keys(constants.limits).length !== Object.keys(fixture.hardBounds).length) throw new Error("Fixture/catalog hard-bound key count differs");
for (const [key, value] of Object.entries(fixture.hardBounds)) {
  if (constants.limits[key] !== value) throw new Error(`Hard bound mismatch for ${key}: fixture=${value}, catalog=${constants.limits[key]}`);
}

console.log(`verified ${schemas.length} executable schemas, ${mappedTypes.size} known messages, ${fixture.schemaCases.length} schema cases, ${requiredFixtureSections.length} shared fixture sections`);

interface Schema {
  readonly $id: string;
  readonly allOf?: readonly {
    readonly if?: { readonly properties?: { readonly type?: { readonly const?: string; readonly enum?: readonly string[] } } };
  }[];
}

function requireValidator(id: string): ReturnType<typeof ajv.compile> {
  const validator = ajv.getSchema(id);
  if (validator === undefined) throw new Error(`Schema was not registered: ${id}`);
  return validator;
}
