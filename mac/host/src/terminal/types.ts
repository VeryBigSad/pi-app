export const MAX_TERMINAL_COLUMNS = 1_000;
export const MAX_TERMINAL_ROWS = 1_000;
export const MAX_TERMINAL_DATA_BYTES = 64 * 1_024;
export const MAX_TERMINAL_HISTORY_LINES = 5_000;
export const MAX_TERMINAL_HISTORY_BYTES = 1_024 * 1_024;
export const MAX_PENDING_INPUT_BYTES = 1_024 * 1_024;
export const MAX_PENDING_INPUTS = 512;
export const MAX_PENDING_OUTPUT_BYTES = 1_024 * 1_024;
export const MAX_PENDING_OUTPUTS = 512;

export type TerminalUnsupportedCode =
  | "HOST_UNSUPPORTED"
  | "TMUX_NOT_FOUND"
  | "TMUX_VERSION_UNSUPPORTED"
  | "NODE_PTY_UNAVAILABLE";

export type TerminalErrorCode =
  | "TERMINAL_INVALID_ARGUMENT"
  | "TERMINAL_NOT_FOUND"
  | "TERMINAL_ALREADY_EXISTS"
  | "TERMINAL_NOT_ATTACHED"
  | "TERMINAL_RESET_REQUIRED"
  | "TERMINAL_RESOURCE_EXHAUSTED"
  | "TERMINAL_PROCESS_FAILED"
  | "TERMINAL_CONTROL_FAILED"
  | "TERMINAL_HISTORY_FAILED"
  | "TERMINAL_CLOSED";

export class TerminalError extends Error {
  readonly code: TerminalErrorCode;

  constructor(code: TerminalErrorCode) {
    super(code);
    this.name = "TerminalError";
    this.code = code;
  }
}

export interface PtySpawnOptions {
  readonly name: string;
  readonly columns: number;
  readonly rows: number;
  readonly cwd: string;
  readonly env: Readonly<NodeJS.ProcessEnv>;
}

export interface TerminalPty {
  readonly pid: number;
  write(bytes: Uint8Array): void;
  resize(columns: number, rows: number): void;
  pause(): void;
  resume(): void;
  kill(signal?: NodeJS.Signals): void;
  onData(listener: (bytes: Uint8Array) => void): { dispose(): void };
  onExit(listener: (event: { readonly exitCode: number; readonly signal?: number }) => void): { dispose(): void };
}

export interface TerminalPtyFactory {
  spawn(executable: string, args: readonly string[], options: PtySpawnOptions): TerminalPty;
}

export interface TmuxRunOptions {
  readonly cwd?: string;
  readonly env?: Readonly<NodeJS.ProcessEnv>;
  readonly signal?: AbortSignal;
  readonly timeoutMs?: number;
  readonly captureBytes?: number;
  readonly captureMode?: "head" | "tail";
}

export interface TmuxRunResult {
  readonly exitCode: number;
  readonly signal: NodeJS.Signals | null;
  readonly stdout: Uint8Array;
  readonly stdoutBytes: number;
  readonly stdoutLines: number;
  readonly stdoutTruncated: boolean;
}

export interface TmuxChildProcess {
  write(bytes: Uint8Array): Promise<void>;
  kill(signal?: NodeJS.Signals): void;
  onData(listener: (bytes: Uint8Array) => void): { dispose(): void };
  onExit(listener: (event: { readonly exitCode: number | null; readonly signal: NodeJS.Signals | null }) => void): { dispose(): void };
}

export interface TmuxProcessFactory {
  run(args: readonly string[], options?: TmuxRunOptions): Promise<TmuxRunResult>;
  spawn(args: readonly string[], options?: Omit<TmuxRunOptions, "timeoutMs" | "captureBytes" | "captureMode">): TmuxChildProcess;
}

export interface CreateTerminalSessionOptions {
  readonly sessionId: string;
  readonly command: string;
  readonly args?: readonly string[];
  readonly cwd: string;
  readonly env?: Readonly<NodeJS.ProcessEnv>;
  readonly columns: number;
  readonly rows: number;
}

export interface TerminalOutput {
  readonly terminalGeneration: bigint;
  readonly sequence: bigint;
  readonly bytes: Uint8Array;
}

export interface TerminalMarker {
  readonly type: "attached" | "reconnected" | "reset_required" | "process_exit";
  readonly terminalGeneration: bigint;
  readonly reason: "initial" | "reconnect" | "sequence_gap" | "renderer_restart" | "resource_exhausted" | "process_exit";
}

export interface AttachTerminalOptions {
  readonly columns: number;
  readonly rows: number;
  readonly onOutput: (output: TerminalOutput) => void | Promise<void>;
  readonly onMarker?: (marker: TerminalMarker) => void | Promise<void>;
}

export interface TerminalInput {
  readonly terminalGeneration: bigint;
  readonly sequence: bigint;
  readonly bytes: Uint8Array;
}

export interface TerminalKeyInput {
  readonly terminalGeneration: bigint;
  readonly sequence: bigint;
  readonly key: string;
  readonly action: "down" | "repeat" | "up" | "text";
  readonly modifiers: readonly ("alt" | "control" | "meta" | "shift")[];
}

export interface TerminalHistoryRequest {
  readonly terminalGeneration: bigint;
  readonly maxLines: number;
  readonly maxBytes: number;
}

export interface TerminalHistoryResult {
  readonly terminalGeneration: bigint;
  readonly capturedAt: string;
  readonly text: string;
  readonly truncatedLines: boolean;
  readonly truncatedBytes: boolean;
}

export interface TerminalProcessExit {
  readonly exitCode: number | null;
  readonly signal: number | null;
  readonly cancelled: boolean;
}
