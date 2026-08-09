# Pi Mobile application protocol v1

Last updated: 2026-08-09
Status: contract to freeze with schemas and cross-language fixtures before implementation

This protocol runs inside TLS 1.3. Normal data connections require mTLS. Pairing alone runs in `PAIRING_PROVISIONAL` over inner server-auth TLS pinned by the QR and cannot access sessions or mutations. Remotely, a standing Mac control WSS lets the relay summon a one-use outbound Mac data WSS to pair with Android; locally the inner stream runs directly over TCP. WebSocket boundaries have no protocol meaning.

## Byte framing

Every frame starts with a 12-byte network-order header:

| Offset | Size | Meaning |
|---:|---:|---|
| 0 | 4 | ASCII magic `PIMB` (`50 49 4d 42`) |
| 4 | 1 | major version, `1` |
| 5 | 1 | kind |
| 6 | 2 | flags, unsigned; must be zero in v1 |
| 8 | 4 | payload length, unsigned big-endian |

Kinds:

| Value | Name | Payload |
|---:|---|---|
| `0x01` | `JSON` | UTF-8 JSON object |
| `0x02` | `BLOB_CHUNK` | binary stream prefix + bytes |
| `0x03` | `AUDIO_PCM` | binary stream prefix + PCM bytes |
| `0x04` | `TERMINAL_BYTES` | terminal generation/sequence prefix + bytes |

Readers must tolerate arbitrary fragmentation/coalescing. Invalid magic, major, flags, UTF-8, length, or JSON closes the connection. No resynchronization scan is attempted on an authenticated stream.

### Bounds

| Item | Limit |
|---|---:|
| Any frame payload | 1 MiB |
| JSON payload | 256 KiB |
| Binary data after prefix | 64 KiB |
| Event batch | 128 events and 256 KiB |
| Inline exact raw Pi record | 128 KiB |
| Outbound queue per connection | 512 frames or 8 MiB |
| Prompt image decoded total | 8 MiB |
| Connected xterm scrollback | 5,000 lines; not restored after reconnect |
| Bounded tmux history response | 5,000 lines and 1 MiB, lower limit wins |
| Finalized semantic messages retained in memory | 500 |

The lower applicable bound wins. Length is checked before allocation. Queue exhaustion never drops authoritative semantic data: producer flow control applies, then the connection closes with `RESOURCE_EXHAUSTED` if progress cannot resume. Terminal frames may be superseded only after an explicit `terminal.reset`; audio stops visibly rather than buffering without bound.

## Binary prefixes

`BLOB_CHUNK` and `AUDIO_PCM` start with:

| Size | Field |
|---:|---|
| 16 | stream UUID bytes |
| 4 | chunk sequence, unsigned big-endian |
| 8 | byte offset, unsigned big-endian |
| remaining | data |

A JSON `stream.open` must precede data and defines `streamId`, `purpose`, media type/PCM format, expected length if known, SHA-256 if known, and per-stream limit. `stream.close` supplies final length and SHA-256. Sequence and offset must both be contiguous; otherwise the receiver cancels the stream. Unknown stream, duplicate chunk, overflow, digest mismatch, or data after close is fatal to that stream and reported by `stream.error`.

`TERMINAL_BYTES` starts with two unsigned 64-bit big-endian fields: `terminalGeneration` and `sequence`, followed by bytes. Terminal sequences are contiguous per direction. A gap forces `terminal.reset` and tmux redraw; input is never replayed.

Audio v1 is signed 16-bit little-endian, 16 kHz, mono PCM. Any other format requires a negotiated future minor version.

## JSON envelope

Every JSON frame is one object:

```json
{
  "v": {"major": 1, "minor": 0},
  "type": "command.submit",
  "messageId": "uuid-v4",
  "replyTo": null,
  "body": {}
}
```

- `messageId` identifies a protocol message, not execution idempotency.
- `replyTo` correlates response/error to a protocol message.
- Commands additionally carry `commandId` UUIDv4 and `payloadHash`: SHA-256 over RFC 8785/JCS of exactly `{sessionId, operation, payload, expectedLeafId}`, excluding absent optional fields and `commandId`.
- Pi `leafId`/`expectedLeafId` is either `null` or exactly eight lowercase hex characters (`^[0-9a-f]{8}$`); it is not a UUID.
- Event messages carry `sessionId`, `streamEpoch` UUIDv4, and unsigned 64-bit `sequence` encoded as a decimal string to avoid JavaScript precision loss.
- Unknown fields are retained. Unknown `type` values are stored and shown by the raw inspector; they are not executed.

Canonical JSON for hashes is UTF-8 RFC 8785/JCS. Hashes are lowercase hex SHA-256 unless a field says otherwise.

## Negotiation

On a normal post-certificate mTLS connection, first application messages are:

```json
{"v":{"major":1,"minor":0},"type":"client.hello","messageId":"550e8400-e29b-41d4-a716-446655440000","replyTo":null,"body":{"minMinor":0,"maxMinor":0,"appVersion":"1.0.0","deviceId":"550e8400-e29b-41d4-a716-446655440002","features":[]}}
{"v":{"major":1,"minor":0},"type":"server.hello","messageId":"550e8400-e29b-41d4-a716-446655440001","replyTo":"550e8400-e29b-41d4-a716-446655440000","body":{"minor":0,"hostVersion":"1.0.0","piVersion":"0.84.0","limits":{}}}
```

A provisional pairing stream instead starts with bounded `pair.begin` after frame-version validation; normal `client.hello` is not accepted there. Different majors fail with `UNSUPPORTED_VERSION`. Normal peers select the highest common minor; features require both advertisements. A peer may tighten limits but not exceed v1 absolutes.

Connection phases are `PAIRING_PROVISIONAL`, `NEGOTIATING`, `DEVICE_AUTHENTICATED`, `USER_AUTHENTICATED`, `SYNCING`, `READY`, `CLOSING`. A QR-pinned server-auth TLS connection enters only `PAIRING_PROVISIONAL`; it accepts bounded pairing/control messages and never session content, command, terminal, voice, or blob access. Certificate issuance closes it. A new mTLS connection starts `NEGOTIATING`. Before user authentication, only hello, assertion, generic push/cert status, ping, and close are accepted. Session content and mutations require `READY`.

## Required message families

| Family | Types |
|---|---|
| Control | `client.hello`, `server.hello`, `ping`, `pong`, `error`, `close` |
| Pairing/authentication | `pair.begin`, `pair.csr`, `auth.registration.options`, `auth.registration.response`, `auth.assertion.options`, `auth.assertion.response`, `auth.result`, `pair.confirm`, `pair.result` |
| Sync | `sync.resume`, `sync.replay`, `sync.reset`, `snapshot.waiting`, `snapshot.begin`, `snapshot.page`, `snapshot.end`, `event.batch`, `event.ack` |
| Commands | `command.submit`, `command.query`, `command.state`, `command.result` |
| Approval | `approval.offer`, `approval.decision`, `approval.expired` |
| Streams/blobs | `stream.open`, `stream.close`, `stream.cancel`, `stream.error`, `blob.ready`, `blob.release` |
| Terminal | `terminal.open`, `terminal.ready`, `terminal.resize`, `terminal.key`, `terminal.reset`, `terminal.history.request`, `terminal.history.result`, `terminal.close` |
| Voice | `voice.start`, `voice.partial`, `voice.finish`, `voice.cancel`, `voice.error` |
| Push | `push.endpoint`, `push.endpoint.revoke` |

Schemas must set required fields and bounds while permitting additive unknown fields.

## Pairing and authentication flow

Before transport, Android generates its non-exportable P-256 TLS key/CSR, hashes canonical DER, and generates a separate P-256 route-auth key. The QR supplies a five-minute invitation and provisional Mac server-certificate fingerprint. Android opens outer WSS, then inner TLS 1.3 with **server authentication only**, pins that fingerprint, and enters `PAIRING_PROVISIONAL`; client-certificate requests are forbidden there.

1. `pair.begin` carries invitation ID, device route-key ID/public key, and CSR hash; `pair.csr` carries the bounded CSR whose hash must match.
2. If no owner credential exists, Mac sends `auth.registration.options`; only `auth.registration.response` is accepted. Otherwise it sends `auth.assertion.options`; only `auth.assertion.response` is accepted. Registration and assertion are never multiplexed through an ambiguous message.
3. The Mac stores a challenge record bound to ceremony kind, invitation ID, TLS exporter, CSR hash, RP, exact Android origin, and expiry. A response on another TLS connection, invitation, CSR, or ceremony fails.
4. `auth.result` precedes transcript-bound short-code display. `pair.confirm` reports waiting/confirmed/rejected state; local Mac confirmation is mandatory and cannot be supplied by Android.
5. On confirmation the Mac atomically consumes the invitation, registers/revokes route public keys as needed, signs the CSR, and returns `pair.result` with the bounded certificate chain. The provisional stream closes. Only a new mTLS connection may enter normal negotiation.

First-owner registration is allowed only under a valid unconsumed invitation plus local Mac confirmation. Later-device pairing requires assertion by the existing owner credential before certificate issuance. Normal unlock/authentication on an already paired mTLS connection uses the assertion messages.

## Pi RPC records and events

The Mac reads Pi stdout with a byte scanner that splits on LF (`0x0a`) only and strips one CR immediately before LF. `U+2028`/`U+2029` inside JSON strings are ordinary bytes. A Pi record over 16 MiB, malformed JSON, or EOF mid-record faults that Pi subprocess.

A Pi event is transported without pretending a parsed object preserves source bytes. The LF terminator is excluded; `rawJson` encoded as UTF-8 must byte-equal the Pi line, `rawSha256` covers those exact bytes, and `projection` is the parsed reducer view:

```json
{
  "type":"event.batch",
  "body":{"events":[{
    "sessionId":"550e8400-e29b-41d4-a716-446655440010",
    "streamEpoch":"550e8400-e29b-41d4-a716-446655440011",
    "sequence":"42",
    "piType":"message_update",
    "rawJson":"{\"type\":\"message_update\",\"assistantMessageEvent\":{}}",
    "rawSize":"52",
    "rawSha256":"39bfc192fb8eaed43aa74182b793c8cfaa69b614eb03ffa3b59a7f5c30760778",
    "projection":{"type":"message_update","assistantMessageEvent":{}}
  }]}
}
```

For inline records, parsing `rawJson` must deep-equal `projection`; only `rawJson` is the lossless form. Over 128 KiB, exact bytes stay on Mac behind `rawRef {streamId,size,sha256,mediaType}` and the event carries a bounded, explicitly non-lossless projection sufficient for routing. Fetching the reference produces the exact `rawJson` bytes and must match the same digest before inspection. Unknown fields remain in exact bytes even when the reducer ignores them. Snapshots/histories are paginated; no message bypasses limits.

### Strict delta assembly

For each assistant message:

1. `message_start` creates provisional content.
2. `text_start/delta/end`, `thinking_start/delta/end`, and `toolcall_start/delta/end` apply to their `contentIndex`.
3. Deltas append only to the matching open block.
4. End events replace, never append to, their block. RPC strips each upstream `partial`, so `text_end.content` and `thinking_end.content` replace only provisional text/thinking content; signatures and redaction are unavailable until authoritative `message_end.message`. `toolcall_end.toolCall` replaces the tool block.
5. Tool execution events correlate by `toolCallId`, not position.
6. `message_end.message` replaces the complete provisional message and is the only committed authoritative message.

Unexpected transition/index, transport sequence gap, stream epoch change, or reconnect discards every provisional block and starts snapshot recovery. Tests compare the content fields available before finalization, then prove `message_end.message` replaces the full provisional message and supplies authoritative signatures/redaction. Fixtures include signed/redacted final thinking, changed end content, interleaving, and parallel tools.

`agent_end` does not settle a session. Only `agent_settled` emits the durable completion event and push outbox entry.

## Command dispatch

`command.submit.body` contains:

```json
{
  "commandId":"550e8400-e29b-41d4-a716-446655440040",
  "sessionId":"550e8400-e29b-41d4-a716-446655440010",
  "operation":"prompt",
  "payload":{"message":"Run the focused tests"},
  "payloadHash":"f6bbf467f22f558199c698aa09256aff619819d97e8773e162128870b28ede42",
  "expectedLeafId":"7fa3c91e"
}
```

Before dispatch, the Mac verifies canonical hash, current READY/user authentication, writer lease, expected leaf, referenced blobs, side-effect classification, and any fresh approval, then durably advances the command. Different hash for an existing ID closes with `COMMAND_ID_REUSE`.

Journal states exposed by `command.state` are `RECEIVED`, `ARMED`, `ACKED`, `REJECTED`, and `INDETERMINATE`, plus `dormant: true` on recovered `RECEIVED`. Recovery never dispatches. `command.query` returns state/result only and cannot wake any row. The sole wake path for dormant `RECEIVED` is a deliberate same-id/same-hash `command.submit` over the **current** READY, user-authenticated connection; all guards above, including leaf and approval, run again. Prior approval does not survive recovery. `ARMED`/`INDETERMINATE` can never be resubmitted for execution; a new execution needs a new ID and explicit action.

Read-only queries may bypass the mutation journal only when enumerated in the frozen schema. Settings are journaled even if observably idempotent.

## Prompt-image upload and ownership

Images never inflate `command.submit` as base64. Complete flow:

1. A READY, user-authenticated client sends `stream.open` with purpose `prompt_image`, upload ID, declared MIME, expected size, and SHA-256. Host accepts only allowlisted image MIME and at most 8 MiB decoded.
2. Contiguous `BLOB_CHUNK` frames arrive, followed by `stream.close` repeating final size/digest. The host verifies sequence, offset, digest, decode, MIME, and bounds, atomically stores mode-`0600` bytes, then emits `blob.ready {blobId,size,sha256,mimeType,expiresAt}`. Before `blob.ready`, no command may reference the upload.
3. Prompt/steer/follow-up payloads carry `imageRef {blobId,size,sha256,mimeType}`; these exact fields participate in `payloadHash`. Host verifies ownership, readiness, expiry, and digest during every submission/resubmission, attaches the blob to the journal row, and only then converts it to Pi's inline `ImageContent` at the final stdin write.
4. `blob.release`, upload cancel/disconnect, expiry, and a startup sweeper remove unreferenced temporary data. A blob referenced by dormant `RECEIVED` stays until that row's bounded recovery expiry; ACKED/REJECTED/INDETERMINATE rows release it after audit retention. Sweeping must never delete a blob needed by a dispatchable current row.

Missing, expired, mismatched, cross-device, not-ready, or orphan references reject before Pi stdin with `BLOB_NOT_READY` or `BLOB_INVALID`. Crash fixtures cover every close/ready/journal/cleanup boundary.

## Synchronization

Android persists `(sessionId, streamEpoch, sequence, leafId)` only after its reducer transaction commits. It sends these in `sync.resume`.

- Matching epoch + contiguous retained events: host emits `sync.replay` then ordered batches.
- Missing range, unknown cursor, epoch mismatch, or leaf mismatch: host emits `sync.reset`; Android drops provisional/live state and retains drafts.
- Canonical snapshot work runs inside the session actor, blocks bridge mutations, and waits for Pi idle/settled. If a gap occurs while active, `snapshot.waiting` marks transcript/live provisional data unavailable; no partial snapshot is emitted.
- At idle the host freezes event cursor `F` and issues exactly one canonical `get_entries` request for the attempt. Its entries and eight-hex/null leaf are the sole transcript source and are paged from one immutable response.
- State/model/thinking/queue/tree/commands queries are optional adjunct pages tagged with `F`; they cannot add, replace, or reorder transcript entries.
- The first response records `lastAppendId` from its final append-order entry separately from `leafId`. Before publication the host calls validation-only `get_entries {since: lastAppendId}`; for an empty session it repeats a full query. It must return no new append-order entries and the same leaf. This response is never merged; any entry or leaf change discards the attempt and retries from idle. The leaf itself is never used as an append cursor because a branched session may point behind later off-branch entries.
- `snapshot.end` includes `F` and verified leaf. Android commits atomically, acknowledges, then receives retained events strictly after `F`.

Events are never silently skipped. Duplicate committed events are ignored by cursor. A next noncontiguous sequence is a gap, not best effort.

## Extension UI

Structured Pi `select`, `confirm`, `input`, and `editor` requests are raw events with stable Pi request IDs. Android responses are journaled `command.submit` operations. Fire-and-forget `notify`, `setStatus`, `setWidget`, `setTitle`, and `set_editor_text` remain raw and are sanitized before display. ANSI control sequences in structured strings are removed or converted by an allowlisted parser.

Approval never uses `extension_ui_request` or Pi `confirm`. `approval.offer` carries broker offer ID, operation ID (`toolCallId` or host `commandId`), final normalized arguments, cwd/resource, reasons, policy version, argument hash, and absolute expiry. `approval.decision` repeats offer/operation/hash and permits only `allow_once` or `deny`; it is accepted only from the originating READY/user-authenticated connection and once on the live operation. The broker permits one globally active offer and a FIFO of at most eight policy requests across sessions/devices. The preload starts a local monotonic 150-second cap at hook invocation. A queued request must be promoted within 30 seconds or blocks without an offer. Promotion fixes a decision deadline at the earlier of 120 seconds later or the hook cap, with matching absolute `expiresAt`; only a decision received before the broker's monotonic deadline can win. `approval.expired` closes the sheet after deadline/cancellation. The local cap covers connect, queue, decision, and response. Overflow, broker loss, disconnect, stale offer, or tuple mismatch yields a block result and safely resumes the turn.

RPC produces no event when `ctx.ui.custom()` returns `undefined`. Therefore the generated invocation manifest classifies each known command path with `requiresTerminal` and side-effect class before dispatch. `/mcp`, `/usage`, `/agents`, `/btw`, `/llama`, and every discovered custom path pre-route to terminal. Destructive or unknown side-effect extension commands require broker approval or explicit terminal routing. An unexpected extension-command watchdog may kill/restart/resync after bounded inactivity, never auto-retries the invocation, and reports direct extension side effects as possibly unknown. It reports a generic compatibility timeout, never detected `custom()`.

## Terminal reconnect and history

`terminalGeneration` changes on reconnect. The new xterm receives only the current visible pane/redraw; connected-client 5,000-line scrollback is not serialized or replayed. `terminal.history.request` asks the Mac for a read-only `tmux capture-pane` snapshot bounded to 5,000 lines/1 MiB. `terminal.history.result` carries its generation, capture timestamp, truncation flags, and bounded text/ref. It renders in a separate history drawer and never feeds xterm, Pi stdin, or replay state. No UI may call it full or restored scrollback.

## Errors and closure

Errors contain stable `code`, safe `message`, `retryable`, and optional correlated IDs. Initial codes:

- `UNSUPPORTED_VERSION`
- `PROTOCOL_VIOLATION`
- `FRAME_TOO_LARGE`
- `RESOURCE_EXHAUSTED`
- `AUTH_REQUIRED`, `AUTH_FAILED`, `PAIRING_PHASE_REQUIRED`, `REVOKED`
- `SEQUENCE_GAP`, `SYNC_REQUIRED`, `SNAPSHOT_WAITING_FOR_IDLE`, `SNAPSHOT_LEAF_CHANGED`
- `COMMAND_ID_REUSE`, `COMMAND_DORMANT`, `COMMAND_INDETERMINATE`, `JOURNAL_UNAVAILABLE`
- `APPROVAL_DENIED`, `APPROVAL_EXPIRED`, `BROKER_UNAVAILABLE`
- `SESSION_LEASE_CONFLICT`
- `STREAM_INVALID`, `BLOB_NOT_READY`, `BLOB_INVALID`, `TERMINAL_RESET_REQUIRED`

Logs record codes and opaque IDs, never prompts, Pi raw payloads, terminal bytes, audio, credentials, or keys.

## Conformance gate

Before feature work, Kotlin and TypeScript codecs consume identical fixtures for fragmented/coalesced frames, all bounds, invalid UTF-8, unknowns, exact `rawJson`/projection/digest, prompt-image ready/ref/orphan flow, eight-hex leaf IDs, active-gap idle snapshots, adjunct cursors/leaf retry/post-fence replay, dormant `RECEIVED` plus `command.query`, approval offer/decision/expiry, pairing registration versus assertion and exporter/CSR binding, terminal history bounds, and content-only end-event replacement plus authoritative signed/redacted `message_end` replacement. Fuzzers prove allocations remain bounded.
