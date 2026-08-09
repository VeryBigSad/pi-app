# Research: mobile UX and performance

Last updated: 2026-08-09

## Prior art surveyed

Both mainstream precedents treat the phone as a **triage and steering surface**, not a terminal.

| Product | Documented behavior | Primary source |
|---|---|---|
| Codex on mobile | Inside the ChatGPT mobile app rather than a standalone app; preview announced 2026-05-14. Work spans multiple active threads with project context and approvals from the phone. Real-time updates include screenshots, terminal output, diffs, test results, and approvals. Users can answer questions, change direction, approve commands, switch models, or start another task. Files, credentials, permissions, and local setup stay on the connected machine. | <https://openai.com/index/work-with-codex-from-anywhere/> |
| Claude Code Remote Control | Remote sessions appear in a mobile session list with online status; conversation stays synchronized across terminal, browser, and phone; runs locally with outbound connections and short-lived scoped credentials; push notifications target task completion or decisions needed to continue. | <https://code.claude.com/docs/en/remote-control> |
| Claude Code Desktop | Visual diff review with inline comments is documented for desktop, while mobile is positioned for monitoring and steering. | <https://code.claude.com/docs/en/desktop> |
| Claude agent view | Agent view emphasizes state, last response, and "needs input." | <https://claude.com/blog/agent-view-in-claude-code> |

Verified distinction: Codex's documented mobile experience is artifact-rich and real-time; Claude's documented mobile strength is synchronized remote control and steering, with the richer inline diff-comment workflow documented on desktop. Recommendation drawn from this: prioritize fast triage and decisions, and make deep diffs progressively available rather than the default view.

## Adopted patterns

1. **Session inbox, not a terminal.** Buckets: Working / Needs you / Ready to review / Done. One-line latest activity per row plus a conspicuous blocker badge. Maps directly to Pi state: streaming, pending `extension_ui_request` dialog, settled with edits, settled clean.
2. **Milestone timeline over raw token firehose.** Stream meaningful milestones ("searched 12 files", "edited auth.ts", "tests running", "awaiting approval"); keep verbose logs behind an expandable event. This is also the performance lever.
3. **Interruptible, steerable execution.** Persistent composer plus quick replies: Approve, Continue, Stop, Change approach, Ask for summary. Stop must remain visible whenever the agent is running. Maps to Pi `steer` / `follow_up` / `abort`.
4. **Diff review as a checkpoint, progressive disclosure.** Change summary and risk/test status first, then file list, then a touch-friendly unified diff. Never open into a dense full-repo diff.
5. **Separate execution from device control.** Label the runtime target explicitly, e.g. "Running on MacBook Pro · local repo · main worktree", and place permission scope next to every destructive approval.
6. **Notification content is specific.** "Tests failed" or "Approval needed", never a vague "Agent update."

Recommended mobile flow: push notification → session landing (status, current step, elapsed time, branch/worktree, 2-3 line summary) → evidence cards (tests, screenshots, commands, changed-file count; tap to expand) → review mode (summary → files → hunks, with comment / request changes / approve / open on desktop) → collapse the reviewed diff into a durable event and resume streaming.

## Streaming list performance

Primary sources (Android Developers):

- Lazy lists and grids — <https://developer.android.com/develop/ui/compose/lists>
- Stability in Compose — <https://developer.android.com/develop/ui/compose/performance/stability>
- Best practices — <https://developer.android.com/develop/ui/compose/performance/bestpractices>
- Strong skipping mode — <https://developer.android.com/develop/ui/compose/performance/stability/strongskipping>
- Compose performance overview — <https://developer.android.com/develop/ui/compose/performance>

Verified guidance:

- Stable `key` and `contentType` on `items` preserve identity across insertion/update and improve lazy composition reuse among structurally similar rows.
- Standard Kotlin `List`/`Map` are treated as unstable by Compose; unchanged composables with unstable parameters can still be skipped via instance equality because Strong Skipping is enabled by default with Kotlin 2.0.20.
- `LazyColumn` composes visible content on demand, but retaining a huge message list still costs memory and makes upstream transformations expensive.
- Debug-build scrolling is not representative. Use R8, an app-specific Baseline Profile, and Macrobenchmark `FrameTimingMetric` on real devices.

Reference shape:

```kotlin
LazyColumn(
    state = listState,
    contentPadding = PaddingValues(vertical = 8.dp)
) {
    items(
        items = messages,
        key = { it.id },
        contentType = { it.kind }
    ) { message ->
        MessageRow(message)
    }
}
```

Recommendations (application-level inference from the guidance above, not verbatim documentation):

- Isolate streaming text to the active assistant row; never emit `messages.toList()` per token.
- Coalesce token rendering outside composition and publish at roughly one frame cadence (~16-50 ms) instead of a state write per token.
- Cache markdown parsing, syntax highlighting, link extraction, and image sizing keyed by message id plus a content version; never sort or filter inside the list builder.
- Respect user scroll: follow the newest message only when already near the bottom, compute the "near bottom / show jump button" condition with `derivedStateOf`, and prefer `scrollToItem`/`requestScrollToItem` over repeatedly launching `animateScrollToItem`.
- Paginate or window very long histories; keep a bounded in-memory window and load older entries on demand, which pairs with Pi's `get_entries since` API.

Named anti-pattern to guard with tests: a ViewModel emitting a fresh full transcript per token while each row receives broad state (typing status, scroll state, whole transcript).

## Voice input UX consequence

Groq transcription is batch-only (see `networking-infra-testing.md`), so the microphone affordance must not promise word-by-word live captioning. Recommended presentation: hold-to-talk with a live waveform and elapsed timer, chunked interim text appended in order as each chunk returns, and a final editable transcript dropped into the composer rather than auto-sent. The UI language should say "transcribing" during gaps, because gaps are inherent to chunk boundaries and not a bug.

## Unresolved tradeoffs

- **Milestone abstraction vs fidelity.** Aggressive milestone summarization is the main performance win but hides raw tool output that power users may want by default. Needs a user-facing verbosity setting with a defensible default.
- **Diff review depth on phone.** Inline per-line commenting is documented as a desktop affordance in the surveyed prior art. Whether to ship line-level comments on phone or only hunk-level actions plus "open on desktop" is undecided.
- **Terminal-output rendering.** Bash and tool output are ANSI-bearing; how much ANSI to interpret versus strip on a narrow screen is unresolved and interacts with the PTY question in `pi-integration.md`.
- **Concrete performance budgets.** R5 requires documented budgets for memory, recomposition, queue depth, and reconnect. Numbers are not yet fixed; they must be set from Macrobenchmark runs on a real release build, not chosen a priori.
