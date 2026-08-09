# Research: networking, notifications, voice, infrastructure, testing

Last updated: 2026-08-09

## Decisive constraints

1. **A background socket is not a reliable push mechanism.** Downstream wakeups must come from FCM; sockets are for short, foreground-visible work windows.
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

Recommended architecture, per the same sources: backend → high-priority FCM only for urgent user-visible events → notification or expedited work → short authenticated HTTPS or WebSocket fetch for state → disconnect. Normal-priority FCM plus WorkManager covers silent syncs.

Project-specific consequence: the Mac bridge observes `agent_settled` (see `pi-integration.md`) and requests a high-priority FCM send for completion and for blocking dialog requests. The phone's live socket exists only while the session UI is foreground, or briefly after a wake, and reconnect performs catch-up via `get_entries since` + `leafId`. R8's requirement to document delivery limits is satisfied by recording the Doze, priority-downgrade, and 6-hour `dataSync` facts above.

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
- Duration: minimum accepted audio 0.01 s; minimum billed duration 10 s. The model is optimized for 30-second segments, so roughly 10-30 s overlapping chunks give the lowest-latency streaming-like behavior.
- Formats: `flac`, `mp3`, `mp4`, `mpeg`, `mpga`, `m4a`, `ogg`, `wav`, `webm`. Only the first track of a multi-track file is transcribed.
- Rate limits (Free): 20 RPM, 2,000 RPD, 7,200 audio seconds/hour, 28,800 audio seconds/day, organization-wide. Dev-plan listings show 400 RPM / 400K audio seconds per hour for turbo.
- Capabilities: multilingual, 99+ languages, **transcription only** — unlike `whisper-large-v3`, turbo does not support audio translation.

Project consequences:

- The 10 s minimum billed duration penalizes very short chunks; chunk sizing is an explicit cost/latency tradeoff, not a free parameter.
- Overlapping chunks require de-duplication at seams; ordering must be preserved because R9 demands ordered partial text.
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

Project consequence: the app must not assume a VPN is present. R10 asks for direct/local operation where feasible plus a minimal, Terraform-managed, content-blind relay. The relay therefore carries opaque frames and the application-layer envelope from `android-security.md` provides confidentiality regardless of which path is used.

## Infrastructure

Any created cloud resource is Terraform-managed per AGENTS.md, with documented cost and destroy steps per R10. Two candidate always-on needs have surfaced from research: the passkey RP host serving `.well-known/assetlinks.json`, and the push/relay endpoint. These are separable, and a static-hosting-plus-minimal-endpoint split is the cheapest shape identified so far. No resources have been created and no provider/region has been selected in this research round.

## Testing implications

Derived from the verified facts above; R11 enumerates the required suites.

- **Protocol contract:** LF-only JSONL framing fixtures including `U+2028`/`U+2029` inside JSON strings; unknown event and `extension_ui_request` types preserved; `agent_end willRetry` vs `agent_settled` sequencing.
- **Notification:** high-priority FCM path with `getPriority()` assertions, downgrade handling that avoids `ForegroundServiceStartNotAllowedException`, Doze-window behavior, and `Service.onTimeout()` for any `dataSync` usage.
- **Voice:** chunk boundary stitching, ordering under out-of-order responses, overlap de-duplication, 25 MB and rate-limit rejection paths, unsupported-format rejection, and the 10 s minimum-billed-duration cost assertion.
- **Transport/fault:** direct path, relayed path, mid-stream network loss, and reconnect catch-up via `get_entries since` + `leafId`.
- **Security:** deployed `assetlinks.json` fetch assertions, replay rejection, revocation, and log redaction.
- **Performance:** Macrobenchmark `FrameTimingMetric` on a release build with Baseline Profile, per `mobile-ux.md`.
- **Manual:** emulator/device passkey ceremony with Bitwarden, real voice capture, and backgrounded completion notification.

## Unresolved tradeoffs

- **Notification content vs privacy.** Specific notification text ("Tests failed") is better UX but leaks session detail to the lockscreen and to FCM. Whether FCM payloads carry only an opaque wake signal, with text fetched after unlock, is undecided.
- **Chunk length.** 10-30 s chunks trade latency against the 10 s minimum billed duration and RPM limits; the operating point is unchosen.
- **VPN vs relay as the default path.** Requiring Tailscale/Headscale improves latency and reduces server cost but adds user setup; a relay-first default is simpler but slower and needs an always-on host.
- **Relay vs RP consolidation.** Merging the passkey RP and the relay into one host is cheaper; keeping them separate reduces blast radius. Not decided.
- **Free vs Dev Groq plan.** Free-tier 20 RPM and 7,200 audio seconds/hour may be tight for habitual voice use; plan selection is deferred.
