import { assertOpaqueId } from "./proof.js";
import {
  CONTROL_MESSAGE_MAX_BYTES,
  DATA_MESSAGE_MAX_BYTES,
  PROOF_LIFETIME_MS,
  RelayError,
  type RelaySocketConnectOptions,
} from "./types.js";

export interface RelayEndpoints {
  readonly controlWss: string;
  readonly dataWss: string;
  readonly devicesHttps: string;
  readonly revokeHttps: string;
  readonly pairingHttps: string;
}

export function relayEndpoints(relayBaseUrl: string, routeId: string): RelayEndpoints {
  assertOpaqueId(routeId, "routeId");
  let base: URL;
  try {
    base = new URL(relayBaseUrl);
  } catch {
    throw new RelayError("RELAY_TLS_REQUIRED", "relay URL is invalid");
  }
  if (
    base.protocol !== "wss:" ||
    base.username !== "" ||
    base.password !== "" ||
    base.hash !== "" ||
    base.search !== "" ||
    base.pathname !== "/"
  ) {
    throw new RelayError("RELAY_TLS_REQUIRED", "relay URL must be an origin-only WSS URL");
  }
  const route = encodeURIComponent(routeId);
  const wssOrigin = base.origin.replace(/^https:/, "wss:");
  const httpsOrigin = base.origin.replace(/^wss:/, "https:");
  return {
    controlWss: `${wssOrigin}/v1/routes/${route}/control`,
    dataWss: `${wssOrigin}/v1/routes/${route}/data`,
    devicesHttps: `${httpsOrigin}/v1/routes/${route}/devices`,
    revokeHttps: `${httpsOrigin}/v1/routes/${route}/revoke`,
    pairingHttps: `${httpsOrigin}/v1/routes/${route}/pairing`,
  };
}

export function controlSocketOptions(): RelaySocketConnectOptions {
  return secureSocketOptions(CONTROL_MESSAGE_MAX_BYTES);
}

export function dataSocketOptions(proofHeader: string): RelaySocketConnectOptions {
  if (Buffer.byteLength(proofHeader, "utf8") === 0 || Buffer.byteLength(proofHeader, "utf8") > CONTROL_MESSAGE_MAX_BYTES) {
    throw new RelayError("RELAY_BAD_PROOF", "relay proof header exceeds bounds");
  }
  return {
    ...secureSocketOptions(DATA_MESSAGE_MAX_BYTES),
    headers: { "X-Relay-Proof": proofHeader },
  };
}

function secureSocketOptions(maxPayload: number): RelaySocketConnectOptions {
  return {
    perMessageDeflate: false,
    maxPayload,
    handshakeTimeoutMs: PROOF_LIFETIME_MS,
    followRedirects: false,
    rejectUnauthorized: true,
    minVersion: "TLSv1.3",
  };
}
