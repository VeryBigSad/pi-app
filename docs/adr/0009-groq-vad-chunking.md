# ADR-0009: VAD-driven 8–12-second Groq chunks

Status: Accepted
Date: 2026-08-09

## Context

Groq `whisper-large-v3-turbo` is batch-only and bills every request for at least ten audio seconds. It currently costs `$0.04` per billed audio hour. Published free-plan limits are organization-wide: 20 RPM, 2,000 RPD, 7,200 audio seconds/hour, and 28,800 audio seconds/day; the account can differ. Roughly ten-second chunks use about 6 RPM per stream, but RPM is not universally binding: daily requests and audio-second windows can bind during long use. Short windows multiply billed duration; long chunks harm latency.

## Decision

Capture 16 kHz mono signed PCM only while foregrounded. Mac VAD keeps 300 ms pre-roll, starts on speech, prefers silence after 8 seconds, forces a cut at 12 seconds, and overlaps about 500 ms. One request is in flight, at most two queue, responses emit strictly in order, and stop flushes the final speech fragment. Merge by timestamps then normalized seam matching.

A durable Mac ledger reserves before every upload attempt, including retries. It counts request attempts, encoded audio seconds including overlap, and conservative billed seconds `max(encodedDuration, 10s)`. Defaults retain 10% headroom: 18 RPM, 1,800 RPD, 6,480 audio seconds/hour, and 25,920 audio seconds/day. The user may change them only to explicit current organization limits. Sliding windows survive restart. Any exhausted window stops capture before upload and shows its reset.

Estimated upper-bound spend is `reservedBilledSeconds / 3600 * $0.04`. Default hard budgets are `$0.25` per UTC day and `$2.00` per UTC month; explicit settings can change them. The app displays attempts, encoded/billed duration, estimate, limits, and reset times without audio or transcript.

For HTTP 429, parse `Retry-After` seconds/date. Wait by monotonic timer when it is at most 120 seconds; a longer value stops and reports the server reset without retrying early. Without a valid header, use full-jitter exponential delays with 1-second base and 30-second cap. Retry only 429/transient transport/5xx, at most three retries after the first attempt, using the same chunk ID; each attempt reserves limits and budget. Backlog over 30 seconds stops capture visibly. Ordered emission suppresses late/duplicate results.

## Rejected

- Claimed token streaming: endpoint does not support it.
- 2–4-second rolling uploads: rate/billing waste.
- Enforcing only RPM: ignores RPD and audio-second windows.
- Unbounded or immediate 429 retry: causes storms and hidden cost.
- Key on Android: violates credential boundary.
- Auto-send transcript: unsafe UX.

## Consequences

Partials arrive roughly every ten seconds. Transcript remains an editable draft. Silence, all quota windows, UTC budget rollover, restart persistence, 429 `Retry-After`/jitter/exhaustion, overlap/order/final-flush, exact billing arithmetic, and temp cleanup are mandatory tests. `~/.groq_key` stays mode `0600` on Mac.
