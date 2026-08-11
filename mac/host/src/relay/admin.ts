import type { RouteKeyRing } from "./key-ring.js";
import {
  assertOpaqueId,
  assertP256Spki,
  createFreshSigned,
  createRouteProof,
  encodeBase64Url,
  SYSTEM_RELAY_ENTROPY,
  type P256RouteSigner,
} from "./proof.js";
import { relayEndpoints } from "./endpoint.js";
import { TLS13_RELAY_FETCH } from "./https-fetch.js";
import {
  PROOF_LIFETIME_MS,
  RelayError,
  type RelayClock,
  type RelayEntropy,
  type RelayFetch,
} from "./types.js";

export interface RelayRouteAdminOptions {
  readonly relayBaseUrl: string;
  readonly keyRing: RouteKeyRing;
  readonly clock: RelayClock;
  readonly fetch?: RelayFetch;
  readonly entropy?: RelayEntropy;
}

export class RelayRouteAdmin {
  private readonly fetch: RelayFetch;
  private readonly entropy: RelayEntropy;

  constructor(private readonly options: RelayRouteAdminOptions) {
    this.fetch = options.fetch ?? TLS13_RELAY_FETCH;
    this.entropy = options.entropy ?? SYSTEM_RELAY_ENTROPY;
  }

  async addDeviceKey(keyId: string, publicKeySpki: Uint8Array, signal?: AbortSignal): Promise<void> {
    assertOpaqueId(keyId, "keyId");
    assertP256Spki(publicKeySpki);
    const snapshot = await this.options.keyRing.snapshot();
    const endpoint = relayEndpoints(this.options.relayBaseUrl, snapshot.routeId).devicesHttps;
    await this.requestWithCandidates(
      endpoint,
      201,
      { keyId, publicKeySpki: encodeBase64Url(publicKeySpki) },
      snapshot.routeId,
      signal,
    );
  }

  async revokeKey(keyId: string, signal?: AbortSignal): Promise<void> {
    assertOpaqueId(keyId, "keyId");
    const snapshot = await this.options.keyRing.snapshot();
    const endpoint = relayEndpoints(this.options.relayBaseUrl, snapshot.routeId).revokeHttps;
    await this.requestWithCandidates(endpoint, 204, { keyId }, snapshot.routeId, signal);
  }

  private async requestWithCandidates(
    endpoint: string,
    expectedStatus: number,
    body: Record<string, string>,
    routeId: string,
    signal?: AbortSignal,
  ): Promise<void> {
    for (const signer of await this.options.keyRing.authenticationCandidates()) {
      const status = await this.request(endpoint, body, signer, routeId, signal);
      if (status === expectedStatus) return;
      if (status !== 401) {
        throw new RelayError("RELAY_ADMIN_REJECTED", `relay admin request rejected with status ${String(status)}`);
      }
    }
    throw new RelayError("RELAY_ADMIN_REJECTED", "relay admin authentication was rejected");
  }

  private async request(
    endpoint: string,
    body: Record<string, string>,
    signer: P256RouteSigner,
    routeId: string,
    signal?: AbortSignal,
  ): Promise<number> {
    if (signal?.aborted === true) throw new RelayError("RELAY_ABORTED", "relay admin request was aborted");
    const signed = createFreshSigned("route-admin", routeId, signer.keyId, this.options.clock, this.entropy);
    const proof = await createRouteProof(signed, signer, true);
    const controller = new AbortController();
    const abort = (): void => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    const timeout = this.options.clock.setTimeout(abort, PROOF_LIFETIME_MS);
    let response: Response;
    try {
      response = await this.fetch(endpoint, {
        method: "POST",
        redirect: "error",
        headers: {
          "Content-Type": "application/json",
          "X-Relay-Proof": JSON.stringify(proof),
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch {
      if (controller.signal.aborted) throw new RelayError("RELAY_ABORTED", "relay admin request was aborted");
      throw new RelayError("RELAY_TRANSPORT", "relay admin request failed");
    } finally {
      this.options.clock.clearTimeout(timeout);
      signal?.removeEventListener("abort", abort);
    }
    await response.body?.cancel().catch(() => undefined);
    return response.status;
  }
}
