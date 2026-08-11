import { chmodSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { randomUUID } from "node:crypto";
import Database from "better-sqlite3";

export interface VoiceLimits {
  readonly requestsPerMinute: number;
  readonly requestsPerDay: number;
  readonly audioSecondsPerHour: number;
  readonly audioSecondsPerDay: number;
  readonly usdPerUtcDay: number;
  readonly usdPerUtcMonth: number;
  readonly usdPerBilledHour: number;
}

export const DEFAULT_VOICE_LIMITS: VoiceLimits = {
  requestsPerMinute: 18,
  requestsPerDay: 1_800,
  audioSecondsPerHour: 6_480,
  audioSecondsPerDay: 25_920,
  usdPerUtcDay: 0.25,
  usdPerUtcMonth: 2,
  usdPerBilledHour: 0.04,
};

export type VoiceReservation =
  | {
      readonly allowed: true;
      readonly attemptId: string;
      readonly encodedSeconds: number;
      readonly billedSeconds: number;
      readonly estimatedUsd: number;
    }
  | {
      readonly allowed: false;
      readonly code: "VOICE_RPM_LIMIT" | "VOICE_RPD_LIMIT" | "VOICE_ASH_LIMIT" | "VOICE_ASD_LIMIT" | "VOICE_DAILY_BUDGET" | "VOICE_MONTHLY_BUDGET";
      readonly resetAtMs: number;
    };

const RETENTION_MS = 32 * 24 * 60 * 60 * 1_000;

interface AttemptRow {
  attempted_at_ms: number;
  encoded_seconds: number;
  billed_seconds: number;
  estimated_usd: number;
}

export class VoiceRateLedger {
  private readonly database: Database.Database;
  readonly limits: VoiceLimits;

  constructor(path: string, limits: VoiceLimits = DEFAULT_VOICE_LIMITS) {
    validateLimits(limits);
    this.limits = limits;
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    this.database = new Database(path);
    chmodSync(path, 0o600);
    this.database.pragma("journal_mode = WAL");
    this.database.pragma("synchronous = FULL");
    this.database.pragma("busy_timeout = 5000");
    this.database.exec(`
      CREATE TABLE IF NOT EXISTS voice_attempts (
        attempt_id TEXT PRIMARY KEY,
        chunk_id TEXT NOT NULL,
        attempted_at_ms INTEGER NOT NULL,
        encoded_seconds REAL NOT NULL,
        billed_seconds REAL NOT NULL,
        estimated_usd REAL NOT NULL
      ) STRICT;
      CREATE INDEX IF NOT EXISTS voice_attempts_time ON voice_attempts(attempted_at_ms);
    `);
  }

  close(): void {
    this.database.close();
  }

  reserve(chunkId: string, encodedSeconds: number, nowMs: number): VoiceReservation {
    if (chunkId.length === 0 || chunkId.length > 128) throw new TypeError("chunkId is invalid");
    if (!Number.isFinite(encodedSeconds) || encodedSeconds <= 0 || encodedSeconds > 30) throw new TypeError("encodedSeconds is invalid");
    if (!Number.isSafeInteger(nowMs) || nowMs < 0) throw new TypeError("nowMs is invalid");
    const billedSeconds = Math.max(encodedSeconds, 10);
    const estimatedUsd = billedSeconds / 3_600 * this.limits.usdPerBilledHour;

    return this.database.transaction((): VoiceReservation => {
      const maxSeen = (this.database.prepare("SELECT MAX(attempted_at_ms) AS value FROM voice_attempts").get() as { value: number | null }).value ?? nowMs;
      const purgeBeforeMs = Math.min(nowMs, maxSeen) - RETENTION_MS;
      this.database.prepare("DELETE FROM voice_attempts WHERE attempted_at_ms < ?").run(purgeBeforeMs);
      const rows = this.database.prepare(`
        SELECT attempted_at_ms, encoded_seconds, billed_seconds, estimated_usd
        FROM voice_attempts WHERE attempted_at_ms > ? AND attempted_at_ms <= ? ORDER BY attempted_at_ms ASC
      `).all(nowMs - RETENTION_MS, nowMs) as AttemptRow[];

      const checks: Array<VoiceReservation | undefined> = [
        countLimit(rows, nowMs, 60_000, this.limits.requestsPerMinute, "VOICE_RPM_LIMIT"),
        countLimit(rows, nowMs, 24 * 60 * 60 * 1_000, this.limits.requestsPerDay, "VOICE_RPD_LIMIT"),
        sumLimit(rows, nowMs, 60 * 60 * 1_000, "encoded_seconds", encodedSeconds, this.limits.audioSecondsPerHour, "VOICE_ASH_LIMIT"),
        sumLimit(rows, nowMs, 24 * 60 * 60 * 1_000, "encoded_seconds", encodedSeconds, this.limits.audioSecondsPerDay, "VOICE_ASD_LIMIT"),
        budgetLimit(rows, nowMs, estimatedUsd, this.limits.usdPerUtcDay, false),
        budgetLimit(rows, nowMs, estimatedUsd, this.limits.usdPerUtcMonth, true),
      ];
      const denied = checks.find((value) => value !== undefined);
      if (denied !== undefined) return denied;

      const attemptId = randomUUID();
      this.database.prepare(`
        INSERT INTO voice_attempts(attempt_id, chunk_id, attempted_at_ms, encoded_seconds, billed_seconds, estimated_usd)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(attemptId, chunkId, nowMs, encodedSeconds, billedSeconds, estimatedUsd);
      return { allowed: true, attemptId, encodedSeconds, billedSeconds, estimatedUsd };
    })();
  }

  totals(nowMs: number): { readonly attempts: number; readonly encodedSeconds: number; readonly billedSeconds: number; readonly estimatedUsd: number } {
    const start = startOfUtcMonth(nowMs);
    const row = this.database.prepare(`
      SELECT COUNT(*) attempts,
             COALESCE(SUM(encoded_seconds), 0) encodedSeconds,
             COALESCE(SUM(billed_seconds), 0) billedSeconds,
             COALESCE(SUM(estimated_usd), 0) estimatedUsd
      FROM voice_attempts WHERE attempted_at_ms >= ? AND attempted_at_ms <= ?
    `).get(start, nowMs) as { attempts: number; encodedSeconds: number; billedSeconds: number; estimatedUsd: number };
    return row;
  }
}

function countLimit(
  rows: AttemptRow[],
  nowMs: number,
  windowMs: number,
  limit: number,
  code: "VOICE_RPM_LIMIT" | "VOICE_RPD_LIMIT",
): VoiceReservation | undefined {
  const active = rows.filter((row) => row.attempted_at_ms > nowMs - windowMs);
  return active.length >= limit ? { allowed: false, code, resetAtMs: requireRow(active[0]).attempted_at_ms + windowMs } : undefined;
}

function sumLimit(
  rows: AttemptRow[],
  nowMs: number,
  windowMs: number,
  field: "encoded_seconds",
  addition: number,
  limit: number,
  code: "VOICE_ASH_LIMIT" | "VOICE_ASD_LIMIT",
): VoiceReservation | undefined {
  const active = rows.filter((row) => row.attempted_at_ms > nowMs - windowMs);
  let total = active.reduce((sum, row) => sum + row[field], 0);
  if (total + addition <= limit) return undefined;
  for (const row of active) {
    total -= row[field];
    if (total + addition <= limit) return { allowed: false, code, resetAtMs: row.attempted_at_ms + windowMs };
  }
  return { allowed: false, code, resetAtMs: nowMs + windowMs };
}

function budgetLimit(rows: AttemptRow[], nowMs: number, addition: number, limit: number, monthly: boolean): VoiceReservation | undefined {
  const start = monthly ? startOfUtcMonth(nowMs) : startOfUtcDay(nowMs);
  const total = rows.filter((row) => row.attempted_at_ms >= start).reduce((sum, row) => sum + row.estimated_usd, 0);
  if (total + addition <= limit + Number.EPSILON) return undefined;
  return {
    allowed: false,
    code: monthly ? "VOICE_MONTHLY_BUDGET" : "VOICE_DAILY_BUDGET",
    resetAtMs: monthly ? startOfNextUtcMonth(nowMs) : start + 24 * 60 * 60 * 1_000,
  };
}

function startOfUtcDay(nowMs: number): number {
  const date = new Date(nowMs);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

function startOfUtcMonth(nowMs: number): number {
  const date = new Date(nowMs);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1);
}

function startOfNextUtcMonth(nowMs: number): number {
  const date = new Date(nowMs);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1);
}

function requireRow(row: AttemptRow | undefined): AttemptRow {
  if (row === undefined) throw new Error("voice ledger invariant failed");
  return row;
}

function validateLimits(limits: VoiceLimits): void {
  for (const value of Object.values(limits)) {
    if (!Number.isFinite(value) || value <= 0) throw new TypeError("voice limit is invalid");
  }
}
