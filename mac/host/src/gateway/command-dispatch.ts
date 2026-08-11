import { commandPayloadHash, isJsonObject, type JsonObject } from "@pimobile/protocol";
import type { CommandJournalStore, JournalRecord, SemanticCommand } from "../journal/types.js";
import { JournalStoreError } from "../journal/types.js";
import type { CommandAuthorization, CommandAuthorizer, CommandDispatchPath, CommandGuardContext, CommandPathRouter, GatewayClock } from "./types.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const LEAF_ID = /^[0-9a-f]{8}$/;

export type CommandGatewayErrorCode =
  | "AUTH_REQUIRED"
  | "COMMAND_ID_REUSE"
  | "COMMAND_INDETERMINATE"
  | "JOURNAL_UNAVAILABLE"
  | "PROTOCOL_VIOLATION";

export class CommandGatewayError extends Error {
  constructor(readonly code: CommandGatewayErrorCode, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "CommandGatewayError";
  }
}

export interface CommandExecutionContext extends CommandGuardContext {
  authorized(): boolean;
}

export interface CommandExecutionOutcome {
  readonly record: JournalRecord;
  readonly dispatched: boolean;
}

export class AtMostOnceCommandDispatcher {
  private readonly active = new Map<string, { readonly payloadHash: string; readonly operation: Promise<CommandExecutionOutcome> }>();
  private readonly readiness: Promise<void>;
  private readinessFailure: unknown;

  constructor(
    private readonly journal: CommandJournalStore,
    private readonly authorizer: CommandAuthorizer,
    private readonly paths: CommandPathRouter,
    private readonly clock: GatewayClock,
    readiness: Promise<void> = Promise.resolve(),
  ) {
    this.readiness = readiness.then(
      () => undefined,
      (error: unknown) => { this.readinessFailure = error; },
    );
  }

  submit(body: JsonObject, context: CommandExecutionContext, signal: AbortSignal): Promise<CommandExecutionOutcome> {
    const command = parseCommand(body);
    const running = this.active.get(command.commandId);
    if (running !== undefined) {
      if (running.payloadHash !== command.payloadHash) {
        return Promise.reject(new CommandGatewayError("COMMAND_ID_REUSE", "commandId was reused with another payload hash"));
      }
      return running.operation;
    }
    const operation = this.execute(command, context, signal).finally(() => this.active.delete(command.commandId));
    this.active.set(command.commandId, { payloadHash: command.payloadHash, operation });
    return operation;
  }

  async query(body: JsonObject): Promise<JournalRecord | undefined> {
    const commandId = body["commandId"];
    if (typeof commandId !== "string" || !UUID_V4.test(commandId)) {
      throw new CommandGatewayError("PROTOCOL_VIOLATION", "command.query identity is invalid");
    }
    await this.requireReady();
    try {
      return await this.journal.get(commandId);
    } catch (error) {
      throw unavailable(error);
    }
  }

  private async execute(command: SemanticCommand, context: CommandExecutionContext, signal: AbortSignal): Promise<CommandExecutionOutcome> {
    await this.requireReady();
    let record: JournalRecord;
    try {
      const existing = await this.journal.get(command.commandId);
      if (existing !== undefined && existing.command.payloadHash !== command.payloadHash) {
        throw new CommandGatewayError("COMMAND_ID_REUSE", "commandId was reused with another payload hash");
      }
      const now = this.clock.now();
      const inserted = await this.journal.insertReceived({
        command,
        state: "RECEIVED",
        dormant: false,
        receivedAtMs: now,
        updatedAtMs: now,
        revision: 0,
      });
      record = inserted.record;
    } catch (error) {
      if (error instanceof CommandGatewayError) throw error;
      throw unavailable(error);
    }

    if (record.state !== "RECEIVED") return { record, dispatched: false };

    const durableCommand = record.command;
    let authorization: CommandAuthorization;
    let path: CommandDispatchPath;
    try {
      path = this.paths.capture(durableCommand.sessionId);
      requireAuthorized(context, signal);
      authorization = await this.authorizer.authorize(durableCommand, context, signal);
      requireAuthorized(context, signal);
      const armed = await this.journal.transition(command.commandId, command.payloadHash, { kind: "arm", atMs: this.clock.now() });
      record = armed.record;
      if (!armed.transitioned) return { record, dispatched: false };
    } catch (error) {
      if (record.state === "RECEIVED") {
        try {
          const rejected = await this.journal.transition(command.commandId, command.payloadHash, {
            kind: "reject",
            atMs: this.clock.now(),
            errorCode: signal.aborted || !context.authorized() ? "AUTH_REQUIRED" : errorCode(error),
          });
          record = rejected.record;
        } catch (journalError) {
          throw unavailable(journalError);
        }
      }
      if (error instanceof CommandGatewayError && error.code === "COMMAND_ID_REUSE") throw error;
      return { record, dispatched: false };
    }

    try {
      await authorization.revalidate(signal);
      requireAuthorized(context, signal);
    } catch (error) {
      try {
        const rejected = await this.journal.transition(command.commandId, command.payloadHash, {
          kind: "reject",
          atMs: this.clock.now(),
          errorCode: signal.aborted || !context.authorized() ? "AUTH_REQUIRED" : errorCode(error),
        });
        return { record: rejected.record, dispatched: false };
      } catch (journalError) {
        throw unavailable(journalError);
      }
    }

    try {
      const result = await path.dispatch(durableCommand, authorization, signal);
      const acked = await this.journal.transition(command.commandId, command.payloadHash, {
        kind: "ack",
        atMs: this.clock.now(),
        result,
      });
      return { record: acked.record, dispatched: true };
    } catch (error) {
      try {
        const indeterminate = await this.journal.transition(command.commandId, command.payloadHash, {
          kind: "indeterminate",
          atMs: this.clock.now(),
          errorCode: "DISPATCH_OUTCOME_UNKNOWN",
        });
        return { record: indeterminate.record, dispatched: true };
      } catch (journalError) {
        throw unavailable(journalError instanceof JournalStoreError ? journalError : error);
      }
    }
  }

  private async requireReady(): Promise<void> {
    await this.readiness;
    if (this.readinessFailure !== undefined) throw unavailable(this.readinessFailure);
  }
}

export function commandStateBody(record: JournalRecord): JsonObject {
  return {
    commandId: record.command.commandId,
    state: record.state,
    dormant: record.dormant,
    ...(record.errorCode === undefined ? {} : { errorCode: record.errorCode }),
    ...(record.result === undefined ? {} : { result: record.result }),
  };
}

function parseCommand(body: JsonObject): SemanticCommand {
  const commandId = body["commandId"];
  const sessionId = body["sessionId"];
  const operation = body["operation"];
  const payload = body["payload"];
  const payloadHash = body["payloadHash"];
  const expectedLeafId = body["expectedLeafId"];
  if (
    typeof commandId !== "string" || !UUID_V4.test(commandId)
    || typeof sessionId !== "string" || !UUID_V4.test(sessionId)
    || typeof operation !== "string" || operation.length === 0 || operation.length > 64
    || !isJsonObject(payload)
    || typeof payloadHash !== "string" || !SHA256.test(payloadHash)
    || (expectedLeafId !== undefined && expectedLeafId !== null && (typeof expectedLeafId !== "string" || !LEAF_ID.test(expectedLeafId)))
  ) {
    throw new CommandGatewayError("PROTOCOL_VIOLATION", "command.submit body is invalid");
  }
  const hashInput = {
    sessionId,
    operation,
    payload,
    ...(Object.hasOwn(body, "expectedLeafId") ? { expectedLeafId: expectedLeafId as null | string } : {}),
  };
  if (commandPayloadHash(hashInput) !== payloadHash) {
    throw new CommandGatewayError("PROTOCOL_VIOLATION", "command.submit payload hash is invalid");
  }
  return {
    commandId,
    sessionId,
    operation,
    payload,
    payloadHash,
    ...(Object.hasOwn(body, "expectedLeafId") ? { expectedLeafId: expectedLeafId as null | string } : {}),
  };
}

function requireAuthorized(context: CommandExecutionContext, signal: AbortSignal): void {
  if (signal.aborted || !context.authorized()) {
    throw new CommandGatewayError("AUTH_REQUIRED", "Command authorization expired");
  }
}

function unavailable(error: unknown): CommandGatewayError {
  if (error instanceof CommandGatewayError) return error;
  if (error instanceof JournalStoreError && error.message === "COMMAND_ID_REUSE") {
    return new CommandGatewayError("COMMAND_ID_REUSE", "commandId was reused with another payload hash", { cause: error });
  }
  return new CommandGatewayError("JOURNAL_UNAVAILABLE", "Command journal is unavailable", { cause: error });
}

function errorCode(error: unknown): string {
  if (error instanceof CommandGatewayError) return error.code;
  if (error instanceof Error && /^[A-Z][A-Z0-9_]{1,63}$/.test(error.message)) return error.message;
  return "COMMAND_REJECTED";
}

