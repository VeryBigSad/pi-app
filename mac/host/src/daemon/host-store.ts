import { constants } from "node:fs";
import { open, readFile } from "node:fs/promises";
import { atomicWriteFile } from "../security/atomic-file.js";
import { SecurityError } from "../security/security-error.js";
import type { AuthenticatorTransportFuture } from "@simplewebauthn/server";
import type { StoredOwnerCredential } from "../security/webauthn.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const BASE64URL = /^[A-Za-z0-9_-]+$/;
const MAX_STORE_BYTES = 256 * 1024;
const MAX_DEVICES = 64;
const MAX_CREDENTIALS = 16;

export interface PairedDevice {
  readonly deviceId: string;
  readonly certificateId: string;
  readonly deviceRouteKeyId?: string;
  readonly createdAtMs: number;
}

interface HostStoreState {
  version: 1;
  instanceId: string;
  ownerUserId?: string;
  ownerUserHandle?: string;
  ownerCredentials: StoredOwnerCredential[];
  devices: PairedDevice[];
  relay?: {
    routeId: string;
    bootstrapPending?: boolean;
  };
  pushEndpoint?: {
    deviceId: string;
    endpointId: string;
    distributor: string;
    endpoint: string;
    wakePublicKey?: string;
  };
}

/** Fail-closed JSON store for owner credentials, paired devices, and relay/push registrations. */
export class HostStore {
  private state: HostStoreState | undefined;

  constructor(private readonly path: string) {}

  async load(createInstanceId: () => string): Promise<void> {
    let raw: Buffer;
    try {
      const handle = await open(this.path, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        const metadata = await handle.stat();
        if (!metadata.isFile() || (metadata.mode & 0o077) !== 0) {
          throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store permissions are unsafe");
        }
        if (metadata.size > MAX_STORE_BYTES) {
          throw new SecurityError("SECURITY_INVALID_INPUT", "host store exceeds size bounds");
        }
        raw = await handle.readFile();
      } finally {
        await handle.close();
      }
    } catch (error) {
      if (isNodeError(error) && error.code === "ENOENT") {
        this.state = {
          version: 1,
          instanceId: createInstanceId(),
          ownerCredentials: [],
          devices: [],
        };
        await this.persist();
        return;
      }
      if (error instanceof SecurityError) throw error;
      throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store could not be read", { cause: error });
    }
    this.state = parseState(raw);
  }

  instanceId(): string {
    return this.requireState().instanceId;
  }

  ownerUserId(): string | undefined {
    return this.requireState().ownerUserId;
  }

  ownerUserHandle(): string | undefined {
    return this.requireState().ownerUserHandle;
  }

  ownerCredentials(): readonly StoredOwnerCredential[] {
    return this.requireState().ownerCredentials.map(copyCredential);
  }

  findOwnerCredential(credentialId: string): StoredOwnerCredential | undefined {
    const found = this.requireState().ownerCredentials.find((credential) => credential.id === credentialId);
    return found === undefined ? undefined : copyCredential(found);
  }

  async ensureOwnerUser(createUserId: () => string, handle: string): Promise<{ userId: string; userHandle: string }> {
    const state = this.requireState();
    if (state.ownerUserId !== undefined && state.ownerUserHandle !== undefined) {
      return { userId: state.ownerUserId, userHandle: state.ownerUserHandle };
    }
    state.ownerUserId = createUserId();
    state.ownerUserHandle = handle;
    await this.persist();
    return { userId: state.ownerUserId, userHandle: state.ownerUserHandle };
  }

  async addOwnerCredential(credential: StoredOwnerCredential): Promise<void> {
    const state = this.requireState();
    if (state.ownerCredentials.some((existing) => existing.id === credential.id)) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "owner credential is already registered");
    }
    if (state.ownerCredentials.length >= MAX_CREDENTIALS) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "owner credential capacity reached");
    }
    state.ownerCredentials.push(copyCredential(credential));
    await this.persist();
  }

  async updateOwnerCredentialCounter(credentialId: string, counter: number): Promise<void> {
    const state = this.requireState();
    const credential = state.ownerCredentials.find((existing) => existing.id === credentialId);
    if (credential === undefined) throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "owner credential is unknown");
    if (!Number.isSafeInteger(counter) || counter < credential.counter) {
      throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "credential counter regressed");
    }
    state.ownerCredentials = state.ownerCredentials.map((existing) =>
      existing.id === credentialId ? { ...existing, counter } : existing,
    );
    await this.persist();
  }

  devices(): readonly PairedDevice[] {
    return this.requireState().devices.map((device) => ({ ...device }));
  }

  findDevice(deviceId: string): PairedDevice | undefined {
    const found = this.requireState().devices.find((device) => device.deviceId === deviceId);
    return found === undefined ? undefined : { ...found };
  }

  async addDevice(device: PairedDevice): Promise<void> {
    validateDevice(device);
    const state = this.requireState();
    if (state.devices.some((existing) => existing.deviceId === device.deviceId)) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "device is already paired");
    }
    if (state.devices.length >= MAX_DEVICES) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "paired device capacity reached");
    }
    state.devices.push({ ...device });
    await this.persist();
  }

  async removeDevice(deviceId: string): Promise<boolean> {
    const state = this.requireState();
    const before = state.devices.length;
    state.devices = state.devices.filter((device) => device.deviceId !== deviceId);
    if (state.devices.length === before) return false;
    const push = state.pushEndpoint;
    if (push?.deviceId === deviceId) delete state.pushEndpoint;
    await this.persist();
    return true;
  }

  relayRegistration(): { routeId: string } | undefined {
    const relay = this.requireState().relay;
    return relay === undefined ? undefined : { routeId: relay.routeId };
  }

  async setRelayRegistration(routeId: string): Promise<void> {
    if (!/^[A-Za-z0-9._-]{1,128}$/.test(routeId)) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "relay route identity is invalid");
    }
    this.requireState().relay = { routeId };
    await this.persist();
  }

  pushEndpoint(): HostStoreState["pushEndpoint"] {
    const endpoint = this.requireState().pushEndpoint;
    return endpoint === undefined ? undefined : { ...endpoint };
  }

  async setPushEndpoint(endpoint: NonNullable<HostStoreState["pushEndpoint"]>): Promise<void> {
    validatePushEndpoint(endpoint);
    this.requireState().pushEndpoint = { ...endpoint };
    await this.persist();
  }

  async clearPushEndpoint(deviceId: string, endpointId: string): Promise<void> {
    const state = this.requireState();
    const endpoint = state.pushEndpoint;
    if (endpoint?.deviceId === deviceId && endpoint.endpointId === endpointId) {
      delete state.pushEndpoint;
      await this.persist();
    }
  }

  private requireState(): HostStoreState {
    if (this.state === undefined) throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store is not loaded");
    return this.state;
  }

  private async persist(): Promise<void> {
    const state = this.requireState();
    // Uint8Array serializes as a byte object; persist public keys as base64url strings
    // to match the read schema exactly.
    const serializable = {
      ...state,
      ownerCredentials: state.ownerCredentials.map((credential) => ({
        ...credential,
        publicKey: Buffer.from(credential.publicKey).toString("base64url"),
      })),
    };
    await atomicWriteFile(this.path, `${JSON.stringify(serializable, null, 2)}\n`, 0o600);
  }
}

function parseState(raw: Buffer): HostStoreState {
  let value: unknown;
  try {
    value = JSON.parse(raw.toString("utf8"));
  } catch (error) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store is not valid JSON", { cause: error });
  }
  if (!isRecord(value) || value["version"] !== 1 || typeof value["instanceId"] !== "string" || !UUID_V4.test(value["instanceId"])) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store is malformed");
  }
  if (!Array.isArray(value["ownerCredentials"]) || !Array.isArray(value["devices"])) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store is malformed");
  }
  const ownerCredentials = value["ownerCredentials"].map((credential) => parseCredential(credential));
  const devices = value["devices"].map((device) => parseDevice(device));
  const ownerUserId = value["ownerUserId"];
  const ownerUserHandle = value["ownerUserHandle"];
  if (ownerUserId !== undefined && (typeof ownerUserId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(ownerUserId))) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store owner identity is malformed");
  }
  if (ownerUserHandle !== undefined && (typeof ownerUserHandle !== "string" || ownerUserHandle.length === 0 || ownerUserHandle.length > 64)) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store owner name is malformed");
  }
  const relay = value["relay"];
  if (relay !== undefined && (!isRecord(relay) || typeof relay["routeId"] !== "string" || !/^[A-Za-z0-9._-]{1,128}$/.test(relay["routeId"]))) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store relay state is malformed");
  }
  const push = value["pushEndpoint"];
  if (push !== undefined) {
    if (!isRecord(push)) throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store push endpoint is malformed");
    validatePushEndpoint({
      deviceId: requireString(push["deviceId"], "deviceId"),
      endpointId: requireString(push["endpointId"], "endpointId"),
      distributor: requireString(push["distributor"], "distributor"),
      endpoint: requireString(push["endpoint"], "endpoint"),
      ...(typeof push["wakePublicKey"] === "string" ? { wakePublicKey: push["wakePublicKey"] } : {}),
    });
  }
  return {
    version: 1,
    instanceId: value["instanceId"],
    ...(typeof ownerUserId === "string" ? { ownerUserId } : {}),
    ...(typeof ownerUserHandle === "string" ? { ownerUserHandle } : {}),
    ownerCredentials,
    devices,
    ...(isRecord(relay) ? { relay: { routeId: relay["routeId"] as string } } : {}),
    ...(isRecord(push)
      ? {
          pushEndpoint: {
            deviceId: push["deviceId"] as string,
            endpointId: push["endpointId"] as string,
            distributor: push["distributor"] as string,
            endpoint: push["endpoint"] as string,
            ...(typeof push["wakePublicKey"] === "string" ? { wakePublicKey: push["wakePublicKey"] } : {}),
          },
        }
      : {}),
  };
}

function parseCredential(value: unknown): StoredOwnerCredential {
  if (!isRecord(value)) throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "credential entry is malformed");
  const id = requireString(value["id"], "id");
  // Tolerate the legacy byte-object form written before the base64url fix.
  const rawPublicKey = value["publicKey"];
  const publicKey = typeof rawPublicKey === "string"
    ? rawPublicKey
    : isRecord(rawPublicKey)
      ? Buffer.from(Object.keys(rawPublicKey).map((key) => Number(key)).sort((a, b) => a - b).map((key) => Number(rawPublicKey[key]))).toString("base64url")
      : requireString(rawPublicKey, "publicKey");
  const counter = value["counter"];
  const transports = value["transports"];
  if (
    !BASE64URL.test(id) || id.length > 1024
    || !BASE64URL.test(publicKey)
    || !Number.isSafeInteger(counter) || (counter as number) < 0
    || (transports !== undefined && (!Array.isArray(transports) || transports.some((item) => typeof item !== "string")))
  ) {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "credential entry is malformed");
  }
  return {
    id,
    publicKey: Buffer.from(publicKey, "base64url"),
    counter: counter as number,
    ...(Array.isArray(transports)
      ? { transports: (transports as string[]).map((item) => item as AuthenticatorTransportFuture) }
      : {}),
  };
}

function parseDevice(value: unknown): PairedDevice {
  if (!isRecord(value)) throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "device entry is malformed");
  const createdAtMs = value["createdAtMs"];
  const device: PairedDevice = {
    deviceId: requireString(value["deviceId"], "deviceId"),
    certificateId: requireString(value["certificateId"], "certificateId"),
    ...(typeof value["deviceRouteKeyId"] === "string" ? { deviceRouteKeyId: value["deviceRouteKeyId"] } : {}),
    createdAtMs: Number.isSafeInteger(createdAtMs) ? (createdAtMs as number) : -1,
  };
  validateDevice(device);
  return device;
}

function validateDevice(device: PairedDevice): void {
  if (
    !UUID_V4.test(device.deviceId)
    || !SHA256.test(device.certificateId)
    || (device.deviceRouteKeyId !== undefined && !/^[A-Za-z0-9._-]{1,128}$/.test(device.deviceRouteKeyId))
    || !Number.isSafeInteger(device.createdAtMs)
    || device.createdAtMs < 0
  ) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "paired device record is invalid");
  }
}

function validatePushEndpoint(endpoint: { deviceId: string; endpointId: string; distributor: string; endpoint: string; wakePublicKey?: string }): void {
  let url: URL;
  try {
    url = new URL(endpoint.endpoint);
  } catch {
    throw new SecurityError("SECURITY_INVALID_INPUT", "push endpoint is not a URL");
  }
  if (
    !UUID_V4.test(endpoint.deviceId)
    || !UUID_V4.test(endpoint.endpointId)
    || endpoint.distributor.length === 0
    || endpoint.distributor.length > 128
    || url.protocol !== "https:"
    || endpoint.endpoint.length > 4096
    || (endpoint.wakePublicKey !== undefined && !BASE64URL.test(endpoint.wakePublicKey))
  ) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "push endpoint record is invalid");
  }
}

function copyCredential(credential: StoredOwnerCredential): StoredOwnerCredential {
  return {
    id: credential.id,
    publicKey: new Uint8Array(credential.publicKey),
    counter: credential.counter,
    ...(credential.transports === undefined ? {} : { transports: [...credential.transports] }),
  };
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== "string") {
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", `host store ${field} is malformed`);
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && "code" in error;
}

export async function readJsonConfig(path: string): Promise<Record<string, unknown> | undefined> {
  let raw: Buffer;
  try {
    raw = await readFile(path);
  } catch (error) {
    if (isNodeError(error) && error.code === "ENOENT") return undefined;
    throw new SecurityError("SECURITY_INVALID_INPUT", "daemon config could not be read", { cause: error });
  }
  if (raw.byteLength > 64 * 1024) throw new SecurityError("SECURITY_INVALID_INPUT", "daemon config exceeds size bounds");
  let value: unknown;
  try {
    value = JSON.parse(raw.toString("utf8"));
  } catch (error) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "daemon config is not valid JSON", { cause: error });
  }
  if (!isRecord(value)) throw new SecurityError("SECURITY_INVALID_INPUT", "daemon config must be an object");
  return value;
}
