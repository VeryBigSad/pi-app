import { randomBytes } from "node:crypto";
import { mkdir, open, readFile, rename, rm, stat } from "node:fs/promises";
import { basename, dirname, join } from "node:path";

const MAX_HOST_STORE_BYTES = 1 << 20;
const MAX_ROUTE_STATE_BYTES = 64 << 10;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const ROUTE_ID = /^[A-Za-z0-9._-]{1,128}$/u;

export interface E2eHostStateSummary {
  readonly ownerCredentialCount: number;
  readonly deviceCount: number;
  readonly instanceId: string;
  readonly routeId?: string;
}

export interface E2eHostStateSnapshot {
  readonly directory: string;
  readonly summary: E2eHostStateSummary;
}

export async function isolateHostState(
  dataDirectory: string,
  backupParent: string,
  onSnapshotPrepared: (snapshot: E2eHostStateSnapshot) => void = () => undefined,
): Promise<E2eHostStateSnapshot> {
  const storePath = join(dataDirectory, "host-store.json");
  const routePath = join(dataDirectory, "relay", "route-state.json");
  const hostStore = await readBoundedFile(storePath, MAX_HOST_STORE_BYTES, 0o600, "E2E_HOST_STORE_UNSAFE");
  const routeState = await readBoundedFile(routePath, MAX_ROUTE_STATE_BYTES, 0o600, "E2E_ROUTE_STATE_UNSAFE");
  const parsed = parseHostStore(hostStore);
  const parsedRoute = parseRouteState(routeState);
  if (parsed.summary.routeId !== undefined && parsedRoute.routeId !== parsed.summary.routeId) {
    throw new Error("E2E_ROUTE_STATE_MISMATCH");
  }
  await mkdir(backupParent, { recursive: true, mode: 0o700 });
  const backupDirectory = join(backupParent, `host-state-${randomBytes(16).toString("hex")}`);
  await mkdir(backupDirectory, { mode: 0o700 });
  try {
    await atomicWriteFile(join(backupDirectory, "host-store.json"), hostStore, 0o600);
    await atomicWriteFile(join(backupDirectory, "route-state.json"), routeState, 0o600);
    const snapshot = { directory: backupDirectory, summary: parsed.summary };
    onSnapshotPrepared(snapshot);
    const isolated: Record<string, unknown> = {
      ...parsed.value,
      ownerCredentials: [],
      devices: [],
    };
    delete isolated["ownerUserId"];
    delete isolated["ownerUserHandle"];
    await atomicWriteFile(storePath, Buffer.from(`${JSON.stringify(isolated, null, 2)}\n`, "utf8"), 0o600);
    const verified = parseHostStore(await readBoundedFile(storePath, MAX_HOST_STORE_BYTES, 0o600, "E2E_HOST_STORE_UNSAFE"));
    if (verified.summary.ownerCredentialCount !== 0 || verified.summary.deviceCount !== 0
      || verified.summary.instanceId !== parsed.summary.instanceId || verified.summary.routeId !== parsed.summary.routeId) {
      throw new Error("E2E_HOST_STATE_ISOLATION_FAILED");
    }
    return snapshot;
  } catch (error) {
    await atomicWriteFile(storePath, hostStore, 0o600).catch(() => undefined);
    await atomicWriteFile(routePath, routeState, 0o600).catch(() => undefined);
    throw error;
  }
}

export async function restoreHostState(dataDirectory: string, snapshot: E2eHostStateSnapshot): Promise<void> {
  const backupStore = await readBoundedFile(
    join(snapshot.directory, "host-store.json"),
    MAX_HOST_STORE_BYTES,
    0o600,
    "E2E_HOST_BACKUP_UNSAFE",
  );
  const backupRoute = await readBoundedFile(
    join(snapshot.directory, "route-state.json"),
    MAX_ROUTE_STATE_BYTES,
    0o600,
    "E2E_ROUTE_BACKUP_UNSAFE",
  );
  const backupSummary = parseHostStore(backupStore).summary;
  if (!sameSummary(backupSummary, snapshot.summary)) throw new Error("E2E_HOST_BACKUP_MISMATCH");
  await atomicWriteFile(join(dataDirectory, "host-store.json"), backupStore, 0o600);
  await atomicWriteFile(join(dataDirectory, "relay", "route-state.json"), backupRoute, 0o600);
  const restored = await inspectHostState(dataDirectory);
  if (!sameSummary(restored, snapshot.summary)) throw new Error("E2E_HOST_RESTORE_MISMATCH");
  await rm(snapshot.directory, { recursive: true, force: true });
}

export async function inspectHostState(dataDirectory: string): Promise<E2eHostStateSummary> {
  const hostStore = await readBoundedFile(
    join(dataDirectory, "host-store.json"),
    MAX_HOST_STORE_BYTES,
    0o600,
    "E2E_HOST_STORE_UNSAFE",
  );
  return parseHostStore(hostStore).summary;
}

export async function atomicWriteFile(path: string, bytes: Uint8Array, mode: number): Promise<void> {
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  const temporary = join(dirname(path), `.${basename(path)}.${randomBytes(12).toString("hex")}.tmp`);
  const handle = await open(temporary, "wx", mode);
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, path);
  const metadata = await stat(path);
  if (!metadata.isFile() || (metadata.mode & 0o777) !== mode || metadata.size !== bytes.byteLength) {
    throw new Error("E2E_ATOMIC_WRITE_VERIFY_FAILED");
  }
}

async function readBoundedFile(path: string, limit: number, mode: number, code: string): Promise<Uint8Array> {
  const metadata = await stat(path).catch(() => undefined);
  if (metadata === undefined || !metadata.isFile() || (metadata.mode & 0o777) !== mode
    || metadata.size <= 0 || metadata.size > limit) {
    throw new Error(code);
  }
  return await readFile(path);
}

function parseHostStore(bytes: Uint8Array): { value: Record<string, unknown>; summary: E2eHostStateSummary } {
  const value = parseRecord(bytes, "E2E_HOST_STORE_INVALID");
  if (value["version"] !== 1 || typeof value["instanceId"] !== "string" || !UUID_V4.test(value["instanceId"])
    || !Array.isArray(value["ownerCredentials"]) || !Array.isArray(value["devices"])) {
    throw new Error("E2E_HOST_STORE_INVALID");
  }
  const relay = value["relay"];
  const routeId = isRecord(relay) && typeof relay["routeId"] === "string" && ROUTE_ID.test(relay["routeId"])
    ? relay["routeId"]
    : undefined;
  return {
    value,
    summary: {
      ownerCredentialCount: value["ownerCredentials"].length,
      deviceCount: value["devices"].length,
      instanceId: value["instanceId"],
      ...(routeId === undefined ? {} : { routeId }),
    },
  };
}

function parseRouteState(bytes: Uint8Array): { readonly routeId: string } {
  const value = parseRecord(bytes, "E2E_ROUTE_STATE_INVALID");
  const routeId = value["routeId"];
  if (typeof routeId !== "string" || !ROUTE_ID.test(routeId)) throw new Error("E2E_ROUTE_STATE_INVALID");
  return { routeId };
}

function parseRecord(bytes: Uint8Array, code: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(Buffer.from(bytes).toString("utf8"));
    if (!isRecord(value)) throw new Error(code);
    return value;
  } catch {
    throw new Error(code);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sameSummary(left: E2eHostStateSummary, right: E2eHostStateSummary): boolean {
  return left.ownerCredentialCount === right.ownerCredentialCount
    && left.deviceCount === right.deviceCount
    && left.instanceId === right.instanceId
    && left.routeId === right.routeId;
}
