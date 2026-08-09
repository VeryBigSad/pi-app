# ADR-0002: Use a bounded raw-event JSON/binary protocol

Status: Accepted
Date: 2026-08-09

## Context

Pi’s full event and extension surface changes over time. Duplicating it into a second exhaustive schema loses unknown behavior. Terminal, images, artifacts, and PCM need binary transfer and hard allocation bounds.

## Decision

Use a 12-byte `PIMB` network-order header with JSON, blob, PCM, and terminal kinds. Carry exact Pi-line `rawJson` UTF-8 (excluding LF), SHA-256/size, and parsed reducer projection inline up to 128 KiB; larger exact bytes use digest/size references plus bounded projection. Cap JSON at 256 KiB, frames 1 MiB, chunks 64 KiB, batches 128 events/256 KiB, queues 512 frames/8 MiB. Retain unknown fields in exact bytes. Freeze schemas and cross-language fixtures, including prompt-image ready/ref/orphan flow.

## Rejected

- Protobuf mirror of Pi semantics: high drift and unknown-event loss.
- Raw unbounded JSONL over the network: unsuitable for binary and allocation safety.
- Relay parsing the inner protocol: violates content-blind boundary.

## Consequences

Kotlin and TypeScript codecs, pagination, binary stream lifecycle, sequence checks, and fuzz tests are mandatory. Additive minor evolution is possible; incompatible majors fail visibly.
