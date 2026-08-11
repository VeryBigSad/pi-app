import type { TerminalKeyInput } from "./types.js";
import { TerminalError } from "./types.js";

const csiLetterKeys: Readonly<Record<string, string>> = {
  ArrowDown: "B",
  ArrowLeft: "D",
  ArrowRight: "C",
  ArrowUp: "A",
  End: "F",
  Home: "H",
};

const csiTildeKeys: Readonly<Record<string, number>> = {
  Delete: 3,
  F5: 15,
  F6: 17,
  F7: 18,
  F8: 19,
  F9: 20,
  F10: 21,
  F11: 23,
  F12: 24,
  Insert: 2,
  PageDown: 6,
  PageUp: 5,
};

const ss3Keys: Readonly<Record<string, string>> = { F1: "P", F2: "Q", F3: "R", F4: "S" };

const privateKeyCodes: Readonly<Record<string, number>> = {
  CapsLock: 57_358,
  ContextMenu: 57_363,
  NumLock: 57_360,
  Pause: 57_362,
  PrintScreen: 57_361,
  ScrollLock: 57_359,
};

const basicKeyCodes: Readonly<Record<string, number>> = { Backspace: 127, Enter: 13, Escape: 27, Tab: 9 };
const actionCode = { down: 1, repeat: 2, up: 3 } as const;
const modifierBits = { shift: 1, alt: 2, control: 4, meta: 8 } as const;

export function encodeKittyKey(input: TerminalKeyInput): Uint8Array {
  if (input.action === "text") throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
  const modifiers = encodeModifiers(input.modifiers);
  const event = actionCode[input.action];
  const csiLetter = csiLetterKeys[input.key];
  if (csiLetter !== undefined) return Buffer.from(csiLetterSequence(csiLetter, modifiers, event), "ascii");
  const ss3 = ss3Keys[input.key];
  if (ss3 !== undefined) return Buffer.from(ss3Sequence(ss3, modifiers, event), "ascii");
  const tilde = csiTildeKeys[input.key];
  if (tilde !== undefined) return Buffer.from(csiTildeSequence(tilde, modifiers, event), "ascii");
  return Buffer.from(csiUSequence(keyCode(input.key), modifiers, event), "ascii");
}

export function encodeTextKey(input: TerminalKeyInput): Uint8Array {
  if (input.action !== "text" || input.modifiers.length !== 0 || input.key.length === 0 || input.key.length > 64 || input.key.includes("\u0000")) {
    throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
  }
  return Buffer.from(input.key, "utf8");
}

function encodeModifiers(values: TerminalKeyInput["modifiers"]): number {
  let bits = 0;
  const seen = new Set<string>();
  for (const modifier of values) {
    if (seen.has(modifier)) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
    seen.add(modifier);
    bits |= modifierBits[modifier];
  }
  return bits === 0 ? 0 : bits + 1;
}

function csiLetterSequence(letter: string, modifiers: number, event: 1 | 2 | 3): string {
  if (modifiers === 0 && event === 1) return `\u001b[${letter}`;
  return `\u001b[1;${String(modifiers === 0 ? 1 : modifiers)}${event === 1 ? "" : `:${String(event)}`}${letter}`;
}

function ss3Sequence(letter: string, modifiers: number, event: 1 | 2 | 3): string {
  if (modifiers === 0 && event === 1) return `\u001bO${letter}`;
  return `\u001b[1;${String(modifiers === 0 ? 1 : modifiers)}${event === 1 ? "" : `:${String(event)}`}${letter}`;
}

function csiTildeSequence(number: number, modifiers: number, event: 1 | 2 | 3): string {
  if (modifiers === 0 && event === 1) return `\u001b[${String(number)}~`;
  return `\u001b[${String(number)};${String(modifiers === 0 ? 1 : modifiers)}${event === 1 ? "" : `:${String(event)}`}~`;
}

function csiUSequence(codePoint: number, modifiers: number, event: 1 | 2 | 3): string {
  if (modifiers === 0 && event === 1) return `\u001b[${String(codePoint)}u`;
  return `\u001b[${String(codePoint)};${String(modifiers === 0 ? 1 : modifiers)}${event === 1 ? "" : `:${String(event)}`}u`;
}

function keyCode(key: string): number {
  const basic = basicKeyCodes[key];
  if (basic !== undefined) return basic;
  const privateCode = privateKeyCodes[key];
  if (privateCode !== undefined) return privateCode;
  const functionMatch = /^F(1[3-9]|2[0-9]|3[0-5])$/.exec(key);
  if (functionMatch !== null) return 57_376 + Number(functionMatch[1]) - 13;
  const point = key.codePointAt(0);
  if (point === undefined || key.length !== (point > 0xffff ? 2 : 1) || key.includes("\u0000") || (point >= 0xd800 && point <= 0xdfff)) {
    throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
  }
  return point;
}
