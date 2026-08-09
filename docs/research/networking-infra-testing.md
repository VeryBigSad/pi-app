# Research: networking, notifications, voice, infrastructure, testing

Last updated: 2026-08-09

## Decisive constraints

1. **A background socket is not reliable push.** Pi Mobile uses UnifiedPush with self-hosted ntfy as Google-independent primary; FCM is optional only. Foreground sockets remain short-lived.
2. **Groq transcription is batch-only.** There is no documented streaming transcription parameter, so "realtime voice" must be built from sequential short chunks.

Both constraints are load-bearing: they determine the notification architecture and the voice UX respectively.

## Background execution and push

Primary sources (Android Developers / Firebase):

- Optimize for Doze and App Standby — <https://developer.android.com/training/monitoring-device-state/doze-standby>
- Restrictions on starting a foreground service from the background — <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Set and manage Android message priority — <https://firebase.google.com/docs/cloud-messaging/android-message-priority>
- Declare foreground services and request permissions — <https://developer.android.com/develop/background-work/services/fgs/declare>
- Foreground service timeouts — <https://developer.android.com/develop/background-work/services/fgs/timeout>

Verified facts:

- In Doze, Android suspends background network access. An existing socket may remain logically open but cannot be relied on to send, receive, or reconnect until a maintenance window or device wake.
- A user-visible foreground service can keep work running, but Android 12+ generally prohibits starting an FGS from the background. A received **high-priority** FCM message is an exception, but only if the message is genuinely high priority on receipt. Check `RemoteMessage.getPriority() == PRIORITY_HIGH`; FCM may downgrade abusive or non-user-visible high-priority traffic, after which an FGS start throws `ForegroundServiceStartNotAllowedException`.
- In `onMessageReceived()`, render the user-visible notification immediately and keep processing very short. For additional notification-related work, enqueue an expedited WorkManager job immediately after a high-priority FCM callback; it has a brief quota exemption. Long socket calls in the callback are to be avoided.
- Apps targeting API 34+ must declare the FGS type and the type-specific permission.
- Android 15 / API 35 caps `dataSync` FGS instances at **6 total hours per 24 hours** while backgrounded, with `Service.onTimeout()` required and prompt stopping. A permanent socket maintained under `dataSync` is therefore an unsuitable design.

Those sources explain why a permanent app socket/FGS is rejected. Project decision is Mac `agent_settled` or blocking input → opaque UnifiedPush wake through self-hosted ntfy → authenticated catch-up; optional FCM can implement the same wake interface but cannot block build/release. App-open catch-up is authoritative. Installed no-Google API 34 lanes are `PiApp_API_34_AOSP_UI` (default image for UI/notification tests) and `PiApp_API_34_AOSP` (headless AOSP ATD for transport). Both need debug-only fake auth because no credential provider is bundled; only physical Android 14+ with Bitwarden can prove the full production no-Google path.

## Voice: Groq `whisper-large-v3-turbo`

Primary sources:

- API reference — <https://console.groq.com/docs/api-reference>
- Speech to text — <https://console.groq.com/docs/speech-to-text>
- Rate limits — <https://console.groq.com/docs/rate-limits>
- Model card, turbo — <https://console.groq.com/docs/model/whisper-large-v3-turbo>
- Model card, non-turbo — <https://console.groq.com/docs/model/whisper-large-v3>

Verified facts:

- Groq positions the model for real-time transcription, but the documented `/openai/v1/audio/transcriptions` request parameters do **not** include `stream`. It accepts an uploaded file or a `url` and returns a completed transcript. True partial-token/SSE transcription streaming is not documented or supported for this endpoint. Live audio must be sent as short sequential chunks and stitched client-side.
- File size: 25 MB on Free, 100 MB on Dev. Direct attachments cap at 25 MB; larger media must use the `url` parameter or be chunked.
- Duration/pricing: minimum accepted audio 0.01 s; minimum billed duration 10 s; turbo is currently `$0.04` per billed audio hour. The model is optimized for 30-second segments, so roughly 10-30 s overlapping chunks give the lowest-latency streaming-like behavior.
- Formats: `flac`, `mp3`, `mp4`, `mpeg`, `mpga`, `m4a`, `ogg`, `wav`, `webm`. Only the first track of a multi-track file is transcribed.
- Rate limits (Free): 20 RPM, 2,000 RPD, 7,200 audio seconds/hour, 28,800 audio seconds/day, organization-wide. Dev-plan listings show 400 RPM / 400K audio seconds per hour for turbo.
- Capabilities: multilingual, 99+ languages, **transcription only** — unlike `whisper-large-v3`, turbo does not support audio translation.

Project consequences:

- No one quota is always binding. A durable pre-send ledger enforces conservative 90% defaults for all published Free windows: 18 RPM, 1,800 RPD, 6,480 audio seconds/hour, and 25,920/day. Encoded overlap and every retry attempt count.
- Every sub-10 s attempt rounds up to 10 billed seconds. `billedSeconds / 3600 × $0.04` is displayed as an upper bound and hard-capped by default at `$0.25`/UTC day and `$2`/UTC month.
- 429 handling honors valid `Retry-After` up to 120 s, otherwise uses full-jitter exponential delay (1 s base, 30 s cap), with three retries after the initial attempt. A longer server delay stops without retrying early.
- Overlapping chunks require de-duplication at seams; ordering and duplicate suppression must hold under retry.
- The key stays on the Mac (`~/.groq_key`, present locally, 57 bytes, never committed), so audio flows phone → Mac → Groq and text returns the same way. The phone never holds the key.
- No translation support means language handling is transcribe-only; any translation feature would need a different model.

## Transport options

Primary sources:

- Headscale — <https://github.com/juanfont/headscale>
- Headscale DERP reference — <https://headscale.net/0.27.1/ref/derp/>
- WireGuard installation — <https://www.wireguard.com/install/>
- Tailscale connection types — <https://tailscale.com/docs/reference/connection-types>
- Tailscale DERP routing troubleshooting — <https://tailscale.com/docs/reference/troubleshooting/network-configuration/derp-routing>

Verified facts:

- Tailscale attempts a direct UDP peer-to-peer path first; on failure it can use a peer relay and then DERP. All modes stay WireGuard end-to-end encrypted, but relays generally have worse latency and throughput than direct paths. `tailscale ping` reports whether a connection is direct or DERP-relayed.
- Headscale is an open-source self-hosted implementation of the Tailscale control server, aimed at personal and small self-hosted networks.
- Headscale's embedded DERP can provide a self-hosted fallback relay; it needs public HTTPS/TCP 443 and STUN/UDP 3478.
- Plain WireGuard is leanest when a public IP or port forward exists, but leaves key management, peer configs, endpoint changes, and mobile NAT/keepalive behavior to the operator. Official clients exist for iOS and macOS.

Comparison as researched:

| Option | Best for | Downsides |
|---|---|---|
| Plain WireGuard | Public IP / port-forwarded host, minimal setup | Manual keys, peer configs, endpoint changes, mobile NAT/keepalive |
| Tailscale, hosted control plane | Easiest and most reliable | Control plane and relay infrastructure not self-hosted |
| Headscale + Tailscale clients | Best self-hosted compromise | Must operate and update a public control server; targets personal/small deployments |
| Own relay only | Predictable fallback, strict NAT bypass | Adds latency, another server to operate, and does not replace coordination/NAT traversal |

Important nuance recorded verbatim from the research: a relay is a fallback, not the normal path. If "private" only means the traffic is unreadable to third parties, ordinary Tailscale suffices because its relay cannot decrypt WireGuard payloads. If it means no third-party coordination or relay at all, Headscale with embedded DERP is required.

Note: the surveyed comparison was framed around iPhone/macOS clients; the mechanisms are platform-independent but the Android client story must be validated separately.

Project consequence: no VPN assumption. Direct LAN uses mTLS; remote relay byte-splices one-use WSS data sockets carrying inner TLS 1.3, so confidentiality is independent of relay outer WSS.

## Infrastructure

Any created cloud resource is Terraform-managed per AGENTS.md, with documented cost and destroy steps per R10. Two candidate always-on needs have surfaced from research: the passkey RP host serving `.well-known/assetlinks.json`, and the push/relay endpoint. These are separable, and a static-hosting-plus-minimal-endpoint split is the cheapest shape identified so far. No resources have been created and no provider/region has been selected in this research round.

## Testing implications

Derived from the verified facts above; R11 enumerates the required suites.

- **Protocol contract:** LF-only JSONL framing fixtures including `U+2028`/`U+2029` inside JSON strings; unknown event and `extension_ui_request` types preserved; `agent_end willRetry` vs `agent_settled` sequencing.
- **Notification:** UnifiedPush registration/opaque ntfy payload, distributor absence, app-open catch-up, default-image/AOSP-ATD no-Google lanes with debug fake auth, Doze/OEM physical behavior; optional FCM priority/downgrade tests stay non-blocking.
- **Voice:** chunk stitching/order/dedupe; 25 MB/format failures; durable RPM/RPD/ASH/ASD boundaries; 429 header/jitter/retry exhaustion; encoded overlap/retry billing; daily/monthly budget rollover.
- **Transport/fault:** direct path, relayed path, mid-stream network loss, and reconnect catch-up via append-order `get_entries since` plus independent `leafId` validation, including backward branch moves.
- **Security:** deployed `assetlinks.json` fetch assertions, replay rejection, revocation, and log redaction.
- **Performance:** Macrobenchmark `FrameTimingMetric` on a release build with Baseline Profile, per `mobile-ux.md`.
- **Manual:** emulator/device passkey ceremony with Bitwarden, real voice capture, and backgrounded completion notification.

## Final choices

- Push is opaque wake only; detail is fetched and rendered after unlock.
- VAD prefers 8 s and forces 12 s, one request in flight/two queued.
- App needs no VPN: direct LAN is opportunistic; remote uses standing Mac control WSS plus one-use data rendezvous and inner TLS.
- GitHub Pages serves RP/DAL; one YC VM hosts relay+ntfy. Relay persists only route public keys/revocation.
- Free/Dev Groq plan remains operational, but the app defaults to conservative Free RPM/RPD/ASH/ASD values and requires explicit current limits to raise them.
