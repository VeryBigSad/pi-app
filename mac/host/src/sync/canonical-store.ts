import { createHash, timingSafeEqual } from "node:crypto";
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
  /** Stable source segment and one-based position within that segment. */
  readonly sourceId: string;
  readonly sourcePosition: string;
  readonly rawJson: string;
  readonly rawSha256: string;
  readonly piType: string;
  readonly projectionJson: string;
  /** Bounded finalized-message representation used only by snapshots. */
  readonly snapshotJson?: string;
}

export interface CanonicalAppendResult {
  /** false when this exact durable source position was already persisted. */
  readonly inserted: boolean;
  /** Sequence assigned to this record (existing sequence when deduplicated). */
  readonly sequence: string;
  /** Durable append-order identifier for finalized messages only. */
  readonly appendId?: string;
}

export interface CanonicalStoredRecord {
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly appendId?: string;
  readonly recordKey: string;
  readonly sourceId: string;
  readonly sourcePosition: string;
  readonly rawJson: string;
  readonly rawSha256: string;
  readonly piType: string;
  readonly projectionJson: string;
  readonly snapshotJson?: string;
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
  append_id: string | null;
  record_key: string;
  source_id: string;
  source_position: string;
  raw_json: string;
  raw_sha256: string;
  pi_type: string;
  projection_json: string;
  snapshot_json: string | null;
  appended_at_ms: number;
}

type E2eDisposalState = "active" | "deleting" | "disposed";

interface E2eSessionRow {
  session_id: string;
  delete_token_sha256: string;
  state: E2eDisposalState;
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const E2E_DELETE_TOKEN = /^[A-Za-z0-9_-]{43}$/u;

/**
 * Source-position identity deliberately does not include the raw hash: Pi can emit
 * byte-identical id-less records as distinct events. Respawn replays are removed by
 * the ordered bounded replay prefix in SessionActor before they reach this store.
 */
export function canonicalRecordKey(sourceId: string, sourcePosition: string): string {
  if (!/^[0-9a-f-]{36}$/u.test(sourceId) || !/^[1-9][0-9]{0,19}$/u.test(sourcePosition)) {
    throw new CanonicalStoreError("canonical source identity is invalid");
  }
  return `source:${sourceId}:${sourcePosition}`;
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
          next_sequence TEXT NOT NULL,
          next_append_id TEXT NOT NULL DEFAULT '1'
        ) STRICT;
        CREATE TABLE IF NOT EXISTS canonical_records (
          session_id TEXT NOT NULL,
          stream_epoch TEXT NOT NULL,
          sequence TEXT NOT NULL,
          append_id TEXT,
          record_key TEXT NOT NULL,
          source_id TEXT NOT NULL DEFAULT '',
          source_position TEXT NOT NULL DEFAULT '0',
          raw_json TEXT NOT NULL,
          raw_sha256 TEXT NOT NULL,
          pi_type TEXT NOT NULL,
          projection_json TEXT NOT NULL,
          snapshot_json TEXT,
          appended_at_ms INTEGER NOT NULL,
          PRIMARY KEY (session_id, stream_epoch, sequence)
        ) STRICT;
        CREATE UNIQUE INDEX IF NOT EXISTS canonical_records_dedup
          ON canonical_records (session_id, stream_epoch, record_key);
        CREATE TABLE IF NOT EXISTS e2e_sessions (
          session_id TEXT PRIMARY KEY,
          delete_token_sha256 TEXT NOT NULL,
          state TEXT NOT NULL CHECK (state IN ('active', 'deleting', 'disposed'))
        ) STRICT;
      `);
      this.migrateSchema();
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

  createE2eSession(sessionId: string, streamEpoch: string, deleteToken: string): CanonicalSessionState {
    assertE2eIdentity(sessionId, deleteToken);
    if (!UUID_V4.test(streamEpoch)) throw new CanonicalStoreError("E2E_SESSION_INVALID");
    const tokenHash = hashDeleteToken(deleteToken);
    const create = this.database.transaction(() => {
      if (this.database.prepare("SELECT 1 FROM canonical_sessions WHERE session_id = ?").get(sessionId) !== undefined
        || this.database.prepare("SELECT 1 FROM e2e_sessions WHERE session_id = ?").get(sessionId) !== undefined) {
        throw new CanonicalStoreError("E2E_SESSION_COLLISION");
      }
      this.database
        .prepare("INSERT INTO canonical_sessions (session_id, stream_epoch, next_sequence) VALUES (?, ?, '1')")
        .run(sessionId, streamEpoch);
      this.database
        .prepare("INSERT INTO e2e_sessions (session_id, delete_token_sha256, state) VALUES (?, ?, 'active')")
        .run(sessionId, tokenHash);
      return { sessionId, streamEpoch, nextSequence: "1" };
    });
    return create.immediate();
  }

  e2eSessionState(sessionId: string, deleteToken: string): E2eDisposalState {
    assertE2eIdentity(sessionId, deleteToken);
    const row = this.database
      .prepare("SELECT session_id, delete_token_sha256, state FROM e2e_sessions WHERE session_id = ?")
      .get(sessionId) as E2eSessionRow | undefined;
    if (row === undefined || !sameDeleteToken(row.delete_token_sha256, deleteToken)) throw new CanonicalStoreError("E2E_SESSION_OWNERSHIP_REQUIRED");
    return row.state;
  }

  e2eSessionUnavailable(sessionId: string): boolean {
    const row = this.database.prepare("SELECT state FROM e2e_sessions WHERE session_id = ?").get(sessionId) as { state: E2eDisposalState } | undefined;
    return row !== undefined && row.state !== "active";
  }

  beginE2eDisposal(sessionId: string, deleteToken: string): E2eDisposalState {
    assertE2eIdentity(sessionId, deleteToken);
    const dispose = this.database.transaction(() => {
      const state = this.e2eSessionState(sessionId, deleteToken);
      if (state !== "active") return state;
      this.database.prepare("DELETE FROM canonical_records WHERE session_id = ?").run(sessionId);
      this.database.prepare("DELETE FROM canonical_sessions WHERE session_id = ?").run(sessionId);
      this.database.prepare("UPDATE e2e_sessions SET state = 'deleting' WHERE session_id = ?").run(sessionId);
      return "deleting" as const;
    });
    return dispose.immediate();
  }

  completeE2eDisposal(sessionId: string, deleteToken: string): void {
    const state = this.e2eSessionState(sessionId, deleteToken);
    if (state === "disposed") return;
    if (state !== "deleting") throw new CanonicalStoreError("E2E_SESSION_DISPOSAL_NOT_STARTED");
    this.database.prepare("UPDATE e2e_sessions SET state = 'disposed' WHERE session_id = ?").run(sessionId);
  }

  listSessions(): CanonicalSessionState[] {
    const rows = this.database
      .prepare("SELECT session_id, stream_epoch, next_sequence FROM canonical_sessions ORDER BY session_id")
      .all() as SessionRow[];
    return rows.map(toSessionState);
  }

  /**
   * Persist-first append: an exact durable source position returns its original
   * sequence without re-appending; new records claim the session high-water
   * sequence and advance it in the same transaction. Respawn replay matching is
   * deliberately performed by SessionActor before this append.
   */
  append(input: CanonicalAppendInput): CanonicalAppendResult {
    const run = this.database.transaction((appendInput: CanonicalAppendInput): CanonicalAppendResult => {
      const recordKey = canonicalRecordKey(appendInput.sourceId, appendInput.sourcePosition);
      const duplicate = this.database
        .prepare(
          `SELECT sequence, append_id FROM canonical_records
           WHERE session_id = ? AND stream_epoch = ? AND record_key = ?`,
        )
        .get(appendInput.sessionId, appendInput.streamEpoch, recordKey) as { sequence: string; append_id: string | null } | undefined;
      if (duplicate !== undefined) {
        return {
          inserted: false,
          sequence: duplicate.sequence,
          ...(duplicate.append_id === null ? {} : { appendId: duplicate.append_id }),
        };
      }

      const session = this.database
        .prepare("SELECT session_id, stream_epoch, next_sequence, next_append_id FROM canonical_sessions WHERE session_id = ?")
        .get(appendInput.sessionId) as (SessionRow & { next_append_id: string }) | undefined;
      if (session === undefined) throw new CanonicalStoreError("canonical session is not registered");
      if (session.stream_epoch !== appendInput.streamEpoch) {
        throw new CanonicalStoreError("stream epoch does not match the durable session epoch");
      }

      const sequence = session.next_sequence;
      const appendId = appendInput.piType === "message_end" ? session.next_append_id : undefined;
      this.database
        .prepare(
          `INSERT INTO canonical_records
             (session_id, stream_epoch, sequence, append_id, record_key, source_id, source_position, raw_json, raw_sha256, pi_type, projection_json, snapshot_json, appended_at_ms)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          appendInput.sessionId,
          appendInput.streamEpoch,
          sequence,
          appendId ?? null,
          recordKey,
          appendInput.sourceId,
          appendInput.sourcePosition,
          appendInput.rawJson,
          appendInput.rawSha256,
          appendInput.piType,
          appendInput.projectionJson,
          appendInput.snapshotJson ?? null,
          Date.now(),
        );
      this.database
        .prepare("UPDATE canonical_sessions SET next_sequence = ?, next_append_id = ? WHERE session_id = ?")
        .run(
          (BigInt(sequence) + 1n).toString(),
          appendId === undefined ? session.next_append_id : (BigInt(appendId) + 1n).toString(),
          appendInput.sessionId,
        );
      return { inserted: true, sequence, ...(appendId === undefined ? {} : { appendId }) };
    });
    return run.immediate(input);
  }

  /** Append-ordered durable records for a session (current epoch unless overridden). */
  records(sessionId: string, streamEpoch?: string): CanonicalStoredRecord[] {
    const rows = (
      streamEpoch === undefined
        ? this.database
            .prepare(
              `SELECT session_id, stream_epoch, sequence, append_id, record_key, source_id, source_position, raw_json, raw_sha256, pi_type, projection_json, snapshot_json, appended_at_ms
               FROM canonical_records WHERE session_id = ?
               ORDER BY length(sequence), sequence`,
            )
            .all(sessionId)
        : this.database
            .prepare(
              `SELECT session_id, stream_epoch, sequence, append_id, record_key, source_id, source_position, raw_json, raw_sha256, pi_type, projection_json, snapshot_json, appended_at_ms
               FROM canonical_records WHERE session_id = ? AND stream_epoch = ?
               ORDER BY length(sequence), sequence`,
            )
            .all(sessionId, streamEpoch)
    ) as RecordRow[];
    return rows.map((row) => ({
      sessionId: row.session_id,
      streamEpoch: row.stream_epoch,
      sequence: row.sequence,
      ...(row.append_id === null ? {} : { appendId: row.append_id }),
      recordKey: row.record_key,
      sourceId: row.source_id,
      sourcePosition: row.source_position,
      rawJson: row.raw_json,
      rawSha256: row.raw_sha256,
      piType: row.pi_type,
      projectionJson: row.projection_json,
      ...(row.snapshot_json === null ? {} : { snapshotJson: row.snapshot_json }),
      appendedAtMs: row.appended_at_ms,
    }));
  }

  private migrateSchema(): void {
    const recordColumns = this.database.pragma("table_info(canonical_records)") as { name: string }[];
    if (!recordColumns.some((column) => column.name === "source_id")) {
      this.database.exec("ALTER TABLE canonical_records ADD COLUMN source_id TEXT NOT NULL DEFAULT ''");
    }
    if (!recordColumns.some((column) => column.name === "source_position")) {
      this.database.exec("ALTER TABLE canonical_records ADD COLUMN source_position TEXT NOT NULL DEFAULT '0'");
    }
    if (!recordColumns.some((column) => column.name === "snapshot_json")) {
      this.database.exec("ALTER TABLE canonical_records ADD COLUMN snapshot_json TEXT");
    }
    const columns = this.database.pragma("table_info(canonical_sessions)") as { name: string }[];
    if (!columns.some((column) => column.name === "next_append_id")) {
      this.database.exec("ALTER TABLE canonical_sessions ADD COLUMN next_append_id TEXT NOT NULL DEFAULT '1'");
    }
    const appendColumns = this.database.pragma("table_info(canonical_records)") as { name: string }[];
    if (!appendColumns.some((column) => column.name === "append_id")) {
      this.database.exec("ALTER TABLE canonical_records ADD COLUMN append_id TEXT");
    }
    const migrate = this.database.transaction(() => {
      const sessions = this.database.prepare("SELECT session_id, stream_epoch FROM canonical_sessions ORDER BY session_id").all() as { session_id: string; stream_epoch: string }[];
      const selectMessages = this.database.prepare(
        `SELECT sequence FROM canonical_records
         WHERE session_id = ? AND stream_epoch = ? AND pi_type = 'message_end'
         ORDER BY length(sequence), sequence`,
      );
      const updateRecord = this.database.prepare(
        "UPDATE canonical_records SET append_id = ? WHERE session_id = ? AND stream_epoch = ? AND sequence = ?",
      );
      const updateSession = this.database.prepare(
        "UPDATE canonical_sessions SET next_append_id = ? WHERE session_id = ?",
      );
      for (const session of sessions) {
        const messages = selectMessages.all(session.session_id, session.stream_epoch) as { sequence: string }[];
        let appendId = 1n;
        for (const message of messages) {
          updateRecord.run(appendId.toString(), session.session_id, session.stream_epoch, message.sequence);
          appendId += 1n;
        }
        updateSession.run(appendId.toString(), session.session_id);
      }
    });
    migrate.immediate();
    this.database.exec(
      `CREATE UNIQUE INDEX IF NOT EXISTS canonical_records_append_order
       ON canonical_records (session_id, stream_epoch, append_id)
       WHERE append_id IS NOT NULL`,
    );
  }
}

function assertE2eIdentity(sessionId: string, deleteToken: string): void {
  if (!UUID_V4.test(sessionId) || !E2E_DELETE_TOKEN.test(deleteToken)) throw new CanonicalStoreError("E2E_SESSION_INVALID");
}

function hashDeleteToken(deleteToken: string): string {
  return createHash("sha256").update(deleteToken, "utf8").digest("hex");
}

function sameDeleteToken(expected: string, received: string): boolean {
  const expectedBytes = Buffer.from(expected, "hex");
  const receivedBytes = Buffer.from(hashDeleteToken(received), "hex");
  return expectedBytes.byteLength === receivedBytes.byteLength && timingSafeEqual(expectedBytes, receivedBytes);
}

function toSessionState(row: SessionRow): CanonicalSessionState {
  return { sessionId: row.session_id, streamEpoch: row.stream_epoch, nextSequence: row.next_sequence };
}
