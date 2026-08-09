# Capability matrix

Last updated: 2026-08-09
Pi inspected: `@earendil-works/pi-coding-agent` 0.84.0 at `/opt/homebrew/lib/node_modules`
Application ID: `io.github.verybigsad.pimobile`

This is the contract for R2 and R3. Every Pi RPC surface, every installed package, and every local extension has a defined treatment. A surface with no row is a requirement failure, enforced by `CapabilityCoverageTest`.

The counts and versions below are what this machine has today. Stage 0 generates this manifest mechanically with version and integrity hashes, and the harness must then **discover the live set** rather than trust hard-coded numbers, because a Pi or extension upgrade invalidates the compatibility claim until the suites pass again.

Treatment vocabulary:

- **native** — a first-class semantic mobile surface.
- **native-degraded** — usable on the phone with a stated loss of fidelity.
- **terminal** — routed to terminal mode, which is a mandatory release feature, not a stub. Terminal mode is a real PTY: bundled xterm in a hardened WebView, `node-pty` on the Mac, a private tmux server, and split input.
- **retained** — not specially rendered, but preserved verbatim and visible in the raw inspector.
- **omitted** — deliberately not exposed, with the reason stated.

## Commands

| Command group | Commands | Treatment | Semantic notes |
|---|---|---|---|
| Prompting | `prompt`, `steer`, `follow_up`, `abort`, `new_session` | native | During streaming, `prompt` must carry `streamingBehavior`; steering and follow-up are separate actions because they land at different points. `abort` stays reachable whenever the agent runs. All are journaled mutations. |
| State | `get_state`, `get_messages` | native | Attach/runtime adjuncts tagged to snapshot cursor; never transcript truth. Canonical recovery comes from one idle `get_entries` response. |
| Model | `set_model`, `cycle_model`, `get_available_models` | native | Enumerated from the host, never hardcoded; the current model is always visible. |
| Thinking | `set_thinking_level`, `cycle_thinking_level`, `get_available_thinking_levels` | native | Levels come from the host. |
| Queue modes | `set_steering_mode`, `set_follow_up_mode` | native | Journaled even though observably idempotent, keeping one uniform mutation rule. |
| Compaction | `compact`, `set_auto_compaction` | native | Compaction is an in-progress state, never completion. |
| Retry | `set_auto_retry`, `abort_retry` | native | Auto-retry is a still-working state; `abort_retry` is offered during retry. |
| Bash | `bash`, `abort_bash` | native-degraded | Output is coalesced with ANSI converted by an allowlisted parser; full fidelity lives in terminal mode. `bash_execution_update` echoes the originating command id, which is a correlation id only. |
| Session | `get_session_stats`, `export_html`, `switch_session`, `fork`, `clone`, `get_fork_messages`, `get_entries`, `get_tree`, `get_last_assistant_text`, `set_session_name` | native | `get_entries` uses final append-order ID as `since` while checking `leafId` independently; never use branch leaf as append cursor; `get_tree` drives branch navigation; `export_html` is a share action. |
| Commands | `get_commands` | native | The palette comes from the live result, grouped/searchable by `source`/`location`, then joins the generated invocation manifest. The observed count is not a fixed expectation. |
| Known custom invocations | `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, plus generated paths | terminal | `requiresTerminal` is invocation-level. Route before sending to RPC; there is no detectable `custom()` event to rescue a command afterward. |
| Built-in TUI commands (`/settings`, `/hotkeys`) | absent from `get_commands` | omitted | Excluded from RPC and would not execute as prompts. Native settings/key surfaces replace them. |
| Bundled Pi `/llama` extension | inline capability class | terminal | Pi 0.84's bundled provider command explicitly requires TUI and can load/unload/download models. Manifest marks `requiresTerminal=true`, side effect `external_mutation`; never send it to semantic RPC and wait for its warning. |

## Events

| Event | Treatment | Semantic notes |
|---|---|---|
| `agent_start` | native | Starts the working indicator and elapsed timer. |
| `agent_end` | native-degraded | **Never completion.** `willRetry` keeps the working state; no wake is published here. |
| `agent_settled` | native | The only completion signal; the only trigger for durable completion state and a push wake. |
| `turn_start`, `turn_end` | native | Turn grouping. |
| `message_start`, `message_update`, `message_end` | native | Delta-only by `contentIndex`; text/thinking end replace content and toolcall end replaces the tool. RPC strips upstream partial metadata, so only authoritative `message_end.message` supplies signatures/redaction. |
| `bash_execution_update` | native-degraded | Coalesced at frame cadence with no content loss; ANSI sanitized for semantic display. |
| `tool_execution_start`, `tool_execution_update`, `tool_execution_end` | native | Correlated by `toolCallId`, never by position. |
| `queue_update` | native | Shows pending steer and follow-up entries. |
| `compaction_start`, `compaction_end` | native | In-progress state. |
| `auto_retry_start`, `auto_retry_end` | native | Rendered as retrying, not failure. |
| `summarization_retry_scheduled`, `summarization_retry_attempt_start`, `summarization_retry_finished` | native-degraded | Compact status line rather than individual rows. |
| `extension_error` | native | Shown with extension name and copyable detail. Review found two local extensions emitting stale-context errors at settlement, so these must never be presented as task failure. |
| Any unknown event | retained | Preserved verbatim, inline up to 128 KiB or by digest reference above that, and visible in the raw inspector. Never executed. |

## Extension UI sub-protocol

| Method | Category | Treatment | Notes |
|---|---|---|---|
| `select` | dialog | native | Chooser sheet; the response is a journaled command bound to the Pi request id. |
| `confirm` | dialog | native | Ordinary extension confirmation only. Security approval never uses this path. |
| `input` | dialog | native | Single-line input. |
| `editor` | dialog | native | Multi-line monospace-capable editor sheet. |
| `notify` | fire-and-forget | native | Transient chip; `notifyType` maps to info, warning, error styling. |
| `setStatus` | fire-and-forget | native | Status line. Review observed raw ANSI arriving here, so an allowlisted sanitizer runs before display. |
| `setWidget` | fire-and-forget | native-degraded | `widgetLines` render as a strip honoring `aboveEditor` and `belowEditor`. Component factories are ignored in RPC, so callback widgets are unavailable and their absence is stated rather than faked. |
| `setTitle` | fire-and-forget | native | Session title. |
| `setEditorText` (wire `set_editor_text`) | fire-and-forget | native | Replaces composer text; user can edit before sending. |
| `custom()` | TUI-only | terminal | Returns `undefined` and emits no RPC signal. Manifest-known invocations pre-route; an unexpected-command watchdog restarts/resyncs without claiming custom detection. |
| `onTerminalInput` | TUI-only | terminal | RPC returns a no-op unsubscribe; raw input behavior exists only in terminal mode. |
| `setWorkingMessage`, `setWorkingVisible`, `setWorkingIndicator`, `setHiddenThinkingLabel` | TUI-only | native-degraded | No-ops in RPC; native working/thinking presentation does not claim extension-specific fidelity. |
| `setFooter`, `setHeader`, `setEditorComponent`, `setToolsExpanded`, `addAutocompleteProvider` | TUI-only | omitted | No-ops in RPC; common native equivalents exist, custom components/providers require terminal. |
| `getEditorText`, `getEditorComponent`, `getToolsExpanded`, `getAllThemes`, `getTheme` | TUI-only | omitted | Return `""`, `undefined`, `false`, `[]`, and `undefined` in RPC. |
| readonly `theme`, `setTheme` | degraded in RPC | omitted | A fallback theme object exists for extension code; switching returns an error. Phone theming is independent. |
| `pasteToEditor` | degraded in RPC | native-degraded | Delegates to `setEditorText`; no paste/collapse handling. |
| Any future RPC-emitted method | unknown | retained | Generic sanitized sheet. TUI-only calls that emit nothing cannot be detected this way. |

## Approval

Pi has no sandbox or generic approval command. Project-pinned Pi 0.84 receives a minimal integrity-checked patch; a `NODE_OPTIONS` preload registers a frozen Unix-socket policy client globally. The patched core calls it after tool handlers and in resolved `AgentSession.executeBash`, including nested AgentSessions and `extensions:false`. This covers normal direct RPC/interactive/programmatic bash once; host bridge-owned actions gate separately.

| Aspect | Mechanism |
|---|---|
| Interception | Tool hook sees handler-mutated args; resolved `executeBash` covers direct RPC/interactive/programmatic once; nested sessions; host gates bridge-owned actions |
| Scope | Versioned destructive/unclassifiable operations. Invocation manifest separately classifies slash-command side effects |
| Binding | Final normalized operation, cwd/resource, reasons, `toolCallId` or host `commandId`, hash, policy version, expiry |
| Wire UI | `approval.offer`, `approval.decision`, `approval.expired`; never Pi `confirm` |
| Response | `allow_once` or `deny`; no persistent allow |
| Concurrency/deadline | One globally active offer; FIFO eight; 30 s queue wait; up to 120 s visible decision; monotonic 150 s cap from hook invocation |
| Failure mode | Queue overflow/timeout, decision expiry, broker unavailable, disconnect, stale offer, changed args, or classifier error returns block/deny and resumes turn |
| Audit | Decision, bound tuple, device, policy version, timestamp |
| Honesty | Guardrail, not sandbox. Extension Node/fs/process side effects can bypass tool hooks; tests and UI admit it |

## Installed packages

All 8 packages from `~/.pi/agent/settings.json`, with real Pi 0.84 source **call-site** counts. Counts are audit evidence, while invocation-level paths—not package-wide guesses—drive routing.

| Package | Version | Role | `ui.custom` call sites | Treatment | Scenarios required |
|---|---|---|---|---|---|
| `pi-mcp-adapter` | 2.21.1 | MCP adapter: gateways/tools/OAuth/elicitation plus `/mcp` panels | 3 | native tools/dialogs; terminal manifest paths | Semantic tool/OAuth/elicitation. PTY `/mcp` and every generated custom path; assert pre-route before RPC |
| `pi-memory` | 0.4.0 | Memory tools `memory_read`, `memory_write`, `memory_search`, `memory_forget`, `memory_restore`, `memory_status`, `scratchpad` | 0 | native | Semantic: write then search an entry; assert results render and no memory content reaches logs. PTY: same commands under terminal mode |
| `pi-web-access` | 0.19.0 | Web search, fetch, GitHub clone, PDF extraction, video analysis; `/websearch` | 0 | native | Semantic: search and fetch; assert coalescing, truncation with expand, and digest download for oversized results. PTY: same |
| `@juicesharp/rpiv-ask-user-question` | 2.4.0 | Structured questionnaire tool | 4 | native question flow; terminal for its custom renderer | Semantic: multi-question, multi-select questionnaire answered by id, which review confirmed has a working structured fallback. PTY: custom renderer path |
| `@tintinweb/pi-subagents` | 0.14.3 | Sub-agents, `/agents`, background agents, agent browser | 6 | native lifecycle/results; terminal `/agents` fleet/conversation paths | Semantic nested agent with policy preload, including `extensions:false`; PTY `/agents`; assert pre-route and parent settlement |
| `@narumitw/pi-plan-mode` | 0.49.3 | Read-only `/plan` mode; heavy `notify`, `setWidget`, `setStatus`, plus `select` and `editor`; plan menus | 0 | native | Semantic: enter plan mode, answer a plan question, complete a plan; assert widget strip, status line, and chips render. Review confirmed its line widgets work. PTY: menu screens |
| `@narumitw/pi-goal` | 0.49.7 | `/goal` autonomy with `goal_complete` and `goal_blocked`; heavy `setStatus`, `confirm`, `editor` | 0 | native | Semantic: start a goal, answer a confirm, complete it; assert the status line tracks goal state. PTY: same |
| `@tmustier/pi-usage-extension` | 0.9.4 | `/usage` dashboard | 2 | terminal | `/usage` pre-routes before RPC; PTY dashboard renders. A separate host-derived native usage summary is not represented as extension UI parity |

## Local extensions

All 5 entries in `~/.pi/agent/extensions/`. Review found these were missing from the original package-only census; they are as load-bearing as the packages.

| Extension | Kind | Role | `ui.custom` | Treatment | Scenarios required |
|---|---|---|---|---|---|
| `btw/` | directory extension | `/btw` side-question overlay with history/copy/fork | 1 call site | terminal | `/btw` pre-routes before RPC; PTY overlay, scroll, copy, fork |
| `macos-input-notifier.ts` | single file | Posts a macOS notification when Pi needs input; listens for the ask-user blocked event and self-reload status | 0 | native, complementary | Trigger a blocking dialog; assert the phone notification and the Mac notification are consistent and only one wake is published per settlement |
| `mcp-tool-search.ts` | single file | Registers `search_tools`; scopes MCP tool activation and path tools | 0 | native | Call `search_tools`; assert results render and scoping is respected |
| `self-reload.ts` | single file | Registers `reload_runtime`; reloads Pi extensions and configuration and persists a reload record | 0 | native-degraded | Call `reload_runtime`; assert a visible reload state, tolerance of the transport interruption, epoch handling, and correct resync afterwards |
| `subagent-model-policy.ts` | single file | Enforces model aliases and an allow-list for sub-agent models | 0 | native, invisible | Launch a sub-agent with a disallowed model; assert a readable policy error rather than a silent failure |

## Extension classes

New extensions inherit a class treatment without a new design round.

| Class | Detection | Treatment |
|---|---|---|
| Tool-registering | `registerTool` | native; calls, arguments, and results render in the timeline |
| Dialog-driven | `select`, `confirm`, `input`, `editor` | native; responses are journaled and bound to the Pi request id |
| Ambient-status | `notify`, `setStatus`, `setWidget`, `setTitle` | native; chips, status line, widget strip, with ANSI sanitized |
| Callback-widget | component factory passed to `setWidget` | native-degraded; unavailable in RPC and stated as such, never simulated |
| Composer-writing | `set_editor_text`, `pasteToEditor` | native-degraded; text lands in the composer without paste or collapse semantics |
| Custom-TUI invocation | generated call graph/command path reaches `ui.custom` | `requiresTerminal=true`; pre-route before RPC because `custom()` emits nothing |
| Slash-command side effect | generated `read_only` / `mutation` / `destructive` / `unknown` class | native if safe; destructive/unknown gates at invocation or routes terminal |
| Lifecycle-hooking | tool/user-bash handlers, `project_trust`, `session_shutdown` | invisible; ordinary handlers may mutate input, then the patched final policy hook runs |
| Host-side-effect | OS notification, direct fs/process, reload, Mac GUI | native-degraded only. These are not sandboxed by tool policy; interruption is tolerated and GUI is not reproduced |

## Invocation routing contract

The generated manifest is keyed by exact command path plus argument shape where needed and records source integrity, `requiresTerminal`, side-effect class, expected RPC activity, and watchdog deadline. Semantic dispatch parses only a recognized leading invocation; ordinary prompt text containing `/mcp` is not rerouted. Every known custom path—including nested setup/browser paths—is tested. If manifest drift lets an extension command hang in RPC, the watchdog terminates that Pi generation, never retries the invocation, restarts, and performs canonical resync. UI says "extension command compatibility timed out" and marks any direct extension side effect unknown. It cannot say "custom UI detected" because no such RPC event exists.

## Terminal mode

Terminal mode is mandatory because RPC cannot render or signal `ctx.ui.custom()`. Real source call-site counts are: `@tintinweb/pi-subagents` **6**, `@juicesharp/rpiv-ask-user-question` **4**, `pi-mcp-adapter` **3**, `@tmustier/pi-usage-extension` **2**, `btw/` **1**. The bundled `/llama` command is a separate inline TUI capability class. Stage 0 regenerates both counts and invocation paths.

| Aspect | Decision | Basis |
|---|---|---|
| Renderer | Reproducible Chromium-91-targeted `@xterm/xterm@6.1.0-beta.292`, full npm/packed/bundle hashes, narrow `structuredClone` shim, local `WebViewAssetLoader`, no CDN | API 29 AVD WebView 91 lacks only clone among required paths; API 29/34/36 runtime canary and source locator gate the beta/shim |
| PTY | Node 22 with `node-pty@1.2.0-beta.15` owning a display client to a private tmux server | Executable-helper packaging had to be corrected during the spike, so packaging smoke is a gate |
| Split input | Output, text, paste, mouse, replies, and resize go through the display PTY; exact Kitty and CSI-u press, repeat, and release bytes are injected into the pane by a persistent tmux control client using serialized `send-keys -H` | tmux consumes Pi's Kitty negotiation and mangles release sequences on a naive path; the spike proved a real key-release custom UI works through the split path |
| Reconnect/history | Connected xterm keeps 5,000 lines. Fresh generation restores visible pane only; a separate bounded read-only `capture-pane` drawer supplies server history; stale input is discarded | Never claim full scrollback restoration or feed capture history into xterm |
| Isolation | Strict CSP; no network/file/content/mixed access; exact-origin narrow channel; bounded rates; Safe Browsing; release debugging off; too-old-engine refusal; renderer recovery | A WebView terminal is a high-risk parser and bridge |
| Images | Native read-only image and artifact cards | tmux does not reliably carry Kitty images; no parity is claimed |
| Concurrency | One writer lease per session; mode handoff requires settled or aborted state and proven exit of the previous process | Two Pi writers on one session would corrupt truth |
| Remaining gap | Gboard and other IME composition, Bluetooth modifiers, repeat and release, touch selection, injector load, renderer kill, and clipboard need physical-device evidence. Contingency is a native `InputConnection` or Compose text bridge, never a Termux fork | Recorded as an external release gate |
