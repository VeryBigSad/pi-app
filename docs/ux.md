# UX specification

Last updated: 2026-08-09

Design thesis, drawn from the prior art in [research/mobile-ux.md](research/mobile-ux.md): the phone is a **triage, decision, steering, and review surface**. Codex's documented mobile experience is artifact-rich and real-time; Claude Code's documented strength is synchronized remote control with push when work completes or a decision is needed, with rich inline diff commenting kept on desktop. Pi Mobile takes both lessons and adds one thing neither documents: a real terminal mode, because Pi extensions can draw their own TUI and pretending otherwise would be dishonest.

Two coequal modes ship in 1.0:

- **Semantic mode** — native Compose UI over `pi --mode rpc`. The default and the primary experience.
- **Terminal mode** — a real PTY through bundled xterm, for extensions whose UI cannot exist outside a terminal. Explicitly labeled, never the default.

## Adopted prior art

| Pattern | Source of the idea | How it appears here |
|---|---|---|
| Session inbox with state buckets and online status | Claude Code remote session list | Buckets: **Needs you**, **Working**, **Ready to review**, **Done**, plus **Indeterminate** |
| Push when finished or a decision is needed | Claude Code Remote Control | Wake only after durable `agent_settled` or a blocking input request |
| Artifact-rich real-time updates | Codex mobile | Evidence cards for tests, commands, diffs, and images, expandable in place |
| Approvals from the phone | Both | A dedicated approval sheet backed by a real pre-execution gate |
| Diff review as a checkpoint | Claude Code Desktop keeps inline comments on desktop | Hunk-level actions plus open-on-desktop; line-level commenting deliberately not in v1 |
| Execution target always labeled | Codex's "local setup stays on your machine" framing | Every session header names the Mac, repository, worktree, and whether the path is direct or relayed |
| Milestones over token firehose | Both, plus Compose performance guidance | Milestones by default, raw output behind an expand, raw inspector always reachable |

## Navigation

```
Inbox ──▶ Session ──▶ one of: Diff review · Approval sheet · Dialog sheet · Raw inspector · Terminal mode · Voice sheet
   └──▶ Settings ──▶ Devices · Notifications · Voice · Appearance · Compatibility · About
```

Phones use a bottom bar with Inbox and Settings. Tablets and unfolded foldables use a two-pane list-detail layout with the inbox permanently visible.

## Screens

### Lock and authentication

Pairing first generates the device CSR key, then shows QR-pinned provisional TLS, first-owner **registration** or later-device **assertion**, Mac short-code confirmation, certificate issuance, and reconnect-with-mTLS as distinct steps. Normal unlock uses passkey assertion. States: idle, in-progress, no credential, provider-cancelled, device-revoked, host-unreachable, unsupported-platform. Release has no skip path.

Host unreachable is its own state with plain copy, because a sleeping Mac cannot produce a fresh assertion and the user needs to know that is normal rather than a failure of the app.

### Inbox

Each row shows a state chip, session name, repository and worktree, one-line latest activity, elapsed or last-active time, and a blocker badge when Pi is waiting on the user. Offline shows a persistent bar with the last-synced time rather than an empty list. Long-press offers Abort, Rename, Fork, and Export.

### Session detail

Header: state, model, thinking level, elapsed time, and the execution target line, for example `MacBook Pro · ~/personal/pi-app · main · direct`.

Timeline: a lazy list with stable keys and content types. Row kinds are user message, assistant text, thinking, tool call, bash output, milestone, evidence card, dialog record, approval record, compatibility notice, and retained-unknown. Thinking is collapsed by default, honoring the user's `hideThinkingBlock` preference on the Mac. Every row can open the raw inspector, so the UI never knows less than the protocol.

Streaming: only the active row updates; token rendering is coalesced outside composition and published at frame cadence. A jump-to-latest chip appears when the user has scrolled away; auto-follow happens only when already near the bottom.

Composer: multiline text, image attach, voice, and send. While streaming, send becomes an explicit choice between **Steer now** and **Queue follow-up**, because they land at different points and conflating them misleads. Stop stays visible whenever the agent runs.

Quick replies: Continue, Stop, Change approach, Summarize. Notably **not** "Approve" — that word is reserved for the real gate.

After a sync gap during active work, provisional transcript rows disappear into a visible **Waiting for canonical state** marker; drafts remain. The UI does not show partial data as recovered until Pi settles and the idle snapshot commits.

Recovered `RECEIVED` commands show **Not sent after restart** with Query and deliberate Resume. Resume resubmits the same id/hash on the current connection and warns that auth/lease/leaf/blob/approval re-run. Recovered `ARMED` is **Outcome unknown** and can never Resume; inspection plus a new command is required.

### Approval sheet

The highest-stakes surface. Shows tool or operation name, exact normalized command, cwd or resource, the reasons the classifier flagged it, and expiry. Two actions only: **Deny** and **Allow once**. Deny holds default focus. Only one offer is visible globally; later operations wait FIFO (maximum eight and 30 seconds) without pretending to be approved. Once shown, a countdown of up to 120 seconds is visible and the copy states that no answer denies, as do disconnect and any classifier error.

The sheet is driven by `approval.offer`, never an extension `confirm`. It states this is a guardrail, not a sandbox: allowed code runs with Mac permissions and direct extension Node/fs/process side effects are not contained.

If the broker socket is unavailable or its hard deadline expires, the sheet closes as **Blocked on Mac — approval service unavailable**. Copy says the operation did not run through the gated path, Pi was resumed with a block result, and offers Retry after broker health returns; it never spins or implies the whole extension runtime is sandboxed.

### Dialog sheet

`select`, `confirm`, `input`, and `editor` render natively and are answered by the Pi request id through a journaled command. Unknown methods render in a generic sheet with the sanitized payload, so a Pi upgrade degrades rather than blocks. Structured strings pass through an allowlisted ANSI sanitizer, because raw escape sequences do arrive in practice.

### Diff review

Three steps of progressive disclosure: summary with risk and test status, then the changed-file list, then a touch-friendly unified diff. Word-level intra-line highlighting; horizontal scroll only inside code blocks. Hunk-level actions: mark reviewed, comment on the file, request changes, open on desktop. Reviewed diffs collapse into a durable timeline event.

### Raw inspector

Shows the exact retained Pi JSON for any event, including unknown types, with digest-referenced oversized records downloaded on demand. Redaction rules still apply to sharing and export.

### Terminal mode

Labeled compatibility, entered deliberately. Invocation manifest pre-routes `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, and all known custom paths; RPC emits no custom event. If drift triggers the watchdog, copy says **Extension command timed out in semantic mode; Pi restarted and resynced; direct extension side effects may be unknown**, never “custom UI detected.” The invocation is never auto-retried.

A native key row provides Esc/Tab/Ctrl/arrows/Enter variants; IME bridge, clipboard, search, selection, and resize are supported. The connected xterm keeps 5,000 lines. Reconnect creates a fresh attach and restores only the visible tmux pane—no input or xterm scrollback replay. A separate **Mac terminal history** drawer fetches a bounded, timestamped `capture-pane` snapshot and visibly labels truncation; it is never called full/restored scrollback or inserted into the live terminal.

TUI images appear as native cards rather than in-terminal graphics, with no parity claim.

### Voice sheet

Hold-to-talk plus a tap-to-toggle mode for long dictation. Live waveform, elapsed timer, and a chunk indicator. Transcription occupies a **separate draft region**: ordered partials never overwrite text the user typed manually. Copy says "transcribing" during gaps, because gaps are inherent to chunk boundaries rather than a defect. Partials arrive roughly every 8-12 seconds.

The final transcript is inserted as editable text and is **never** auto-sent. Cancel discards audio and leaves the composer untouched. If the backlog exceeds 30 seconds, capture stops visibly rather than buffering silently.

Chunking rationale: every sub-10 s request rounds up to 10 billed seconds. Preferred 8 s/forced 12 s speech boundaries produce roughly 10 s requests without claiming token streaming; longer chunks hurt latency. VAD keeps 300 ms pre-roll and ~500 ms overlap. Voice settings show conservative RPM/RPD/hour/day usage, encoded versus billed duration, estimated upper-bound spend, `$0.25` daily/`$2` monthly default budget, and reset times. Quota/budget/long `Retry-After` stops are explicit, never silent.

### Settings

Devices with per-device certificate revocation, separate from passkey revocation. Notifications with the distributor picker, battery-optimization guidance, and lock-screen privacy level. Voice with language hint and cadence. Appearance with theme and density. Compatibility showing the pinned Pi and extension manifest and which extensions require terminal mode. About with app, host, and protocol versions and a redaction-safe log export.

## Notifications and privacy

The wake payload is **opaque and bounded**: no session name, prompt, file name, tool output, or result. The phone performs authenticated catch-up and composes text locally, and detail appears only after unlock. A forged wake therefore grants nothing beyond triggering a catch-up.

Lock-screen privacy levels:

| Level | Lock screen | After unlock |
|---|---|---|
| Minimal (default) | "Pi needs you" or "Pi finished" | Full detail |
| Descriptive | Adds session name and outcome class | Full detail |
| Silent | Badge only | Full detail |

Channels: **Needs you** for blocking dialogs and approvals, **Finished** for settlement, **Sync problems** at low importance. Notifications group per session, and an acknowledged settlement never re-notifies after catch-up.

Honest limits shown in Settings rather than buried: delivery depends on the distributor, OEM battery policy, force-stop state, and notification permission; there is no guarantee; app-open catch-up is authoritative. With no distributor installed, Settings explains the situation and links to options instead of pretending push works.

## Accessibility

- Content descriptions on every interactive element; no unlabeled icon buttons.
- Minimum 48 dp targets, with clear separation between destructive and non-destructive actions.
- TalkBack traversal order verified for inbox, timeline, composer, approval, dialogs, review, and the terminal key row. Approval and dialog sheets take focus on appearance and announce their purpose once.
- Streaming announcements are polite, not assertive, and never per token.
- Usable at 200% font scale with no truncation of actionable text; code and terminal content scroll rather than shrink.
- State is never encoded by color alone; every chip carries a label or icon. Contrast checked in both themes.
- Reduced-motion preference replaces slides and scales with cross-fades.
- Voice is never the only way to do anything, and terminal mode is never the only way to reach a common action.

## Motion and feel

Sheets animate in 150-250 ms; content swaps cross-fade; no parallax. The working indicator is a low-amplitude pulse rather than a spinner, so a long session does not feel frantic. Haptics fire on approval arrival, on send, and on voice start and stop, and nowhere else.

## Copy rules

- Name the real thing: "Allow `rm -rf build` once?", not "Action required".
- Never claim completion before `agent_settled`. During retry or compaction the copy says still working, because it is.
- Never say approved, secure, or sandboxed about the approval gate. Say the operation was allowed once.
- Dormant is not indeterminate: recovered RECEIVED says not sent and can Resume with full revalidation; recovered ARMED says outcome unknown and cannot resume.
- Active-gap state says canonical data unavailable, not “syncing latest” while provisional content is hidden.
- Broker unreachable says the gated operation was blocked and Pi resumed; never leave a pending approval spinner.
- Errors state what failed, what remains true, and the next action, using stable codes for support without leaking content.
- Terminal notices name the extension and the reason plainly.

## Open decisions

| Decision | Status |
|---|---|
| Milestone verbosity default | Milestones by default with a per-session override; revisit after manual use |
| Line-level diff comments | Deferred past v1; hunk-level plus open-on-desktop |
| ANSI fidelity in semantic bash rows | Color and bold honored, cursor movement and redraw not; full fidelity in terminal mode |
| Terminal input contingency | If Gboard composition fails on a physical device, add a native `InputConnection` or Compose text bridge; never fork Termux |
