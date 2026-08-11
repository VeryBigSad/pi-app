import { createPrivateKey, generateKeyPairSync, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { mkdir, open, readFile } from "node:fs/promises";
import { join } from "node:path";
import { atomicWriteFile } from "../security/atomic-file.js";
import type { WrappingSecretProvider } from "../security/keychain.js";
import {
  MacRelayClient,
  NodeP256RouteSigner,
  NodeWsWebSocketFactory,
  RelayError,
  RelayPairingClient,
  RelayRouteAdmin,
  type RelayPairingFetch,
  RouteKeyRing,
  SYSTEM_RELAY_CLOCK,
  createFreshSigned,
  createRouteProof,
  encodeBase64Url,
  type P256RouteSigner,
  type PersistedRouteKeyState,
  type RelayConnectionState,
  type RelayFetch,
  type RelayTunnel,
  type RouteKeyPersistence,
  type RouteNotice,
} from "../relay/index.js";

const MAX_KEY_FILE_BYTES = 64 * 1024;
const MAX_STATE_BYTES = 64 * 1024;

/** Encrypts P-256 route keys at rest with the Keychain-wrapped secret. */
class FileRouteKeyPersistence implements RouteKeyPersistence {
  constructor(
    private readonly directory: string,
    private readonly secrets: WrappingSecretProvider,
  ) {}

  async readState(): Promise<unknown> {
    try {
      const raw = await readFile(join(this.directory, "route-state.json"));
      if (raw.byteLength > MAX_STATE_BYTES) throw new RelayError("RELAY_PERSISTENCE", "route state exceeds bounds");
      return JSON.parse(raw.toString("utf8")) as unknown;
    } catch (error) {
      if (error instanceof Error && "code" in error && error.code === "ENOENT") return undefined;
      if (error instanceof RelayError) throw error;
      throw new RelayError("RELAY_PERSISTENCE", "route key state could not be read");
    }
  }

  async writeStateAtomically(state: PersistedRouteKeyState, expectedState: PersistedRouteKeyState | undefined): Promise<boolean> {
    const current = await this.readState().catch(() => undefined);
    const currentNormalized = current === undefined || current === null ? undefined : JSON.parse(JSON.stringify(current)) as PersistedRouteKeyState;
    const expected = expectedState === undefined ? undefined : JSON.stringify(expectedState);
    const actual = currentNormalized === undefined ? undefined : JSON.stringify(currentNormalized);
    if (expected !== actual) return false;
    await atomicWriteFile(join(this.directory, "route-state.json"), `${JSON.stringify(state)}\n`, 0o600);
    return true;
  }

  async loadSigner(keyId: string): Promise<P256RouteSigner | undefined> {
    if (!/^[A-Za-z0-9._-]{1,128}$/.test(keyId)) return undefined;
    const path = join(this.directory, "keys", `${keyId}.json`);
    let raw: Buffer;
    try {
      const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        const metadata = await handle.stat();
        if (!metadata.isFile() || (metadata.mode & 0o077) !== 0 || metadata.size <= 0 || metadata.size > MAX_KEY_FILE_BYTES) {
          throw new RelayError("RELAY_BAD_KEY", "route key file is unsafe");
        }
        raw = await handle.readFile();
      } finally {
        await handle.close();
      }
    } catch (error) {
      if (error instanceof Error && "code" in error && error.code === "ENOENT") return undefined;
      if (error instanceof RelayError) throw error;
      throw new RelayError("RELAY_BAD_KEY", "route key could not be read");
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw.toString("utf8"));
    } catch {
      throw new RelayError("RELAY_BAD_KEY", "route key file is malformed");
    }
    if (typeof parsed !== "object" || parsed === null || (parsed as Record<string, unknown>)["v"] !== 1) {
      throw new RelayError("RELAY_BAD_KEY", "route key file is malformed");
    }
    const pem = (parsed as Record<string, unknown>)["encryptedPkcs8Pem"];
    if (typeof pem !== "string") throw new RelayError("RELAY_BAD_KEY", "route key file is malformed");
    const secret = await this.secrets.getSecret();
    try {
      const key = createPrivateKey({ key: pem, format: "pem", passphrase: secret });
      return new NodeP256RouteSigner(keyId, key);
    } catch {
      throw new RelayError("RELAY_BAD_KEY", "route key could not be decrypted");
    } finally {
      secret.fill(0);
    }
  }

  async createSigner(keyId: string): Promise<P256RouteSigner> {
    const pair = generateKeyPairSync("ec", { namedCurve: "P-256" });
    const secret = await this.secrets.getSecret();
    let pem: string;
    try {
      pem = pair.privateKey.export({ format: "pem", type: "pkcs8", cipher: "aes-256-cbc", passphrase: secret }).toString();
    } finally {
      secret.fill(0);
    }
    await mkdir(join(this.directory, "keys"), { recursive: true, mode: 0o700 });
    await atomicWriteFile(
      join(this.directory, "keys", `${keyId}.json`),
      `${JSON.stringify({ v: 1, keyId, encryptedPkcs8Pem: pem })}\n`,
      0o600,
    );
    return new NodeP256RouteSigner(keyId, pair.privateKey);
  }
}

export interface RelayManagerOptions {
  readonly relayBaseUrl: string;
  readonly bootstrapToken?: string;
  readonly directory: string;
  readonly secrets: WrappingSecretProvider;
  readonly fetch?: RelayFetch;
  readonly pairingFetch?: RelayPairingFetch;
  readonly onTunnel: (tunnel: RelayTunnel, notice: RouteNotice) => void;
  readonly onPairingRequest?: (pairingId: string) => void;
  readonly onStateChange?: (state: RelayConnectionState, fault?: RelayError) => void;
  readonly registeredRouteId?: () => string | undefined;
  readonly onRegistered?: (routeId: string) => void;
}

export class RelayManager {
  private readonly persistence: FileRouteKeyPersistence;
  private readonly keyRing: RouteKeyRing;
  private client: MacRelayClient | undefined;
  private lastState: RelayConnectionState = "stopped";
  private lastFaultCode: string | undefined;

  constructor(private readonly options: RelayManagerOptions) {
    this.persistence = new FileRouteKeyPersistence(options.directory, options.secrets);
    this.keyRing = new RouteKeyRing(this.persistence, SYSTEM_RELAY_CLOCK);
  }

  routeKeyRing(): RouteKeyRing {
    return this.keyRing;
  }

  state(): { state: RelayConnectionState; fault?: string; routeId?: string } {
    const registered = this.options.registeredRouteId?.();
    return {
      state: this.lastState,
      ...(this.lastFaultCode === undefined ? {} : { fault: this.lastFaultCode }),
      ...(registered === undefined ? {} : { routeId: registered }),
    };
  }

  /** Idempotent: registers a Mac route key when none exists, then starts the control loop. */
  async start(): Promise<void> {
    let routeId = this.options.registeredRouteId?.();
    const initialized = await this.keyRing.initialize();
    if (initialized === undefined || routeId === undefined) {
      const token = this.options.bootstrapToken;
      if (token === undefined) {
        throw new RelayError("RELAY_PERSISTENCE", "relay route is not bootstrapped");
      }
      routeId ??= randomUUID();
      const keyId = `mac-${randomUUID()}`;
      const signer = await this.persistence.createSigner(keyId);
      await this.registerRoute(routeId, keyId, await signer.publicKeySpki(), token);
      if (initialized === undefined) await this.keyRing.installInitial(routeId, keyId);
      this.options.onRegistered?.(routeId);
    }
    this.client = new MacRelayClient({
      relayBaseUrl: this.options.relayBaseUrl,
      keyRing: this.keyRing,
      webSockets: new NodeWsWebSocketFactory(),
      clock: SYSTEM_RELAY_CLOCK,
      onTunnel: this.options.onTunnel,
      ...(this.options.onPairingRequest === undefined ? {} : { onPairingRequest: this.options.onPairingRequest }),
      onStateChange: (state, fault) => {
        this.lastState = state;
        this.lastFaultCode = fault?.code;
        this.options.onStateChange?.(state, fault);
      },
    });
    await this.client.start();
  }

  pairing(): RelayPairingClient {
    return new RelayPairingClient({
      relayBaseUrl: this.options.relayBaseUrl,
      keyRing: this.keyRing,
      clock: SYSTEM_RELAY_CLOCK,
      ...(this.options.pairingFetch === undefined ? {} : { fetch: this.options.pairingFetch }),
    });
  }

  admin(): RelayRouteAdmin {
    return new RelayRouteAdmin({
      relayBaseUrl: this.options.relayBaseUrl,
      keyRing: this.keyRing,
      clock: SYSTEM_RELAY_CLOCK,
      ...(this.options.fetch === undefined ? {} : { fetch: this.options.fetch }),
    });
  }

  /** Rotates the active route key, registering the successor through the admin API first. */
  async rotate(): Promise<{ activeKeyId: string }> {
    const keyId = `mac-${randomUUID()}`;
    const signer = await this.persistence.createSigner(keyId);
    const spki = await signer.publicKeySpki();
    await this.registerMacKey(keyId, spki);
    await this.keyRing.rotateTo(keyId);
    const snapshot = await this.keyRing.snapshot();
    return { activeKeyId: snapshot.activeKeyId };
  }

  private async registerMacKey(keyId: string, spki: Uint8Array): Promise<void> {
    const snapshot = await this.keyRing.snapshot();
    const url = new URL(this.options.relayBaseUrl.replace(/^wss:/, "https:"));
    const endpoint = `${url.origin}/v1/routes/${encodeURIComponent(snapshot.routeId)}/register`;
    for (const candidate of await this.keyRing.authenticationCandidates()) {
      const signed = createFreshSigned("route-admin", snapshot.routeId, candidate.keyId, SYSTEM_RELAY_CLOCK);
      const proof = await createRouteProof(signed, candidate, true);
      const response = await fetch(endpoint, {
        method: "POST",
        redirect: "error",
        headers: { "Content-Type": "application/json", "X-Relay-Proof": JSON.stringify(proof) },
        body: JSON.stringify({ keyId, publicKeySpki: encodeBase64Url(spki) }),
        signal: AbortSignal.timeout(15_000),
      });
      await response.body?.cancel().catch(() => undefined);
      if (response.status === 201) return;
      if (response.status !== 401) {
        throw new RelayError("RELAY_ADMIN_REJECTED", `route key rotation rejected with status ${String(response.status)}`);
      }
    }
    throw new RelayError("RELAY_ADMIN_REJECTED", "route key rotation was rejected");
  }

  private async registerRoute(routeId: string, keyId: string, spki: Uint8Array, token: string): Promise<void> {
    const url = new URL(this.options.relayBaseUrl.replace(/^wss:/, "https:"));
    const endpoint = `${url.origin}/v1/routes/${encodeURIComponent(routeId)}/register`;
    const response = await fetch(endpoint, {
      method: "POST",
      redirect: "error",
      headers: { "Content-Type": "application/json", "X-Relay-Bootstrap": token },
      body: JSON.stringify({ keyId, publicKeySpki: encodeBase64Url(spki) }),
      signal: AbortSignal.timeout(15_000),
    }).catch(() => {
      throw new RelayError("RELAY_TRANSPORT", "relay route registration failed");
    });
    await response.body?.cancel().catch(() => undefined);
    if (response.status !== 201) {
      throw new RelayError("RELAY_ADMIN_REJECTED", `relay route registration rejected with status ${String(response.status)}`);
    }
  }

  stop(): void {
    this.client?.stop();
    this.client = undefined;
    this.lastState = "stopped";
  }
}
