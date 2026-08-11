import WebSocket, { type ClientOptions } from "ws";
import type {
  RelaySocketConnectOptions,
  RelaySocketEvent,
  RelaySocketListener,
  RelayWebSocket,
  RelayWebSocketFactory,
} from "./types.js";

export class NodeWsWebSocketFactory implements RelayWebSocketFactory {
  connect(url: string, options: RelaySocketConnectOptions): RelayWebSocket {
    const clientOptions: ClientOptions = {
      perMessageDeflate: options.perMessageDeflate,
      maxPayload: options.maxPayload,
      handshakeTimeout: options.handshakeTimeoutMs,
      followRedirects: options.followRedirects,
      rejectUnauthorized: options.rejectUnauthorized,
      minVersion: options.minVersion,
    };
    if (options.headers !== undefined) clientOptions.headers = { ...options.headers };
    return new NodeWsSocket(new WebSocket(url, clientOptions));
  }
}

class NodeWsSocket implements RelayWebSocket {
  private readonly listeners = new Map<RelaySocketEvent, Map<RelaySocketListener, (...arguments_: unknown[]) => void>>();

  constructor(private readonly socket: WebSocket) {}

  get extensions(): string {
    return this.socket.extensions;
  }

  get readyState(): number {
    return this.socket.readyState;
  }

  on(event: RelaySocketEvent, listener: RelaySocketListener): this {
    const wrapper = this.wrap(event, listener);
    let eventListeners = this.listeners.get(event);
    if (eventListeners === undefined) {
      eventListeners = new Map();
      this.listeners.set(event, eventListeners);
    }
    eventListeners.set(listener, wrapper);
    this.socket.on(event, wrapper);
    return this;
  }

  off(event: RelaySocketEvent, listener: RelaySocketListener): this {
    const eventListeners = this.listeners.get(event);
    const wrapper = eventListeners?.get(listener);
    if (wrapper !== undefined) {
      this.socket.off(event, wrapper);
      eventListeners?.delete(listener);
    }
    return this;
  }

  send(
    data: string | Uint8Array,
    options: { readonly binary: boolean; readonly compress: false },
    callback: (error?: Error) => void,
  ): void {
    this.socket.send(data, options, callback);
  }

  ping(callback: (error?: Error) => void): void {
    this.socket.ping(callback);
  }

  pause(): void {
    this.socket.pause();
  }

  resume(): void {
    this.socket.resume();
  }

  close(code: number, reason: string): void {
    this.socket.close(code, reason);
  }

  terminate(): void {
    this.socket.terminate();
  }

  private wrap(_event: RelaySocketEvent, listener: RelaySocketListener): (...arguments_: unknown[]) => void {
    return (...arguments_) => listener(...arguments_);
  }
}
