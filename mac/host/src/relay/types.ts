export const CONTROL_MESSAGE_MAX_BYTES = 16 << 10;
export const DATA_MESSAGE_MAX_BYTES = 64 << 10;
export const CONTROL_PING_INTERVAL_MS = 30_000;
export const CONTROL_LIVENESS_MS = 90_000;
export const PROOF_LIFETIME_MS = 30_000;
export const RENDEZVOUS_LIFETIME_MS = 20_000;
export const NOTICE_REPLAY_RETENTION_MS = 2 * 60_000;
export const ROTATION_OVERLAP_MS = 24 * 60 * 60 * 1_000;
export const MAX_TUNNEL_WRITE_BYTES = 16 << 20;

export type RelayErrorCode =
  | "RELAY_ABORTED"
  | "RELAY_ADMIN_REJECTED"
  | "RELAY_AUTH_REJECTED"
  | "RELAY_BAD_KEY"
  | "RELAY_BAD_NOTICE"
  | "RELAY_BAD_PROOF"
  | "RELAY_COMPRESSION_NEGOTIATED"
  | "RELAY_CONTRACT_MISMATCH"
  | "RELAY_DATA_PROTOCOL"
  | "RELAY_HANDSHAKE_TIMEOUT"
  | "RELAY_LIVENESS_TIMEOUT"
  | "RELAY_NOT_READY"
  | "RELAY_PERSISTENCE"
  | "RELAY_RESOURCE_EXHAUSTED"
  | "RELAY_TLS_REQUIRED"
  | "RELAY_TRANSPORT";

export class RelayError extends Error {
  constructor(readonly code: RelayErrorCode, message: string) {
    super(message);
    this.name = "RelayError";
  }
}

export interface RelayClock {
  nowMs(): number;
  random(): number;
  setTimeout(callback: () => void, milliseconds: number): unknown;
  clearTimeout(handle: unknown): void;
}

export const SYSTEM_RELAY_CLOCK: RelayClock = {
  nowMs: () => Date.now(),
  random: () => Math.random(),
  setTimeout: (callback, milliseconds) => setTimeout(callback, milliseconds),
  clearTimeout: (handle) => clearTimeout(handle as NodeJS.Timeout),
};

export interface RelaySocketConnectOptions {
  readonly headers?: Readonly<Record<string, string>>;
  readonly perMessageDeflate: false;
  readonly maxPayload: number;
  readonly handshakeTimeoutMs: number;
  readonly followRedirects: false;
  readonly rejectUnauthorized: true;
  readonly minVersion: "TLSv1.3";
}

export type RelaySocketEvent = "close" | "error" | "message" | "open" | "ping" | "pong";
export type RelaySocketListener = (...arguments_: unknown[]) => void;

export interface RelayWebSocket {
  readonly extensions: string;
  readonly readyState: number;
  on(event: RelaySocketEvent, listener: RelaySocketListener): this;
  off(event: RelaySocketEvent, listener: RelaySocketListener): this;
  send(
    data: string | Uint8Array,
    options: { readonly binary: boolean; readonly compress: false },
    callback: (error?: Error) => void,
  ): void;
  ping(callback: (error?: Error) => void): void;
  pause(): void;
  resume(): void;
  close(code: number, reason: string): void;
  terminate(): void;
}

export interface RelayWebSocketFactory {
  connect(url: string, options: RelaySocketConnectOptions): RelayWebSocket;
}

export interface RelayEntropy {
  randomBytes(size: number): Uint8Array;
}

export type RelayFetch = (input: string, init: RequestInit) => Promise<Response>;

export type RelayConnectionState = "authenticating" | "backoff" | "connecting" | "ready" | "stopped";
