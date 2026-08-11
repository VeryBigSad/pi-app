import type { Duplex } from "node:stream";
import type { ByteTransport } from "../gateway/types.js";

const MAX_READ_BYTES = 1024 * 1024 + 12;

/**
 * Adapts a full-duplex stream (TLS socket or spliced relay tunnel) to the bounded
 * gateway ByteTransport. Reads never buffer plaintext before admission beyond a
 * single in-flight chunk, writes are serialized by the stream itself, and close
 * codes never carry payload data.
 */
export class DuplexByteTransport implements ByteTransport {
  private reading: Promise<Uint8Array | null> | undefined;
  private closed = false;
  private pending: Buffer | undefined;

  constructor(private readonly stream: Duplex) {}

  read(maxBytes: number, signal: AbortSignal): Promise<Uint8Array | null> {
    if (this.closed) return Promise.resolve(null);
    if (this.reading !== undefined) return Promise.reject(new Error("concurrent transport read"));
    const bounded = Math.min(Math.max(1, maxBytes), MAX_READ_BYTES);
    const operation = new Promise<Uint8Array | null>((resolveRead, rejectRead) => {
      const stream = this.stream;
      const cleanup = (): void => {
        stream.removeListener("readable", onReadable);
        stream.removeListener("end", onEnd);
        stream.removeListener("error", onError);
        stream.removeListener("close", onClose);
        signal.removeEventListener("abort", onAbort);
      };
      const settle = (value: Uint8Array | null, error?: unknown): void => {
        cleanup();
        if (error === undefined) resolveRead(value);
        else rejectRead(error instanceof Error ? error : new Error("transport read failed"));
      };
      const onReadable = (): void => {
        if (this.pending !== undefined && this.pending.byteLength > 0) {
          const head = this.pending.subarray(0, bounded);
          this.pending = this.pending.subarray(head.byteLength);
          settle(new Uint8Array(head.buffer, head.byteOffset, head.byteLength));
          return;
        }
        // read(size) waits for the full size or EOF; read() drains whatever is
        // buffered. A TLS record smaller than maxBytes must still resolve.
        const chunk = stream.read() as Buffer | null;
        if (chunk === null) return;
        const head = chunk.subarray(0, bounded);
        const rest = chunk.subarray(head.byteLength);
        this.pending = rest.byteLength > 0 ? Buffer.from(rest) : undefined;
        settle(new Uint8Array(head.buffer, head.byteOffset, head.byteLength));
      };
      const onEnd = (): void => settle(null);
      const onClose = (): void => settle(null);
      const onError = (): void => settle(null, new Error("transport read failed"));
      const onAbort = (): void => settle(null, signal.reason);
      stream.on("readable", onReadable);
      stream.once("end", onEnd);
      stream.once("error", onError);
      stream.once("close", onClose);
      signal.addEventListener("abort", onAbort, { once: true });
      onReadable();
    });
    this.reading = operation;
    return operation.finally(() => {
      if (this.reading === operation) this.reading = undefined;
    });
  }

  write(bytes: Uint8Array, signal: AbortSignal): Promise<void> {
    if (this.closed) return Promise.reject(new Error("transport is closed"));
    signal.throwIfAborted();
    return new Promise<void>((resolveWrite, rejectWrite) => {
      const onAbort = (): void => rejectWrite(signal.reason instanceof Error ? signal.reason : new Error("write aborted"));
      signal.addEventListener("abort", onAbort, { once: true });
      this.stream.write(Buffer.from(bytes), (error) => {
        signal.removeEventListener("abort", onAbort);
        if (error === null || error === undefined) resolveWrite();
        else rejectWrite(error);
      });
    });
  }

  close(code: string): Promise<void> {
    void code;
    if (this.closed) return Promise.resolve();
    this.closed = true;
    this.stream.destroy();
    return Promise.resolve();
  }
}
