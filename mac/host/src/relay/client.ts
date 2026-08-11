import type { RouteKeyRing } from "./key-ring.js";
import { controlSocketOptions, dataSocketOptions, relayEndpoints, type RelayEndpoints } from "./endpoint.js";
import {
  assertControlChallenge,
  createMacDataSigned,
  createRouteProof,
  parseControlChallenge,
  parseControlReady,
  parsePairingRequestNotification,
  parseRouteNotice,
  type P256RouteSigner,
  type RouteNotice,
} from "./proof.js";
import { RelayTunnel } from "./tunnel.js";
import {
  CONTROL_LIVENESS_MS,
  CONTROL_PING_INTERVAL_MS,
  NOTICE_REPLAY_RETENTION_MS,
  PROOF_LIFETIME_MS,
  RelayError,
  type RelayClock,
  type RelayConnectionState,
  type RelaySocketListener,
  type RelayWebSocket,
  type RelayWebSocketFactory,
} from "./types.js";

const MAX_PENDING_DATA_HANDSHAKES = 16;
const MAX_REMEMBERED_NOTICES = 1_024;
const DEFAULT_RECONNECT_BASE_MS = 500;
const DEFAULT_RECONNECT_CAP_MS = 30_000;

type ControlStage = "opening" | "signing" | "waiting-challenge" | "waiting-ready" | "ready";

export interface RelayClientOptions {
  readonly relayBaseUrl: string;
  readonly keyRing: RouteKeyRing;
  readonly webSockets: RelayWebSocketFactory;
  readonly clock: RelayClock;
  readonly reconnectBaseMs?: number;
  readonly reconnectCapMs?: number;
  readonly onTunnel: (tunnel: RelayTunnel, notice: RouteNotice) => void;
  readonly onPairingRequest?: (pairingId: string) => void;
  readonly onStateChange?: (state: RelayConnectionState, fault?: RelayError) => void;
}

export class MacRelayClient {
  private stateValue: RelayConnectionState = "stopped";
  private faultValue: RelayError | undefined;
  private stopped = true;
  private endpoints!: RelayEndpoints;
  private routeId = "";
  private socket: RelayWebSocket | undefined;
  private signer: P256RouteSigner | undefined;
  private stage: ControlStage = "opening";
  private generation = 0;
  private reconnectAttempt = 0;
  private candidateCursor = 0;
  private reconnectTimer: unknown;
  private handshakeTimer: unknown;
  private heartbeatTimer: unknown;
  private lastPongMs = 0;
  private pendingDataHandshakes = 0;
  private readonly usedNotices = new Map<string, number>();
  private readonly tunnels = new Set<RelayTunnel>();
  private readonly pendingDataAborts = new Map<RelayWebSocket, () => void>();
  private readonly reconnectBaseMs: number;
  private readonly reconnectCapMs: number;

  constructor(private readonly options: RelayClientOptions) {
    this.reconnectBaseMs = checkedDelay(options.reconnectBaseMs ?? DEFAULT_RECONNECT_BASE_MS, "base");
    this.reconnectCapMs = checkedDelay(options.reconnectCapMs ?? DEFAULT_RECONNECT_CAP_MS, "cap");
    if (this.reconnectBaseMs > this.reconnectCapMs) {
      throw new RelayError("RELAY_TRANSPORT", "relay reconnect base exceeds cap");
    }
  }

  get state(): RelayConnectionState {
    return this.stateValue;
  }

  get lastFault(): RelayError | undefined {
    return this.faultValue;
  }

  async start(): Promise<void> {
    if (!this.stopped) return;
    const snapshot = await this.options.keyRing.snapshot();
    this.routeId = snapshot.routeId;
    this.endpoints = relayEndpoints(this.options.relayBaseUrl, snapshot.routeId);
    this.stopped = false;
    this.reconnectAttempt = 0;
    this.candidateCursor = 0;
    await this.connect();
  }

  stop(): void {
    if (this.stopped) return;
    this.stopped = true;
    this.generation += 1;
    this.clearTimer("reconnect");
    this.clearTimer("handshake");
    this.clearTimer("heartbeat");
    const socket = this.socket;
    this.socket = undefined;
    this.signer = undefined;
    socket?.close(1000, "");
    this.abortPendingData();
    for (const tunnel of this.tunnels) tunnel.destroy();
    this.tunnels.clear();
    this.transition("stopped");
  }

  private async connect(): Promise<void> {
    if (this.stopped) return;
    const generation = ++this.generation;
    this.transition("connecting");
    let candidates: readonly P256RouteSigner[];
    try {
      candidates = await this.options.keyRing.authenticationCandidates();
    } catch (error) {
      this.recordAndReconnect(generation, relayError(error, "RELAY_BAD_KEY", "route signer load failed"));
      return;
    }
    if (!this.isGenerationActive(generation)) return;
    const signer = candidates[this.candidateCursor % candidates.length];
    if (signer === undefined) {
      this.recordAndReconnect(generation, new RelayError("RELAY_BAD_KEY", "route signer is unavailable"));
      return;
    }
    this.candidateCursor = (this.candidateCursor + 1) % candidates.length;
    let socket: RelayWebSocket;
    try {
      socket = this.options.webSockets.connect(this.endpoints.controlWss, controlSocketOptions());
    } catch {
      this.recordAndReconnect(generation, new RelayError("RELAY_TRANSPORT", "relay control connection failed"));
      return;
    }
    this.socket = socket;
    this.signer = signer;
    this.stage = "opening";
    const openListener: RelaySocketListener = () => this.controlOpen(generation, socket, signer);
    const messageListener: RelaySocketListener = (...arguments_) => {
      void this.controlMessage(generation, socket, signer, arguments_[0], arguments_[1]);
    };
    const pongListener: RelaySocketListener = () => {
      if (generation === this.generation && this.stage === "ready") this.lastPongMs = this.options.clock.nowMs();
    };
    const closeListener: RelaySocketListener = () => {
      this.recordAndReconnect(generation, new RelayError("RELAY_TRANSPORT", "relay control connection closed"));
    };
    const errorListener: RelaySocketListener = () => {
      this.recordAndReconnect(generation, new RelayError("RELAY_TRANSPORT", "relay control connection failed"));
    };
    socket.on("open", openListener);
    socket.on("message", messageListener);
    socket.on("pong", pongListener);
    socket.on("close", closeListener);
    socket.on("error", errorListener);
    this.handshakeTimer = this.options.clock.setTimeout(() => {
      this.recordAndReconnect(generation, new RelayError("RELAY_HANDSHAKE_TIMEOUT", "relay control handshake timed out"));
    }, PROOF_LIFETIME_MS);
  }

  private controlOpen(generation: number, socket: RelayWebSocket, signer: P256RouteSigner): void {
    if (!this.isCurrent(generation, socket)) return;
    if (socket.extensions !== "") {
      this.recordAndReconnect(generation, new RelayError("RELAY_COMPRESSION_NEGOTIATED", "relay negotiated WebSocket compression"));
      return;
    }
    this.stage = "waiting-challenge";
    this.transition("authenticating");
    this.sendControl(generation, socket, {
      type: "route.control.begin",
      routeId: this.routeId,
      keyId: signer.keyId,
    });
  }

  private async controlMessage(
    generation: number,
    socket: RelayWebSocket,
    signer: P256RouteSigner,
    raw: unknown,
    isBinary: unknown,
  ): Promise<void> {
    if (!this.isCurrent(generation, socket)) return;
    try {
      if (this.stage === "waiting-challenge") {
        this.stage = "signing";
        const challenge = parseControlChallenge(raw, isBinary, this.options.clock.nowMs());
        assertControlChallenge(challenge, this.routeId, signer.keyId);
        const proof = await createRouteProof(challenge, signer, true);
        if (!this.isCurrent(generation, socket)) return;
        this.stage = "waiting-ready";
        this.sendControl(generation, socket, proof);
        return;
      }
      if (this.stage === "waiting-ready") {
        parseControlReady(raw, isBinary, this.routeId, signer.keyId);
        this.clearTimer("handshake");
        this.stage = "ready";
        this.reconnectAttempt = 0;
        this.candidateCursor = 0;
        this.lastPongMs = this.options.clock.nowMs();
        this.transition("ready");
        this.scheduleHeartbeat(generation, socket);
        return;
      }
      if (this.stage === "ready") {
        const pairingId = parsePairingRequestNotification(raw, isBinary);
        if (pairingId !== undefined) {
          this.options.onPairingRequest?.(pairingId);
          return;
        }
        const notice = parseRouteNotice(raw, isBinary, this.options.clock.nowMs());
        this.consumeNotice(notice);
        void this.openDataTunnel(generation, notice, signer);
        return;
      }
      throw new RelayError("RELAY_AUTH_REJECTED", "relay control message arrived out of order");
    } catch (error) {
      this.recordAndReconnect(generation, relayError(error, "RELAY_AUTH_REJECTED", "relay control protocol failed"));
    }
  }

  private sendControl(generation: number, socket: RelayWebSocket, value: object): void {
    const raw = JSON.stringify(value);
    socket.send(raw, { binary: false, compress: false }, (error) => {
      if (error !== undefined) {
        this.recordAndReconnect(generation, new RelayError("RELAY_TRANSPORT", "relay control write failed"));
      }
    });
  }

  private scheduleHeartbeat(generation: number, socket: RelayWebSocket): void {
    this.clearTimer("heartbeat");
    this.heartbeatTimer = this.options.clock.setTimeout(() => {
      if (!this.isCurrent(generation, socket) || this.stage !== "ready") return;
      if (this.options.clock.nowMs() - this.lastPongMs >= CONTROL_LIVENESS_MS) {
        this.recordAndReconnect(generation, new RelayError("RELAY_LIVENESS_TIMEOUT", "relay control pong timed out"));
        return;
      }
      socket.ping((error) => {
        if (error !== undefined) {
          this.recordAndReconnect(generation, new RelayError("RELAY_TRANSPORT", "relay control ping failed"));
        }
      });
      this.scheduleHeartbeat(generation, socket);
    }, CONTROL_PING_INTERVAL_MS);
  }

  private consumeNotice(notice: RouteNotice): void {
    const now = this.options.clock.nowMs();
    for (const [id, expiresAtMs] of this.usedNotices) {
      if (expiresAtMs <= now) this.usedNotices.delete(id);
    }
    if (this.usedNotices.has(notice.rendezvousId)) {
      throw new RelayError("RELAY_BAD_NOTICE", "relay notice was reused");
    }
    if (this.usedNotices.size >= MAX_REMEMBERED_NOTICES || this.pendingDataHandshakes >= MAX_PENDING_DATA_HANDSHAKES) {
      throw new RelayError("RELAY_RESOURCE_EXHAUSTED", "relay notice capacity reached");
    }
    this.usedNotices.set(notice.rendezvousId, now + NOTICE_REPLAY_RETENTION_MS);
    this.pendingDataHandshakes += 1;
  }

  private async openDataTunnel(generation: number, notice: RouteNotice, signer: P256RouteSigner): Promise<void> {
    try {
      if (!this.isGenerationActive(generation) || notice.expiresAtMs <= this.options.clock.nowMs()) {
        throw new RelayError("RELAY_BAD_NOTICE", "relay notice expired before data attachment");
      }
      const signed = createMacDataSigned(notice, this.routeId, signer.keyId);
      const proof = await createRouteProof(signed, signer, true);
      if (!this.isGenerationActive(generation) || notice.expiresAtMs <= this.options.clock.nowMs()) {
        throw new RelayError("RELAY_BAD_NOTICE", "relay notice expired before data attachment");
      }
      const socket = this.options.webSockets.connect(
        this.endpoints.dataWss,
        dataSocketOptions(JSON.stringify(proof)),
      );
      await this.acceptDataSocket(socket, notice, generation);
    } catch (error) {
      if (!this.stopped) this.faultValue = relayError(error, "RELAY_TRANSPORT", "relay data attachment failed");
    } finally {
      this.pendingDataHandshakes -= 1;
    }
  }

  private acceptDataSocket(socket: RelayWebSocket, notice: RouteNotice, generation: number): Promise<void> {
    return new Promise((resolve, reject) => {
      let settled = false;
      const abort = (fault: RelayError, terminate: boolean): void => {
        if (settled) return;
        settled = true;
        this.options.clock.clearTimeout(timeout);
        this.pendingDataAborts.delete(socket);
        if (terminate) socket.terminate();
        reject(fault);
      };
      const timeout = this.options.clock.setTimeout(() => {
        abort(new RelayError("RELAY_HANDSHAKE_TIMEOUT", "relay data handshake timed out"), true);
      }, Math.min(PROOF_LIFETIME_MS, Math.max(1, notice.expiresAtMs - this.options.clock.nowMs())));
      this.pendingDataAborts.set(socket, () => {
        abort(new RelayError("RELAY_ABORTED", "relay data connection was aborted"), true);
      });
      const fail = (): void => {
        abort(new RelayError("RELAY_TRANSPORT", "relay data connection failed"), false);
      };
      socket.on("error", fail);
      socket.on("close", fail);
      socket.on("open", () => {
        if (settled) return;
        if (!this.isGenerationActive(generation)) {
          abort(new RelayError("RELAY_ABORTED", "relay data connection was aborted"), true);
          return;
        }
        if (socket.extensions !== "") {
          abort(new RelayError("RELAY_COMPRESSION_NEGOTIATED", "relay negotiated WebSocket compression"), true);
          return;
        }
        settled = true;
        this.options.clock.clearTimeout(timeout);
        this.pendingDataAborts.delete(socket);
        const tunnel = new RelayTunnel(socket);
        tunnel.on("error", () => undefined);
        tunnel.once("close", () => this.tunnels.delete(tunnel));
        this.tunnels.add(tunnel);
        try {
          this.options.onTunnel(tunnel, notice);
          resolve();
        } catch {
          tunnel.destroy();
          reject(new RelayError("RELAY_TRANSPORT", "relay tunnel consumer rejected attachment"));
        }
      });
    });
  }

  private recordAndReconnect(generation: number, fault: RelayError): void {
    if (this.stopped || generation !== this.generation) return;
    this.faultValue = fault;
    this.generation += 1;
    this.clearTimer("handshake");
    this.clearTimer("heartbeat");
    const socket = this.socket;
    this.socket = undefined;
    this.signer = undefined;
    socket?.terminate();
    this.abortPendingData();
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer !== undefined) return;
    const exponent = Math.min(this.reconnectAttempt, 30);
    const ceiling = Math.min(this.reconnectCapMs, this.reconnectBaseMs * 2 ** exponent);
    const random = this.options.clock.random();
    const boundedRandom = Number.isFinite(random) ? Math.min(Math.max(random, 0), 1 - Number.EPSILON) : 0;
    const delay = Math.floor(boundedRandom * ceiling);
    this.reconnectAttempt += 1;
    this.transition("backoff", this.faultValue);
    this.reconnectTimer = this.options.clock.setTimeout(() => {
      this.reconnectTimer = undefined;
      void this.connect();
    }, delay);
  }

  private abortPendingData(): void {
    for (const abort of [...this.pendingDataAborts.values()]) abort();
    this.pendingDataAborts.clear();
  }

  private isGenerationActive(generation: number): boolean {
    return !this.stopped && generation === this.generation;
  }

  private isCurrent(generation: number, socket: RelayWebSocket): boolean {
    return this.isGenerationActive(generation) && socket === this.socket;
  }

  private transition(state: RelayConnectionState, fault?: RelayError): void {
    this.stateValue = state;
    if (fault !== undefined) this.faultValue = fault;
    this.options.onStateChange?.(state, fault);
  }

  private clearTimer(timer: "handshake" | "heartbeat" | "reconnect"): void {
    const handle = timer === "handshake"
      ? this.handshakeTimer
      : timer === "heartbeat"
        ? this.heartbeatTimer
        : this.reconnectTimer;
    if (handle !== undefined) this.options.clock.clearTimeout(handle);
    if (timer === "handshake") this.handshakeTimer = undefined;
    else if (timer === "heartbeat") this.heartbeatTimer = undefined;
    else this.reconnectTimer = undefined;
  }
}

function checkedDelay(value: number, kind: string): number {
  if (!Number.isSafeInteger(value) || value <= 0 || value > 60_000) {
    throw new RelayError("RELAY_TRANSPORT", `relay reconnect ${kind} is invalid`);
  }
  return value;
}

function relayError(error: unknown, code: RelayError["code"], message: string): RelayError {
  return error instanceof RelayError ? error : new RelayError(code, message);
}
