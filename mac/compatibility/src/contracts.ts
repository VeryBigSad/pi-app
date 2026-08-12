import type { CapabilityTreatment, PinnedRuntimeIdentity, UiMethodCategory, UiRpcBehavior } from "./types.js";

interface DocumentedCommandGroup {
  readonly id: string;
  readonly treatment: CapabilityTreatment;
  readonly commands: readonly string[];
}

interface DocumentedEvent {
  readonly name: string;
  readonly treatment: CapabilityTreatment;
}

interface DocumentedUiMethod {
  readonly method: string;
  readonly wireMethod: string | null;
  readonly category: UiMethodCategory;
  readonly treatment: CapabilityTreatment;
  readonly rpcBehavior: UiRpcBehavior;
}

export const MANIFEST_VERSION = 1;

export const PINNED_PI_RUNTIME = {
  packageName: "@earendil-works/pi-coding-agent",
  version: "0.84.0",
} as const satisfies PinnedRuntimeIdentity;

export const DOCUMENTED_RPC_COMMAND_GROUPS = [
  {
    id: "prompting",
    treatment: "native",
    commands: ["prompt", "steer", "follow_up", "abort", "new_session"],
  },
  {
    id: "state",
    treatment: "native",
    commands: ["get_state", "get_messages"],
  },
  {
    id: "model",
    treatment: "native",
    commands: ["set_model", "cycle_model", "get_available_models"],
  },
  {
    id: "thinking",
    treatment: "native",
    commands: ["set_thinking_level", "cycle_thinking_level", "get_available_thinking_levels"],
  },
  {
    id: "queue-modes",
    treatment: "native",
    commands: ["set_steering_mode", "set_follow_up_mode"],
  },
  {
    id: "compaction",
    treatment: "native",
    commands: ["compact", "set_auto_compaction"],
  },
  {
    id: "retry",
    treatment: "native",
    commands: ["set_auto_retry", "abort_retry"],
  },
  {
    id: "bash",
    treatment: "native-degraded",
    commands: ["bash", "abort_bash"],
  },
  {
    id: "session",
    treatment: "native",
    commands: [
      "get_session_stats",
      "export_html",
      "switch_session",
      "fork",
      "clone",
      "get_fork_messages",
      "get_entries",
      "get_tree",
      "get_last_assistant_text",
      "set_session_name",
    ],
  },
  {
    id: "commands",
    treatment: "native",
    commands: ["get_commands"],
  },
] as const satisfies readonly DocumentedCommandGroup[];

export type RpcCommandGroupId = (typeof DOCUMENTED_RPC_COMMAND_GROUPS)[number]["id"];
export type RpcCommandName = (typeof DOCUMENTED_RPC_COMMAND_GROUPS)[number]["commands"][number];

export const DOCUMENTED_RPC_EVENTS = [
  { name: "agent_start", treatment: "native" },
  { name: "agent_end", treatment: "native-degraded" },
  { name: "agent_settled", treatment: "native" },
  { name: "turn_start", treatment: "native" },
  { name: "turn_end", treatment: "native" },
  { name: "message_start", treatment: "native" },
  { name: "message_update", treatment: "native" },
  { name: "message_end", treatment: "native" },
  { name: "bash_execution_update", treatment: "native-degraded" },
  { name: "tool_execution_start", treatment: "native" },
  { name: "tool_execution_update", treatment: "native" },
  { name: "tool_execution_end", treatment: "native" },
  { name: "queue_update", treatment: "native" },
  { name: "compaction_start", treatment: "native" },
  { name: "compaction_end", treatment: "native" },
  { name: "auto_retry_start", treatment: "native" },
  { name: "auto_retry_end", treatment: "native" },
  { name: "summarization_retry_scheduled", treatment: "native-degraded" },
  { name: "summarization_retry_attempt_start", treatment: "native-degraded" },
  { name: "summarization_retry_finished", treatment: "native-degraded" },
  { name: "extension_error", treatment: "native" },
] as const satisfies readonly DocumentedEvent[];

export type RpcEventName = (typeof DOCUMENTED_RPC_EVENTS)[number]["name"];

export const DOCUMENTED_UI_METHODS = [
  { method: "select", wireMethod: "select", category: "dialog", treatment: "native", rpcBehavior: "response" },
  { method: "confirm", wireMethod: "confirm", category: "dialog", treatment: "native", rpcBehavior: "response" },
  { method: "input", wireMethod: "input", category: "dialog", treatment: "native", rpcBehavior: "response" },
  { method: "editor", wireMethod: "editor", category: "dialog", treatment: "native", rpcBehavior: "response" },
  { method: "notify", wireMethod: "notify", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit" },
  { method: "setStatus", wireMethod: "setStatus", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit" },
  { method: "setWidget", wireMethod: "setWidget", category: "fire-and-forget", treatment: "native-degraded", rpcBehavior: "emit" },
  { method: "setTitle", wireMethod: "setTitle", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit" },
  { method: "setEditorText", wireMethod: "set_editor_text", category: "fire-and-forget", treatment: "native", rpcBehavior: "emit" },
  { method: "custom", wireMethod: null, category: "tui-only", treatment: "terminal", rpcBehavior: "no-op" },
  { method: "onTerminalInput", wireMethod: null, category: "tui-only", treatment: "terminal", rpcBehavior: "no-op" },
  { method: "setWorkingMessage", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op" },
  { method: "setWorkingVisible", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op" },
  { method: "setWorkingIndicator", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op" },
  { method: "setHiddenThinkingLabel", wireMethod: null, category: "tui-only", treatment: "native-degraded", rpcBehavior: "no-op" },
  { method: "setFooter", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op" },
  { method: "setHeader", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op" },
  { method: "setEditorComponent", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op" },
  { method: "setToolsExpanded", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op" },
  { method: "addAutocompleteProvider", wireMethod: null, category: "tui-only", treatment: "omitted", rpcBehavior: "no-op" },
  { method: "getEditorText", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor" },
  { method: "getEditorComponent", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor" },
  { method: "getToolsExpanded", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor" },
  { method: "getAllThemes", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor" },
  { method: "getTheme", wireMethod: null, category: "accessor", treatment: "omitted", rpcBehavior: "accessor" },
  { method: "theme", wireMethod: null, category: "degraded", treatment: "omitted", rpcBehavior: "fallback" },
  { method: "setTheme", wireMethod: null, category: "degraded", treatment: "omitted", rpcBehavior: "fallback" },
  { method: "pasteToEditor", wireMethod: "set_editor_text", category: "degraded", treatment: "native-degraded", rpcBehavior: "fallback" },
] as const satisfies readonly DocumentedUiMethod[];

export type UiMethodName = (typeof DOCUMENTED_UI_METHODS)[number]["method"];

export const REQUIRED_TERMINAL_PATHS = ["/mcp", "/usage", "/agents", "/btw", "/llama"] as const;
