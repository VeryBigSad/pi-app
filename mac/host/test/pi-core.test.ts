import { describe, expect, it } from "vitest";
import { DeltaAssembler } from "../src/pi/delta-assembler.js";
import { StrictLfJsonFramer } from "../src/pi/lf-json-framer.js";
import { LifecycleTracker } from "../src/pi/lifecycle.js";

function cursor(sequence: number) {
  return { streamEpoch: "550e8400-e29b-41d4-a716-446655440000", sequence: String(sequence) };
}

describe("StrictLfJsonFramer", () => {
  it("splits only LF and preserves Unicode separators", () => {
    const framer = new StrictLfJsonFramer();
    const raw = Buffer.from('{"text":"a b c"}\r\n{"type":"ok"}\n');
    const first = framer.push(raw.subarray(0, 8));
    const second = framer.push(raw.subarray(8));
    expect(first).toEqual([]);
    expect(second).toHaveLength(2);
    expect(second[0]?.rawJson).toBe('{"text":"a b c"}');
    expect(second[1]?.value).toEqual({ type: "ok" });
    framer.end();
  });

  it("faults permanently after malformed JSON", () => {
    const framer = new StrictLfJsonFramer();
    expect(() => framer.push(Buffer.from("{]\n"))).toThrow(/valid JSON/);
    expect(() => framer.push(Buffer.from("{}\n"))).toThrow(/no longer writable/);
  });

  it("rejects an unterminated record", () => {
    const framer = new StrictLfJsonFramer();
    framer.push(Buffer.from("{}"));
    expect(() => framer.end()).toThrow(/middle of a record/);
  });
});

describe("DeltaAssembler", () => {
  it("uses content-only ends then authoritative final metadata", () => {
    const assembler = new DeltaAssembler();
    assembler.apply({ type: "message_start", message: { role: "assistant", content: [] } }, cursor(1));
    assembler.apply({
      type: "message_update",
      assistantMessageEvent: { type: "thinking_start", contentIndex: 0 },
    }, cursor(2));
    assembler.apply({
      type: "message_update",
      assistantMessageEvent: { type: "thinking_delta", contentIndex: 0, delta: "draft" },
    }, cursor(3));
    assembler.apply({
      type: "message_update",
      assistantMessageEvent: { type: "thinking_end", contentIndex: 0, content: "final" },
    }, cursor(4));
    expect(assembler.provisionalMessage()?.["content"]).toEqual([{ type: "thinking", thinking: "final" }]);
    const result = assembler.apply({
      type: "message_end",
      message: {
        role: "assistant",
        content: [{ type: "thinking", thinking: "final", thinkingSignature: "signed", redacted: true }],
      },
    }, cursor(5));
    expect(result).toEqual({
      kind: "committed",
      message: {
        role: "assistant",
        content: [{ type: "thinking", thinking: "final", thinkingSignature: "signed", redacted: true }],
      },
    });
  });

  it("forces recovery on a sequence gap", () => {
    const assembler = new DeltaAssembler();
    assembler.apply({ type: "message_start", message: { role: "assistant", content: [] } }, cursor(1));
    expect(() => assembler.apply({ type: "agent_start" }, cursor(3))).toThrow(/not contiguous/);
    expect(assembler.needsRecovery()).toBe(true);
  });
});

describe("LifecycleTracker", () => {
  it("settles only on agent_settled and deduplicates the cursor", () => {
    const tracker = new LifecycleTracker({ nowMs: () => 42 });
    expect(tracker.apply({ type: "agent_end", willRetry: true }, cursor(1))).toBeUndefined();
    expect(tracker.apply({ type: "auto_retry_start" }, cursor(2))).toBeUndefined();
    expect(tracker.apply({ type: "agent_settled" }, cursor(3))).toEqual({
      key: `${cursor(3).streamEpoch}:3`,
      streamEpoch: cursor(3).streamEpoch,
      sequence: "3",
      settledAtMs: 42,
    });
    expect(tracker.apply({ type: "agent_settled" }, cursor(3))).toBeUndefined();
  });
});
