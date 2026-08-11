import type { JsonValue } from "../pi/raw-projector.js";

export type CommandState = "RECEIVED" | "ARMED" | "ACKED" | "REJECTED" | "INDETERMINATE";

export interface SemanticCommand {
  readonly commandId: string;
  readonly sessionId: string;
  readonly operation: string;
  readonly payload: JsonValue;
  readonly payloadHash: string;
  readonly expectedLeafId?: string | null;
}

export interface JournalRecord {
  readonly command: SemanticCommand;
  readonly state: CommandState;
  readonly dormant: boolean;
  readonly receivedAtMs: number;
  readonly updatedAtMs: number;
  readonly revision: number;
  readonly result?: JsonValue;
  readonly errorCode?: string;
}

export interface JournalInsertResult {
  readonly inserted: boolean;
  readonly record: JournalRecord;
}

export interface JournalTransitionResult {
  readonly transitioned: boolean;
  readonly record: JournalRecord;
}

export interface JournalRecoveryResult {
  readonly dormantReceived: number;
  readonly indeterminateArmed: number;
}

export type JournalTransition =
  | { readonly kind: "arm"; readonly atMs: number }
  | { readonly kind: "ack"; readonly atMs: number; readonly result?: JsonValue }
  | { readonly kind: "reject"; readonly atMs: number; readonly errorCode: string; readonly result?: JsonValue }
  | { readonly kind: "indeterminate"; readonly atMs: number; readonly errorCode: string };

export interface CommandJournalStore {
  get(commandId: string): Promise<JournalRecord | undefined>;
  insertReceived(record: JournalRecord): Promise<JournalInsertResult>;
  transition(commandId: string, payloadHash: string, transition: JournalTransition): Promise<JournalTransitionResult>;
  recover(nowMs: number): Promise<JournalRecoveryResult>;
}

export class JournalStoreError extends Error {
  readonly code = "JOURNAL_UNAVAILABLE";

  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "JournalStoreError";
  }
}
