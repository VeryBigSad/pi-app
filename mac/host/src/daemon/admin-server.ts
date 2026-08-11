import { chmod, mkdir, unlink } from "node:fs/promises";
import { createConnection, createServer, type Server, type Socket } from "node:net";
import { dirname, isAbsolute } from "node:path";

const MAX_LINE_BYTES = 64 * 1024;
const MAX_CONNECTIONS = 8;

export interface AdminRequest {
  readonly method: string;
  readonly params?: Record<string, unknown>;
}

export type AdminHandler = (request: AdminRequest) => Promise<unknown>;

/**
 * Newline-delimited JSON admin RPC over a mode-0600 Unix socket. Only stable
 * error codes cross the wire; payloads and secrets never enter the log path.
 */
export class AdminServer {
  private server: Server | undefined;

  constructor(
    private readonly socketPath: string,
    private readonly handler: AdminHandler,
  ) {
    if (!isAbsolute(socketPath)) throw new TypeError("admin socket path must be absolute");
  }

  async start(): Promise<void> {
    await mkdir(dirname(this.socketPath), { recursive: true, mode: 0o700 });
    await unlink(this.socketPath).catch(() => undefined);
    const server = createServer((socket) => this.handle(socket));
    server.maxConnections = MAX_CONNECTIONS;
    await new Promise<void>((resolveListen, rejectListen) => {
      server.once("error", rejectListen);
      server.listen(this.socketPath, () => {
        server.removeListener("error", rejectListen);
        resolveListen();
      });
    });
    await chmod(this.socketPath, 0o600);
    this.server = server;
  }

  async stop(): Promise<void> {
    const server = this.server;
    this.server = undefined;
    if (server === undefined) return;
    await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
    await unlink(this.socketPath).catch(() => undefined);
  }

  private handle(socket: Socket): void {
    socket.setNoDelay(true);
    let buffered = Buffer.alloc(0);
    let responded = false;
    const fail = (code: string): void => {
      if (responded) return;
      responded = true;
      socket.end(`${JSON.stringify({ ok: false, error: { code } })}\n`);
    };
    socket.on("data", (chunk: Buffer) => {
      buffered = Buffer.concat([buffered, chunk]);
      if (buffered.byteLength > MAX_LINE_BYTES) {
        fail("REQUEST_TOO_LARGE");
        socket.destroy();
        return;
      }
      const newline = buffered.indexOf(0x0a);
      if (newline === -1) return;
      const line = buffered.subarray(0, newline).toString("utf8");
      socket.pause();
      void this.dispatch(line)
        .then((response) => {
          if (!responded) {
            responded = true;
            socket.end(`${response}\n`);
          }
        })
        .catch(() => fail("ADMIN_INTERNAL"));
    });
    socket.on("error", () => socket.destroy());
  }

  private async dispatch(line: string): Promise<string> {
    let parsed: unknown;
    try {
      parsed = JSON.parse(line);
    } catch {
      return JSON.stringify({ ok: false, error: { code: "INVALID_REQUEST" } });
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return JSON.stringify({ ok: false, error: { code: "INVALID_REQUEST" } });
    }
    const method = (parsed as Record<string, unknown>)["method"];
    const params = (parsed as Record<string, unknown>)["params"];
    if (typeof method !== "string" || method.length === 0 || method.length > 64) {
      return JSON.stringify({ ok: false, error: { code: "INVALID_REQUEST" } });
    }
    if (params !== undefined && (typeof params !== "object" || params === null || Array.isArray(params))) {
      return JSON.stringify({ ok: false, error: { code: "INVALID_REQUEST" } });
    }
    try {
      const result = await this.handler({
        method,
        ...(params === undefined ? {} : { params: params as Record<string, unknown> }),
      });
      return JSON.stringify({ ok: true, result: result ?? null });
    } catch (error) {
      const code = error instanceof Error && /^[A-Z][A-Z0-9_]{1,63}$/.test(error.message) ? error.message : "ADMIN_REJECTED";
      return JSON.stringify({ ok: false, error: { code } });
    }
  }
}

export async function adminCall(socketPath: string, request: AdminRequest, timeoutMs = 15_000): Promise<unknown> {
  return await new Promise<unknown>((resolveCall, rejectCall) => {
    const socket = createConnection(socketPath);
    const timer = setTimeout(() => {
      socket.destroy();
      rejectCall(new Error("ADMIN_TIMEOUT"));
    }, timeoutMs);
    let buffered = Buffer.alloc(0);
    socket.once("connect", () => {
      socket.write(`${JSON.stringify(request)}\n`);
    });
    socket.on("data", (chunk: Buffer) => {
      buffered = Buffer.concat([buffered, chunk]);
      const newline = buffered.indexOf(0x0a);
      if (newline === -1) return;
      clearTimeout(timer);
      const line = buffered.subarray(0, newline).toString("utf8");
      socket.destroy();
      try {
        const parsed = JSON.parse(line) as { ok?: unknown; result?: unknown; error?: { code?: unknown } };
        if (parsed.ok === true) {
          resolveCall(parsed.result);
          return;
        }
        const code = typeof parsed.error?.code === "string" ? parsed.error.code : "ADMIN_REJECTED";
        rejectCall(new Error(code));
      } catch {
        rejectCall(new Error("ADMIN_INVALID_RESPONSE"));
      }
    });
    socket.once("error", () => {
      clearTimeout(timer);
      rejectCall(new Error("DAEMON_UNAVAILABLE"));
    });
  });
}
