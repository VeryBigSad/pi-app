import { execFile } from "node:child_process";
import { chmodSync, mkdtempSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { GroqTranscriber, VoiceError, parseRetryAfter } from "../src/voice/groq-client.js";
import { DEFAULT_VOICE_LIMITS, VoiceRateLedger, type VoiceLimits } from "../src/voice/rate-ledger.js";
import { TranscriptionQueue } from "../src/voice/transcription-queue.js";

function path(name: string): string {
  return join(mkdtempSync(join(tmpdir(), "pi-mobile-voice-")), name);
}

function limits(overrides: Partial<VoiceLimits>): VoiceLimits {
  return { ...DEFAULT_VOICE_LIMITS, ...overrides };
}

describe("durable voice accounting", () => {
  it("bills every attempt for at least ten seconds and persists", () => {
    const database = path("voice.db");
    let ledger = new VoiceRateLedger(database);
    const first = ledger.reserve("chunk", 1.5, 1_000);
    expect(first).toMatchObject({ allowed: true, billedSeconds: 10 });
    ledger.close();
    ledger = new VoiceRateLedger(database);
    expect(ledger.totals(2_000)).toMatchObject({ attempts: 1, encodedSeconds: 1.5, billedSeconds: 10 });
    ledger.close();
  });

  it("opens a rolling limit only at its exact boundary", () => {
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ requestsPerMinute: 1 }));
    expect(ledger.reserve("a", 1, 1_000).allowed).toBe(true);
    expect(ledger.reserve("b", 1, 60_999)).toEqual({ allowed: false, code: "VOICE_RPM_LIMIT", resetAtMs: 61_000 });
    expect(ledger.reserve("c", 1, 61_000).allowed).toBe(true);
    ledger.close();
  });

  it("enforces UTC budget boundaries", () => {
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ usdPerUtcDay: 0.00012, usdPerUtcMonth: 1 }));
    const beforeMidnight = Date.UTC(2026, 0, 1, 23, 59, 59);
    expect(ledger.reserve("a", 10, beforeMidnight).allowed).toBe(true);
    expect(ledger.reserve("b", 10, beforeMidnight + 1).allowed).toBe(false);
    expect(ledger.reserve("c", 10, Date.UTC(2026, 0, 2)).allowed).toBe(true);
    ledger.close();
  });
});

describe("voice ledger clock robustness", () => {
  const base = Date.UTC(2026, 5, 10, 12);
  const roomy = {
    requestsPerMinute: 100,
    requestsPerDay: 100,
    audioSecondsPerHour: 100,
    audioSecondsPerDay: 100,
    usdPerUtcDay: 100,
    usdPerUtcMonth: 100,
  };
  const attemptCost = 10 / 3_600 * DEFAULT_VOICE_LIMITS.usdPerBilledHour;

  it("fills every window and budget, then denies after wall-clock rollback without losing cost", () => {
    const cases: { readonly code: string; readonly overrides: Partial<VoiceLimits> }[] = [
      { code: "VOICE_RPM_LIMIT", overrides: { ...roomy, requestsPerMinute: 2 } },
      { code: "VOICE_RPD_LIMIT", overrides: { ...roomy, requestsPerDay: 2 } },
      { code: "VOICE_ASH_LIMIT", overrides: { ...roomy, audioSecondsPerHour: 2 } },
      { code: "VOICE_ASD_LIMIT", overrides: { ...roomy, audioSecondsPerDay: 2 } },
      { code: "VOICE_DAILY_BUDGET", overrides: { ...roomy, usdPerUtcDay: 2 * attemptCost } },
      { code: "VOICE_MONTHLY_BUDGET", overrides: { ...roomy, usdPerUtcMonth: 2 * attemptCost } },
    ];
    for (const [index, scenario] of cases.entries()) {
      const ledger = new VoiceRateLedger(path(`voice-${String(index)}.db`), limits(scenario.overrides));
      expect(ledger.reserve("a", 1, base).allowed).toBe(true);
      expect(ledger.reserve("b", 1, base + 1).allowed).toBe(true);
      expect(ledger.reserve("rollback", 1, base - 40 * 24 * 60 * 60 * 1_000)).toMatchObject({ allowed: false, code: scenario.code });
      expect(ledger.totals(base - 1)).toMatchObject({ attempts: 2, encodedSeconds: 2, billedSeconds: 20 });
      expect(ledger.totals(base).estimatedUsd).toBeCloseTo(2 * attemptCost, 12);
      ledger.close();
    }
  });

  it("persists the effective-time high-water mark across restart", () => {
    const database = path("voice.db");
    let ledger = new VoiceRateLedger(database, limits({ ...roomy, requestsPerMinute: 1 }));
    expect(ledger.reserve("fill", 1, base).allowed).toBe(true);
    ledger.close();
    ledger = new VoiceRateLedger(database, limits({ ...roomy, requestsPerMinute: 1 }));
    expect(ledger.reserve("rollback", 1, base - 60_000)).toEqual({ allowed: false, code: "VOICE_RPM_LIMIT", resetAtMs: base + 60_000 });
    expect(ledger.totals(base - 60_000)).toMatchObject({ attempts: 1, encodedSeconds: 1, billedSeconds: 10 });
    ledger.close();
  });

  it("serializes concurrent rollback reservations across database connections", async () => {
    const database = path("voice.db");
    const configured = limits({ ...roomy, requestsPerMinute: 1 });
    const ledger = new VoiceRateLedger(database, configured);
    expect(ledger.reserve("fill", 1, base).allowed).toBe(true);
    ledger.close();
    const results = await Promise.all(Array.from({ length: 4 }, (_, index) => reserveInChild(database, configured, `rollback-${String(index)}`, base - 60_000)));
    expect(results).toEqual(Array.from({ length: 4 }, () => ({ allowed: false, code: "VOICE_RPM_LIMIT", resetAtMs: base + 60_000 })));
    const reopened = new VoiceRateLedger(database, configured);
    expect(reopened.totals(base - 60_000).attempts).toBe(1);
    reopened.close();
  });

  it("pins a forward jump so a later correction cannot reopen limits", () => {
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ ...roomy, requestsPerMinute: 1 }));
    const forward = base + 40 * 24 * 60 * 60 * 1_000;
    expect(ledger.reserve("before", 1, base).allowed).toBe(true);
    expect(ledger.reserve("forward", 1, forward).allowed).toBe(true);
    expect(ledger.reserve("corrected", 1, base + 61_000)).toEqual({ allowed: false, code: "VOICE_RPM_LIMIT", resetAtMs: forward + 60_000 });
    expect(ledger.totals(base).attempts).toBe(1);
    ledger.close();
  });
});

describe("Groq transport", () => {
  it("reserves every retry and follows bounded Retry-After", async () => {
    const key = path("groq-key");
    writeFileSync(key, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    const ledger = new VoiceRateLedger(path("voice.db"));
    const waits: number[] = [];
    let requests = 0;
    const client = new GroqTranscriber({
      ledger,
      keyPath: key,
      nowMs: () => 1_000,
      sleep: (milliseconds) => { waits.push(milliseconds); return Promise.resolve(); },
      fetch: () => {
        requests += 1;
        return Promise.resolve(requests === 1
          ? new Response("", { status: 429, headers: { "retry-after": "2" } })
          : Response.json({ text: "hello" }));
      },
    });
    await expect(client.transcribe("chunk", Buffer.alloc(32_000))).resolves.toBe("hello");
    expect(waits).toEqual([2_000]);
    expect(ledger.totals(1_000)).toMatchObject({ attempts: 2, encodedSeconds: 2, billedSeconds: 20 });
    ledger.close();
  });

  it("rejects permissive key files before reserving quota", async () => {
    const key = path("groq-key");
    writeFileSync(key, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    chmodSync(key, 0o644);
    const ledger = new VoiceRateLedger(path("voice.db"));
    const client = new GroqTranscriber({ ledger, keyPath: key, fetch: () => Promise.resolve(Response.json({ text: "bad" })) });
    await expect(client.transcribe("chunk", Buffer.alloc(32_000))).rejects.toMatchObject({ code: "VOICE_KEY_PERMISSIONS" });
    expect(ledger.totals(Date.now()).attempts).toBe(0);
    ledger.close();
  });

  it("rejects symlinked, non-0600, and oversized key files before quota", async () => {
    const target = path("target-key");
    writeFileSync(target, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    const symlink = path("linked-key");
    symlinkSync(target, symlink);
    const strict = path("strict-key");
    writeFileSync(strict, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    chmodSync(strict, 0o400);
    const oversized = path("oversized-key");
    writeFileSync(oversized, "x".repeat(4_097), { mode: 0o600 });
    const ledger = new VoiceRateLedger(path("voice.db"));
    for (const keyPath of [symlink, strict, oversized]) {
      const client = new GroqTranscriber({ ledger, keyPath, fetch: () => Promise.resolve(Response.json({ text: "bad" })) });
      await expect(client.transcribe(keyPath, Buffer.alloc(32_000))).rejects.toBeInstanceOf(VoiceError);
    }
    expect(ledger.totals(Date.now()).attempts).toBe(0);
    ledger.close();
  });

  it("parses both Retry-After forms", () => {
    expect(parseRetryAfter("1.5", 0)).toBe(1_500);
    expect(parseRetryAfter("Thu, 01 Jan 1970 00:00:05 GMT", 2_000)).toBe(3_000);
    expect(parseRetryAfter("invalid", 0)).toBeUndefined();
  });
});

describe("transcription queue", () => {
  it("keeps one request active, queues two, and rejects overflow", async () => {
    const releases: (() => void)[] = [];
    let active = 0;
    let maximum = 0;
    const queue = new TranscriptionQueue({
      transcribe: async (id) => {
        active += 1;
        maximum = Math.max(maximum, active);
        await new Promise<void>((resolve) => releases.push(resolve));
        active -= 1;
        return id;
      },
    });
    const one = queue.submit("one", Buffer.alloc(2));
    const two = queue.submit("two", Buffer.alloc(2));
    const three = queue.submit("three", Buffer.alloc(2));
    await expect(queue.submit("four", Buffer.alloc(2))).rejects.toMatchObject({ code: "VOICE_QUEUE_FULL" });
    releases.shift()?.();
    await expect(one).resolves.toBe("one");
    await Promise.resolve();
    releases.shift()?.();
    await expect(two).resolves.toBe("two");
    await Promise.resolve();
    releases.shift()?.();
    await expect(three).resolves.toBe("three");
    expect(maximum).toBe(1);
  });

  it("cancels active and queued work and suppresses late active completion", async () => {
    let finishActive: ((text: string) => void) | undefined;
    const started: string[] = [];
    const queue = new TranscriptionQueue({
      transcribe: (id) => {
        started.push(id);
        return new Promise<string>((resolve) => { finishActive = resolve; });
      },
    });
    const active = queue.submit("active", Buffer.alloc(2));
    const queued = queue.submit("queued", Buffer.alloc(2));
    queue.cancelAll();
    await expect(active).rejects.toMatchObject({ code: "VOICE_CANCELED" });
    await expect(queued).rejects.toMatchObject({ code: "VOICE_CANCELED" });
    finishActive?.("late");
    await Promise.resolve();
    await Promise.resolve();
    expect(started).toEqual(["active"]);
    expect(queue.size).toBe(0);
  });
});

function reserveInChild(database: string, configured: VoiceLimits, chunkId: string, nowMs: number): Promise<unknown> {
  const moduleUrl = new URL("../src/voice/rate-ledger.ts", import.meta.url).href;
  const script = `import { VoiceRateLedger } from ${JSON.stringify(moduleUrl)}; const ledger = new VoiceRateLedger(process.argv[1], JSON.parse(process.argv[2])); const result = ledger.reserve(process.argv[3], 1, Number(process.argv[4])); ledger.close(); process.stdout.write(JSON.stringify(result));`;
  return new Promise((resolve, reject) => {
    execFile(process.execPath, ["--import", "tsx", "--input-type=module", "--eval", script, database, JSON.stringify(configured), chunkId, String(nowMs)], (error, stdout, stderr) => {
      if (error !== null) {
        reject(new Error(`voice ledger child failed: ${stderr}`, { cause: error }));
        return;
      }
      resolve(JSON.parse(stdout) as unknown);
    });
  });
}
