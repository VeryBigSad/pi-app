import { chmodSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import Database from "better-sqlite3";

export class CanonicalStoreError extends Error {
  constructor(message: string, options?: { readonly cause?: unknown }) {
    super(message, options);
    this.name = "CanonicalStoreError";
  }
}

export interface CanonicalSessionState {
  readonly sessionId: string;
  readonly streamEpoch: string;
  /** Next unassigned sequence as canonical unsigned decimal text (first record is "1"). */
  readonly nextSequence: string;
}

export interface CanonicalAppendInput {
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly recordKey: string;
  readonly rawJson: string;
  readonly rawSha256: string;
  readonly piType: string;
  readonly projectionJson: string;
}

export interface CanonicalAppendResult {
  /** false when recordKey was already persisted (Pi child respawn replay). */
  readonly inserted: boolean;
  /** Sequence assigned to this record (existing sequence when deduplicated). */
  readonly sequence: string;
}

export interface CanonicalStoredRecord {
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly recordKey: string;
  readonly rawJson: string;
  readonly rawSha256: string;
  readonly piType: string;
  readonly projectionJson: string;
  readonly appendedAtMs: number;
}

interface SessionRow {
  session_id: string;
  stream_epoch: string;
  next_sequence: string;
}

interface RecordRow {
  session_id: string;
  stream_epoch: string;
  sequence: string;
  record_key: string;
  raw_json: string;
  raw_sha256: string;
  pi_type: string;
  projection_json: string;
  appended_at_ms: number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Stable dedup identity for a Pi record: its own id/timestamp fields when present,
 * always pinned to the raw content hash so byte-identical respawn replays dedup
 * while distinct records sharing an id still append.
 */
export function canonicalRecordKey(value: unknown, rawSha256: string): string {
  if (isRecord(value)) {
    const id = value["id"];
    if (typeof id === "string" && id.length > 0 && id.length <= 256) {
      const stamp = value["timestamp"] ?? value["ts"] ?? value["createdAt"];
      const stampPart = typeof stamp === "string" || typeof stamp === "number" ? `:t:${String(stamp)}` : "";
      return `id:${id}${stampPart}:h:${rawSha256}`;
    }
  }
  return `sha256:${rawSha256}`;
}

/**
 * Durable canonical record log: per session+streamEpoch append-ordered records
 * with a monotonic u64 sequence (canonical decimal text) and a per-session
 * streamEpoch + sequence high-water mark. WAL journal, FULL synchronous commits
 * (fsync on commit), all multi-statement mutations in immediate transactions.
 */
export class CanonicalStore {
  private readonly database: Database.Database;

  constructor(path: string) {
    try {
      mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
      this.database = new Database(path);
      chmodSync(path, 0o600);
      this.database.pragma("journal_mode = WAL");
      this.database.pragma("synchronous = FULL");
      this.database.pragma("foreign_keys = ON");
      this.database.pragma("busy_timeout = 5000");
      this.database.exec(`
        CREATE TABLE IF NOT EXISTS canonical_sessions (
          session_id TEXT PRIMARY KEY,
          stream_epoch TEXT NOT NULL,
          next_sequence TEXT NOT NULL
        ) STRICT;
        CREATE TABLE IF NOT EXISTS canonical_records (
          session_id TEXT NOT NULL,
          stream_epoch TEXT NOT NULL,
          sequence TEXT NOT NULL,
          record_key TEXT NOT NULL,
          raw_json TEXT NOT NULL,
          raw_sha256 TEXT NOT NULL,
          pi_type TEXT NOT NULL,
          projection_json TEXT NOT NULL,
          appended_at_ms INTEGER NOT NULL,
          PRIMARY KEY (session_id, stream_epoch, sequence)
        ) STRICT;
        CREATE UNIQUE INDEX IF NOT EXISTS canonical_records_dedup
          ON canonical_records (session_id, stream_epoch, record_key);
      `);
      const check: unknown = this.database.pragma("quick_check", { simple: true });
      if (check !== "ok") throw new Error("SQLite quick_check failed");
    } catch (error) {
      throw new CanonicalStoreError("failed to open canonical record store", { cause: error });
    }
  }

  close(): void {
    this.database.close();
  }

  /** Returns the durable session state, creating it with the supplied epoch when unknown. */
  ensureSession(sessionId: string, newStreamEpoch: string): CanonicalSessionState {
    const existing = this.database
      .prepare("SELECT session_id, stream_epoch, next_sequence FROM canonical_sessions WHERE session_id = ?")
      .get(sessionId) as SessionRow | undefined;
    if (existing !== undefined) return toSessionState(existing);
    this.database
      .prepare("INSERT INTO canonical_sessions (session_id, stream_epoch, next_sequence) VALUES (?, ?, '1')")
      .run(sessionId, newStreamEpoch);
    return { sessionId, streamEpoch: newStreamEpoch, nextSequence: "1" };
  }

  sessionState(sessionId: string): CanonicalSessionState | undefined {
    const row = this.database
      .prepare("SELECT session_id, stream_epoch, next_sequence FROM canonical_sessions WHERE session_id = ?")
      .get(sessionId) as SessionRow | undefined;
    return row === undefined ? undefined : toSessionState(row);
  }

  listSessions(): CanonicalSessionState[] {
    const rows = this.database
      .prepare("SELECT session_id, stream_epoch, next_sequence FROM canonical_sessions ORDER BY session_id")
      .all() as SessionRow[];
    return rows.map(toSessionState);
  }

  /**
   * Persist-first append with replay dedup: already-persisted recordKeys return
   * their original sequence without re-appending; new records claim the session
   * high-water sequence and advance it in the same transaction.
   */
  append(input: CanonicalAppendInput): CanonicalAppendResult {
    const run = this.database.transaction((appendInput: CanonicalAppendInput): CanonicalAppendResult => {
      const duplicate = this.database
        .prepare(
          `SELECT sequence FROM canonical_records
           WHERE session_id = ? AND stream_epoch = ? AND record_key = ?`,
        )
        .get(appendInput.sessionId, appendInput.streamEpoch, appendInput.recordKey) as { sequence: string } | undefined;
      if (duplicate !== undefined) return { inserted: false, sequence: duplicate.sequence };

      const session = this.database
        .prepare("SELECT session_id, stream_epoch, next_sequence FROM canonical_sessions WHERE session_id = ?")
        .get(appendInput.sessionId) as SessionRow | undefined;
      if (session === undefined) throw new CanonicalStoreError("canonical session is not registered");
      if (session.stream_epoch !== appendInput.streamEpoch) {
        throw new CanonicalStoreError("stream epoch does not match the durable session epoch");
      }

      const sequence = session.next_sequence;
      this.database
        .prepare(
          `INSERT INTO canonical_records
             (session_id, stream_epoch, sequence, record_key, raw_json, raw_sha256, pi_type, projection_json, appended_at_ms)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          appendInput.sessionId,
          appendInput.streamEpoch,
          sequence,
          appendInput.recordKey,
          appendInput.rawJson,
          appendInput.rawSha256,
          appendInput.piType,
          appendInput.projectionJson,
          Date.now(),
        );
      this.database
        .prepare("UPDATE canonical_sessions SET next_sequence = ? WHERE session_id = ?")
        .run((BigInt(sequence) + 1n).toString(), appendInput.sessionId);
      return { inserted: true, sequence };
    });
    return run.immediate(input);
  }

  /** Append-ordered durable records for a session (current epoch unless overridden). */
  records(sessionId: string, streamEpoch?: string): CanonicalStoredRecord[] {
    const rows = (
      streamEpoch === undefined
        ? this.database
            .prepare(
              `SELECT session_id, stream_epoch, sequence, record_key, raw_json, raw_sha256, pi_type, projection_json, appended_at_ms
               FROM canonical_records WHERE session_id = ?
               ORDER BY length(sequence), sequence`,
            )
            .all(sessionId)
        : this.database
            .prepare(
              `SELECT session_id, stream_epoch, sequence, record_key, raw_json, raw_sha256, pi_type, projection_json, appended_at_ms
               FROM canonical_records WHERE session_id = ? AND stream_epoch = ?
               ORDER BY length(sequence), sequence`,
            )
            .all(sessionId, streamEpoch)
    ) as RecordRow[];
    return rows.map((row) => ({
      sessionId: row.session_id,
      streamEpoch: row.stream_epoch,
      sequence: row.sequence,
      recordKey: row.record_key,
      rawJson: row.raw_json,
      rawSha256: row.raw_sha256,
      piType: row.pi_type,
      projectionJson: row.projection_json,
      appendedAtMs: row.appended_at_ms,
    }));
  }
}

function toSessionState(row: SessionRow): CanonicalSessionState {
  return { sessionId: row.session_id, streamEpoch: row.stream_epoch, nextSequence: row.next_sequence };
}
