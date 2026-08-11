import { Duplex } from "node:stream";
import {
  DATA_MESSAGE_MAX_BYTES,
  MAX_TUNNEL_WRITE_BYTES,
  RelayError,
  type RelaySocketListener,
  type RelayWebSocket,
} from "./types.js";

export interface RelayByteChannel {
  readonly readable: NodeJS.ReadableStream;
  readonly writable: NodeJS.WritableStream;
  close(): void;
}

export class RelayTunnel extends Duplex {
  private remoteClosed = false;
  private paused = false;
  private readonly messageListener: RelaySocketListener;
  private readonly closeListener: RelaySocketListener;
  private readonly errorListener: RelaySocketListener;

  constructor(private readonly socket: RelayWebSocket) {
    super({
      allowHalfOpen: false,
      readableHighWaterMark: DATA_MESSAGE_MAX_BYTES,
      writableHighWaterMark: DATA_MESSAGE_MAX_BYTES,
    });
    this.messageListener = (...arguments_) => this.receive(arguments_[0], arguments_[1]);
    this.closeListener = () => this.remoteClose();
    this.errorListener = () => this.destroy(new RelayError("RELAY_TRANSPORT", "relay data socket failed"));
    socket.on("message", this.messageListener);
    socket.on("close", this.closeListener);
    socket.on("error", this.errorListener);
  }

  byteChannel(): RelayByteChannel {
    return {
      readable: this,
      writable: this,
      close: () => this.destroy(),
    };
  }

  override _read(): void {
    if (this.paused) {
      this.paused = false;
      this.socket.resume();
    }
  }

  override _write(chunk: Buffer | string, encoding: BufferEncoding, callback: (error?: Error | null) => void): void {
    let bytes: Buffer;
    try {
      bytes = typeof chunk === "string" ? Buffer.from(chunk, encoding) : Buffer.from(chunk);
    } catch {
      callback(new RelayError("RELAY_DATA_PROTOCOL", "outbound relay bytes are invalid"));
      return;
    }
    if (bytes.byteLength === 0) {
      callback();
      return;
    }
    if (bytes.byteLength > MAX_TUNNEL_WRITE_BYTES) {
      callback(new RelayError("RELAY_RESOURCE_EXHAUSTED", "relay tunnel write exceeds bounds"));
      return;
    }
    this.sendChunks(bytes, 0, callback);
  }

  override _final(callback: (error?: Error | null) => void): void {
    if (!this.remoteClosed) this.socket.close(1000, "");
    callback();
  }

  override _destroy(error: Error | null, callback: (error?: Error | null) => void): void {
    this.socket.off("message", this.messageListener);
    this.socket.off("close", this.closeListener);
    this.socket.off("error", this.errorListener);
    if (!this.remoteClosed) this.socket.close(error === null ? 1000 : 1002, "");
    callback(error);
  }

  private receive(raw: unknown, isBinary: unknown): void {
    if (isBinary !== true) {
      this.protocolFault("relay tunnel received a non-binary frame");
      return;
    }
    let bytes: Buffer;
    try {
      bytes = normalizeBinary(raw);
    } catch (error) {
      if (error instanceof RelayError && error.code === "RELAY_RESOURCE_EXHAUSTED") {
        this.socket.close(1009, "");
        this.destroy(error);
      } else {
        this.protocolFault("relay tunnel received an invalid binary frame");
      }
      return;
    }
    if (bytes.byteLength > DATA_MESSAGE_MAX_BYTES) {
      this.socket.close(1009, "");
      this.destroy(new RelayError("RELAY_RESOURCE_EXHAUSTED", "relay tunnel frame exceeds bounds"));
      return;
    }
    if (!this.push(bytes)) {
      this.paused = true;
      this.socket.pause();
    }
  }

  private sendChunks(bytes: Buffer, offset: number, callback: (error?: Error | null) => void): void {
    if (offset >= bytes.byteLength) {
      callback();
      return;
    }
    if (this.socket.readyState !== 1 || this.remoteClosed) {
      callback(new RelayError("RELAY_NOT_READY", "relay tunnel is not writable"));
      return;
    }
    const chunk = bytes.subarray(offset, Math.min(offset + DATA_MESSAGE_MAX_BYTES, bytes.byteLength));
    this.socket.send(chunk, { binary: true, compress: false }, (error) => {
      if (error !== undefined) {
        callback(new RelayError("RELAY_TRANSPORT", "relay tunnel write failed"));
        return;
      }
      queueMicrotask(() => this.sendChunks(bytes, offset + chunk.byteLength, callback));
    });
  }

  private protocolFault(message: string): void {
    this.socket.close(1003, "");
    this.destroy(new RelayError("RELAY_DATA_PROTOCOL", message));
  }

  private remoteClose(): void {
    if (this.remoteClosed) return;
    this.remoteClosed = true;
    this.push(null);
    if (!this.writableEnded) this.end();
  }
}

function normalizeBinary(raw: unknown): Buffer {
  if (Buffer.isBuffer(raw)) return Buffer.from(raw);
  if (raw instanceof ArrayBuffer) return Buffer.from(raw);
  if (ArrayBuffer.isView(raw)) return Buffer.from(raw.buffer, raw.byteOffset, raw.byteLength);
  if (Array.isArray(raw)) {
    const parts = raw.map((part) => normalizeBinary(part));
    const size = parts.reduce((total, part) => total + part.byteLength, 0);
    if (size > DATA_MESSAGE_MAX_BYTES) throw new RelayError("RELAY_RESOURCE_EXHAUSTED", "relay tunnel frame exceeds bounds");
    return Buffer.concat(parts, size);
  }
  throw new RelayError("RELAY_DATA_PROTOCOL", "relay tunnel binary representation is invalid");
}
