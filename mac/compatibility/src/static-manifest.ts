import type { RpcCommandGroupId, RpcCommandName, RpcEventName, UiMethodName } from "./contracts.js";
import type {
  CapabilitySource,
  ExpectedInvocationActivity,
  InvocationManifestEntry,
  InvocationWatchdog,
  RpcCommandGroup,
  RpcCommandSpec,
  RpcEventSpec,
  UiMethodSpec,
} from "./types.js";

type DocumentedRpcCommandSpec = Omit<RpcCommandSpec, "name"> & { readonly name: RpcCommandName };
type DocumentedRpcCommandGroup = Omit<RpcCommandGroup, "id" | "commands"> & {
  readonly id: RpcCommandGroupId;
  readonly commands: readonly DocumentedRpcCommandSpec[];
};
type DocumentedRpcEventSpec = Omit<RpcEventSpec, "name"> & { readonly name: RpcEventName | "*" };
type DocumentedUiMethodSpec = Omit<UiMethodSpec, "method"> & { readonly method: UiMethodName | "*" };

const TERMINAL_ACTIVITY = {
  rpc: "none",
  terminal: "attached",
} as const satisfies ExpectedInvocationActivity;

const TERMINAL_WATCHDOG = {
  deadlineMs: 15_000,
  expectedActivity: "terminal_attached",
  timeoutAction: "fail_terminal_start",
  retryInvocation: false,
  markSideEffectUnknown: true,
} as const satisfies InvocationWatchdog;

export const CAPABILITY_SOURCES = [
  { id: "pi-runtime", identity: "@earendil-works/pi-coding-agent", kind: "runtime", version: "0.84.0", treatment: "native", declaredCustomUiCallSites: null },
  { id: "pi-mcp-adapter", identity: "pi-mcp-adapter", kind: "package", version: "2.21.1", treatment: "native", declaredCustomUiCallSites: 3 },
  { id: "pi-memory", identity: "pi-memory", kind: "package", version: "0.4.0", treatment: "native", declaredCustomUiCallSites: 0 },
  { id: "pi-web-access", identity: "pi-web-access", kind: "package", version: "0.19.0", treatment: "native", declaredCustomUiCallSites: 0 },
  { id: "rpiv-ask-user-question", identity: "@juicesharp/rpiv-ask-user-question", kind: "package", version: "2.4.0", treatment: "native", declaredCustomUiCallSites: 4 },
  { id: "pi-subagents", identity: "@tintinweb/pi-subagents", kind: "package", version: "0.14.3", treatment: "native", declaredCustomUiCallSites: 6 },
  { id: "pi-plan-mode", identity: "@narumitw/pi-plan-mode", kind: "package", version: "0.49.3", treatment: "native", declaredCustomUiCallSites: 0 },
  { id: "pi-goal", identity: "@narumitw/pi-goal", kind: "package", version: "0.49.7", treatment: "native", declaredCustomUiCallSites: 0 },
  { id: "pi-usage-extension", identity: "@tmustier/pi-usage-extension", kind: "package", version: "0.9.4", treatment: "terminal", declaredCustomUiCallSites: 2 },
  { id: "btw", identity: "btw/", kind: "local-extension", version: null, treatment: "terminal", declaredCustomUiCallSites: 1 },
  { id: "macos-input-notifier", identity: "macos-input-notifier.ts", kind: "local-extension", version: null, treatment: "native-degraded", declaredCustomUiCallSites: 0 },
  { id: "mcp-tool-search", identity: "mcp-tool-search.ts", kind: "local-extension", version: null, treatment: "native", declaredCustomUiCallSites: 0 },
  { id: "self-reload", identity: "self-reload.ts", kind: "local-extension", version: null, treatment: "native-degraded", declaredCustomUiCallSites: 0 },
  { id: "subagent-model-policy", identity: "subagent-model-policy.ts", kind: "local-extension", version: null, treatment: "native", declaredCustomUiCallSites: 0 },
  { id: "llama", identity: "bundled:/llama", kind: "bundled-extension", version: "0.84.0", treatment: "terminal", declaredCustomUiCallSites: null },
] as const satisfies readonly CapabilitySource[];

export const RPC_COMMAND_GROUPS = [
  {
    id: "prompting",
    treatment: "native",
    commands: [
      { name: "prompt", treatment: "native", activity: "agent_request" },
      { name: "steer", treatment: "native", activity: "agent_request" },
      { name: "follow_up", treatment: "native", activity: "agent_request" },
      { name: "abort", treatment: "native", activity: "agent_request" },
      { name: "new_session", treatment: "native", activity: "session_navigation" },
    ],
  },
  {
    id: "state",
    treatment: "native",
    commands: [
      { name: "get_state", treatment: "native", activity: "state_query" },
      { name: "get_messages", treatment: "native", activity: "state_query" },
    ],
  },
  {
    id: "model",
    treatment: "native",
    commands: [
      { name: "set_model", treatment: "native", activity: "journaled_mutation" },
      { name: "cycle_model", treatment: "native", activity: "journaled_mutation" },
      { name: "get_available_models", treatment: "native", activity: "host_enumeration" },
    ],
  },
  {
    id: "thinking",
    treatment: "native",
    commands: [
      { name: "set_thinking_level", treatment: "native", activity: "journaled_mutation" },
      { name: "cycle_thinking_level", treatment: "native", activity: "journaled_mutation" },
      { name: "get_available_thinking_levels", treatment: "native", activity: "host_enumeration" },
    ],
  },
  {
    id: "queue-modes",
    treatment: "native",
    commands: [
      { name: "set_steering_mode", treatment: "native", activity: "journaled_mutation" },
      { name: "set_follow_up_mode", treatment: "native", activity: "journaled_mutation" },
    ],
  },
  {
    id: "compaction",
    treatment: "native",
    commands: [
      { name: "compact", treatment: "native", activity: "in_progress" },
      { name: "set_auto_compaction", treatment: "native", activity: "journaled_mutation" },
    ],
  },
  {
    id: "retry",
    treatment: "native",
    commands: [
      { name: "set_auto_retry", treatment: "native", activity: "journaled_mutation" },
      { name: "abort_retry", treatment: "native", activity: "agent_request" },
    ],
  },
  {
    id: "bash",
    treatment: "native-degraded",
    commands: [
      { name: "bash", treatment: "native-degraded", activity: "bash_execution" },
      { name: "abort_bash", treatment: "native-degraded", activity: "bash_execution" },
    ],
  },
  {
    id: "session",
    treatment: "native",
    commands: [
      { name: "get_session_stats", treatment: "native", activity: "state_query" },
      { name: "export_html", treatment: "native", activity: "share_action" },
      { name: "switch_session", treatment: "native", activity: "session_navigation" },
      { name: "fork", treatment: "native", activity: "session_navigation" },
      { name: "clone", treatment: "native", activity: "session_navigation" },
      { name: "get_fork_messages", treatment: "native", activity: "state_query" },
      { name: "get_entries", treatment: "native", activity: "state_query" },
      { name: "get_tree", treatment: "native", activity: "state_query" },
      { name: "get_last_assistant_text", treatment: "native", activity: "state_query" },
      { name: "set_session_name", treatment: "native", activity: "session_mutation" },
    ],
  },
  {
    id: "commands",
    treatment: "native",
    commands: [{ name: "get_commands", treatment: "native", activity: "command_discovery" }],
  },
] as const satisfies readonly DocumentedRpcCommandGroup[];

export const RPC_EVENTS = [
  { name: "agent_start", treatment: "native", activity: "work_started", isCatchAll: false },
  { name: "agent_end", treatment: "native-degraded", activity: "not_completion", isCatchAll: false },
  { name: "agent_settled", treatment: "native", activity: "settled", isCatchAll: false },
  { name: "turn_start", treatment: "native", activity: "turn_boundary", isCatchAll: false },
  { name: "turn_end", treatment: "native", activity: "turn_boundary", isCatchAll: false },
  { name: "message_start", treatment: "native", activity: "message_delta", isCatchAll: false },
  { name: "message_update", treatment: "native", activity: "message_delta", isCatchAll: false },
  { name: "message_end", treatment: "native", activity: "message_delta", isCatchAll: false },
  { name: "bash_execution_update", treatment: "native-degraded", activity: "bash_delta", isCatchAll: false },
  { name: "tool_execution_start", treatment: "native", activity: "tool_lifecycle", isCatchAll: false },
  { name: "tool_execution_update", treatment: "native", activity: "tool_lifecycle", isCatchAll: false },
  { name: "tool_execution_end", treatment: "native", activity: "tool_lifecycle", isCatchAll: false },
  { name: "queue_update", treatment: "native", activity: "queue_state", isCatchAll: false },
  { name: "compaction_start", treatment: "native", activity: "compaction_state", isCatchAll: false },
  { name: "compaction_end", treatment: "native", activity: "compaction_state", isCatchAll: false },
  { name: "auto_retry_start", treatment: "native", activity: "retry_state", isCatchAll: false },
  { name: "auto_retry_end", treatment: "native", activity: "retry_state", isCatchAll: false },
  { name: "summarization_retry_scheduled", treatment: "native-degraded", activity: "summarization_state", isCatchAll: false },
  { name: "summarization_retry_attempt_start", treatment: "native-degraded", activity: "summarization_state", isCatchAll: false },
  { name: "summarization_retry_finished", treatment: "native-degraded", activity: "summarization_state", isCatchAll: false },
  { name: "extension_error", treatment: "native", activity: "extension_failure", isCatchAll: false },
  { name: "*", treatment: "retained", activity: "retained_raw", isCatchAll: true },
] as const satisfies readonly DocumentedRpcEventSpec[];

export const UI_METHODS = [
  { method: "select", wireMethod: "select", category: "dialog", treatment: "native", rpcBehavior: "response", isCatchAll: false },
  { method: "confirm", wireMethod: "confirm", category: "dialog", treatment: "native", rpcBehavior: "response", isCatchAll: false },
  { method: "input", wireMethod: "input", category: "dialog", treatment: "native", rpcBehavior: "response", isCatchAll: false },
  { method: "editor", wireMethod: "editor", category: "dialog", treatment: "native", rpcBehavior: "response", isCatchAll: false },
  { method: "notify", wireMethod: "notify", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit", isCatchAll: false },
  { method: "setStatus", wireMethod: "setStatus", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit", isCatchAll: false },
  { method: "setWidget", wireMethod: "setWidget", category: "fire-and-forget", treatment: "native-degraded", rpcBehavior: "emit", isCatchAll: false },
  { method: "setTitle", wireMethod: "setTitle", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit", isCatchAll: false },
  { method: "setEditorText", wireMethod: "set_editor_text", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit", isCatchAll: false },
  { method: "custom", wireMethod: null, category: "tui-only", treatment: "terminal", rpcBehavior: "no-op", isCatchAll: false },
  { method: "onTerminalInput", wireMethod: null, category: "tui-only", treatment: "terminal", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setWorkingMessage", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setWorkingVisible", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setWorkingIndicator", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setHiddenThinkingLabel", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setFooter", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setHeader", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setEditorComponent", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op", isCatchAll: false },
  { method: "setToolsExpanded", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op", isCatchAll: false },
  { method: "addAutocompleteProvider", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op", isCatchAll: false },
  { method: "getEditorText", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor", isCatchAll: false },
  { method: "getEditorComponent", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor", isCatchAll: false },
  { method: "getToolsExpanded", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor", isCatchAll: false },
  { method: "getAllThemes", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor", isCatchAll: false },
  { method: "getTheme", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor", isCatchAll: false },
  { method: "theme", wireMethod: null, category: "degraded", treatment: "omitted", rpcBehavior: "fallback", isCatchAll: false },
  { method: "setTheme", wireMethod: null, category: "degraded", treatment: "omitted", rpcBehavior: "fallback", isCatchAll: false },
  { method: "pasteToEditor", wireMethod: "set_editor_text", category: "degraded", treatment: "native-degraded", rpcBehavior: "fallback", isCatchAll: false },
  { method: "*", wireMethod: null, category: "degraded", treatment: "retained", rpcBehavior: "fallback", isCatchAll: true },
] as const satisfies readonly DocumentedUiMethodSpec[];

export const INVOCATIONS = [
  {
    id: "pi-mcp-adapter:/mcp",
    path: "/mcp",
    argumentShape: { kind: "any" },
    requiresTerminal: true,
    treatment: "terminal",
    sideEffectClass: "unknown",
    expectedActivity: TERMINAL_ACTIVITY,
    watchdog: TERMINAL_WATCHDOG,
    sourceIds: ["pi-mcp-adapter"],
  },
  {
    id: "pi-usage-extension:/usage",
    path: "/usage",
    argumentShape: { kind: "any" },
    requiresTerminal: true,
    treatment: "terminal",
    sideEffectClass: "read_only",
    expectedActivity: TERMINAL_ACTIVITY,
    watchdog: TERMINAL_WATCHDOG,
    sourceIds: ["pi-usage-extension"],
  },
  {
    id: "pi-subagents:/agents",
    path: "/agents",
    argumentShape: { kind: "any" },
    requiresTerminal: true,
    treatment: "terminal",
    sideEffectClass: "mutation",
    expectedActivity: TERMINAL_ACTIVITY,
    watchdog: TERMINAL_WATCHDOG,
    sourceIds: ["pi-subagents"],
  },
  {
    id: "btw:/btw",
    path: "/btw",
    argumentShape: { kind: "any" },
    requiresTerminal: true,
    treatment: "terminal",
    sideEffectClass: "mutation",
    expectedActivity: TERMINAL_ACTIVITY,
    watchdog: TERMINAL_WATCHDOG,
    sourceIds: ["btw"],
  },
  {
    id: "llama:/llama",
    path: "/llama",
    argumentShape: { kind: "any" },
    requiresTerminal: true,
    treatment: "terminal",
    sideEffectClass: "external_mutation",
    expectedActivity: TERMINAL_ACTIVITY,
    watchdog: TERMINAL_WATCHDOG,
    sourceIds: ["llama"],
  },
] as const satisfies readonly InvocationManifestEntry[];
