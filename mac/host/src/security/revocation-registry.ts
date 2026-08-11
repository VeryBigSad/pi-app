import { closeSync, constants, lstatSync, mkdirSync, openSync } from "node:fs";
import { dirname } from "node:path";
import Database from "better-sqlite3";
import { SecurityError } from "./security-error.js";

export type RevocationKind = "device_certificate" | "passkey" | "route_key";
export type RevocationReason = "user_requested" | "credential_compromise" | "superseded" | "owner_reset";

export interface RevocationRecord {
  readonly kind: RevocationKind;
  readonly id: string;
  readonly reason: RevocationReason;
  readonly revokedAtMs: number;
}

interface RevocationRow {
  kind: RevocationKind;
  identifier: string;
  reason: RevocationReason;
  revoked_at_ms: number;
}

export class SqliteRevocationRegistry {
  private readonly database: Database.Database;

  constructor(path: string) {
    try {
      const directory = dirname(path);
      mkdirSync(directory, { recursive: true, mode: 0o700 });
      const directoryMetadata = lstatSync(directory);
      if (!directoryMetadata.isDirectory() || directoryMetadata.isSymbolicLink() || (directoryMetadata.mode & 0o077) !== 0) {
        throw new Error("revocation directory permissions are unsafe");
      }
      createPrivateDatabaseFile(path);
      this.database = new Database(path);
      this.database.pragma("journal_mode = WAL");
      this.database.pragma("synchronous = FULL");
      this.database.pragma("fullfsync = ON");
      this.database.pragma("foreign_keys = ON");
      this.database.pragma("busy_timeout = 5000");
      this.database.exec(`
        CREATE TABLE IF NOT EXISTS security_revocations (
          kind TEXT NOT NULL CHECK (kind IN ('device_certificate','passkey','route_key')),
          identifier TEXT NOT NULL,
          reason TEXT NOT NULL CHECK (reason IN ('user_requested','credential_compromise','superseded','owner_reset')),
          revoked_at_ms INTEGER NOT NULL CHECK (revoked_at_ms >= 0),
          PRIMARY KEY (kind, identifier)
        ) STRICT;
      `);
      if (this.database.pragma("quick_check", { simple: true }) !== "ok") {
        throw new Error("SQLite quick_check failed");
      }
    } catch (error) {
      throw unavailable(error);
    }
  }

  revoke(record: RevocationRecord): RevocationRecord {
    validateRecord(record);
    try {
      this.database.prepare(`
        INSERT INTO security_revocations (kind, identifier, reason, revoked_at_ms)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(kind, identifier) DO NOTHING
      `).run(record.kind, record.id, record.reason, record.revokedAtMs);
      return requireRecord(this.get(record.kind, record.id));
    } catch (error) {
      throw unavailable(error);
    }
  }

  get(kind: RevocationKind, id: string): RevocationRecord | undefined {
    validateIdentity(kind, id);
    try {
      const row = this.database.prepare(
        "SELECT kind, identifier, reason, revoked_at_ms FROM security_revocations WHERE kind = ? AND identifier = ?",
      ).get(kind, id) as RevocationRow | undefined;
      return row === undefined ? undefined : fromRow(row);
    } catch (error) {
      throw unavailable(error);
    }
  }

  isRevoked(kind: RevocationKind, id: string): boolean {
    return this.get(kind, id) !== undefined;
  }

  assertNotRevoked(kind: RevocationKind, id: string): void {
    if (this.isRevoked(kind, id)) {
      throw new SecurityError("SECURITY_REVOKED", "credential is revoked");
    }
  }

  close(): void {
    try {
      this.database.pragma("wal_checkpoint(TRUNCATE)");
      this.database.close();
    } catch (error) {
      throw unavailable(error);
    }
  }
}

function createPrivateDatabaseFile(path: string): void {
  try {
    const descriptor = openSync(
      path,
      constants.O_CREAT | constants.O_EXCL | constants.O_RDWR | constants.O_NOFOLLOW,
      0o600,
    );
    closeSync(descriptor);
  } catch (error) {
    if (!isNodeError(error) || error.code !== "EEXIST") throw error;
  }
  const metadata = lstatSync(path);
  if (!metadata.isFile() || metadata.isSymbolicLink() || (metadata.mode & 0o777) !== 0o600) {
    throw new Error("revocation database permissions are unsafe");
  }
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && "code" in error;
}

function validateRecord(record: RevocationRecord): void {
  validateIdentity(record.kind, record.id);
  if (
    !["user_requested", "credential_compromise", "superseded", "owner_reset"].includes(record.reason) ||
    !Number.isSafeInteger(record.revokedAtMs) ||
    record.revokedAtMs < 0
  ) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "revocation record is invalid");
  }
}

function validateIdentity(kind: RevocationKind, id: string): void {
  if (!(["device_certificate", "passkey", "route_key"] as const).includes(kind)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "revocation kind is invalid");
  }
  if (id.length === 0 || id.length > 1024 || hasControlCharacter(id)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "revocation identity is invalid");
  }
}

function hasControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 31 || code === 127) return true;
  }
  return false;
}

function fromRow(row: RevocationRow): RevocationRecord {
  return {
    kind: row.kind,
    id: row.identifier,
    reason: row.reason,
    revokedAtMs: row.revoked_at_ms,
  };
}

function requireRecord(record: RevocationRecord | undefined): RevocationRecord {
  if (record === undefined) throw new Error("revocation write was not durable");
  return record;
}

function unavailable(error: unknown): SecurityError {
  return error instanceof SecurityError
    ? error
    : new SecurityError("SECURITY_REVOCATION_UNAVAILABLE", "revocation registry unavailable", { cause: error });
}
