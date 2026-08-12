import { randomUUID } from "node:crypto";
import { logError, logWarn } from "../daemon/log.js";
import {
  FrameKind,
  MAX_BINARY_DATA_BYTES,
  MAX_FRAME_PAYLOAD_BYTES,
  MAX_JSON_PAYLOAD_BYTES,
  assertEnvelope,
  createEnvelope,
  decodeJsonPayload,
  encodeJsonPayload,
  type Envelope,
  type JsonObject,
} from "@pimobile/protocol";
import { ProtocolError } from "@pimobile/protocol";
import { AtMostOnceCommandDispatcher, CommandGatewayError, commandStateBody } from "./command-dispatch.js";
import { deferredVoid } from "./deferred.js";
import { BoundedFrameReader, BoundedFrameWriter } from "./framing.js";
import { ContentStreamManager, StreamGatewayError } from "./streams.js";
import { CanonicalSyncSequencer, SyncSequenceError } from "./sync-sequencer.js";
import type {
  CompleteUserVerification,
  ConnectionPhase,
  GatewayConnection,
  HostGateway,
  HostGatewayOptions,
  MutualTlsTransportFacts,
  OutboundMessage,
  PairingContext,
  ProvisionalTransportFacts,
  TransportVerificationPort,
  UserAuthenticationBinding,
  VerifiedTransportAdmission,
  VerifiedUserAuthentication,
  VerifiedUserIdentity,
  VoiceAudioChunk,
  VoiceTranscriptSink,
} from "./types.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const PAIRING_INBOUND = new Set([
  "pair.begin",
  "pair.csr",
  "auth.registration.response",
  "auth.assertion.response",
  "pair.confirm",
]);
const PAIRING_OUTBOUND = new Set([
  "auth.assertion.options",
  "auth.registration.options",
  "auth.result",
  "pair.confirm",
  "pair.result",
]);
const IMMEDIATE_INBOUND = new Set(["auth.lock", "close", "ping", "pong"]);
const READY_ONLY = new Set([
  "approval.decision",
  "blob.release",
  "command.query",
  "command.submit",
  "stream.cancel",
  "stream.close",
  "stream.open",
  "terminal.close",
  "terminal.history.request",
  "terminal.open",
  "terminal.resize",
  "voice.audio",
]);
const DEFAULT_PASSKEY_SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const MAX_PASSKEY_SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_VOICE_CHUNK_BYTES = 30 * 16_000 * 2;
const MAX_VOICE_CHUNK_SEQUENCE = 1_048_575;
const BASE64URL = /^[A-Za-z0-9_-]+$/;
const CLOSE_CODES = new Set([
  "AUTH_FAILED",
  "COMMAND_ID_REUSE",
  "FRAME_TOO_LARGE",
  "PROTOCOL_VIOLATION",
  "RESOURCE_EXHAUSTED",
  "REVOKED",
  "UNSUPPORTED_VERSION",
]);

export function createHostGateway(options: HostGatewayOptions): HostGateway {
  return new HostGatewayImpl(options);
}

class HostGatewayImpl implements HostGateway {
  readonly transportVerification: TransportVerificationPort;
  private readonly admissions = new WeakSet<object>();
  private readonly connections = new Set<GatewayConnectionImpl>();
  private readonly devicePaths = new Map<string, GatewayConnectionImpl>();
  private readonly pathCounters = new Map<string, number>();
  private readonly commands: AtMostOnceCommandDispatcher;
  private closeOperation: Promise<void> | undefined;
  private closing = false;

  constructor(private readonly options: HostGatewayOptions) {
    validateOptions(options);
    this.transportVerification = createTransportVerificationPort(this.admissions);
    const recovery = options.journal.recover(options.clock.now()).then(() => undefined);
    this.commands = new AtMostOnceCommandDispatcher(options.journal, options.commandAuthorizer, options.commandPaths, options.clock, recovery);
  }

  accept(admission: VerifiedTransportAdmission): GatewayConnection {
    if (this.closing) throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Gateway is closing");
    if (!this.admissions.delete(admission)) throw new GatewayRuntimeError("AUTH_FAILED", "Transport admission is not verified");
    let pathGeneration = 0;
    if (admission.mode === "mutual-tls") {
      const facts = admission.facts;
      pathGeneration = (this.pathCounters.get(facts.deviceId) ?? 0) + 1;
      if (!Number.isSafeInteger(pathGeneration)) throw new GatewayRuntimeError("RESOURCE_EXHAUSTED", "Transport path generation exhausted");
      this.pathCounters.set(facts.deviceId, pathGeneration);
      const previous = this.devicePaths.get(facts.deviceId);
      const connection = new GatewayConnectionImpl(this.options, admission, pathGeneration, this.commands, (closed) => this.remove(closed));
      this.connections.add(connection);
      this.devicePaths.set(facts.deviceId, connection);
      if (previous !== undefined) void previous.close("PATH_REPLACED");
      return connection;
    }
    const connection = new GatewayConnectionImpl(this.options, admission, pathGeneration, this.commands, (closed) => this.remove(closed));
    this.connections.add(connection);
    return connection;
  }

  publishToReady(type: string, body: JsonObject): void {
    for (const connection of this.connections) connection.publish(type, body);
  }

  close(): Promise<void> {
    if (this.closeOperation !== undefined) return this.closeOperation;
    this.closing = true;
    this.closeOperation = Promise.all([...this.connections].map(async (connection) => connection.close("HOST_SHUTDOWN"))).then(() => undefined);
    return this.closeOperation;
  }

  private remove(connection: GatewayConnectionImpl): void {
    this.connections.delete(connection);
    const deviceId = connection.deviceId();
    if (deviceId !== undefined && this.devicePaths.get(deviceId) === connection) this.devicePaths.delete(deviceId);
  }
}

class GatewayConnectionImpl implements GatewayConnection {
  readonly pathGeneration: number;
  private currentPhase: ConnectionPhase;
  private readonly controller = new AbortController();
  private readonly reader: BoundedFrameReader;
  private readonly writer: BoundedFrameWriter;
  private readonly completion = deferredVoid();
  private readonly commandControllers = new Set<AbortController>();
  private readonly facts: ProvisionalTransportFacts | MutualTlsTransportFacts;
  private workTail: Promise<void> = Promise.resolve();
  private workCapacity: ReturnType<typeof deferredVoid> | undefined;
  private workFrames = 0;
  private workBytes = 0;
  private readonly mutualFacts: MutualTlsTransportFacts | undefined;
  private readonly pairingContext: PairingContext | undefined;
  private readonly sync: CanonicalSyncSequencer;
  private readonly streams: ContentStreamManager;
  private user: VerifiedUserAuthentication | undefined;
  private authorizationGeneration = 0;
  private authenticationController: AbortController | undefined;
  private foregroundLease: unknown;
  private negotiatedMinor: number | undefined;
  private syncController: AbortController | undefined;
  private cleaned = false;

  constructor(
    private readonly options: HostGatewayOptions,
    admission: VerifiedTransportAdmission,
    pathGeneration: number,
    private readonly commands: AtMostOnceCommandDispatcher,
    private readonly onClosed: (connection: GatewayConnectionImpl) => void,
  ) {
    this.pathGeneration = pathGeneration;
    this.facts = admission.facts;
    this.mutualFacts = admission.mode === "mutual-tls" ? admission.facts : undefined;
    this.currentPhase = admission.mode === "provisional" ? "PAIRING_PROVISIONAL" : "NEGOTIATING";
    this.reader = new BoundedFrameReader(this.facts.transport);
    this.writer = new BoundedFrameWriter(this.facts.transport, options.clock, {
      frames: options.outboundQueueFrames ?? 512,
      bytes: options.outboundQueueBytes ?? 8 * 1024 * 1024,
      stallMs: options.outboundStallMs ?? 10_000,
    });
    if (admission.mode === "provisional") {
      const facts = admission.facts;
      this.pairingContext = {
        invitationId: facts.invitationId,
        serverCertificateSha256: facts.serverCertificateSha256,
        signal: this.controller.signal,
      };
    }
    this.sync = new CanonicalSyncSequencer(options.sync, async (type, body, replyTo) => {
      if (this.currentPhase !== "SYNCING" || this.syncController === undefined) throw new GatewayRuntimeError("AUTH_REQUIRED", "Synchronization authorization expired");
      logWarn("gateway", `sync send ${type}`);
      await this.send(type, body, replyTo, this.syncController.signal);
    });
    this.streams = new ContentStreamManager(
      options.blobs,
      options.terminal,
      async (kind, payload, signal) => this.writer.send(kind, payload, signal),
      async (message) => this.sendMessage(message),
      this.controller.signal,
    );
    void this.run();
  }

  phase(): ConnectionPhase {
    return this.currentPhase;
  }

  closed(): Promise<void> {
    return this.completion.promise;
  }

  async close(code = "NORMAL_CLOSE"): Promise<void> {
    this.requestClose(code);
    await this.completion.promise;
  }

  deviceId(): string | undefined {
    return this.mutualFacts?.deviceId;
  }

  /** Best-effort live event push; only READY connections receive it. */
  publish(type: string, body: JsonObject): void {
    if (this.currentPhase !== "READY" || this.controller.signal.aborted) return;
    void this.send(type, body).catch(() => undefined);
  }

  private async run(): Promise<void> {
    try {
      while (!this.controller.signal.aborted) {
        const frame = await this.reader.next(this.controller.signal);
        if (frame === null) break;
        logWarn("gateway", `frame kind=${String(frame.kind)} bytes=${String(frame.payload.byteLength)} phase=${this.currentPhase}`);
        await this.handleFrame(frame.kind, frame.payload);
      }
    } catch (error) {
      logError("gateway", "read loop", error);
      if (!this.controller.signal.aborted) await this.handleFailure(error);
    } finally {
      await this.cleanup();
    }
  }

  private async handleFrame(kind: FrameKind, payload: Uint8Array): Promise<void> {
    if (kind === FrameKind.Json) {
      const value = decodeJsonPayload(payload);
      assertEnvelope(value);
      if (IMMEDIATE_INBOUND.has(value.type)) await this.handleEnvelope(value);
      else await this.enqueueWork(payload.length, () => this.handleEnvelope(value));
      return;
    }
    await this.enqueueWork(payload.length, () => this.handleBinaryFrame(kind, payload));
  }

  private async handleBinaryFrame(kind: FrameKind, payload: Uint8Array): Promise<void> {
    if (this.currentPhase !== "READY") throw new GatewayRuntimeError("AUTH_REQUIRED", "Binary streams require READY authorization");
    if (kind === FrameKind.BlobChunk) return this.streams.blobChunk(payload);
    if (kind === FrameKind.TerminalBytes) return this.streams.terminalBytes(payload);
    throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Audio stream is not accepted by this gateway runtime");
  }

  private async enqueueWork(bytes: number, operation: () => Promise<void>): Promise<void> {
    while (this.workFrames >= 512 || this.workBytes + bytes > 8 * 1024 * 1024) await this.waitForWorkCapacity();
    this.controller.signal.throwIfAborted();
    this.workFrames += 1;
    this.workBytes += bytes;
    const running = this.workTail.then(() => {
      this.controller.signal.throwIfAborted();
      return operation();
    });
    this.workTail = running.catch((error: unknown) => this.respondFailure(error, null)).catch((error: unknown) => {
      this.requestClose(normalizeError(error).code);
    }).finally(() => {
      this.workFrames -= 1;
      this.workBytes -= bytes;
      this.workCapacity?.resolve();
      this.workCapacity = undefined;
    });
  }

  private async waitForWorkCapacity(): Promise<void> {
    const waiter = deferredVoid();
    this.workCapacity = waiter;
    const timeout = this.options.clock.setTimeout(() => waiter.reject(new GatewayRuntimeError("RESOURCE_EXHAUSTED", "Inbound work queue remained full")), this.options.outboundStallMs ?? 10_000);
    const abort = (): void => waiter.reject(this.controller.signal.reason);
    this.controller.signal.addEventListener("abort", abort, { once: true });
    try {
      await waiter.promise;
    } finally {
      if (this.workCapacity === waiter) this.workCapacity = undefined;
      this.options.clock.clearTimeout(timeout);
      this.controller.signal.removeEventListener("abort", abort);
    }
  }

  private async handleEnvelope(message: Envelope): Promise<void> {
    try {
      if (this.currentPhase === "PAIRING_PROVISIONAL" && message.v.minor !== 0) {
        throw new GatewayRuntimeError("UNSUPPORTED_VERSION", "Pairing minor version is unsupported");
      }
      if (this.currentPhase !== "PAIRING_PROVISIONAL" && this.currentPhase !== "NEGOTIATING" && message.v.minor !== this.negotiatedMinor) {
        throw new GatewayRuntimeError("UNSUPPORTED_VERSION", "Message minor version was not negotiated");
      }
      if (message.type === "close") {
        this.requestClose("PEER_CLOSED");
        return;
      }
      if (message.type === "ping") {
        this.handleForegroundLease(message.body);
        await this.send("pong", {}, message.messageId);
        return;
      }
      if (message.type === "pong") return;
      if (this.currentPhase === "PAIRING_PROVISIONAL") {
        await this.handlePairing(message);
        return;
      }
      await this.handleNormal(message);
    } catch (error) {
      await this.respondFailure(error, message.messageId);
    }
  }

  private async handlePairing(message: Envelope): Promise<void> {
    if (!PAIRING_INBOUND.has(message.type)) {
      throw new GatewayRuntimeError("PAIRING_PHASE_REQUIRED", "Provisional transport accepts pairing messages only");
    }
    const context = this.pairingContext;
    if (context === undefined) throw new GatewayRuntimeError("AUTH_FAILED", "Pairing transport facts are unavailable");
    const result = await this.options.pairing.handle(message, context);
    const replies = result.replies ?? [];
    if (replies.some((reply) => !PAIRING_OUTBOUND.has(reply.type))) {
      throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Pairing runtime attempted application output");
    }
    if (result.certificateIssued === true && !replies.some((reply) => reply.type === "pair.result")) {
      throw new GatewayRuntimeError("AUTH_FAILED", "Certificate issuance omitted pair.result");
    }
    for (const reply of replies) await this.sendMessage({ ...reply, replyTo: reply.replyTo ?? message.messageId });
    if (result.certificateIssued === true) this.requestClose("PAIRING_COMPLETE");
  }

  private async handleNormal(message: Envelope): Promise<void> {
    this.requireMutualFacts();
    if (message.type === "auth.lock") {
      if (this.currentPhase === "NEGOTIATING") throw new GatewayRuntimeError("AUTH_REQUIRED", "Connection has not negotiated hello");
      await this.downgrade("AUTH_LOCK");
      return;
    }
    if (message.type === "client.hello") {
      await this.negotiate(message);
      return;
    }
    if (message.type === "auth.assertion.response") {
      await this.authenticate(message);
      return;
    }
    if (message.type === "sync.resume") {
      await this.startSync(message);
      return;
    }
    if (message.type === "event.ack") {
      if (this.currentPhase !== "SYNCING") throw this.phaseError();
      const controller = this.syncController;
      if (controller === undefined) throw new GatewayRuntimeError("SYNC_REQUIRED", "Synchronization is not active");
      const drained = await this.sync.acknowledge(message.body, controller.signal);
      if (!drained) return;
      controller.abort("sync_committed");
      this.syncController = undefined;
      this.currentPhase = "READY";
      return;
    }
    if (message.type === "push.endpoint" || message.type === "push.endpoint.revoke") {
      if (this.currentPhase !== "USER_AUTHENTICATED" && this.currentPhase !== "SYNCING" && this.currentPhase !== "READY") throw this.phaseError();
      const runtime = this.options.pushEndpoints;
      if (runtime === undefined) throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Known mutation has no gateway runtime");
      const facts = this.requireMutualFacts();
      if (message.type === "push.endpoint") await runtime.register(facts.deviceId, message.body);
      else await runtime.revoke(facts.deviceId, message.body);
      return;
    }
    if (READY_ONLY.has(message.type)) {
      if (this.currentPhase !== "READY") throw this.phaseError();
      await this.handleReady(message);
      return;
    }
    if (this.currentPhase !== "READY") throw this.phaseError();
    await this.options.unknownMessages?.retain(message);
  }

  private async negotiate(message: Envelope): Promise<void> {
    if (this.currentPhase !== "NEGOTIATING") {
      throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "client.hello is not valid in this phase");
    }
    const facts = this.requireMutualFacts();
    const minMinor = message.body["minMinor"];
    const maxMinor = message.body["maxMinor"];
    const appVersion = message.body["appVersion"];
    const deviceId = message.body["deviceId"];
    const features = message.body["features"];
    if (
      !Number.isInteger(minMinor) || !Number.isInteger(maxMinor)
      || (minMinor as number) < 0 || (maxMinor as number) > 255 || (minMinor as number) > (maxMinor as number)
      || typeof appVersion !== "string" || appVersion.length === 0 || appVersion.length > 128
      || deviceId !== facts.deviceId
      || !Array.isArray(features) || features.length > 128
      || features.some((feature) => typeof feature !== "string" || feature.length === 0 || feature.length > 64)
      || new Set(features).size !== features.length
    ) {
      throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "client.hello body is invalid");
    }
    if ((minMinor as number) > 0 || (maxMinor as number) < 0) {
      throw new GatewayRuntimeError("UNSUPPORTED_VERSION", "No common protocol minor version exists");
    }
    const negotiatedFeatures = this.options.features.filter((feature) => features.includes(feature));
    this.negotiatedMinor = 0;
    this.currentPhase = "DEVICE_AUTHENTICATED";
    await this.send("server.hello", {
      minor: 0,
      hostVersion: this.options.hostVersion,
      piVersion: this.options.piVersion,
      features: negotiatedFeatures,
      limits: {
        framePayloadBytes: MAX_FRAME_PAYLOAD_BYTES,
        jsonPayloadBytes: MAX_JSON_PAYLOAD_BYTES,
        binaryDataBytes: MAX_BINARY_DATA_BYTES,
        outboundQueueFrames: this.options.outboundQueueFrames ?? 512,
        outboundQueueBytes: this.options.outboundQueueBytes ?? 8 * 1024 * 1024,
      },
    }, message.messageId);
    const binding = this.authenticationBinding();
    const options = await this.options.authentication.assertionOptions(binding, this.controller.signal);
    await this.send("auth.assertion.options", options);
  }

  private async authenticate(message: Envelope): Promise<void> {
    if (this.currentPhase !== "DEVICE_AUTHENTICATED") throw this.phaseError();
    const authorizationGeneration = this.authorizationGeneration;
    const controller = linkedController(this.controller.signal);
    this.authenticationController = controller;
    const binding = this.authenticationBinding();
    let issued: VerifiedUserAuthentication | undefined;
    const currentFacts = new WeakSet<object>();
    const complete: CompleteUserVerification = (identity) => {
      if (controller.signal.aborted) throw new GatewayRuntimeError("AUTH_REQUIRED", "User verification was cancelled");
      if (issued !== undefined) throw new GatewayRuntimeError("AUTH_FAILED", "User verification completion was reused");
      validateIdentity(identity);
      const value = Object.freeze({ ...identity, binding }) as VerifiedUserAuthentication;
      currentFacts.add(value);
      issued = value;
      return value;
    };
    let result: VerifiedUserAuthentication;
    try {
      result = await this.options.authentication.verifyAssertion(message.body, binding, complete, controller.signal);
    } finally {
      if (this.authenticationController === controller) this.authenticationController = undefined;
    }
    if (this.phase() !== "DEVICE_AUTHENTICATED" || this.authorizationGeneration !== authorizationGeneration) {
      throw new GatewayRuntimeError("AUTH_REQUIRED", "Authentication was cancelled by a lock");
    }
    if (result !== issued || !currentFacts.has(result) || result.binding !== binding) {
      throw new GatewayRuntimeError("AUTH_FAILED", "Authentication runtime did not return the current verified fact");
    }
    this.user = result;
    this.authorizationGeneration += 1;
    this.currentPhase = "USER_AUTHENTICATED";
    this.armForegroundLease();
    const ttlMs = validatedPasskeySessionTtlMs(this.options.passkeySessionTtlMs);
    await this.send("auth.result", {
      success: true,
      expiresAt: new Date(this.options.clock.now() + ttlMs).toISOString(),
    }, message.messageId);
  }

  private async startSync(message: Envelope): Promise<void> {
    if (this.currentPhase !== "USER_AUTHENTICATED") throw this.phaseError();
    const authorizationGeneration = this.authorizationGeneration;
    const controller = linkedController(this.controller.signal);
    this.syncController = controller;
    this.currentPhase = "SYNCING";
    try {
      await this.sync.start(message.body, message.messageId, controller.signal);
      if (this.phase() !== "SYNCING" || this.authorizationGeneration !== authorizationGeneration) {
        throw new GatewayRuntimeError("AUTH_REQUIRED", "Synchronization authorization expired");
      }
      if (this.sync.completedWithoutWork) {
        // Nothing to commit: catalogs + sync.complete were sent, no ack fence needed.
        controller.abort("sync_committed");
        if (this.syncController === controller) this.syncController = undefined;
        this.currentPhase = "READY";
      }
    } catch (error) {
      if (this.syncController === controller) this.syncController = undefined;
      controller.abort("sync_failed");
      this.sync.cancel();
      if (this.phase() === "SYNCING" && this.authorizationGeneration === authorizationGeneration) this.currentPhase = "USER_AUTHENTICATED";
      throw error;
    }
  }

  private async handleReady(message: Envelope): Promise<void> {
    if (message.type === "command.submit") {
      this.startCommand(message);
      return;
    }
    if (message.type === "command.query") {
      const record = await this.commands.query(message.body);
      if (record === undefined) throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Command does not exist");
      await this.send("command.state", commandStateBody(record), message.messageId);
      return;
    }
    if (message.type === "stream.open") return this.streams.openBlob(message.body);
    if (message.type === "stream.close") return this.streams.closeBlob(message.body);
    if (message.type === "stream.cancel") return this.streams.cancelBlob(message.body);
    if (message.type === "blob.release") return this.streams.releaseBlob(message.body);
    if (message.type === "terminal.open") return this.streams.openTerminal(message.body);
    if (message.type === "terminal.resize") return this.streams.resizeTerminal(message.body);
    if (message.type === "terminal.history.request") return this.streams.terminalHistory(message.body);
    if (message.type === "terminal.close") return this.streams.closeTerminal();
    if (message.type === "voice.audio") {
      this.startVoiceChunk(message);
      return;
    }
    throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Known mutation has no gateway runtime");
  }

  private startCommand(message: Envelope): void {
    if (this.user === undefined) throw new GatewayRuntimeError("AUTH_REQUIRED", "Command requires user authentication");
    const facts = this.requireMutualFacts();
    const commandController = linkedController(this.controller.signal);
    this.commandControllers.add(commandController);
    const authorizationGeneration = this.authorizationGeneration;
    const user = this.user;
    const context = {
      deviceId: facts.deviceId,
      certificateId: facts.certificateId,
      userId: user.userId,
      path: facts.path,
      pathGeneration: this.pathGeneration,
      authorizationGeneration,
      authorized: () => this.currentPhase === "READY" && this.authorizationGeneration === authorizationGeneration && this.user === user && !commandController.signal.aborted,
    };
    logWarn("gateway", `command.submit received`);
    void this.commands.submit(message.body, context, commandController.signal).then(async (outcome) => {
      logWarn("gateway", `command outcome state=${outcome.record.state}`);
      if (this.controller.signal.aborted || !context.authorized()) return;
      const type = outcome.record.state === "ACKED" ? "command.result" : "command.state";
      await this.send(type, commandStateBody(outcome.record), message.messageId);
    }).catch(async (error: unknown) => {
      logError("gateway", "command dispatch", error);
      await this.respondFailure(error, message.messageId);
    }).finally(() => {
      this.commandControllers.delete(commandController);
    });
  }

  private startVoiceChunk(message: Envelope): void {
    const runtime = this.options.voice;
    if (runtime === undefined) throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Voice runtime is unavailable");
    const chunk = parseVoiceAudio(message.body);
    const controller = linkedController(this.controller.signal);
    this.commandControllers.add(controller);
    let lastRevision = 0;
    let finished = false;
    const sink: VoiceTranscriptSink = {
      partial: async (update, signal) => {
        signal.throwIfAborted();
        if (finished || update.revision <= lastRevision || update.sessionId !== chunk.sessionId || update.chunkSequence !== chunk.chunkSequence) {
          throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "voice partial revisions must increase monotonically per chunk");
        }
        lastRevision = update.revision;
        await this.send("voice.partial", {
          sessionId: update.sessionId,
          chunkSequence: update.chunkSequence,
          revision: update.revision,
          text: update.text,
        }, null, controller.signal);
      },
      finish: async (update, signal) => {
        signal.throwIfAborted();
        if (finished || update.sessionId !== chunk.sessionId || update.chunkSequence !== chunk.chunkSequence) {
          throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "voice chunk is already closed");
        }
        finished = true;
        await this.send("voice.finish", {
          sessionId: update.sessionId,
          chunkSequence: update.chunkSequence,
          text: update.text,
        }, null, controller.signal);
      },
    };
    void runtime.submit(chunk, sink, controller.signal).catch(async (error: unknown) => {
      if (this.controller.signal.aborted) return;
      const raw = error instanceof Error && "code" in error && typeof (error as { code?: unknown }).code === "string"
        ? (error as { code: string }).code
        : "";
      const code = /^[A-Z][A-Z0-9_]{1,63}$/.test(raw) ? raw : "VOICE_FAILED";
      await this.send("voice.error", {
        sessionId: chunk.sessionId,
        chunkSequence: chunk.chunkSequence,
        code,
        message: "Voice transcription failed",
      }, null, controller.signal).catch(() => undefined);
    }).finally(() => {
      this.commandControllers.delete(controller);
    });
  }

  private handleForegroundLease(body: JsonObject): void {
    const foregroundLease = body["foregroundLease"];
    if (foregroundLease !== undefined && typeof foregroundLease !== "boolean") {
      throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "Foreground lease signal is invalid");
    }
    if (foregroundLease === true && (this.currentPhase === "USER_AUTHENTICATED" || this.currentPhase === "SYNCING" || this.currentPhase === "READY")) {
      this.armForegroundLease();
    }
  }

  private armForegroundLease(): void {
    if (this.foregroundLease !== undefined) this.options.clock.clearTimeout(this.foregroundLease);
    this.foregroundLease = this.options.clock.setTimeout(() => {
      this.foregroundLease = undefined;
      void this.downgrade("FOREGROUND_LEASE_EXPIRED");
    }, 5 * 60_000);
  }

  private async downgrade(reason: string): Promise<void> {
    if (this.currentPhase === "NEGOTIATING" || this.currentPhase === "PAIRING_PROVISIONAL" || this.currentPhase === "CLOSING") return;
    if (this.foregroundLease !== undefined) {
      this.options.clock.clearTimeout(this.foregroundLease);
      this.foregroundLease = undefined;
    }
    this.authorizationGeneration += 1;
    this.user = undefined;
    this.authenticationController?.abort(reason);
    this.authenticationController = undefined;
    this.syncController?.abort(reason);
    this.syncController = undefined;
    this.sync.cancel();
    this.currentPhase = "DEVICE_AUTHENTICATED";
    for (const controller of this.commandControllers) controller.abort(reason);
    await this.streams.cancelAll(reason);
  }

  private authenticationBinding(): UserAuthenticationBinding {
    const facts = this.requireMutualFacts();
    return Object.freeze({
      deviceId: facts.deviceId,
      certificateId: facts.certificateId,
      tlsExporter: facts.tlsExporter.slice(),
      pathGeneration: this.pathGeneration,
    });
  }

  private phaseError(): GatewayRuntimeError {
    if (this.currentPhase === "DEVICE_AUTHENTICATED" || this.currentPhase === "NEGOTIATING") {
      return new GatewayRuntimeError("AUTH_REQUIRED", "User authentication is required");
    }
    return new GatewayRuntimeError("SYNC_REQUIRED", "Synchronization must commit before application access");
  }

  private async sendMessage(message: OutboundMessage): Promise<void> {
    await this.send(message.type, message.body, message.replyTo);
  }

  private async send(
    type: string,
    body: JsonObject,
    replyTo: string | null = null,
    signal: AbortSignal = this.controller.signal,
  ): Promise<void> {
    if (this.controller.signal.aborted) throw this.controller.signal.reason;
    signal.throwIfAborted();
    const envelope = createEnvelope(type, randomUUID(), replyTo, body);
    await this.writer.send(FrameKind.Json, encodeJsonPayload(envelope), signal);
  }

  private async respondFailure(error: unknown, replyTo: string | null): Promise<void> {
    const gatewayError = normalizeError(error);
    if (!this.controller.signal.aborted) {
      try {
        await this.send("error", {
          code: gatewayError.code,
          message: safeMessage(gatewayError.code),
          retryable: !CLOSE_CODES.has(gatewayError.code),
        }, replyTo);
      } catch {
        this.requestClose(gatewayError.code);
        return;
      }
    }
    if (CLOSE_CODES.has(gatewayError.code)) this.requestClose(gatewayError.code);
  }

  private async handleFailure(error: unknown): Promise<void> {
    const gatewayError = normalizeError(error);
    try {
      await this.respondFailure(gatewayError, null);
    } finally {
      this.requestClose(gatewayError.code);
    }
  }

  private requestClose(code: string): void {
    if (this.currentPhase === "CLOSING") return;
    this.currentPhase = "CLOSING";
    if (this.foregroundLease !== undefined) {
      this.options.clock.clearTimeout(this.foregroundLease);
      this.foregroundLease = undefined;
    }
    this.authorizationGeneration += 1;
    for (const controller of this.commandControllers) controller.abort(code);
    this.authenticationController?.abort(code);
    this.authenticationController = undefined;
    this.syncController?.abort(code);
    this.syncController = undefined;
    this.controller.abort(code);
    this.writer.abort(code);
  }

  private async cleanup(): Promise<void> {
    if (this.cleaned) return;
    this.cleaned = true;
    this.requestClose("CONNECTION_CLOSED");
    this.sync.cancel();
    await this.streams.cancelAll("CONNECTION_CLOSED");
    try {
      if (this.pairingContext !== undefined) await this.options.pairing.cancel?.(this.pairingContext);
    } catch (error) {
      void error;
    }
    try {
      await this.facts.transport.close(typeof this.controller.signal.reason === "string" ? this.controller.signal.reason : "CONNECTION_CLOSED");
    } catch (error) {
      void error;
    }
    this.onClosed(this);
    this.completion.resolve();
  }

  private requireMutualFacts(): MutualTlsTransportFacts {
    if (this.mutualFacts === undefined) throw new GatewayRuntimeError("AUTH_FAILED", "Mutual TLS facts are unavailable");
    return this.mutualFacts;
  }
}

class GatewayRuntimeError extends Error {
  constructor(readonly code: string, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "GatewayRuntimeError";
  }
}

function createTransportVerificationPort(verifiedAdmissions: WeakSet<object>): TransportVerificationPort {
  return Object.freeze({
    provisionalVerified(facts: ProvisionalTransportFacts): VerifiedTransportAdmission {
      validateProvisionalFacts(facts);
      const snapshot = Object.freeze({ ...facts });
      return verifiedAdmission("provisional", snapshot, verifiedAdmissions);
    },
    mutualTlsVerified(facts: MutualTlsTransportFacts): VerifiedTransportAdmission {
      validateMutualTlsFacts(facts);
      const snapshot = Object.freeze({ ...facts, tlsExporter: facts.tlsExporter.slice() });
      return verifiedAdmission("mutual-tls", snapshot, verifiedAdmissions);
    },
  });
}

function verifiedAdmission(
  mode: "mutual-tls" | "provisional",
  facts: ProvisionalTransportFacts | MutualTlsTransportFacts,
  verifiedAdmissions: WeakSet<object>,
): VerifiedTransportAdmission {
  const admission = Object.freeze({ mode, facts }) as VerifiedTransportAdmission;
  verifiedAdmissions.add(admission);
  return admission;
}

function validateProvisionalFacts(facts: ProvisionalTransportFacts): void {
  if (!UUID_V4.test(facts.invitationId) || !SHA256.test(facts.serverCertificateSha256)) {
    throw new GatewayRuntimeError("AUTH_FAILED", "Provisional transport verification facts are invalid");
  }
}

function validateMutualTlsFacts(facts: MutualTlsTransportFacts): void {
  if (!UUID_V4.test(facts.deviceId) || facts.certificateId.length === 0 || facts.certificateId.length > 256 || facts.tlsExporter.length !== 32 || !new Set<string>(["direct", "relay"]).has(facts.path)) {
    throw new GatewayRuntimeError("AUTH_FAILED", "Mutual TLS verification facts are invalid");
  }
}

function validateIdentity(identity: VerifiedUserIdentity): void {
  if (identity.userId.length === 0 || identity.userId.length > 256 || identity.credentialId.length === 0 || identity.credentialId.length > 1024) {
    throw new GatewayRuntimeError("AUTH_FAILED", "Verified user identity is invalid");
  }
}

function validateOptions(options: HostGatewayOptions): void {
  if (options.hostVersion.length === 0 || options.hostVersion.length > 128 || options.piVersion.length === 0 || options.piVersion.length > 128) {
    throw new RangeError("gateway versions are invalid");
  }
  if (options.features.length > 128 || options.features.some((feature) => feature.length === 0 || feature.length > 64) || new Set(options.features).size !== options.features.length) {
    throw new RangeError("gateway features are invalid");
  }
  validatedPasskeySessionTtlMs(options.passkeySessionTtlMs);
}

function normalizeError(error: unknown): GatewayRuntimeError {
  if (error instanceof GatewayRuntimeError) return error;
  if (error instanceof CommandGatewayError || error instanceof StreamGatewayError || error instanceof SyncSequenceError || error instanceof ProtocolError) {
    return new GatewayRuntimeError(error.code, error.message, { cause: error });
  }
  if (error instanceof Error && error.name === "AbortError") return new GatewayRuntimeError("AUTH_REQUIRED", "Authorized operation was cancelled", { cause: error });
  return new GatewayRuntimeError("PROTOCOL_VIOLATION", "Gateway runtime failed", { cause: error });
}

function safeMessage(code: string): string {
  const messages: Record<string, string> = {
    AUTH_FAILED: "Authentication failed",
    AUTH_REQUIRED: "Authentication required",
    COMMAND_ID_REUSE: "Command identifier reuse",
    FRAME_TOO_LARGE: "Frame exceeds negotiated limit",
    JOURNAL_UNAVAILABLE: "Command journal unavailable",
    PAIRING_PHASE_REQUIRED: "Pairing phase required",
    PROTOCOL_VIOLATION: "Protocol violation",
    RESOURCE_EXHAUSTED: "Connection resources exhausted",
    STREAM_INVALID: "Stream invalid",
    SYNC_REQUIRED: "Synchronization required",
    TERMINAL_RESET_REQUIRED: "Terminal reset required",
    UNSUPPORTED_VERSION: "Protocol version unsupported",
  };
  return messages[code] ?? "Request failed";
}

function validatedPasskeySessionTtlMs(value: number | undefined): number {
  const ttl = value ?? DEFAULT_PASSKEY_SESSION_TTL_MS;
  if (!Number.isSafeInteger(ttl) || ttl < 60_000 || ttl > MAX_PASSKEY_SESSION_TTL_MS) {
    throw new RangeError("passkey session TTL is invalid");
  }
  return ttl;
}

function parseVoiceAudio(body: JsonObject): VoiceAudioChunk {
  const sessionId = body["sessionId"];
  const chunkSequence = body["chunkSequence"];
  const final = body["final"];
  const audio = body["audio"];
  if (
    typeof sessionId !== "string" || !UUID_V4.test(sessionId)
    || !Number.isSafeInteger(chunkSequence) || (chunkSequence as number) < 0 || (chunkSequence as number) > MAX_VOICE_CHUNK_SEQUENCE
    || typeof final !== "boolean"
    || typeof audio !== "string" || audio.length === 0 || !BASE64URL.test(audio)
  ) {
    throw new GatewayRuntimeError("PROTOCOL_VIOLATION", "voice.audio body is invalid");
  }
  const pcm = Buffer.from(audio, "base64url");
  if (pcm.byteLength === 0 || pcm.byteLength % 2 !== 0 || pcm.byteLength > MAX_VOICE_CHUNK_BYTES) {
    throw new GatewayRuntimeError("RESOURCE_EXHAUSTED", "voice chunk exceeds bounds");
  }
  return {
    sessionId,
    chunkSequence: chunkSequence as number,
    final,
    pcm16le: new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength),
  };
}

function linkedController(parent: AbortSignal): AbortController {
  const controller = new AbortController();
  if (parent.aborted) controller.abort(parent.reason);
  else parent.addEventListener("abort", () => controller.abort(parent.reason), { once: true });
  return controller;
}
