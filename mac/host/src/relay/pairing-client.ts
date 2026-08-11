import { request } from "node:https";
import { relayEndpoints } from "./endpoint.js";
import type { RouteKeyRing } from "./key-ring.js";
import {
  assertOpaqueId,
  createFreshSigned,
  createRouteProof,
  SYSTEM_RELAY_ENTROPY,
  type P256RouteSigner,
} from "./proof.js";
import {
  PROOF_LIFETIME_MS,
  RelayError,
  type RelayClock,
  type RelayEntropy,
} from "./types.js";

const MAX_PAIRING_MESSAGE_BYTES = 16 << 10;
const MAX_PAIRING_RESPONSE_BYTES = 32 << 10;
const PAIRING_ID = /^[A-Za-z0-9._-]{1,128}$/;
const PAIRING_SECRET = /^[A-Za-z0-9_-]{1,128}$/;

export interface RelayPairingFetchResponse {
  readonly status: number;
  readonly body: Uint8Array;
}

export type RelayPairingFetch = (
  url: string,
  init: {
    readonly method: "GET" | "POST";
    readonly headers: Readonly<Record<string, string>>;
    readonly body?: string;
    readonly signal?: AbortSignal;
  },
) => Promise<RelayPairingFetchResponse>;

export interface RelayPairingExchange {
  readonly pairingId: string;
  readonly secret: string;
  readonly expiresAt: string;
}

export interface RelayPairingClientOptions {
  readonly relayBaseUrl: string;
  readonly keyRing: RouteKeyRing;
  readonly clock: RelayClock;
  readonly fetch?: RelayPairingFetch;
  readonly entropy?: RelayEntropy;
}

/** TLS 1.3 JSON fetch for the relay provisional pairing rendezvous endpoints. */
export const TLS13_RELAY_PAIRING_FETCH: RelayPairingFetch = (url, init) => {
  let target: URL;
  try {
    target = new URL(url);
  } catch {
    return Promise.reject(new RelayError("RELAY_TLS_REQUIRED", "relay pairing URL is invalid"));
  }
  if (target.protocol !== "https:") {
    return Promise.reject(new RelayError("RELAY_TLS_REQUIRED", "relay pairing requires HTTPS"));
  }
  return new Promise<RelayPairingFetchResponse>((resolve, reject) => {
    const outgoing = request(target, {
      method: init.method,
      headers: init.headers,
      minVersion: "TLSv1.3",
      rejectUnauthorized: true,
      agent: false,
      ...(init.signal === undefined ? {} : { signal: init.signal }),
    }, (incoming) => {
      const chunks: Buffer[] = [];
      let received = 0;
      incoming.on("data", (chunk: Buffer) => {
        received += chunk.byteLength;
        if (received > MAX_PAIRING_RESPONSE_BYTES) {
          incoming.destroy();
          reject(new RelayError("RELAY_RESOURCE_EXHAUSTED", "relay pairing response exceeds bounds"));
          return;
        }
        chunks.push(chunk);
      });
      incoming.once("end", () => {
        const status = incoming.statusCode;
        if (status === undefined || status < 200 || status > 599) {
          reject(new RelayError("RELAY_TRANSPORT", "relay pairing response status is invalid"));
          return;
        }
        resolve({ status, body: new Uint8Array(Buffer.concat(chunks)) });
      });
      incoming.once("error", () => reject(new RelayError("RELAY_TRANSPORT", "relay pairing HTTPS response failed")));
    });
    outgoing.once("error", () => reject(new RelayError("RELAY_TRANSPORT", "relay pairing HTTPS request failed")));
    if (init.body !== undefined) outgoing.write(init.body);
    outgoing.end();
  });
};

/**
 * Mac-side client for the relay's single-use provisional pairing rendezvous
 * (POST/GET /v1/routes/{route}/pairing[...]). Message content stays opaque to
 * the relay; the Mac authenticates with route-admin proofs.
 */
export class RelayPairingClient {
  private readonly fetch: RelayPairingFetch;
  private readonly entropy: RelayEntropy;

  constructor(private readonly options: RelayPairingClientOptions) {
    this.fetch = options.fetch ?? TLS13_RELAY_PAIRING_FETCH;
    this.entropy = options.entropy ?? SYSTEM_RELAY_ENTROPY;
  }

  async openExchange(signal?: AbortSignal): Promise<RelayPairingExchange> {
    const { response } = await this.authenticated("POST", await this.pairingUrl(), undefined, [201], signal);
    const value = parseJsonObject(response.body, "pairing exchange");
    const pairingId = value["pairingId"];
    const secret = value["secret"];
    const expiresAt = value["expiresAt"];
    if (
      typeof pairingId !== "string" || !PAIRING_ID.test(pairingId)
      || typeof secret !== "string" || !PAIRING_SECRET.test(secret)
      || typeof expiresAt !== "string" || !Number.isFinite(Date.parse(expiresAt))
    ) {
      throw new RelayError("RELAY_ADMIN_REJECTED", "relay pairing exchange is malformed");
    }
    return { pairingId, secret, expiresAt };
  }

  /** Returns the deposited device message, or undefined when none has arrived yet. */
  async fetchRequest(pairingId: string, signal?: AbortSignal): Promise<Uint8Array | undefined> {
    assertOpaqueId(pairingId, "pairingId");
    const { response, status } = await this.authenticated("GET", `${await this.pairingUrl()}/${encodeURIComponent(pairingId)}`, undefined, [200, 404], signal);
    if (status === 404) return undefined;
    const value = parseJsonObject(response.body, "pairing request");
    const message = value["message"];
    if (typeof message !== "string") throw new RelayError("RELAY_ADMIN_REJECTED", "relay pairing request is malformed");
    return decodeMessage(message);
  }

  async submitReply(pairingId: string, message: Uint8Array, signal?: AbortSignal): Promise<void> {
    assertOpaqueId(pairingId, "pairingId");
    if (message.byteLength === 0 || message.byteLength > MAX_PAIRING_MESSAGE_BYTES) {
      throw new RelayError("RELAY_RESOURCE_EXHAUSTED", "pairing reply exceeds bounds");
    }
    const body = JSON.stringify({ message: Buffer.from(message).toString("base64url") });
    const { status } = await this.authenticated(
      "POST",
      `${await this.pairingUrl()}/${encodeURIComponent(pairingId)}/reply`,
      body,
      [202, 404],
      signal,
    );
    if (status === 404) throw new RelayError("RELAY_NOT_READY", "pairing exchange is unavailable");
  }

  private async pairingUrl(): Promise<string> {
    const snapshot = await this.options.keyRing.snapshot();
    return relayEndpoints(this.options.relayBaseUrl, snapshot.routeId).pairingHttps;
  }

  private async authenticated(
    method: "GET" | "POST",
    endpoint: string,
    body: string | undefined,
    accepted: readonly number[],
    signal?: AbortSignal,
  ): Promise<{ response: RelayPairingFetchResponse; status: number }> {
    const snapshot = await this.options.keyRing.snapshot();
    for (const signer of await this.options.keyRing.authenticationCandidates()) {
      const response = await this.request(method, endpoint, body, signer, snapshot.routeId, signal);
      if (accepted.includes(response.status)) return { response, status: response.status };
      if (response.status === 401) continue;
      if (response.status === 409 || response.status === 429 || response.status === 503) {
        throw new RelayError("RELAY_RESOURCE_EXHAUSTED", `relay pairing rejected with status ${String(response.status)}`);
      }
      throw new RelayError("RELAY_ADMIN_REJECTED", `relay pairing rejected with status ${String(response.status)}`);
    }
    throw new RelayError("RELAY_ADMIN_REJECTED", "relay pairing authentication was rejected");
  }

  private async request(
    method: "GET" | "POST",
    endpoint: string,
    body: string | undefined,
    signer: P256RouteSigner,
    routeId: string,
    signal?: AbortSignal,
  ): Promise<RelayPairingFetchResponse> {
    if (signal?.aborted === true) throw new RelayError("RELAY_ABORTED", "relay pairing request was aborted");
    const signed = createFreshSigned("route-admin", routeId, signer.keyId, this.options.clock, this.entropy);
    const proof = await createRouteProof(signed, signer, true);
    const controller = new AbortController();
    const abort = (): void => controller.abort();
    signal?.addEventListener("abort", abort, { once: true });
    const timeout = this.options.clock.setTimeout(abort, PROOF_LIFETIME_MS);
    try {
      return await this.fetch(endpoint, {
        method,
        headers: {
          "X-Relay-Proof": JSON.stringify(proof),
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        ...(body === undefined ? {} : { body }),
        signal: controller.signal,
      });
    } catch (error) {
      if (error instanceof RelayError) throw error;
      if (controller.signal.aborted) throw new RelayError("RELAY_ABORTED", "relay pairing request was aborted");
      throw new RelayError("RELAY_TRANSPORT", "relay pairing request failed");
    } finally {
      this.options.clock.clearTimeout(timeout);
      signal?.removeEventListener("abort", abort);
    }
  }
}

function parseJsonObject(body: Uint8Array, kind: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(Buffer.from(body).toString("utf8"));
  } catch {
    throw new RelayError("RELAY_ADMIN_REJECTED", `relay ${kind} response is not JSON`);
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new RelayError("RELAY_ADMIN_REJECTED", `relay ${kind} response is malformed`);
  }
  return value as Record<string, unknown>;
}

function decodeMessage(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new RelayError("RELAY_ADMIN_REJECTED", "relay pairing message is malformed");
  const decoded = Buffer.from(value, "base64url");
  if (decoded.byteLength === 0 || decoded.byteLength > MAX_PAIRING_MESSAGE_BYTES) {
    throw new RelayError("RELAY_ADMIN_REJECTED", "relay pairing message exceeds bounds");
  }
  return new Uint8Array(decoded.buffer, decoded.byteOffset, decoded.byteLength);
}
