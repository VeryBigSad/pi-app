# Research: Pi integration surface

Last updated: 2026-08-09
Pi version inspected: `@earendil-works/pi-coding-agent` 0.84.0 (`/opt/homebrew/lib/node_modules/@earendil-works/pi-coding-agent`)

## Decisive constraint

**Arbitrary Pi custom TUI cannot be reproduced remotely without PTY compatibility.** Pi's extension UI has two disjoint tiers: a structured request/response tier that survives headless operation, and a terminal-rendering tier that requires a real terminal. Any extension that draws its own component through `ctx.ui.custom()` is unavailable to a non-terminal client unless that client hosts a PTY and interprets ANSI/Kitty-protocol output.

Verified from `docs/rpc.md:1155-1165`:

- `custom()` returns `undefined` in RPC mode.
- `setWorkingMessage()`, `setWorkingIndicator()`, `setFooter()`, `setHeader()`, `setEditorComponent()`, `setToolsExpanded()` are no-ops.
- `getEditorText()` returns `""`; `getToolsExpanded()` returns `false`.
- `pasteToEditor()` degrades to `setEditorText()`.
- `getAllThemes()` returns `[]`; `getTheme()` returns `undefined`; `setTheme()` returns `{ success: false, error: "..." }`.
- `ctx.mode` is `"rpc"` and `ctx.hasUI` is `true`, because dialogs and fire-and-forget notifications do work. Extensions are documented to gate terminal-only features on `ctx.mode === "tui"` (`docs/extensions.md:942`, `docs/extensions.md:2897-2901`).

Component contract that a PTY-free client would otherwise have to emulate (`docs/tui.md:9-29`): `render(width): string[]`, optional `handleInput(data)`, `wantsKeyRelease`, `invalidate()`, with per-line SGR/OSC 8 resets. Input relies on the Kitty keyboard protocol (`docs/terminal-setup.md:3`).

Local extension census (`~/.pi/agent/settings.json` packages, files under `~/.pi/agent/npm/node_modules`) — files containing `ui.custom(`:

| Package | Files using `ui.custom(` |
|---|---|
| `@tintinweb/pi-subagents` | 6 |
| `@juicesharp/rpiv-ask-user-question` | 2 |
| `pi-mcp-adapter` | 1 |
| `@tmustier/pi-usage-extension` | 1 |
| `pi-memory`, `pi-web-access`, `@narumitw/pi-plan-mode`, `@narumitw/pi-goal` | 0 |

Verified fact: four of eight installed packages contain custom-component code paths, so the gap is real for this user's configuration, not hypothetical. Not verified: whether each call site has a non-TUI fallback branch; per-extension behavior must be tested individually.

## Verified transport facts

RPC mode is started with `pi --mode rpc [options]` and speaks JSONL over stdin/stdout (`docs/rpc.md:1-40`).

- Commands are JSON objects on stdin, one per line; responses carry `type: "response"`; events stream on stdout as JSON lines.
- Optional `id` correlates request/response. `bash_execution_update` echoes the `id` of its originating `bash` command.
- **Framing is strict LF-only.** Split on `\n` only; strip an optional trailing `\r`. Node `readline` is explicitly called out as non-compliant because it also splits on `U+2028`/`U+2029`, which are legal inside JSON strings (`docs/rpc.md:28-39`). This dictates a hand-rolled framer on both the Mac bridge and any re-encoding hop.
- `--mode json` is a separate one-shot stream mode. **RPC `message_update` is also delta-only**: it omits cumulative `message` and `assistantMessageEvent.partial` snapshots (`docs/rpc.md:915-959`). RPC is the correct interactive choice because it is bidirectional, not because it supplies cumulative stream state.

Command groups present in `docs/rpc.md`: prompting (`prompt`, `steer`, `follow_up`, `abort`, `new_session`), state (`get_state`, `get_messages`), model (`set_model`, `cycle_model`, `get_available_models`), thinking (`set_thinking_level`, `cycle_thinking_level`, `get_available_thinking_levels`), queue modes (`set_steering_mode`, `set_follow_up_mode`), compaction (`compact`, `set_auto_compaction`), retry (`set_auto_retry`, `abort_retry`), bash (`bash`, `abort_bash`), session (`get_session_stats`, `export_html`, `switch_session`, `fork`, `clone`, `get_fork_messages`, `get_entries`, `get_tree`, `get_last_assistant_text`, `set_session_name`), commands (`get_commands`).

Prompt semantics worth encoding in the client state machine:

- While streaming, `prompt` requires an explicit `streamingBehavior`, otherwise it errors. `"steer"` delivers after the current assistant turn's tool calls finish and before the next LLM call; `"followUp"` delivers only once the agent stops (`docs/rpc.md:60-70`).
- `success: true` means accepted/queued/handled, not completed. Post-acceptance failures arrive through the event stream, never as a second `response` for the same id (`docs/rpc.md:76`).
- Extension commands (`/mycommand`) execute immediately even during streaming; skill commands (`/skill:name`) and prompt templates are expanded before send (`docs/rpc.md:71-74`).
- Images ride inline as `ImageContent`: `{"type": "image", "data": "<base64>", "mimeType": "image/png"}` on `prompt`, `steer`, and `follow_up` (`docs/rpc.md:51-53`, `88-93`, `110-115`).

`get_commands` returns `name`, `description`, `source` (`extension` | `prompt` | `skill`), optional `location` (`user` | `project` | `path`), and an absolute `path`. **Built-in TUI commands such as `/settings` and `/hotkeys` are excluded and would not execute if sent via `prompt`** (`docs/rpc.md:830`). The mobile client must therefore implement its own settings/hotkey surfaces rather than proxying those slash commands.

## Streaming assembly

**RPC deltas are provisional.** A client must start from `message_start`, assemble `text_*`, `thinking_*`, and `toolcall_*` blocks by `contentIndex`, buffer tool arguments until `toolcall_end.toolCall`, and correlate tool execution by `toolCallId`. `text_end.content` and `toolcall_end.toolCall` replace their assembled block; `message_end.message` replaces the entire provisional message and is authoritative (`docs/rpc.md:915-959`).

A mobile transport must add its own sequence numbers. On a sequence gap or reconnect, it must discard provisional content and rebuild from a host snapshot or persisted entries rather than silently appending possibly incomplete deltas.

## Completion signal

**`agent_settled` is the completion trigger.** `agent_end` marks one low-level run and may be followed by automatic retry, compaction-then-retry, or queued follow-up delivery; `agent_end` carries `willRetry`. `agent_settled` is emitted only when Pi will not continue automatically (`docs/rpc.md:836-889`, `docs/extensions.md:558-569`, lifecycle diagram `docs/extensions.md:312`).

Consequence: notifications, "task finished" UI state, and any push fan-out must key off `agent_settled`, not `agent_end`, or the user receives premature and duplicated completions.

Full event vocabulary (`docs/rpc.md:836-861`): `agent_start`, `agent_end`, `agent_settled`, `turn_start`, `turn_end`, `message_start`, `message_update`, `message_end`, `bash_execution_update`, `tool_execution_start`, `tool_execution_update`, `tool_execution_end`, `queue_update`, `compaction_start`, `compaction_end`, `auto_retry_start`, `auto_retry_end`, `summarization_retry_scheduled`, `summarization_retry_attempt_start`, `summarization_retry_finished`, `extension_error`.

## Extension UI sub-protocol (what does work headless)

`docs/rpc.md:1145-1153` splits methods into:

- **Dialog methods** — `select`, `confirm`, `input`, `editor`. Emit `extension_ui_request` on stdout and block until a matching `extension_ui_response` arrives on stdin. An optional `timeout` (ms) is auto-resolved agent-side, so the client does not track timers.
- **Fire-and-forget methods** — `notify`, `setStatus`, `setWidget`, `setTitle`, `set_editor_text`. Emitted but never answered; a client may render or ignore them.

Response shapes (`docs/rpc.md:1312-1330`): value response for `select`/`input`/`editor`, `confirmed: true/false` for `confirm`, `cancelled: true` for any dialog.

`setWidget` accepts `widgetLines: string[]` with `widgetPlacement` `"aboveEditor"` (default) or `"belowEditor"`; **component factories are ignored in RPC mode** (`docs/rpc.md:1269-1284`). `notifyType` is `"info" | "warning" | "error"`, defaulting to `"info"`.

This is the load-bearing good news: a native client can render Pi's interactive approval and question flows as first-class mobile UI, with no terminal emulation, as long as it answers dialog requests by `id`.

## Hosting alternatives considered

| Option | Verified basis | Assessment |
|---|---|---|
| Spawn `pi --mode rpc` subprocess and speak JSONL | `docs/rpc.md:1-40` | Recommended. Preserves the user's real settings, packages, skills, and extension set because it is the same binary the user runs. |
| Embed `AgentSession` / `createAgentSessionRuntime()` from the SDK | `docs/rpc.md:3`, `docs/sdk.md:46-180`, `docs/sdk.md:1097-1131` | Recommendation deferred. Gives typed in-process access and avoids a process boundary, but reimplements startup/resource-loading semantics and risks drifting from the user's CLI behavior. |
| Run Pi on the phone under Termux | `docs/termux.md:1-98` | Rejected as the product model. Documented limitations include no image clipboard; it also moves provider credentials onto the phone, which requirement constraints forbid. |
| Host a PTY and mirror the real TUI | `docs/tui.md:9-29`, `docs/terminal-setup.md:3` | Unresolved tradeoff, see below. |

## Session and state model

- Sessions auto-save to `~/.pi/agent/sessions/`, organized by working directory, as JSONL files with a tree structure (`docs/sessions.md:7`).
- Path layout: `~/.pi/agent/sessions/--<path>--/<timestamp>_<uuid>.jsonl`, where `<path>` is the working directory with `/` replaced by `-` (`docs/session-format.md:5-11`).
- Entries form a tree via `id`/`parentId`, enabling in-place branching without new files. Current version is 3; v1 (linear) and v2 (tree) auto-migrate on load. v3 renamed the `hookMessage` role to `custom` (`docs/session-format.md:3`, `21-27`).
- `get_entries` accepts `since` and returns `leafId` — `null` for an empty session — so one round trip reveals whether the active branch moved. An unrecognized `since` yields `success: false` (`docs/rpc.md:722`).

Recommendation: use `get_entries` + `leafId` as the resync primitive after reconnect, and treat the session tree as the source of truth rather than the client's event log.

## Recommendations

1. Mac-side bridge spawns `pi --mode rpc` per session, inheriting the user's environment so settings, `packages`, skills, prompts, and extensions load exactly as in the terminal (satisfies R3 by construction).
2. Implement a strict LF-only JSONL framer with explicit `\r` stripping; never use a generic line reader. Add a fixture asserting `U+2028`/`U+2029` inside string payloads do not split records.
3. Assemble `message_update` deltas by `contentIndex`, detect transport sequence gaps, and replace provisional state with `message_end.message`. Add interleaved thinking/text/tool fixtures whose assembled output is byte-identical to the authoritative final message.
4. Model completion as `agent_settled`. Track `agent_end.willRetry`, `auto_retry_*`, `compaction_*`, and `summarization_retry_*` as intermediate "still working" states so the UI does not flip to done and back.
5. Render the four dialog methods natively (select / confirm / input / editor) and surface fire-and-forget `notify`/`setStatus`/`setWidget` as transient chips, a status line, and a widget strip.
6. Preserve unknown event and `extension_ui_request` types verbatim in a generic inspectable representation (AGENTS.md non-negotiable), so a Pi upgrade degrades rather than breaks.
7. Do not proxy built-in TUI slash commands; enumerate real commands with `get_commands` and build native equivalents for `/settings`-class functionality.
8. Show an explicit, honest compatibility banner when an extension attempts `custom()`, naming the extension and offering the terminal compatibility surface.

## Unresolved tradeoffs

- **PTY mirroring.** A hosted PTY plus an ANSI/Kitty-capable mobile renderer would achieve literal 100% extension fidelity, at the cost of a second rendering pipeline, touch-hostile keyboard semantics, and a large security/complexity surface. Decision deferred to architecture; the fallback banner is the minimum acceptable behavior.
- **Subprocess vs embedded SDK.** Not yet decided; subprocess is favored for fidelity, SDK for typing and lifecycle control.
- **Bash streaming volume.** `bash_execution_update` and `tool_execution_update` can be high-rate. Whether the Mac bridge coalesces before transmit or the phone coalesces before render is unresolved and interacts with the performance budget in `mobile-ux.md`.
- **Per-extension fallback quality.** The four packages with `custom()` call sites need individual integration scenarios before R3 can be claimed.
