import {
  accessSync,
  chmodSync,
  constants,
  existsSync,
  lstatSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { describe, it } from "vitest";
import { GroqTranscriber } from "../src/voice/groq-client.js";
import { VoiceRateLedger } from "../src/voice/rate-ledger.js";

const LIVE_REQUESTED = process.env["PI_GROQ_LIVE"] === "1";
const liveIt = LIVE_REQUESTED ? it : it.skip;
const KEY_PATH = join(homedir(), ".groq_key");
const SAY_PATH = "/usr/bin/say";
const AFCONVERT_PATH = "/usr/bin/afconvert";
const PHRASE = "Cobalt lantern seven confirms mobile dictation.";
const MIN_DURATION_SECONDS = 2;
const MAX_DURATION_SECONDS = 8;
const MAX_WAVE_BYTES = MAX_DURATION_SECONDS * 16_000 * 2 + 64 * 1_024;

function requireLive(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function requirePrerequisites(): void {
  requireLive(process.platform === "darwin", "PI_GROQ_LIVE=1 requires macOS");
  for (const tool of [SAY_PATH, AFCONVERT_PATH]) {
    try {
      accessSync(tool, constants.X_OK);
    } catch {
      throw new Error("PI_GROQ_LIVE=1 requires macOS speech fixture tools");
    }
  }

  let metadata;
  try {
    metadata = lstatSync(KEY_PATH);
  } catch {
    throw new Error("PI_GROQ_LIVE=1 requires an owner-only ~/.groq_key");
  }
  const ownerUid = process.getuid?.();
  requireLive(
    ownerUid !== undefined
      && metadata.isFile()
      && !metadata.isSymbolicLink()
      && metadata.uid === ownerUid
      && (metadata.mode & 0o777) === 0o600
      && metadata.size > 0
      && metadata.size <= 4_096,
    "PI_GROQ_LIVE=1 requires an owner-only ~/.groq_key",
  );
}

function runQuietly(command: string, arguments_: readonly string[]): void {
  const result = spawnSync(command, arguments_, {
    stdio: "ignore",
    timeout: 30_000,
  });
  requireLive(result.error === undefined && result.status === 0, "synthetic speech fixture generation failed");
}

function generateSpokenPcm(directory: string): Buffer {
  const aiffPath = join(directory, "spoken.aiff");
  const wavePath = join(directory, "spoken.wav");
  runQuietly(SAY_PATH, ["-v", "Samantha", "-r", "155", "-o", aiffPath, PHRASE]);
  chmodSync(aiffPath, 0o600);
  runQuietly(AFCONVERT_PATH, [aiffPath, wavePath, "-f", "WAVE", "-d", "LEI16@16000", "-c", "1"]);
  chmodSync(wavePath, 0o600);
  requireLive((statSync(aiffPath).mode & 0o777) === 0o600, "synthetic source audio permissions are unsafe");
  requireLive((statSync(wavePath).mode & 0o777) === 0o600, "synthetic converted audio permissions are unsafe");
  requireLive(statSync(wavePath).size <= MAX_WAVE_BYTES, "synthetic speech fixture is oversized");
  return extractPcm16Mono16k(readFileSync(wavePath));
}

function extractPcm16Mono16k(wave: Buffer): Buffer {
  requireLive(wave.length >= 44 && wave.toString("ascii", 0, 4) === "RIFF" && wave.toString("ascii", 8, 12) === "WAVE", "synthetic speech fixture is not WAVE");
  let format: Buffer | undefined;
  let audio: Buffer | undefined;
  let offset = 12;
  while (offset + 8 <= wave.length) {
    const chunkId = wave.toString("ascii", offset, offset + 4);
    const chunkSize = wave.readUInt32LE(offset + 4);
    const dataOffset = offset + 8;
    const dataEnd = dataOffset + chunkSize;
    requireLive(dataEnd <= wave.length, "synthetic speech fixture has an invalid chunk");
    if (chunkId === "fmt ") format = wave.subarray(dataOffset, dataEnd);
    if (chunkId === "data") audio = wave.subarray(dataOffset, dataEnd);
    offset = dataEnd + (chunkSize % 2);
  }

  requireLive(format !== undefined && format.length >= 16, "synthetic speech fixture has no format");
  requireLive(
    format.readUInt16LE(0) === 1
      && format.readUInt16LE(2) === 1
      && format.readUInt32LE(4) === 16_000
      && format.readUInt32LE(8) === 32_000
      && format.readUInt16LE(12) === 2
      && format.readUInt16LE(14) === 16,
    "synthetic speech fixture is not 16 kHz mono s16le",
  );
  requireLive(audio !== undefined && audio.length > 0 && audio.length % 2 === 0, "synthetic speech fixture has no PCM audio");
  return Buffer.from(audio);
}

function normalizeTranscript(text: string): Set<string> {
  return new Set(text.normalize("NFKC").toLocaleLowerCase("en-US").match(/[\p{L}\p{N}]+/gu) ?? []);
}

describe("opt-in live Groq", () => {
  liveIt("transcribes bounded synthetic speech and cleans local state", async () => {
    requirePrerequisites();
    const directory = mkdtempSync(join(tmpdir(), "pi-mobile-groq-live-"));
    let ledger: VoiceRateLedger | undefined;
    try {
      const pcm = generateSpokenPcm(directory);
      const durationSeconds = pcm.length / (16_000 * 2);
      requireLive(durationSeconds >= MIN_DURATION_SECONDS && durationSeconds <= MAX_DURATION_SECONDS, "synthetic speech duration is outside the live-test bound");

      ledger = new VoiceRateLedger(join(directory, "voice.db"));
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 30_000);
      let text: string;
      try {
        text = await new GroqTranscriber({ ledger, keyPath: KEY_PATH }).transcribe("live-spoken-fixture", pcm, controller.signal);
      } finally {
        clearTimeout(timeout);
      }

      requireLive(text.trim().length > 0, "live transcription returned no text");
      const words = normalizeTranscript(text);
      const requiredKeywordGroups = [["cobalt"], ["lantern"], ["seven", "7"], ["dictation"]];
      requireLive(requiredKeywordGroups.every((group) => group.some((word) => words.has(word))), "live transcription missed synthetic fixture keywords");

      const totals = ledger.totals(Date.now());
      requireLive(totals.attempts >= 1 && totals.attempts <= 4, "live transcription ledger attempt count is invalid");
      requireLive(Math.abs(totals.encodedSeconds - durationSeconds * totals.attempts) < 1e-6, "live transcription ledger encoded duration is invalid");
      requireLive(Math.abs(totals.billedSeconds - Math.max(durationSeconds, 10) * totals.attempts) < 1e-6, "live transcription ledger billed duration is invalid");
      requireLive(Math.abs(totals.estimatedUsd - totals.billedSeconds / 3_600 * ledger.limits.usdPerBilledHour) < 1e-12, "live transcription ledger cost is invalid");
    } finally {
      try {
        ledger?.close();
      } finally {
        rmSync(directory, { recursive: true, force: true });
      }
    }
    requireLive(!existsSync(directory), "live transcription temporary directory was not removed");
  }, 45_000);
});
