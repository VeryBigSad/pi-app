export type CapabilityTreatment = "native" | "native-degraded" | "terminal" | "retained" | "omitted";

export type SideEffectClass = "read_only" | "mutation" | "destructive" | "unknown" | "external_mutation";

export type RpcCommandActivity =
  | "agent_request"
  | "state_query"
  | "host_enumeration"
  | "journaled_mutation"
  | "in_progress"
  | "bash_execution"
  | "session_navigation"
  | "session_mutation"
  | "share_action"
  | "command_discovery";

export type EventActivity =
  | "work_started"
  | "not_completion"
  | "settled"
  | "turn_boundary"
  | "message_delta"
  | "bash_delta"
  | "tool_lifecycle"
  | "queue_state"
  | "compaction_state"
  | "retry_state"
  | "summarization_state"
  | "extension_failure"
  | "retained_raw";

export type UiMethodCategory = "dialog" | "fire-and-forget" | "tui-only" | "accessor" | "degraded";

export type UiRpcBehavior = "response" | "emit" | "no-op" | "fallback" | "accessor";

export interface RpcCommandSpec {
  readonly name: string;
  readonly treatment: CapabilityTreatment;
  readonly activity: RpcCommandActivity;
}

export interface RpcCommandGroup {
  readonly id: string;
  readonly treatment: CapabilityTreatment;
  readonly commands: readonly RpcCommandSpec[];
}

export interface RpcEventSpec {
  readonly name: string;
  readonly treatment: CapabilityTreatment;
  readonly activity: EventActivity;
  readonly isCatchAll: boolean;
}

export interface UiMethodSpec {
  readonly method: string;
  readonly wireMethod: string | null;
  readonly category: UiMethodCategory;
  readonly treatment: CapabilityTreatment;
  readonly rpcBehavior: UiRpcBehavior;
  readonly isCatchAll: boolean;
}

export interface PinnedRuntimeIdentity {
  readonly packageName: string;
  readonly version: string;
}

export interface SourceIntegrityInput {
  readonly sourceId: string;
  readonly sha256: string;
  readonly version?: string;
}

export interface CapabilitySource {
  readonly id: string;
  readonly identity: string;
  readonly kind: "runtime" | "package" | "local-extension" | "bundled-extension";
  readonly version: string | null;
  readonly treatment: CapabilityTreatment;
  readonly declaredCustomUiCallSites: number | null;
}

export type InvocationArgumentShape =
  | { readonly kind: "any" }
  | { readonly kind: "empty" }
  | { readonly kind: "first-token"; readonly values: readonly string[] };

export interface ExpectedInvocationActivity {
  readonly rpc: "none" | "prompt_response_then_settled";
  readonly terminal: "none" | "attached";
}

export interface InvocationWatchdog {
  readonly deadlineMs: number;
  readonly expectedActivity: "terminal_attached" | "rpc_response_then_settled";
  readonly timeoutAction: "fail_terminal_start" | "restart_and_resync";
  readonly retryInvocation: false;
  readonly markSideEffectUnknown: boolean;
}

export interface InvocationManifestEntry {
  readonly id: string;
  readonly path: string;
  readonly argumentShape: InvocationArgumentShape;
  readonly requiresTerminal: boolean;
  readonly treatment: CapabilityTreatment;
  readonly sideEffectClass: SideEffectClass;
  readonly expectedActivity: ExpectedInvocationActivity;
  readonly watchdog: InvocationWatchdog;
  readonly sourceIds: readonly string[];
}

export interface ManifestIntegrity {
  readonly algorithm: "sha256";
  readonly digest: string;
  readonly sourceInputs: readonly SourceIntegrityInput[];
}

export interface CompatibilityManifest {
  readonly manifestVersion: number;
  readonly runtime: PinnedRuntimeIdentity;
  readonly sources: readonly CapabilitySource[];
  readonly rpcCommandGroups: readonly RpcCommandGroup[];
  readonly events: readonly RpcEventSpec[];
  readonly uiMethods: readonly UiMethodSpec[];
  readonly invocations: readonly InvocationManifestEntry[];
  readonly integrity: ManifestIntegrity;
}

export interface ManifestBuildInput {
  readonly runtime: PinnedRuntimeIdentity;
  readonly sourceIntegrityInputs: readonly SourceIntegrityInput[];
}

export interface ParsedLeadingInvocation {
  readonly path: string;
  readonly argumentText: string;
}

export interface TerminalInvocationRoute {
  readonly target: "terminal";
  readonly input: string;
  readonly path: string;
  readonly argumentText: string;
  readonly invocation: InvocationManifestEntry;
  readonly mustNotSendToRpc: true;
}

export interface SemanticInvocationRoute {
  readonly target: "semantic";
  readonly input: string;
  readonly leadingInvocation: ParsedLeadingInvocation | null;
  readonly invocation: InvocationManifestEntry | null;
  readonly sideEffectClass: SideEffectClass;
  readonly expectedActivity: ExpectedInvocationActivity;
  readonly watchdog: InvocationWatchdog | null;
}

export type InvocationRoute = TerminalInvocationRoute | SemanticInvocationRoute;
