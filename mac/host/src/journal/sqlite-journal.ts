import { chmodSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import Database from "better-sqlite3";
import type {
  CommandJournalStore,
  CommandState,
  JournalInsertResult,
  JournalRecord,
  JournalRecoveryResult,
  JournalTransition,
  JournalTransitionResult,
  SemanticCommand,
} from "./types.js";
import { JournalStoreError } from "./types.js";
import type { JsonValue } from "../pi/raw-projector.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const LEAF_ID = /^[0-9a-f]{8}$/;

interface CommandRow {
  command_id: string;
  session_id: string;
  operation: string;
  payload_json: string;
  payload_hash: string;
  expected_leaf_id: null | string;
  expected_leaf_present: number;
  state: CommandState;
  dormant: number;
  received_at_ms: number;
  updated_at_ms: number;
  revision: number;
  result_json: null | string;
  error_code: null | string;
}

export class SqliteCommandJournal implements CommandJournalStore {
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
        CREATE TABLE IF NOT EXISTS commands (
          command_id TEXT PRIMARY KEY,
          session_id TEXT NOT NULL,
          operation TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          payload_hash TEXT NOT NULL,
          expected_leaf_id TEXT,
          expected_leaf_present INTEGER NOT NULL CHECK (expected_leaf_present IN (0, 1)),
          state TEXT NOT NULL CHECK (state IN ('RECEIVED','ARMED','ACKED','REJECTED','INDETERMINATE')),
          dormant INTEGER NOT NULL CHECK (dormant IN (0, 1)),
          received_at_ms INTEGER NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          revision INTEGER NOT NULL,
          result_json TEXT,
          error_code TEXT
        ) STRICT;
      `);
      const check = this.database.pragma("quick_check", { simple: true });
      if (check !== "ok") throw new Error("SQLite quick_check failed");
    } catch (error) {
      throw new JournalStoreError("failed to open command journal", { cause: error });
    }
  }

  close(): void {
    this.database.close();
  }

  async get(commandId: string): Promise<JournalRecord | undefined> {
    await Promise.resolve();
    try {
      const row = this.database.prepare("SELECT * FROM commands WHERE command_id = ?").get(commandId) as CommandRow | undefined;
      return row === undefined ? undefined : rowToRecord(row);
    } catch (error) {
      throw unavailable(error);
    }
  }

  async insertReceived(record: JournalRecord): Promise<JournalInsertResult> {
    await Promise.resolve();
    validateRecord(record);
    if (record.state !== "RECEIVED" || record.dormant || record.revision !== 0) {
      throw new JournalStoreError("new command must be active RECEIVED revision zero");
    }
    try {
      return this.database.transaction(() => {
        const existing = this.select(record.command.commandId);
        if (existing !== undefined) {
          assertSameHash(existing, record.command.payloadHash);
          return { inserted: false, record: rowToRecord(existing) };
        }
        this.database.prepare(`
          INSERT INTO commands (
            command_id, session_id, operation, payload_json, payload_hash,
            expected_leaf_id, expected_leaf_present, state, dormant,
            received_at_ms, updated_at_ms, revision, result_json, error_code
          ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', 0, ?, ?, 0, NULL, NULL)
        `).run(
          record.command.commandId,
          record.command.sessionId,
          record.command.operation,
          stringify(record.command.payload),
          record.command.payloadHash,
          record.command.expectedLeafId ?? null,
          Object.hasOwn(record.command, "expectedLeafId") ? 1 : 0,
          record.receivedAtMs,
          record.updatedAtMs,
        );
        return { inserted: true, record: rowToRecord(requireRow(this.select(record.command.commandId))) };
      })();
    } catch (error) {
      if (error instanceof JournalStoreError) throw error;
      throw unavailable(error);
    }
  }

  async transition(
    commandId: string,
    payloadHash: string,
    transition: JournalTransition,
  ): Promise<JournalTransitionResult> {
    await Promise.resolve();
    try {
      return this.database.transaction(() => {
        const current = requireRow(this.select(commandId));
        assertSameHash(current, payloadHash);
        const next = transitionState(current.state, transition.kind);
        if (next === undefined) return { transitioned: false, record: rowToRecord(current) };
        const result = "result" in transition ? transition.result : undefined;
        const errorCode = "errorCode" in transition ? transition.errorCode : undefined;
        this.database.prepare(`
          UPDATE commands
          SET state = ?, dormant = 0, updated_at_ms = ?, revision = revision + 1,
              result_json = ?, error_code = ?
          WHERE command_id = ? AND revision = ?
        `).run(next, transition.atMs, result === undefined ? null : stringify(result), errorCode ?? null, commandId, current.revision);
        return { transitioned: true, record: rowToRecord(requireRow(this.select(commandId))) };
      })();
    } catch (error) {
      if (error instanceof JournalStoreError) throw error;
      throw unavailable(error);
    }
  }

  async recover(nowMs: number): Promise<JournalRecoveryResult> {
    await Promise.resolve();
    if (!Number.isSafeInteger(nowMs) || nowMs < 0) throw new JournalStoreError("recovery time is invalid");
    try {
      return this.database.transaction(() => {
        const dormantReceived = this.database.prepare(`
          UPDATE commands SET dormant = 1, updated_at_ms = ?, revision = revision + 1
          WHERE state = 'RECEIVED' AND dormant = 0
        `).run(nowMs).changes;
        const indeterminateArmed = this.database.prepare(`
          UPDATE commands
          SET state = 'INDETERMINATE', dormant = 0, updated_at_ms = ?,
              revision = revision + 1, error_code = 'HOST_RECOVERED_AFTER_ARM'
          WHERE state = 'ARMED'
        `).run(nowMs).changes;
        return { dormantReceived, indeterminateArmed };
      })();
    } catch (error) {
      throw unavailable(error);
    }
  }

  async deleteSession(sessionId: string): Promise<number> {
    await Promise.resolve();
    if (!UUID_V4.test(sessionId)) throw new JournalStoreError("E2E_SESSION_INVALID");
    try {
      return this.database.transaction(() =>
        this.database.prepare("DELETE FROM commands WHERE session_id = ?").run(sessionId).changes,
      ).immediate();
    } catch (error) {
      throw unavailable(error);
    }
  }

  private select(commandId: string): CommandRow | undefined {
    return this.database.prepare("SELECT * FROM commands WHERE command_id = ?").get(commandId) as CommandRow | undefined;
  }
}

function transitionState(state: CommandState, kind: JournalTransition["kind"]): CommandState | undefined {
  if (state === "RECEIVED" && kind === "arm") return "ARMED";
  if (state === "RECEIVED" && kind === "reject") return "REJECTED";
  if (state === "ARMED" && kind === "ack") return "ACKED";
  if (state === "ARMED" && kind === "reject") return "REJECTED";
  if (state === "ARMED" && kind === "indeterminate") return "INDETERMINATE";
  return undefined;
}

function rowToRecord(row: CommandRow): JournalRecord {
  const command: SemanticCommand = {
    commandId: row.command_id,
    sessionId: row.session_id,
    operation: row.operation,
    payload: parse(row.payload_json),
    payloadHash: row.payload_hash,
    ...(row.expected_leaf_present === 1 ? { expectedLeafId: row.expected_leaf_id } : {}),
  };
  return {
    command,
    state: row.state,
    dormant: row.dormant === 1,
    receivedAtMs: row.received_at_ms,
    updatedAtMs: row.updated_at_ms,
    revision: row.revision,
    ...(row.result_json === null ? {} : { result: parse(row.result_json) }),
    ...(row.error_code === null ? {} : { errorCode: row.error_code }),
  };
}

function validateRecord(record: JournalRecord): void {
  const command = record.command;
  if (!UUID_V4.test(command.commandId) || !UUID_V4.test(command.sessionId)) throw new JournalStoreError("command identity is invalid");
  if (!SHA256.test(command.payloadHash) || command.operation.length === 0 || command.operation.length > 64) throw new JournalStoreError("command metadata is invalid");
  if (command.expectedLeafId !== undefined && command.expectedLeafId !== null && !LEAF_ID.test(command.expectedLeafId)) throw new JournalStoreError("expected leaf is invalid");
  if (!Number.isSafeInteger(record.receivedAtMs) || !Number.isSafeInteger(record.updatedAtMs)) throw new JournalStoreError("journal timestamp is invalid");
  stringify(command.payload);
}

function assertSameHash(row: CommandRow, hash: string): void {
  if (row.payload_hash !== hash) throw new JournalStoreError("COMMAND_ID_REUSE");
}

function requireRow(row: CommandRow | undefined): CommandRow {
  if (row === undefined) throw new JournalStoreError("command not found");
  return row;
}

function stringify(value: JsonValue): string {
  const encoded: unknown = JSON.stringify(value);
  if (typeof encoded !== "string") throw new JournalStoreError("journal JSON is invalid");
  return encoded;
}

function parse(value: string): JsonValue {
  return JSON.parse(value) as JsonValue;
}

function unavailable(error: unknown): JournalStoreError {
  return error instanceof JournalStoreError ? error : new JournalStoreError("command journal unavailable", { cause: error });
}
