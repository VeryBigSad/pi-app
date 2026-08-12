import type { Envelope, JsonObject, JsonValue } from "@pimobile/protocol";
import type { CommandJournalStore, SemanticCommand } from "../journal/types.js";
import type { SnapshotSource } from "../sync/canonical-snapshot.js";

export type ConnectionPhase =
  | "PAIRING_PROVISIONAL"
  | "NEGOTIATING"
  | "DEVICE_AUTHENTICATED"
  | "USER_AUTHENTICATED"
  | "SYNCING"
  | "READY"
  | "CLOSING";

export type TransportPath = "direct" | "relay";

export interface ByteTransport {
  read(maxBytes: number, signal: AbortSignal): Promise<Uint8Array | null>;
  write(bytes: Uint8Array, signal: AbortSignal): Promise<void>;
  close(code: string): Promise<void>;
}

export interface GatewayClock {
  now(): number;
  setTimeout(operation: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}

export interface ProvisionalTransportFacts {
  readonly transport: ByteTransport;
  readonly invitationId: string;
  readonly serverCertificateSha256: string;
}

export interface MutualTlsTransportFacts {
  readonly transport: ByteTransport;
  readonly deviceId: string;
  readonly certificateId: string;
  readonly tlsExporter: Uint8Array;
  readonly path: TransportPath;
}

declare const verifiedTransportBrand: unique symbol;

export type VerifiedTransportAdmission =
  | {
      readonly [verifiedTransportBrand]: true;
      readonly facts: ProvisionalTransportFacts;
      readonly mode: "provisional";
    }
  | {
      readonly [verifiedTransportBrand]: true;
      readonly facts: MutualTlsTransportFacts;
      readonly mode: "mutual-tls";
    };

export interface TransportVerificationPort {
  provisionalVerified(facts: ProvisionalTransportFacts): VerifiedTransportAdmission;
  mutualTlsVerified(facts: MutualTlsTransportFacts): VerifiedTransportAdmission;
}

export interface PairingContext {
  readonly invitationId: string;
  readonly serverCertificateSha256: string;
  readonly signal: AbortSignal;
}

export interface PairingResult {
  readonly replies?: readonly OutboundMessage[];
  readonly certificateIssued?: boolean;
}

export interface PairingRuntime {
  handle(message: Envelope, context: PairingContext): Promise<PairingResult>;
  cancel?(context: PairingContext): Promise<void>;
}

export interface UserAuthenticationBinding {
  readonly deviceId: string;
  readonly certificateId: string;
  readonly tlsExporter: Uint8Array;
  readonly pathGeneration: number;
}

export interface VerifiedUserIdentity {
  readonly userId: string;
  readonly credentialId: string;
}

declare const verifiedUserBrand: unique symbol;

export interface VerifiedUserAuthentication extends VerifiedUserIdentity {
  readonly [verifiedUserBrand]: true;
  readonly binding: UserAuthenticationBinding;
}

export type CompleteUserVerification = (identity: VerifiedUserIdentity) => VerifiedUserAuthentication;

export interface UserAuthenticationRuntime {
  assertionOptions(binding: UserAuthenticationBinding, signal: AbortSignal): Promise<JsonObject>;
  verifyAssertion(
    response: JsonObject,
    binding: UserAuthenticationBinding,
    complete: CompleteUserVerification,
    signal: AbortSignal,
  ): Promise<VerifiedUserAuthentication>;
}

export interface ReplaySyncPlan {
  readonly kind: "replay";
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly fromSequence: bigint;
  readonly throughSequence: bigint;
  readonly events: readonly JsonObject[];
}

export interface SnapshotSyncPlan {
  readonly kind: "snapshot";
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly source: SnapshotSource;
  readonly adjunctPages?: readonly JsonObject[];
  readonly catalog?: JsonObject;
  readonly agentsCatalog?: JsonObject;
}

export type GatewaySyncPlan = ReplaySyncPlan | SnapshotSyncPlan;

export interface SyncRuntime {
  prepare(resume: JsonObject, signal: AbortSignal): Promise<GatewaySyncPlan>;
  committed(plan: GatewaySyncPlan, sequence: bigint, signal: AbortSignal): Promise<void>;
  /** Resume-shaped entries for every currently supervised session; used when a device resumes with no cursors. */
  listAll?(signal: AbortSignal): Promise<JsonObject[]>;
}

export interface CommandGuardContext {
  readonly deviceId: string;
  readonly certificateId: string;
  readonly userId: string;
  readonly path: TransportPath;
  readonly pathGeneration: number;
  readonly authorizationGeneration: number;
}

export interface CommandAuthorization {
  readonly approvedAtMs: number;
  revalidate(signal: AbortSignal): Promise<void>;
}

export interface CommandAuthorizer {
  authorize(command: SemanticCommand, context: CommandGuardContext, signal: AbortSignal): Promise<CommandAuthorization>;
}

export interface CommandDispatchPath {
  readonly generation: number;
  dispatch(command: SemanticCommand, authorization: CommandAuthorization, signal: AbortSignal): Promise<JsonValue>;
}

export interface CommandPathRouter {
  capture(sessionId: string): CommandDispatchPath;
}

export interface BlobStreamMetadata {
  readonly streamId: string;
  readonly purpose: string;
  readonly mediaType: string;
  readonly limit: bigint;
  readonly expectedLength?: bigint;
  readonly sha256?: string;
}

export interface BlobStreamUpload {
  write(sequence: number, offset: bigint, data: Uint8Array, signal: AbortSignal): Promise<void>;
  close(length: bigint, sha256: string, signal: AbortSignal): Promise<OutboundMessage | undefined>;
  cancel(reason: string): Promise<void>;
}

export interface BlobOutput {
  write(streamId: string, sequence: number, offset: bigint, data: Uint8Array, signal: AbortSignal): Promise<void>;
}

export interface BlobRuntime {
  open(metadata: BlobStreamMetadata, output: BlobOutput, signal: AbortSignal): Promise<BlobStreamUpload>;
  release?(blobId: string, signal: AbortSignal): Promise<void>;
}

export interface TerminalOutput {
  write(data: Uint8Array, signal: AbortSignal): Promise<void>;
  reset(reason: string, signal: AbortSignal): Promise<void>;
}

export interface TerminalChannel {
  write(data: Uint8Array, signal: AbortSignal): Promise<void>;
  resize?(columns: number, rows: number, signal: AbortSignal): Promise<void>;
  reset?(reason: string, signal: AbortSignal): Promise<void>;
  close(reason: string): Promise<void>;
}

export interface TerminalOpenResult {
  readonly generation: bigint;
  readonly channel: TerminalChannel;
  readonly body?: JsonObject;
}

export interface TerminalRuntime {
  open(request: JsonObject, output: TerminalOutput, signal: AbortSignal): Promise<TerminalOpenResult>;
  history?(request: JsonObject, signal: AbortSignal): Promise<JsonObject>;
}

export interface OutboundMessage {
  readonly type: string;
  readonly body: JsonObject;
  readonly replyTo?: string | null;
}

export interface VoiceAudioChunk {
  readonly sessionId: string;
  readonly chunkSequence: number;
  readonly final: boolean;
  readonly pcm16le: Uint8Array;
}

export interface VoiceTranscriptSink {
  partial(update: { readonly sessionId: string; readonly chunkSequence: number; readonly revision: number; readonly text: string }, signal: AbortSignal): Promise<void>;
  finish(update: { readonly sessionId: string; readonly chunkSequence: number; readonly text: string }, signal: AbortSignal): Promise<void>;
}

export interface VoiceRuntime {
  submit(chunk: VoiceAudioChunk, sink: VoiceTranscriptSink, signal: AbortSignal): Promise<void>;
}

export interface UnknownMessageSink {
  retain(message: Envelope): Promise<void>;
}

/** Handles device-registered UnifiedPush endpoints (push.endpoint / push.endpoint.revoke). */
export interface PushEndpointRuntime {
  register(deviceId: string, body: JsonObject): Promise<void>;
  revoke(deviceId: string, body: JsonObject): Promise<void>;
}

export interface HostGatewayOptions {
  readonly hostVersion: string;
  readonly piVersion: string;
  readonly features: readonly string[];
  readonly clock: GatewayClock;
  readonly pairing: PairingRuntime;
  readonly authentication: UserAuthenticationRuntime;
  readonly sync: SyncRuntime;
  readonly journal: CommandJournalStore;
  readonly commandAuthorizer: CommandAuthorizer;
  readonly commandPaths: CommandPathRouter;
  readonly blobs: BlobRuntime;
  readonly terminal: TerminalRuntime;
  readonly voice?: VoiceRuntime;
  readonly pushEndpoints?: PushEndpointRuntime;
  readonly passkeySessionTtlMs?: number;
  readonly unknownMessages?: UnknownMessageSink;
  readonly outboundQueueFrames?: number;
  readonly outboundQueueBytes?: number;
  readonly outboundStallMs?: number;
}

export interface GatewayConnection {
  readonly pathGeneration: number;
  phase(): ConnectionPhase;
  closed(): Promise<void>;
  close(code?: string): Promise<void>;
}

export interface HostGateway {
  readonly transportVerification: TransportVerificationPort;
  accept(admission: VerifiedTransportAdmission): GatewayConnection;
  publishToReady(type: string, body: JsonObject): void;
  close(): Promise<void>;
}
