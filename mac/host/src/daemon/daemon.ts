import { createHash, randomUUID } from "node:crypto";
import { logError, logWarn } from "./log.js";
import { networkInterfaces } from "node:os";
import { constants } from "node:fs";
import { open, readFile, unlink } from "node:fs/promises";
import { join } from "node:path";
import type { ApprovalOffer } from "@pimobile/approval";
import type { JsonObject } from "@pimobile/protocol";
import { MANIFEST_VERSION } from "@pimobile/compatibility";
import { PINNED_PI_VERSION } from "@pimobile/pi-patch";
import { createHostGateway } from "../gateway/host-gateway.js";
import type { GatewayClock, CommandGuardContext, HostGateway } from "../gateway/types.js";
import type { SemanticCommand } from "../journal/types.js";
import { SqliteCommandJournal } from "../journal/sqlite-journal.js";
import { RuntimeSupervisor, type PiRuntimeProvisioner } from "../runtime/supervisor.js";
import { MacOsKeychainWrappingSecret, type WrappingSecretProvider } from "../security/keychain.js";
import { PairingCeremonyStore } from "../security/pairing-ceremony.js";
import {
  encodePairingInvitationUri,
  signPairingInvitation,
  type PairingInvitationPayload,
  type SignedPairingInvitation,
} from "../security/pairing-invitation.js";
import { SqliteRevocationRegistry } from "../security/revocation-registry.js";
import { createLocalTerminalBackend, type TerminalBackend } from "../terminal/backend.js";
import { AdminServer } from "./admin-server.js";
import { BlobStore } from "./blob-store.js";
import { DirectTlsListeners, admitTlsSocket } from "./direct-tls.js";
import { HostStore, readJsonConfig } from "./host-store.js";
import { terminateInnerTls, type InnerTlsContext, createInnerTlsContext } from "./inner-tls.js";
import { ensurePrivateDirectories, createPathLayout, type HostPathLayout } from "./paths.js";
import { PairingCoordinator, type PairingStatus } from "./pairing-coordinator.js";
import { NtfyPushPublisher, parseNtfyConfig, type PushPublisher } from "./push.js";
import { RelayManager } from "./relay-manager.js";
import { SessionService, type AgentUpdateNotice, type SessionAppend, type SettlementNotice } from "./session-service.js";
import { TerminalRuntimeAdapter } from "./terminal-runtime.js";
import { loadOrCreateTlsMaterial, type HostTlsMaterial } from "./tls-material.js";
import { UserAuthenticationService } from "./user-auth.js";
import { GatewayVoiceRuntime, VoiceService } from "./voice-service.js";
import type { RelayTunnel, RouteNotice } from "../relay/index.js";
import { SecurityError } from "../security/security-error.js";

const HOST_VERSION = "0.1.0";
const GATEWAY_FEATURES = ["sync", "commands", "terminal", "blobs"] as const;

const LOCAL_ADMIN_DEVICE_ID = "00000000-0000-4000-8000-000000000000";
const LOCAL_ADMIN_CERTIFICATE_ID = "0".repeat(64);
const MAX_PAIRING_DIRECT_CANDIDATES = 16;
const OPAQUE_ID = /^[A-Za-z0-9._-]{1,128}$/;
const BASE64URL = /^[A-Za-z0-9_-]+$/;

export interface HostDaemonOptions {
  readonly provisioner?: PiRuntimeProvisioner;
  readonly preloadPath?: string;
  readonly nodeExecutable?: string;
  readonly secrets?: WrappingSecretProvider;
}

export interface HostDaemonConfig {
  readonly directPort: number;
  readonly provisionalPort: number;
  readonly relay?: { readonly baseUrl: string; readonly bootstrapToken?: string };
  readonly ntfy?: Parameters<typeof parseNtfyConfig>[0];
  readonly groqKeyPath?: string;
  readonly passkeySessionTtlMs?: number;
}

export interface DaemonStatus {
  readonly ok: boolean;
  readonly version: string;
  readonly protocolMajor: number;
  readonly compatibilityManifest: number;
  readonly piVersion: string;
  readonly uptimeMs: number;
  readonly sessions: readonly string[];
  readonly devices: number;
  readonly relay: { state: string; fault?: string; routeId?: string };
  readonly terminal: { available: boolean; reason?: string };
  readonly voice: { queueSize: number };
  readonly push: { configured: boolean; published: number; failed: number; skipped: number };
  readonly listeners: { directPort: number; provisionalPort: number };
  readonly pendingApprovals: number;
}

const SYSTEM_CLOCK: GatewayClock = {
  now: () => Date.now(),
  setTimeout: (operation, delayMs) => setTimeout(operation, delayMs),
  clearTimeout: (handle) => clearTimeout(handle as NodeJS.Timeout),
};

/** Composition root: owns every global resource and the startup/shutdown order. */
export class HostDaemon {
  private readonly layout: HostPathLayout;
  private config: HostDaemonConfig | undefined;
  private store: HostStore | undefined;
  private revocations: SqliteRevocationRegistry | undefined;
  private journal: SqliteCommandJournal | undefined;
  private supervisor: RuntimeSupervisor | undefined;
  private gateway: HostGateway | undefined;
  private sessions: SessionService | undefined;
  private pairing: PairingCoordinator | undefined;
  private listeners: DirectTlsListeners | undefined;
  private relayManager: RelayManager | undefined;
  private terminalBackend: TerminalBackend | undefined;
  private terminalUnavailable: string | undefined;
  private voice: VoiceService | undefined;
  private push: PushPublisher | undefined;
  private admin: AdminServer | undefined;
  private material: HostTlsMaterial | undefined;
  private innerTls: InnerTlsContext | undefined;
  private lockPath: string;
  private boundPorts = { directPort: 0, provisionalPort: 0 };
  private startedAtMs = 0;
  private readonly pendingApprovals = new Map<string, ApprovalOffer>();
  private stopping = false;

  constructor(
    dataDirectory?: string,
    private readonly daemonOptions: HostDaemonOptions = {},
  ) {
    this.layout = createPathLayout(dataDirectory);
    this.lockPath = join(this.layout.runtimeDirectory, "daemon.lock");
  }

  paths(): HostPathLayout {
    return this.layout;
  }

  async start(): Promise<void> {
    ensurePrivateDirectories(this.layout);
    await this.acquireLock();
    try {
      await this.startInternal();
    } catch (error) {
      await this.stopInternal().catch(() => undefined);
      await this.releaseLock().catch(() => undefined);
      throw error;
    }
  }

  private async startInternal(): Promise<void> {
    this.startedAtMs = Date.now();
    this.config = await this.loadConfig();
    const secrets = this.daemonOptions.secrets ?? new MacOsKeychainWrappingSecret();
    const store = new HostStore(this.layout.hostStorePath);
    await store.load(() => randomUUID());
    this.store = store;
    this.revocations = new SqliteRevocationRegistry(this.layout.revocationPath);
    this.material = await loadOrCreateTlsMaterial({
      keyDirectory: this.layout.keyDirectory,
      secrets,
      instanceId: store.instanceId(),
    });
    this.innerTls = await createInnerTlsContext(this.material);
    this.journal = new SqliteCommandJournal(this.layout.journalPath);

    const pendingApprovals = this.pendingApprovals;
    this.push = new NtfyPushPublisher(parseNtfyConfig(this.config.ntfy), () => this.store?.pushEndpoint());
    const supervisor = new RuntimeSupervisor({
      dataDirectory: this.layout.dataDirectory,
      approvalSocketPath: this.layout.approvalSocketPath,
      ...(this.daemonOptions.provisioner === undefined ? {} : { provisioner: this.daemonOptions.provisioner }),
      ...(this.daemonOptions.preloadPath === undefined ? {} : { preloadPath: this.daemonOptions.preloadPath }),
      ...(this.daemonOptions.nodeExecutable === undefined ? {} : { nodeExecutable: this.daemonOptions.nodeExecutable }),
      onApprovalOffer: (offer) => {
        pendingApprovals.set(offer.offerId, offer);
        const push = this.push;
        if (push !== undefined) {
          void push.publishWake({
            settlementId: randomUUID(),
            sessionId: "00000000-0000-4000-8000-000000000000",
            streamEpoch: "00000000-0000-4000-8000-000000000000",
            sequence: "0",
            settledAtMs: Date.now(),
          });
        }
      },
    });
    await supervisor.start();
    this.supervisor = supervisor;

    this.sessions = new SessionService({
      supervisor,
      sessionsDirectory: join(this.layout.dataDirectory, "sessions"),
      onSettlement: (notice) => this.onSettlement(notice),
      onAppend: (append) => this.onSessionAppend(append),
      onAgentsUpdate: (notice) => this.onAgentsUpdate(notice),
    });

    const terminalResult = await createLocalTerminalBackend({ runtimeParent: this.layout.terminalRuntimeParent });
    if (terminalResult.state === "supported") this.terminalBackend = terminalResult.backend;
    else this.terminalUnavailable = terminalResult.code;

    this.voice = new VoiceService(this.layout.voiceLedgerPath, {
      ...(this.config.groqKeyPath === undefined ? {} : { keyPath: this.config.groqKeyPath }),
    });

    this.pairing = new PairingCoordinator({
      ceremonies: new PairingCeremonyStore(),
      store,
      authority: () => this.requireMaterial().authority,
      revocations: this.revocations,
      onDevicePaired: (device) => {
        void this.registerDeviceRouteKey(device.deviceRouteKeyId, device.deviceRoutePublicKey);
      },
    });
    const authentication = new UserAuthenticationService(store, this.revocations);
    const blobs = new BlobStore(this.layout.blobDirectory);
    const terminal = this.terminalBackend === undefined
      ? undefined
      : new TerminalRuntimeAdapter({
          backend: this.terminalBackend,
          command: process.execPath,
          args: [join(this.supervisorRoot(), "dist", "cli.js")],
          cwdForSession: (sessionId) => join(this.layout.dataDirectory, "sessions", sessionId),
        });

    this.gateway = createHostGateway({
      hostVersion: HOST_VERSION,
      piVersion: PINNED_PI_VERSION,
      features: GATEWAY_FEATURES,
      clock: SYSTEM_CLOCK,
      pairing: this.pairing,
      authentication,
      sync: this.sessions,
      journal: this.journal,
      commandAuthorizer: this.sessions,
      commandPaths: this.sessions,
      blobs,
      terminal: terminal ?? disabledTerminalRuntime,
      voice: new GatewayVoiceRuntime(this.voice),
      pushEndpoints: {
        register: (deviceId, body) => this.registerPushEndpoint(deviceId, body),
        revoke: (deviceId, body) => this.revokePushEndpoint(deviceId, body),
      },
      ...(this.config.passkeySessionTtlMs === undefined ? {} : { passkeySessionTtlMs: this.config.passkeySessionTtlMs }),
    });

    this.listeners = new DirectTlsListeners({
      material: this.material,
      gateway: this.gateway,
      isRevoked: (certificateId) => this.requireRevocations().isRevoked("device_certificate", certificateId),
      activeInvitationId: () => this.pairing?.activeInvitationId(),
    });
    const bound = await this.listeners.start(this.config.directPort, this.config.provisionalPort);
    this.boundPorts = { directPort: bound.normalPort, provisionalPort: bound.provisionalPort };

    if (this.config.relay !== undefined) {
      const relayConfig = this.config.relay;
      this.relayManager = new RelayManager({
        relayBaseUrl: relayConfig.baseUrl,
        ...(relayConfig.bootstrapToken === undefined ? {} : { bootstrapToken: relayConfig.bootstrapToken }),
        directory: this.layout.relayDirectory,
        secrets,
        onTunnel: (tunnel, notice) => this.onRelayTunnel(tunnel, notice),
        onPairingRequest: (pairingId) => {
          void this.handleRelayPairingRequest(pairingId).catch((error: unknown) => logError("pairing", "relay pairing request", error));
        },
        registeredRouteId: () => this.store?.relayRegistration()?.routeId,
        onRegistered: (routeId) => {
          void this.store?.setRelayRegistration(routeId).catch(() => undefined);
        },
      });
      await this.relayManager.start();
    }

    this.admin = new AdminServer(this.layout.adminSocketPath, async (request) => await this.handleAdmin(request.method, request.params ?? {}));
    await this.admin.start();
  }

  private requireMaterial(): HostTlsMaterial {
    if (this.material === undefined) throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "TLS material unavailable");
    return this.material;
  }

  private requireRevocations(): SqliteRevocationRegistry {
    if (this.revocations === undefined) throw new SecurityError("SECURITY_REVOCATION_UNAVAILABLE", "revocations unavailable");
    return this.revocations;
  }

  private supervisorRoot(): string {
    const runtime = this.supervisor?.pinnedRuntime();
    if (runtime === undefined) throw new Error("RUNTIME_NOT_READY");
    return runtime.root;
  }

  private async registerPushEndpoint(deviceId: string, body: JsonObject): Promise<void> {
    const store = this.store;
    if (store === undefined) throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "host store is not loaded");
    const endpointId = body["endpointId"];
    const distributor = body["distributor"];
    const endpoint = body["endpoint"];
    if (typeof endpointId !== "string" || typeof distributor !== "string" || typeof endpoint !== "string") {
      throw new SecurityError("SECURITY_INVALID_INPUT", "push endpoint message is invalid");
    }
    const wakeKey = body["wakePublicKey"];
    const existing = store.pushEndpoint();
    const wakePublicKey = typeof wakeKey === "string"
      ? wakeKey
      : existing?.deviceId === deviceId
        ? existing.wakePublicKey
        : undefined;
    if (wakePublicKey === undefined) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "push endpoint message is invalid");
    }
    await store.setPushEndpoint({ deviceId, endpointId, distributor, endpoint, wakePublicKey });
  }

  private async revokePushEndpoint(deviceId: string, body: JsonObject): Promise<void> {
    const endpointId = body["endpointId"];
    if (typeof endpointId !== "string") {
      throw new SecurityError("SECURITY_INVALID_INPUT", "push endpoint revoke message is invalid");
    }
    await this.store?.clearPushEndpoint(deviceId, endpointId);
  }

  private onSettlement(notice: SettlementNotice): void {
    void this.push?.publishWake(notice);
    this.gateway?.publishToReady("session.settlement", {
      settlementId: notice.settlementId,
      sessionId: notice.sessionId,
      streamEpoch: notice.streamEpoch,
      sequence: notice.sequence,
      settledAtMs: notice.settledAtMs,
    });
  }

  private onAgentsUpdate(notice: AgentUpdateNotice): void {
    this.gateway?.publishToReady("agents.update", {
      sessionId: notice.sessionId,
      agent: notice.agent as unknown as never,
    });
  }

  private onSessionAppend(append: SessionAppend): void {
    this.gateway?.publishToReady("message.append", {
      appendId: append.appendId,
      sessionId: append.sessionId,
      streamEpoch: append.streamEpoch,
      sequence: append.sequence,
      record: append.record as never,
    });
  }

  /**
   * Remote first-pairing rendezvous: the device deposited its one message through
   * the relay pairing exchange; register its route key so it can attach a
   * pairing_provisional data tunnel, then close the exchange with an acceptance.
   */
  private async handleRelayPairingRequest(pairingId: string): Promise<void> {
    logWarn("pairing", `relay pairing request received id=${pairingId}`);
    const manager = this.relayManager;
    if (manager === undefined) return;
    const message = await manager.pairing().fetchRequest(pairingId);
    if (message === undefined) {
      logWarn("pairing", `relay pairing request not found id=${pairingId}`);
      return;
    }
    let value: unknown;
    try {
      value = JSON.parse(Buffer.from(message).toString("utf8"));
    } catch {
      return;
    }
    if (typeof value !== "object" || value === null || Array.isArray(value)) return;
    const record = value as Record<string, unknown>;
    const invitationId = record["invitationId"];
    const deviceRouteKeyId = record["deviceRouteKeyId"];
    const deviceRoutePublicKey = record["deviceRoutePublicKey"];
    if (
      typeof invitationId !== "string" || invitationId !== this.pairing?.activeInvitationId()
      || typeof deviceRouteKeyId !== "string" || !OPAQUE_ID.test(deviceRouteKeyId)
      || typeof deviceRoutePublicKey !== "string" || !BASE64URL.test(deviceRoutePublicKey)
    ) {
      logWarn("pairing", `relay pairing request rejected id=${pairingId} (invitation mismatch or malformed)`);
      return;
    }
    const spki = Buffer.from(deviceRoutePublicKey, "base64url");
    await manager.admin().addDeviceKey(deviceRouteKeyId, new Uint8Array(spki.buffer, spki.byteOffset, spki.byteLength));
    const reply = Buffer.from(JSON.stringify({ accepted: true, invitationId }), "utf8");
    await manager.pairing().submitReply(pairingId, new Uint8Array(reply.buffer, reply.byteOffset, reply.byteLength));
    logWarn("pairing", `relay pairing request accepted id=${pairingId}`);
  }

  private onRelayTunnel(tunnel: RelayTunnel, notice: RouteNotice): void {
    logWarn("relay", `tunnel incoming mode=${notice.mode} rendezvous=${notice.rendezvousId}`);
    const inner = this.innerTls;
    const listeners = this.listeners;
    if (inner === undefined || listeners === undefined) {
      tunnel.destroy();
      return;
    }
    const provisional = notice.mode === "pairing_provisional";
    void terminateInnerTls(tunnel, inner, provisional)
      .then(async (socket) => {
        logWarn("relay", `inner TLS established provisional=${String(provisional)}`);
        socket.once("data", (chunk: Buffer) => logWarn("relay", `inner TLS first app bytes n=${String(chunk.byteLength)}`));
        setTimeout(() => logWarn("relay", "inner TLS 10s without app data"), 10_000).unref();
        await admitTlsSocket(socket, {
          material: this.requireMaterial(),
          gateway: this.requireGateway(),
          path: "relay",
          isRevoked: (certificateId) => this.requireRevocations().isRevoked("device_certificate", certificateId),
          activeInvitationId: () => this.pairing?.activeInvitationId(),
        }, provisional);
      })
      .catch((error: unknown) => {
        logError("relay", "inner TLS termination", error);
        tunnel.destroy();
      });
  }

  private requireGateway(): HostGateway {
    if (this.gateway === undefined) throw new Error("RUNTIME_NOT_READY");
    return this.gateway;
  }

  private async registerDeviceRouteKey(routeKeyId: string | undefined, publicKey: string | undefined): Promise<void> {
    if (routeKeyId === undefined || publicKey === undefined || this.relayManager === undefined) return;
    const spki = Buffer.from(publicKey, "base64url");
    await this.relayManager
      .admin()
      .addDeviceKey(routeKeyId, new Uint8Array(spki))
      .catch((error: unknown) => logError("relay", "device key registration", error));
  }

  private async handleAdmin(method: string, params: Record<string, unknown>): Promise<unknown> {
    switch (method) {
      case "status":
        return this.status();
      case "pair.begin":
        return await this.adminPairBegin();
      case "pair.status":
        return this.pairing?.status() ?? { state: "idle" };
      case "pair.confirm":
        return this.pairing?.confirmLocally(params["approved"] === true) ?? { state: "idle" };
      case "devices.list":
        return this.store?.devices() ?? [];
      case "devices.revoke":
        return await this.adminRevoke(params);
      case "approvals.list":
        return [...this.pendingApprovals.keys()];
      case "approvals.decide":
        return this.adminDecideApproval(params);
      case "relay.rotate":
        return await this.adminRelayRotate();
      case "voice.status":
        return this.voice?.status() ?? { queueSize: 0 };
      case "sessions.run":
        return await this.adminRunSessionCommand(params);
      case "stop":
        queueMicrotask(() => void this.stop());
        return { stopping: true };
      default:
        throw new Error("UNKNOWN_METHOD");
    }
  }

  private async adminPairBegin(): Promise<unknown> {
    if (this.pairing === undefined || this.material === undefined || this.store === undefined) {
      throw new Error("RUNTIME_NOT_READY");
    }
    const relayManager = this.relayManager;
    const registration = this.store.relayRegistration();
    const relayBaseUrl = this.config?.relay?.baseUrl;
    if (relayManager === undefined || registration === undefined || relayBaseUrl === undefined) {
      throw new Error("RELAY_NOT_READY");
    }
    const invitation = this.pairing.issueInvitation();
    const signer = await relayManager.routeKeyRing().activeSigner();
    let relayPairing: PairingInvitationPayload["relayPairing"];
    try {
      const exchange = await relayManager.pairing().openExchange();
      relayPairing = { pairingId: exchange.pairingId, secret: exchange.secret, expiresAt: exchange.expiresAt };
    } catch (error) {
      logError("pairing", "relay exchange open", error);
      relayPairing = undefined;
    }
    const payload: PairingInvitationPayload = {
      version: 1,
      relayUrl: relayBaseUrl,
      routeId: registration.routeId,
      routeKeyId: signer.keyId,
      invitationId: invitation.invitationId,
      expiresAt: new Date(invitation.expiresAtMs).toISOString(),
      nonce: invitation.nonce,
      serverCertificateSha256: this.material.serverCertificateSha256,
      directCandidates: this.directCandidates(),
      macInstanceId: this.store.instanceId(),
      ...(relayPairing === undefined ? {} : { relayPairing }),
    };
    const signed: SignedPairingInvitation = await signPairingInvitation(payload, signer);
    return {
      invitationId: invitation.invitationId,
      nonce: invitation.nonce,
      expiresAtMs: invitation.expiresAtMs,
      provisionalPort: this.boundPorts.provisionalPort,
      serverCertificateSha256: this.material.serverCertificateSha256,
      relayRouteId: registration.routeId,
      invitation: signed,
      uri: encodePairingInvitationUri(signed),
    };
  }

  private directCandidates(): PairingInvitationPayload["directCandidates"] {
    const port = this.boundPorts.provisionalPort;
    if (port <= 0) return [];
    return lanIPv4Addresses().slice(0, MAX_PAIRING_DIRECT_CANDIDATES).map((host) => ({ host, port }));
  }

  private async adminRevoke(params: Record<string, unknown>): Promise<unknown> {
    const deviceId = params["deviceId"];
    if (typeof deviceId !== "string") throw new Error("SECURITY_INVALID_INPUT");
    const store = this.store;
    if (store === undefined) throw new Error("RUNTIME_NOT_READY");
    const device = store.findDevice(deviceId);
    if (device === undefined) throw new Error("DEVICE_NOT_FOUND");
    this.requireRevocations().revoke({
      kind: "device_certificate",
      id: device.certificateId,
      reason: "user_requested",
      revokedAtMs: Date.now(),
    });
    if (device.deviceRouteKeyId !== undefined) {
      this.requireRevocations().revoke({
        kind: "route_key",
        id: device.deviceRouteKeyId,
        reason: "user_requested",
        revokedAtMs: Date.now(),
      });
      await this.relayManager?.admin().revokeKey(device.deviceRouteKeyId).catch(() => undefined);
    }
    await store.removeDevice(deviceId);
    return { revoked: deviceId };
  }

  private adminDecideApproval(params: Record<string, unknown>): unknown {
    const offerId = params["offerId"];
    const decision = params["decision"];
    if (typeof offerId !== "string" || (decision !== "allow_once" && decision !== "deny")) {
      throw new Error("SECURITY_INVALID_INPUT");
    }
    const offer = this.pendingApprovals.get(offerId);
    if (offer === undefined) throw new Error("APPROVAL_EXPIRED");
    const decided = this.supervisor?.decideApproval({
      offerId: offer.offerId,
      operationId: offer.operationId,
      argumentHash: offer.argumentHash,
      connectionId: offer.connectionId,
      decision,
    }) ?? false;
    this.pendingApprovals.delete(offerId);
    if (!decided) throw new Error("APPROVAL_EXPIRED");
    return { decided: offerId };
  }

  private async adminRelayRotate(): Promise<unknown> {
    if (this.relayManager === undefined) throw new Error("RELAY_TRANSPORT");
    return await this.relayManager.rotate();
  }

  /** Local trusted channel: runs one allow-listed semantic command through the real dispatch path. */
  private async adminRunSessionCommand(params: Record<string, unknown>): Promise<unknown> {
    const sessions = this.sessions;
    if (sessions === undefined) throw new Error("RUNTIME_NOT_READY");
    const sessionId = params["sessionId"];
    const operation = params["operation"];
    const payload = params["payload"] ?? {};
    if (typeof sessionId !== "string" || typeof operation !== "string") {
      throw new Error("SECURITY_INVALID_INPUT");
    }
    const command: SemanticCommand = {
      commandId: randomUUID(),
      sessionId,
      operation,
      payload: payload as SemanticCommand["payload"],
      payloadHash: createHash("sha256").update(JSON.stringify(payload), "utf8").digest("hex"),
    };
    const path = sessions.capture(sessionId);
    const context: CommandGuardContext = {
      deviceId: LOCAL_ADMIN_DEVICE_ID,
      certificateId: LOCAL_ADMIN_CERTIFICATE_ID,
      userId: "local-admin",
      path: "direct",
      pathGeneration: path.generation,
      authorizationGeneration: 0,
    };
    const signal = AbortSignal.timeout(120_000);
    const authorization = await sessions.authorize(command, context, signal);
    return await path.dispatch(command, authorization, signal);
  }

  status(): DaemonStatus {
    const relayState = this.relayManager?.state();
    return {
      ok: true,
      version: HOST_VERSION,
      protocolMajor: 1,
      compatibilityManifest: MANIFEST_VERSION,
      piVersion: PINNED_PI_VERSION,
      uptimeMs: Date.now() - this.startedAtMs,
      sessions: this.sessions?.sessionIds() ?? [],
      devices: this.store?.devices().length ?? 0,
      relay: {
        state: relayState?.state ?? "disabled",
        ...(relayState?.fault === undefined ? {} : { fault: relayState.fault }),
        ...(relayState?.routeId === undefined ? {} : { routeId: relayState.routeId }),
      },
      terminal: this.terminalBackend === undefined
        ? { available: false, ...(this.terminalUnavailable === undefined ? {} : { reason: this.terminalUnavailable }) }
        : { available: true },
      voice: { queueSize: this.voice?.status().queueSize ?? 0 },
      push: this.push?.status() ?? { configured: false, published: 0, failed: 0, skipped: 0 },
      listeners: this.boundPorts,
      pendingApprovals: this.pendingApprovals.size,
    };
  }

  async stop(): Promise<void> {
    if (this.stopping) return;
    this.stopping = true;
    try {
      await this.stopInternal();
    } finally {
      await this.releaseLock().catch(() => undefined);
    }
  }

  private async stopInternal(): Promise<void> {
    await this.admin?.stop().catch(() => undefined);
    this.admin = undefined;
    this.relayManager?.stop();
    this.relayManager = undefined;
    await this.listeners?.stop().catch(() => undefined);
    this.listeners = undefined;
    await this.gateway?.close().catch(() => undefined);
    this.gateway = undefined;
    await this.sessions?.stop().catch(() => undefined);
    this.sessions = undefined;
    await this.terminalBackend?.stop().catch(() => undefined);
    this.terminalBackend = undefined;
    await this.supervisor?.shutdown().catch(() => undefined);
    this.supervisor = undefined;
    this.voice?.close();
    this.voice = undefined;
    this.journal?.close();
    this.journal = undefined;
    this.revocations?.close();
    this.revocations = undefined;
    this.pendingApprovals.clear();
  }

  private async acquireLock(): Promise<void> {
    let handle;
    try {
      handle = await open(this.lockPath, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
    } catch (error) {
      if (error instanceof Error && "code" in error && error.code === "EEXIST") {
        const existing = await this.readLockPid();
        if (existing !== undefined && processAlive(existing)) {
          throw new Error("DAEMON_ALREADY_RUNNING", { cause: error });
        }
        await unlink(this.lockPath).catch(() => undefined);
        await this.acquireLock();
        return;
      }
      throw error;
    }
    await handle.writeFile(String(process.pid));
    await handle.close();
  }

  private async readLockPid(): Promise<number | undefined> {
    try {
      const raw = await readFile(this.lockPath, "utf8");
      const pid = Number(raw.trim());
      return Number.isSafeInteger(pid) && pid > 0 ? pid : undefined;
    } catch {
      return undefined;
    }
  }

  private async releaseLock(): Promise<void> {
    await unlink(this.lockPath).catch(() => undefined);
  }

  private async loadConfig(): Promise<HostDaemonConfig> {
    const raw = await readJsonConfig(this.layout.configPath);
    if (raw === undefined) return { directPort: 4411, provisionalPort: 4412 };
    const ports = raw["ports"];
    const relay = raw["relay"];
    const ntfy = raw["ntfy"];
    const config: HostDaemonConfig = {
      directPort: readPort(ports, "direct", 4411),
      provisionalPort: readPort(ports, "provisional", 4412),
      ...readPasskeySessionTtl(raw["passkeySessionTtlMs"]),
      ...(ntfy === undefined ? {} : { ntfy }),
    };
    if (relay === undefined) return config;
    if (typeof relay !== "object" || relay === null || Array.isArray(relay)) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "relay config is invalid");
    }
    const relayRecord = relay as Record<string, unknown>;
    const baseUrl = relayRecord["baseUrl"];
    const bootstrapToken = relayRecord["bootstrapToken"];
    if (typeof baseUrl !== "string" || !baseUrl.startsWith("wss://")) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "relay baseUrl must be a wss:// URL");
    }
    if (bootstrapToken !== undefined && typeof bootstrapToken !== "string") {
      throw new SecurityError("SECURITY_INVALID_INPUT", "relay bootstrapToken is invalid");
    }
    return {
      ...config,
      relay: {
        baseUrl,
        ...(typeof bootstrapToken === "string" ? { bootstrapToken } : {}),
      },
    };
  }
}

function readPasskeySessionTtl(value: unknown): { passkeySessionTtlMs?: number } {
  if (value === undefined) return {};
  if (!Number.isSafeInteger(value) || (value as number) < 60_000 || (value as number) > 30 * 24 * 60 * 60 * 1000) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "passkeySessionTtlMs config is invalid");
  }
  return { passkeySessionTtlMs: value as number };
}

function lanIPv4Addresses(): readonly string[] {
  const addresses: string[] = [];
  for (const infos of Object.values(networkInterfaces())) {
    for (const info of infos ?? []) {
      if (info.family === "IPv4" && !info.internal) addresses.push(info.address);
    }
  }
  return addresses.sort();
}

function readPort(ports: unknown, key: string, fallback: number): number {
  if (ports === undefined) return fallback;
  if (typeof ports !== "object" || ports === null || Array.isArray(ports)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "ports config is invalid");
  }
  const value = (ports as Record<string, unknown>)[key];
  if (value === undefined) return fallback;
  if (!Number.isInteger(value) || (value as number) < 0 || (value as number) > 65535) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "port config is invalid");
  }
  return value as number;
}

function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

const disabledTerminalRuntime = {
  open: () => Promise.reject(new Error("TERMINAL_CLOSED")),
};

export type { PairingStatus };
