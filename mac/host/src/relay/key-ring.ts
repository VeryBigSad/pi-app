import { assertOpaqueId, assertP256Spki, type P256RouteSigner } from "./proof.js";
import { ROTATION_OVERLAP_MS, RelayError, type RelayClock } from "./types.js";

export interface PersistedOverlapKey {
  readonly keyId: string;
  readonly retireAfterMs: number;
}

export interface PersistedRouteKeyState {
  readonly version: 1;
  readonly routeId: string;
  readonly activeKeyId: string;
  readonly overlapKeys: readonly PersistedOverlapKey[];
}

export interface RouteKeyPersistence {
  readState(): Promise<unknown>;
  writeStateAtomically(
    state: PersistedRouteKeyState,
    expectedState: PersistedRouteKeyState | undefined,
  ): Promise<boolean>;
  loadSigner(keyId: string): Promise<P256RouteSigner | undefined>;
}

export class RouteKeyRing {
  private state: PersistedRouteKeyState | undefined;
  private operation: Promise<void> = Promise.resolve();

  constructor(
    private readonly persistence: RouteKeyPersistence,
    private readonly clock: RelayClock,
  ) {}

  initialize(): Promise<PersistedRouteKeyState | undefined> {
    return this.serial(async () => {
      const state = await this.loadStateLocked();
      if (state === undefined) return undefined;
      await this.pruneExpiredLocked();
      return cloneState(this.currentStateLocked());
    });
  }

  installInitial(routeId: string, keyId: string): Promise<void> {
    return this.serial(async () => {
      const persisted = await this.persistence.readState();
      if (this.state !== undefined || persisted !== undefined && persisted !== null) {
        throw new RelayError("RELAY_PERSISTENCE", "route key state already exists");
      }
      assertOpaqueId(routeId, "routeId");
      assertOpaqueId(keyId, "keyId");
      await this.requireSigner(keyId);
      await this.commit({ version: 1, routeId, activeKeyId: keyId, overlapKeys: [] });
    });
  }

  rotateTo(keyId: string, overlapMs = ROTATION_OVERLAP_MS): Promise<void> {
    return this.serial(async () => {
      const state = await this.requireStateLocked();
      assertOpaqueId(keyId, "keyId");
      if (!Number.isSafeInteger(overlapMs) || overlapMs <= 0 || overlapMs > ROTATION_OVERLAP_MS) {
        throw new RelayError("RELAY_PERSISTENCE", "route key overlap is outside bounds");
      }
      await this.requireSigner(keyId);
      if (keyId === state.activeKeyId) return;
      const now = this.clock.nowMs();
      const overlapKeys = state.overlapKeys
        .filter((item) => item.keyId !== keyId && item.keyId !== state.activeKeyId && item.retireAfterMs > now);
      overlapKeys.push({ keyId: state.activeKeyId, retireAfterMs: now + overlapMs });
      await this.commit({
        version: 1,
        routeId: state.routeId,
        activeKeyId: keyId,
        overlapKeys,
      });
    });
  }

  removeOverlapKey(keyId: string): Promise<void> {
    return this.serial(async () => {
      const state = await this.requireStateLocked();
      if (keyId === state.activeKeyId) {
        throw new RelayError("RELAY_PERSISTENCE", "cannot remove the active route key");
      }
      const overlapKeys = state.overlapKeys.filter((item) => item.keyId !== keyId);
      if (overlapKeys.length === state.overlapKeys.length) return;
      await this.commit({ ...state, overlapKeys });
    });
  }

  authenticationCandidates(): Promise<readonly P256RouteSigner[]> {
    return this.serial(async () => {
      await this.requireStateLocked();
      await this.pruneExpiredLocked();
      const state = this.currentStateLocked();
      const keyIds = [state.activeKeyId, ...state.overlapKeys.map((item) => item.keyId)];
      const signers: P256RouteSigner[] = [];
      for (const keyId of keyIds) {
        const signer = await this.persistence.loadSigner(keyId);
        if (signer !== undefined) {
          if (signer.keyId !== keyId) throw new RelayError("RELAY_BAD_KEY", "persisted signer key ID mismatch");
          assertP256Spki(await signer.publicKeySpki());
          signers.push(signer);
        }
      }
      if (signers.length === 0) throw new RelayError("RELAY_BAD_KEY", "no persisted P-256 route signer is available");
      return signers;
    });
  }

  activeSigner(): Promise<P256RouteSigner> {
    return this.serial(async () => {
      const state = await this.requireStateLocked();
      return this.requireSigner(state.activeKeyId);
    });
  }

  snapshot(): Promise<PersistedRouteKeyState> {
    return this.serial(async () => {
      await this.requireStateLocked();
      await this.pruneExpiredLocked();
      return cloneState(this.currentStateLocked());
    });
  }

  private async loadStateLocked(): Promise<PersistedRouteKeyState | undefined> {
    if (this.state !== undefined) return this.state;
    const raw = await this.persistence.readState();
    if (raw === undefined || raw === null) return undefined;
    this.state = parseState(raw);
    return this.state;
  }

  private async pruneExpiredLocked(): Promise<void> {
    if (this.state === undefined) return;
    const overlapKeys = this.state.overlapKeys.filter((item) => item.retireAfterMs > this.clock.nowMs());
    if (overlapKeys.length !== this.state.overlapKeys.length) {
      await this.commit({ ...this.state, overlapKeys });
    }
  }

  private async requireStateLocked(): Promise<PersistedRouteKeyState> {
    const state = await this.loadStateLocked();
    if (state === undefined) throw new RelayError("RELAY_PERSISTENCE", "route key state is not initialized");
    return state;
  }

  private currentStateLocked(): PersistedRouteKeyState {
    if (this.state === undefined) throw new RelayError("RELAY_PERSISTENCE", "route key state is not initialized");
    return this.state;
  }

  private async requireSigner(keyId: string): Promise<P256RouteSigner> {
    const signer = await this.persistence.loadSigner(keyId);
    if (signer?.keyId !== keyId) {
      throw new RelayError("RELAY_BAD_KEY", "persisted route signer is unavailable");
    }
    assertP256Spki(await signer.publicKeySpki());
    return signer;
  }

  private async commit(state: PersistedRouteKeyState): Promise<void> {
    const checked = parseState(state);
    const expectedState = this.state === undefined ? undefined : cloneState(this.state);
    let written: boolean;
    try {
      written = await this.persistence.writeStateAtomically(cloneState(checked), expectedState);
    } catch {
      this.state = undefined;
      throw new RelayError("RELAY_PERSISTENCE", "route key state persistence failed");
    }
    if (!written) {
      this.state = undefined;
      throw new RelayError("RELAY_PERSISTENCE", "route key state changed concurrently");
    }
    this.state = checked;
  }

  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.operation.then(operation, operation);
    this.operation = next.then(() => undefined, () => undefined);
    return next;
  }
}

function parseState(value: unknown): PersistedRouteKeyState {
  if (!isRecord(value)) throw new RelayError("RELAY_PERSISTENCE", "persisted route key state is malformed");
  assertExactKeys(value, ["activeKeyId", "overlapKeys", "routeId", "version"]);
  if (value["version"] !== 1 || typeof value["routeId"] !== "string" || typeof value["activeKeyId"] !== "string" || !Array.isArray(value["overlapKeys"])) {
    throw new RelayError("RELAY_PERSISTENCE", "persisted route key state is malformed");
  }
  assertOpaqueId(value["routeId"], "routeId");
  assertOpaqueId(value["activeKeyId"], "activeKeyId");
  if (value["overlapKeys"].length > 8) throw new RelayError("RELAY_PERSISTENCE", "too many overlap route keys");
  const seen = new Set([value["activeKeyId"]]);
  const overlapKeys = value["overlapKeys"].map((item) => {
    if (!isRecord(item)) throw new RelayError("RELAY_PERSISTENCE", "persisted overlap key is malformed");
    assertExactKeys(item, ["keyId", "retireAfterMs"]);
    const keyId = item["keyId"];
    const retireAfterMs = item["retireAfterMs"];
    if (typeof keyId !== "string" || typeof retireAfterMs !== "number" || !Number.isSafeInteger(retireAfterMs) || retireAfterMs <= 0) {
      throw new RelayError("RELAY_PERSISTENCE", "persisted overlap key is malformed");
    }
    assertOpaqueId(keyId, "keyId");
    if (seen.has(keyId)) throw new RelayError("RELAY_PERSISTENCE", "persisted route key IDs are duplicated");
    seen.add(keyId);
    return { keyId, retireAfterMs };
  });
  return {
    version: 1,
    routeId: value["routeId"],
    activeKeyId: value["activeKeyId"],
    overlapKeys,
  };
}

function cloneState(state: PersistedRouteKeyState): PersistedRouteKeyState {
  return {
    version: 1,
    routeId: state.routeId,
    activeKeyId: state.activeKeyId,
    overlapKeys: state.overlapKeys.map((item) => ({ ...item })),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function assertExactKeys(value: Record<string, unknown>, keys: readonly string[]): void {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    throw new RelayError("RELAY_PERSISTENCE", "persisted route key state has unexpected fields");
  }
}
