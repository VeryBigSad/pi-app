export type DeltaAssemblyErrorCode =
  | "DELTA_INVALID_EVENT"
  | "DELTA_INVALID_TRANSITION"
  | "DELTA_PROVISIONAL_TOO_LARGE"
  | "DELTA_RECOVERY_REQUIRED"
  | "SEQUENCE_GAP"
  | "STREAM_EPOCH_CHANGED";

export class DeltaAssemblyError extends Error {
  readonly code: DeltaAssemblyErrorCode;

  constructor(code: DeltaAssemblyErrorCode, message: string) {
    super(message);
    this.name = "DeltaAssemblyError";
    this.code = code;
  }
}

export interface PiStreamCursor {
  readonly streamEpoch: string;
  readonly sequence: string;
}

export type DeltaApplyResult =
  | { readonly kind: "committed"; readonly message: Readonly<Record<string, unknown>> }
  | { readonly kind: "duplicate" }
  | { readonly kind: "ignored" }
  | { readonly kind: "updated" };

export interface ToolExecutionSnapshot {
  readonly toolCallId: string;
  readonly toolName: string;
  readonly args: unknown;
  readonly status: "running" | "ended";
  readonly partialResult?: unknown;
  readonly result?: unknown;
  readonly isError?: boolean;
}

type BlockKind = "text" | "thinking" | "toolCall";

interface OpenBlock {
  readonly kind: BlockKind;
  open: boolean;
  readonly toolCallId?: string;
  argumentsJson?: string;
}

interface MutableToolExecution {
  readonly toolCallId: string;
  readonly toolName: string;
  readonly args: unknown;
  status: "running" | "ended";
  partialResult?: unknown;
  result?: unknown;
  isError?: boolean;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requiredRecord(value: unknown, field: string): Record<string, unknown> {
  if (!isRecord(value)) {
    throw new DeltaAssemblyError("DELTA_INVALID_EVENT", `${field} must be an object`);
  }
  return value;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== "string") {
    throw new DeltaAssemblyError("DELTA_INVALID_EVENT", `${field} must be a string`);
  }
  return value;
}

function requiredIndex(value: unknown): number {
  if (!Number.isSafeInteger(value) || typeof value !== "number" || value < 0) {
    throw new DeltaAssemblyError("DELTA_INVALID_EVENT", "contentIndex must be a non-negative safe integer");
  }
  return value;
}

function cloneUnknown<T>(value: T): T {
  return structuredClone(value);
}

function serializedBytes(value: unknown): number {
  const serialized: unknown = JSON.stringify(value);
  if (typeof serialized !== "string") {
    throw new DeltaAssemblyError("DELTA_INVALID_EVENT", "event value is not JSON serializable");
  }
  return Buffer.byteLength(serialized, "utf8");
}

function parseSequence(value: string): bigint {
  if (!/^(0|[1-9][0-9]*)$/.test(value)) {
    throw new DeltaAssemblyError("DELTA_INVALID_EVENT", "sequence must be unsigned decimal text");
  }
  return BigInt(value);
}

/** Strictly assembles one provisional assistant message and parallel tool executions. */
export class DeltaAssembler {
  readonly maxProvisionalBytes: number;
  private message: Record<string, unknown> | undefined;
  private content: unknown[] = [];
  private readonly blocks = new Map<number, OpenBlock>();
  private readonly toolBlockIndexes = new Map<string, number>();
  private readonly toolExecutions = new Map<string, MutableToolExecution>();
  private provisionalBytes = 0;
  private recoveryRequired = false;
  private epoch: string | undefined;
  private lastSequence: bigint | undefined;

  constructor(maxProvisionalBytes = 16 * 1024 * 1024) {
    if (!Number.isSafeInteger(maxProvisionalBytes) || maxProvisionalBytes < 1) {
      throw new RangeError("maxProvisionalBytes must be a positive safe integer");
    }
    this.maxProvisionalBytes = maxProvisionalBytes;
  }

  apply(event: unknown, cursor?: PiStreamCursor): DeltaApplyResult {
    if (this.recoveryRequired) {
      throw new DeltaAssemblyError("DELTA_RECOVERY_REQUIRED", "canonical recovery is required before more deltas");
    }

    if (cursor !== undefined && this.observeCursor(cursor)) {
      return { kind: "duplicate" };
    }

    try {
      const root = requiredRecord(event, "event");
      const type = requiredString(root["type"], "event.type");
      switch (type) {
        case "message_start":
          this.startMessage(root["message"]);
          return { kind: "updated" };
        case "message_update":
          this.updateMessage(root["assistantMessageEvent"]);
          return { kind: "updated" };
        case "message_end":
          return { kind: "committed", message: this.endMessage(root["message"]) };
        case "tool_execution_start":
          this.startToolExecution(root);
          return { kind: "updated" };
        case "tool_execution_update":
          this.updateToolExecution(root);
          return { kind: "updated" };
        case "tool_execution_end":
          this.endToolExecution(root);
          return { kind: "updated" };
        default:
          return { kind: "ignored" };
      }
    } catch (error) {
      if (error instanceof DeltaAssemblyError) {
        this.enterRecovery(error);
      }
      this.enterRecovery(new DeltaAssemblyError("DELTA_INVALID_EVENT", "delta assembly failed"));
    }
  }

  provisionalMessage(): Readonly<Record<string, unknown>> | undefined {
    if (this.message === undefined) {
      return undefined;
    }
    const copy = cloneUnknown(this.message);
    copy["content"] = cloneUnknown(this.content);
    return copy;
  }

  toolArguments(toolCallId: string): string | undefined {
    const index = this.toolBlockIndexes.get(toolCallId);
    if (index === undefined) {
      return undefined;
    }
    return this.blocks.get(index)?.argumentsJson;
  }

  toolExecution(toolCallId: string): ToolExecutionSnapshot | undefined {
    const execution = this.toolExecutions.get(toolCallId);
    if (execution === undefined) {
      return undefined;
    }
    return cloneUnknown(execution);
  }

  needsRecovery(): boolean {
    return this.recoveryRequired;
  }

  reconnect(): never {
    return this.enterRecovery(new DeltaAssemblyError("STREAM_EPOCH_CHANGED", "Pi stream reconnected"));
  }

  resetAfterCanonicalSnapshot(cursor?: PiStreamCursor): void {
    this.clearProvisional();
    this.toolExecutions.clear();
    this.recoveryRequired = false;
    if (cursor === undefined) {
      this.epoch = undefined;
      this.lastSequence = undefined;
    } else {
      this.epoch = cursor.streamEpoch;
      this.lastSequence = parseSequence(cursor.sequence);
    }
  }

  private observeCursor(cursor: PiStreamCursor): boolean {
    const sequence = parseSequence(cursor.sequence);
    if (this.epoch === undefined) {
      this.epoch = cursor.streamEpoch;
      this.lastSequence = sequence;
      return false;
    }
    if (cursor.streamEpoch !== this.epoch) {
      this.enterRecovery(new DeltaAssemblyError("STREAM_EPOCH_CHANGED", "Pi stream epoch changed"));
    }

    const previous = this.lastSequence;
    if (previous === undefined) {
      this.lastSequence = sequence;
      return false;
    }
    if (sequence <= previous) {
      return true;
    }
    if (sequence !== previous + 1n) {
      this.enterRecovery(new DeltaAssemblyError("SEQUENCE_GAP", "Pi event sequence is not contiguous"));
    }
    this.lastSequence = sequence;
    return false;
  }

  private startMessage(value: unknown): void {
    if (this.message !== undefined) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "message_start received while a message is open");
    }
    const message = requiredRecord(value, "message");
    if (message["role"] !== "assistant" || !Array.isArray(message["content"])) {
      throw new DeltaAssemblyError("DELTA_INVALID_EVENT", "message_start must carry assistant content");
    }
    this.message = cloneUnknown(message);
    this.content = cloneUnknown(message["content"]);
    this.provisionalBytes = serializedBytes(this.message);
    this.checkSize();
  }

  private updateMessage(value: unknown): void {
    if (this.message === undefined) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "message_update received without message_start");
    }
    const update = requiredRecord(value, "assistantMessageEvent");
    const type = requiredString(update["type"], "assistantMessageEvent.type");
    const index = requiredIndex(update["contentIndex"]);

    switch (type) {
      case "text_start":
        this.startBlock(index, "text", { type: "text", text: "" });
        break;
      case "thinking_start":
        this.startBlock(index, "thinking", { type: "thinking", thinking: "" });
        break;
      case "toolcall_start": {
        const id = requiredString(update["id"], "toolcall_start.id");
        const toolName = requiredString(update["toolName"], "toolcall_start.toolName");
        if (this.toolBlockIndexes.has(id)) {
          throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "tool call id was started twice");
        }
        this.startBlock(index, "toolCall", { type: "toolCall", id, name: toolName, arguments: {} }, id);
        this.toolBlockIndexes.set(id, index);
        break;
      }
      case "text_delta":
        this.appendText(index, "text", "text", requiredString(update["delta"], "text_delta.delta"));
        break;
      case "thinking_delta":
        this.appendText(index, "thinking", "thinking", requiredString(update["delta"], "thinking_delta.delta"));
        break;
      case "toolcall_delta":
        this.appendToolArguments(index, requiredString(update["delta"], "toolcall_delta.delta"));
        break;
      case "text_end":
        this.endText(index, "text", "text", requiredString(update["content"], "text_end.content"));
        break;
      case "thinking_end":
        this.endText(index, "thinking", "thinking", requiredString(update["content"], "thinking_end.content"));
        break;
      case "toolcall_end":
        this.endToolCall(index, update["toolCall"]);
        break;
      default:
        throw new DeltaAssemblyError("DELTA_INVALID_EVENT", "unknown assistant message delta type");
    }
  }

  private startBlock(index: number, kind: BlockKind, value: Record<string, unknown>, toolCallId?: string): void {
    if (index !== this.content.length || this.blocks.has(index)) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "content block started at an unexpected index");
    }
    this.content.push(value);
    const block: OpenBlock = { kind, open: true };
    if (toolCallId !== undefined) {
      block.argumentsJson = "";
      Object.defineProperty(block, "toolCallId", { value: toolCallId, enumerable: true });
    }
    this.blocks.set(index, block);
    this.provisionalBytes += serializedBytes(value);
    this.checkSize();
  }

  private requireOpenBlock(index: number, kind: BlockKind): OpenBlock {
    const block = this.blocks.get(index);
    if (block?.kind !== kind || !block.open) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "delta did not match an open content block");
    }
    return block;
  }

  private appendText(index: number, kind: "text" | "thinking", field: "text" | "thinking", delta: string): void {
    this.requireOpenBlock(index, kind);
    const value = requiredRecord(this.content[index], "content block");
    const previous = requiredString(value[field], field);
    value[field] = previous + delta;
    this.provisionalBytes += Buffer.byteLength(delta, "utf8");
    this.checkSize();
  }

  private appendToolArguments(index: number, delta: string): void {
    const block = this.requireOpenBlock(index, "toolCall");
    block.argumentsJson = (block.argumentsJson ?? "") + delta;
    this.provisionalBytes += Buffer.byteLength(delta, "utf8");
    this.checkSize();

    try {
      const parsed: unknown = JSON.parse(block.argumentsJson);
      if (isRecord(parsed)) {
        requiredRecord(this.content[index], "tool call block")["arguments"] = parsed;
      }
    } catch {
      return;
    }
  }

  private endText(index: number, kind: "text" | "thinking", field: "text" | "thinking", content: string): void {
    const block = this.requireOpenBlock(index, kind);
    const value = requiredRecord(this.content[index], "content block");
    const previous = requiredString(value[field], field);
    value[field] = content;
    block.open = false;
    this.provisionalBytes += Buffer.byteLength(content, "utf8") - Buffer.byteLength(previous, "utf8");
    this.checkSize();
  }

  private endToolCall(index: number, value: unknown): void {
    const block = this.requireOpenBlock(index, "toolCall");
    const toolCall = requiredRecord(value, "toolcall_end.toolCall");
    const id = requiredString(toolCall["id"], "toolCall.id");
    requiredString(toolCall["name"], "toolCall.name");
    if (!isRecord(toolCall["arguments"]) || id !== block.toolCallId) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "completed tool call does not match its start");
    }
    this.content[index] = cloneUnknown(toolCall);
    block.open = false;
    this.provisionalBytes = serializedBytes({ ...this.message, content: this.content });
    this.checkSize();
  }

  private endMessage(value: unknown): Readonly<Record<string, unknown>> {
    if (this.message === undefined) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "message_end received without message_start");
    }
    for (const block of this.blocks.values()) {
      if (block.open) {
        throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "message_end received with an open content block");
      }
    }
    const authoritative = requiredRecord(value, "message_end.message");
    if (authoritative["role"] !== "assistant" || !Array.isArray(authoritative["content"])) {
      throw new DeltaAssemblyError("DELTA_INVALID_EVENT", "message_end must carry assistant content");
    }
    if (serializedBytes(authoritative) > this.maxProvisionalBytes) {
      throw new DeltaAssemblyError("DELTA_PROVISIONAL_TOO_LARGE", "authoritative assistant message exceeds the limit");
    }
    const committed = cloneUnknown(authoritative);
    this.clearProvisional();
    return committed;
  }

  private startToolExecution(event: Record<string, unknown>): void {
    const toolCallId = requiredString(event["toolCallId"], "toolCallId");
    const toolName = requiredString(event["toolName"], "toolName");
    if (this.toolExecutions.has(toolCallId)) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "tool execution id was started twice");
    }
    const args = cloneUnknown(event["args"]);
    this.checkExternalValue(args);
    this.toolExecutions.set(toolCallId, { toolCallId, toolName, args, status: "running" });
  }

  private updateToolExecution(event: Record<string, unknown>): void {
    const toolCallId = requiredString(event["toolCallId"], "toolCallId");
    const execution = this.requireRunningToolExecution(toolCallId);
    if (event["toolName"] !== execution.toolName) {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "tool execution name changed");
    }
    const partialResult = cloneUnknown(event["partialResult"]);
    this.checkExternalValue(partialResult);
    execution.partialResult = partialResult;
  }

  private endToolExecution(event: Record<string, unknown>): void {
    const toolCallId = requiredString(event["toolCallId"], "toolCallId");
    const execution = this.requireRunningToolExecution(toolCallId);
    if (event["toolName"] !== execution.toolName || typeof event["isError"] !== "boolean") {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "tool execution completion does not match its start");
    }
    const result = cloneUnknown(event["result"]);
    this.checkExternalValue(result);
    execution.result = result;
    execution.isError = event["isError"];
    execution.status = "ended";
  }

  private requireRunningToolExecution(toolCallId: string): MutableToolExecution {
    const execution = this.toolExecutions.get(toolCallId);
    if (execution?.status !== "running") {
      throw new DeltaAssemblyError("DELTA_INVALID_TRANSITION", "tool execution update has no matching start");
    }
    return execution;
  }

  private checkExternalValue(value: unknown): void {
    if (serializedBytes(value) > this.maxProvisionalBytes) {
      throw new DeltaAssemblyError("DELTA_PROVISIONAL_TOO_LARGE", "tool execution value exceeds the limit");
    }
  }

  private checkSize(): void {
    if (this.provisionalBytes > this.maxProvisionalBytes) {
      throw new DeltaAssemblyError("DELTA_PROVISIONAL_TOO_LARGE", "provisional assistant message exceeds the limit");
    }
  }

  private clearProvisional(): void {
    this.message = undefined;
    this.content = [];
    this.blocks.clear();
    this.toolBlockIndexes.clear();
    this.provisionalBytes = 0;
  }

  private enterRecovery(error: DeltaAssemblyError): never {
    this.clearProvisional();
    this.toolExecutions.clear();
    this.recoveryRequired = true;
    throw error;
  }
}
