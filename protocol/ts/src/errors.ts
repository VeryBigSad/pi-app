export type ProtocolErrorCode =
  | "UNSUPPORTED_VERSION"
  | "PROTOCOL_VIOLATION"
  | "FRAME_TOO_LARGE"
  | "RESOURCE_EXHAUSTED"
  | "AUTH_REQUIRED"
  | "AUTH_FAILED"
  | "PAIRING_PHASE_REQUIRED"
  | "REVOKED"
  | "SEQUENCE_GAP"
  | "SYNC_REQUIRED"
  | "SNAPSHOT_WAITING_FOR_IDLE"
  | "SNAPSHOT_LEAF_CHANGED"
  | "COMMAND_ID_REUSE"
  | "COMMAND_DORMANT"
  | "COMMAND_INDETERMINATE"
  | "JOURNAL_UNAVAILABLE"
  | "APPROVAL_DENIED"
  | "APPROVAL_EXPIRED"
  | "BROKER_UNAVAILABLE"
  | "SESSION_LEASE_CONFLICT"
  | "STREAM_INVALID"
  | "BLOB_NOT_READY"
  | "BLOB_INVALID"
  | "TERMINAL_RESET_REQUIRED";

export class ProtocolError extends Error {
  readonly code: ProtocolErrorCode;
  readonly context: Readonly<Record<string, number | string>>;

  constructor(
    code: ProtocolErrorCode,
    message: string,
    context: Readonly<Record<string, number | string>> = {}
  ) {
    super(message);
    this.name = "ProtocolError";
    this.code = code;
    this.context = context;
  }
}
