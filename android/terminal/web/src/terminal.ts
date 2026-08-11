import { installedNarrowStructuredClone } from "./compat.js";
import { Terminal } from "@xterm/xterm";
import {
  MAX_TERMINAL_DATA_BYTES,
  decodeTerminalPacket,
  encodeBase64,
  encodeTerminalPacket,
  incrementUint64,
  parseNativeCommand,
} from "./protocol.js";

interface NativeChannel {
  postMessage(value: string | ArrayBuffer): void;
}

declare global {
  interface Window {
    pimobile?: NativeChannel;
  }
}

function element<T extends HTMLElement>(id: string, constructor: { new(): T }): T {
  const value = document.getElementById(id);
  if (!(value instanceof constructor)) throw new Error("TERMINAL_DOM_MISSING");
  return value;
}

function report(value: object): void {
  window.pimobile?.postMessage(JSON.stringify(value));
}

function cloneCanary(): boolean {
  const source = { enabled: true, nested: [1, "two", null] };
  const clone = structuredClone(source);
  if (clone === source || clone.nested === source.nested || clone.nested[1] !== "two") return false;
  let rejectedObject = false;
  let rejectedCycle = false;
  try {
    structuredClone(new Date());
  } catch {
    rejectedObject = true;
  }
  const cyclic: { self?: object } = {};
  cyclic.self = cyclic;
  try {
    structuredClone(cyclic);
  } catch {
    rejectedCycle = true;
  }
  return (!installedNarrowStructuredClone || rejectedObject) && rejectedCycle;
}

function boot(): void {
  const shell = element("terminal-shell", HTMLElement);
  const root = element("terminal", HTMLDivElement);
  const status = element("terminal-status", HTMLDivElement);
  const reconnect = element("terminal-reconnect", HTMLDivElement);
  const scrollbackIndicator = element("terminal-scrollback-indicator", HTMLDivElement);
  const selectionIndicator = element("terminal-selection-indicator", HTMLDivElement);
  const linkCopy = element("terminal-link-copy", HTMLDivElement);
  const history = element("terminal-history", HTMLElement);
  const historyMeta = element("terminal-history-meta", HTMLDivElement);
  const historyTruncated = element("terminal-history-truncated", HTMLParagraphElement);
  const historyContent = element("terminal-history-content", HTMLPreElement);
  const historyClose = element("terminal-history-close", HTMLButtonElement);
  const encoder = new TextEncoder();
  const terminal = new Terminal({
    allowProposedApi: true,
    convertEol: false,
    cursorBlink: true,
    cursorStyle: "block",
    disableStdin: false,
    drawBoldTextInBrightColors: true,
    fontFamily: '"Roboto Mono", "Noto Sans Mono", "Droid Sans Mono", monospace',
    fontSize: 16,
    fontWeight: "400",
    fontWeightBold: "700",
    lineHeight: 1.2,
    minimumContrastRatio: 4.5,
    rightClickSelectsWord: true,
    screenReaderMode: true,
    scrollback: 5000,
    scrollOnUserInput: true,
    tabStopWidth: 8,
    cols: 80,
    rows: 24,
    theme: {
      background: "#10141b",
      foreground: "#e8edf5",
      cursor: "#d9e8ff",
      cursorAccent: "#10141b",
      selectionBackground: "#36577e",
      selectionForeground: "#ffffff",
      brightBlack: "#738197",
      brightBlue: "#8ab4ff",
    },
  });
  let generation: bigint | undefined;
  let nextInputSequence: bigint | undefined;
  let nextOutputSequence: bigint | undefined;
  let connected = false;
  let arrayBufferBridge = false;
  let canaryCapturing = true;
  let canaryInput = "";
  let canaryPassed = false;
  let linkCopyTimeout: number | undefined;
  let viewingScrollback = false;
  const forcedCanaryFailure = new URLSearchParams(window.location.search).has("forceCanaryFailure");

  function showStatus(text: string, kind: "connected" | "disconnected" | "warning"): void {
    status.textContent = text;
    status.dataset.kind = kind;
    status.hidden = false;
    const reconnecting = kind === "disconnected";
    shell.toggleAttribute("data-reconnecting", reconnecting);
    reconnect.hidden = !reconnecting;
  }

  function updateScrollbackIndicator(): void {
    const active = terminal.buffer.active;
    const nextViewingScrollback = active.viewportY < active.baseY;
    if (nextViewingScrollback === viewingScrollback) return;
    viewingScrollback = nextViewingScrollback;
    scrollbackIndicator.hidden = !viewingScrollback;
  }

  function showLinkCopy(text: string): void {
    linkCopy.textContent = text;
    linkCopy.hidden = false;
    if (linkCopyTimeout !== undefined) window.clearTimeout(linkCopyTimeout);
    linkCopyTimeout = window.setTimeout(() => {
      linkCopy.hidden = true;
      linkCopyTimeout = undefined;
    }, 2200);
  }

  function fallbackCopy(text: string): boolean {
    const copyArea = document.createElement("textarea");
    copyArea.value = text;
    copyArea.setAttribute("readonly", "");
    copyArea.style.position = "fixed";
    copyArea.style.opacity = "0";
    document.body.append(copyArea);
    copyArea.select();
    const copied = document.execCommand("copy");
    copyArea.remove();
    return copied;
  }

  function copyLink(text: string): void {
    const clipboard = navigator.clipboard;
    if (clipboard?.writeText !== undefined) {
      void clipboard.writeText(text).then(() => {
        showLinkCopy("Link copied");
        report({ type: "terminal.linkCopied" });
      }).catch(() => {
        if (fallbackCopy(text)) {
          showLinkCopy("Link copied");
          report({ type: "terminal.linkCopied" });
        } else {
          showLinkCopy("Unable to copy link");
        }
      });
      return;
    }
    if (fallbackCopy(text)) {
      showLinkCopy("Link copied");
      report({ type: "terminal.linkCopied" });
    } else {
      showLinkCopy("Unable to copy link");
    }
  }

  function sendInput(data: string): void {
    if (!connected || generation === undefined || nextInputSequence === undefined) {
      showStatus("Input not sent. Reconnect before typing.", "warning");
      return;
    }
    const bytes = encoder.encode(data);
    for (let offset = 0; offset < bytes.byteLength; offset += MAX_TERMINAL_DATA_BYTES) {
      const sequence = nextInputSequence;
      if (sequence === undefined) {
        connected = false;
        terminal.options.disableStdin = true;
        showStatus("Terminal input sequence exhausted. Reconnect required.", "warning");
        report({ type: "terminal.resetRequired", reason: "INPUT_SEQUENCE_EXHAUSTED" });
        return;
      }
      const chunk = bytes.subarray(offset, Math.min(offset + MAX_TERMINAL_DATA_BYTES, bytes.byteLength));
      if (arrayBufferBridge) {
        window.pimobile?.postMessage(encodeTerminalPacket(generation, sequence, chunk));
      } else {
        report({
          type: "terminal.input",
          generation: generation.toString(),
          sequence: sequence.toString(),
          bytes: encodeBase64(chunk),
        });
      }
      nextInputSequence = incrementUint64(sequence);
    }
  }

  terminal.onData((data) => {
    if (canaryCapturing) {
      canaryInput += data;
      return;
    }
    sendInput(data);
  });
  terminal.open(root);
  terminal.onScroll(updateScrollbackIndicator);
  terminal.onSelectionChange(() => {
    const selected = terminal.hasSelection();
    root.toggleAttribute("data-selection-active", selected);
    selectionIndicator.hidden = !selected;
  });
  terminal.registerLinkProvider({
    provideLinks(bufferLineNumber, callback) {
      const line = terminal.buffer.active.getLine(bufferLineNumber - 1);
      const text = line?.translateToString(true) ?? "";
      const links = [...text.matchAll(/https?:\/\/[^\s<>"'`]+/g)].map((match) => {
        const linkText = match[0];
        const start = (match.index ?? 0) + 1;
        return {
          range: {
            start: { x: start, y: bufferLineNumber },
            end: { x: start + linkText.length, y: bufferLineNumber },
          },
          text: linkText,
          decorations: { pointerCursor: true, underline: true },
          activate(event: MouseEvent): void {
            event.preventDefault();
            copyLink(linkText);
          },
        };
      });
      callback(links.length === 0 ? undefined : links);
    },
  });
  terminal.textarea?.setAttribute("aria-label", "Pi terminal input");
  terminal.textarea?.setAttribute("autocomplete", "off");
  terminal.textarea?.setAttribute("autocapitalize", "off");
  terminal.textarea?.setAttribute("autocorrect", "off");
  terminal.textarea?.setAttribute("inputmode", "text");
  terminal.textarea?.setAttribute("spellcheck", "false");
  terminal.textarea?.addEventListener("focus", () => report({ type: "terminal.focus", focused: true }));
  terminal.textarea?.addEventListener("blur", () => report({ type: "terminal.focus", focused: false }));
  terminal.textarea?.addEventListener("compositionstart", () => report({ type: "terminal.composition", composing: true }));
  terminal.textarea?.addEventListener("compositionend", () => report({ type: "terminal.composition", composing: false }));

  function fit(): void {
    const measure = terminal.element?.querySelector<HTMLElement>(".xterm-char-measure-element");
    if (measure === null || measure === undefined) return;
    const cell = measure.getBoundingClientRect();
    const width = root.clientWidth;
    const height = root.clientHeight;
    if (cell.width <= 0 || cell.height <= 0 || width <= 0 || height <= 0) return;
    const cols = Math.max(2, Math.min(1000, Math.floor(width / cell.width)));
    const rows = Math.max(1, Math.min(1000, Math.floor(height / cell.height)));
    if (cols === terminal.cols && rows === terminal.rows) return;
    terminal.resize(cols, rows);
    report({ type: "terminal.resize", cols, rows });
  }

  function closeHistory(): void {
    history.hidden = true;
    root.removeAttribute("aria-hidden");
    report({ type: "terminal.history.closed" });
    terminal.focus();
  }

  historyClose.addEventListener("click", closeHistory);
  history.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      closeHistory();
    }
  });
  new ResizeObserver(fit).observe(root);
  window.visualViewport?.addEventListener("resize", fit);

  function acceptOutput(packet: { readonly generation: bigint; readonly sequence: bigint; readonly bytes: Uint8Array } | undefined): void {
    if (packet === undefined || !connected || generation === undefined || packet.generation !== generation || nextOutputSequence === undefined || packet.sequence !== nextOutputSequence) {
      connected = false;
      terminal.options.disableStdin = true;
      showStatus("Terminal output gap. Reconnect for a fresh visible-pane redraw.", "warning");
      report({ type: "terminal.resetRequired", reason: "OUTPUT_SEQUENCE_GAP" });
      return;
    }
    nextOutputSequence = incrementUint64(packet.sequence);
    terminal.write(packet.bytes);
  }

  window.addEventListener("message", (event: MessageEvent<unknown>) => {
    if (event.data instanceof ArrayBuffer) {
      acceptOutput(decodeTerminalPacket(event.data));
      return;
    }
    if (typeof event.data !== "string") return;
    const command = parseNativeCommand(event.data);
    if (command === undefined) {
      report({ type: "terminal.resetRequired", reason: "CONTROL_MESSAGE_INVALID" });
      return;
    }
    switch (command.type) {
      case "terminal.generation":
        if (!canaryPassed) break;
        generation = command.generation;
        arrayBufferBridge = command.arrayBufferBridge;
        nextInputSequence = 0n;
        nextOutputSequence = 0n;
        connected = command.connected;
        terminal.reset();
        terminal.options.disableStdin = !connected;
        showStatus(
          connected
            ? "Connected. Prior client scrollback was not restored; awaiting visible-pane redraw."
            : "Disconnected. Terminal contents were not restored.",
          connected ? "connected" : "disconnected",
        );
        break;
      case "terminal.output":
        acceptOutput(command);
        break;
      case "terminal.connection":
        connected = command.connected;
        terminal.options.disableStdin = !connected;
        showStatus(
          connected ? "Connected." : "Disconnected. Uncertain input is never replayed.",
          connected ? "connected" : "disconnected",
        );
        break;
      case "terminal.paste":
        if (connected) terminal.paste(command.text);
        break;
      case "terminal.key":
        if (connected) terminal.input(command.data, true);
        break;
      case "terminal.focus":
        terminal.focus();
        break;
      case "terminal.history": {
        if (generation === undefined || command.generation !== generation) {
          report({ type: "terminal.resetRequired", reason: "HISTORY_GENERATION_MISMATCH" });
          break;
        }
        const markers: string[] = [];
        if (command.truncatedLines) markers.push("Earlier history lines were truncated.");
        if (command.truncatedBytes) markers.push("History was truncated at the byte limit.");
        historyMeta.textContent = `Read-only server capture from ${command.capturedAt}${markers.length === 0 ? "." : `. ${markers.join(" ")}`}`;
        historyTruncated.hidden = markers.length === 0;
        historyTruncated.textContent = markers.length === 0 ? "" : markers.join(" ");
        historyContent.textContent = command.text;
        history.hidden = false;
        root.setAttribute("aria-hidden", "true");
        historyClose.focus();
        break;
      }
      case "terminal.history.close":
        closeHistory();
        break;
      case "terminal.restored":
        generation = undefined;
        nextInputSequence = undefined;
        nextOutputSequence = undefined;
        connected = false;
        arrayBufferBridge = false;
        terminal.reset();
        terminal.options.disableStdin = true;
        showStatus("Terminal screen and scrollback were not saved. Reconnect for a fresh redraw.", "warning");
        break;
    }
  });

  terminal.resize(81, 25);
  terminal.input("x", true);
  terminal.write("\u001b[>3u\u001b[?2004hPi Mobile ✓ 界 e\u0301 🙂 \u001b[38;2;1;2;3mTC\u001b[0m \u001b]8;;https://example.invalid/\u001b\\L\u001b]8;;\u001b\\\r\n", () => {
    terminal.textarea?.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, code: "KeyA", key: "a" }));
    terminal.textarea?.dispatchEvent(new KeyboardEvent("keyup", { bubbles: true, code: "KeyA", key: "a" }));
    terminal.paste("p");
    const line = terminal.buffer.active.getLine(0);
    const rendered = line?.translateToString(true) ?? "";
    let wide = false;
    let combining = false;
    let truecolor = false;
    if (line !== undefined) {
      for (let column = 0; column < line.length; column += 1) {
        const cell = line.getCell(column);
        if (cell?.getChars() === "界" && cell.getWidth() === 2) wide = true;
        if (cell?.getChars() === "e\u0301") combining = true;
        if (cell?.getChars() === "T" && cell.getFgColor() === 0x010203) truecolor = true;
      }
    }
    const checks = {
      weakRef: typeof WeakRef === "function",
      resizeObserver: typeof ResizeObserver === "function",
      clone: cloneCanary(),
      dimensions: terminal.cols === 81 && terminal.rows === 25,
      input: canaryInput.startsWith("x"),
      bracketedPaste: canaryInput.includes("\u001b[200~p\u001b[201~"),
      rendered: rendered.includes("Pi Mobile") && rendered.includes("🙂") && rendered.includes("TC L"),
      wide,
      combining,
      truecolor,
    };
    const failedChecks = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name.toUpperCase());
    if (forcedCanaryFailure) failedChecks.push("FORCED");
    const ok = failedChecks.length === 0;
    report({
      type: "terminal.canary",
      ok,
      error: ok ? "" : `TERMINAL_RUNTIME_CANARY_FAILED_${failedChecks.join("_")}`,
      cols: terminal.cols,
      rows: terminal.rows,
      input: canaryInput,
      hasWeakRef: typeof WeakRef === "function",
      hasStructuredClone: typeof structuredClone === "function",
      narrowStructuredClone: installedNarrowStructuredClone,
      wide,
      combining,
      truecolor,
      keyRelease: canaryInput.includes(":3u"),
      bracketedPaste: canaryInput.includes("\u001b[200~p\u001b[201~"),
      osc8: rendered.includes("TC L"),
      binaryBridge: typeof ArrayBuffer === "function" ? "array-buffer-or-base64" : "base64",
    });
    if (!ok) {
      terminal.options.disableStdin = true;
      showStatus("Terminal runtime self-check failed. This terminal stays disconnected.", "warning");
      return;
    }
    terminal.reset();
    terminal.options.disableStdin = true;
    showStatus("Waiting for a terminal connection. No terminal screen is restored locally.", "disconnected");
    canaryCapturing = false;
    canaryPassed = true;
    report({ type: "terminal.ready", canaryOk: true });
    requestAnimationFrame(fit);
  });
}

try {
  boot();
} catch {
  report({ type: "terminal.canary", ok: false, error: "TERMINAL_RUNTIME_BOOT_FAILED" });
}
