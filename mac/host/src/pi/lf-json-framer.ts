export const MAX_PI_RECORD_BYTES = 16 * 1024 * 1024;

export type PiFramingErrorCode =
  | "PI_RECORD_MALFORMED"
  | "PI_RECORD_TOO_LARGE"
  | "PI_STREAM_FAULTED"
  | "PI_STREAM_TRUNCATED";

export class PiFramingError extends Error {
  readonly code: PiFramingErrorCode;
  readonly context: Readonly<Record<string, number>>;

  constructor(code: PiFramingErrorCode, message: string, context: Readonly<Record<string, number>> = {}) {
    super(message);
    this.name = "PiFramingError";
    this.code = code;
    this.context = context;
  }
}

export interface PiJsonRecord {
  readonly rawBytes: Buffer;
  readonly rawJson: string;
  readonly value: Readonly<Record<string, unknown>>;
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Splits Pi stdout on LF bytes only and permanently faults after the first invalid record. */
export class StrictLfJsonFramer {
  readonly maxRecordBytes: number;
  private readonly pending: Buffer[] = [];
  private pendingBytes = 0;
  private faulted = false;
  private ended = false;

  constructor(maxRecordBytes = MAX_PI_RECORD_BYTES) {
    if (!Number.isSafeInteger(maxRecordBytes) || maxRecordBytes < 1) {
      throw new RangeError("maxRecordBytes must be a positive safe integer");
    }
    this.maxRecordBytes = maxRecordBytes;
  }

  push(chunk: Uint8Array): PiJsonRecord[] {
    this.assertWritable();
    const bytes = Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);
    const records: PiJsonRecord[] = [];
    let offset = 0;

    while (offset < bytes.length) {
      const newline = bytes.indexOf(0x0a, offset);
      if (newline === -1) {
        this.append(bytes.subarray(offset));
        break;
      }

      this.append(bytes.subarray(offset, newline));
      records.push(this.finishRecord());
      offset = newline + 1;
    }

    return records;
  }

  end(): void {
    this.assertWritable();
    this.ended = true;
    if (this.pendingBytes !== 0) {
      this.raise(
        new PiFramingError("PI_STREAM_TRUNCATED", "Pi stdout ended in the middle of a record", {
          bufferedBytes: this.pendingBytes,
        }),
      );
    }
  }

  private assertWritable(): void {
    if (this.faulted || this.ended) {
      throw new PiFramingError("PI_STREAM_FAULTED", "Pi stdout framer is no longer writable");
    }
  }

  private append(segment: Buffer): void {
    if (segment.length === 0) {
      return;
    }

    const nextBytes = this.pendingBytes + segment.length;
    if (nextBytes > this.maxRecordBytes + 1) {
      this.raiseTooLarge(nextBytes);
    }
    if (nextBytes === this.maxRecordBytes + 1 && segment[segment.length - 1] !== 0x0d) {
      this.raiseTooLarge(nextBytes);
    }

    this.pending.push(Buffer.from(segment));
    this.pendingBytes = nextBytes;
  }

  private finishRecord(): PiJsonRecord {
    let rawBytes = Buffer.concat(this.pending, this.pendingBytes);
    this.pending.length = 0;
    this.pendingBytes = 0;

    if (rawBytes.length > 0 && rawBytes[rawBytes.length - 1] === 0x0d) {
      rawBytes = rawBytes.subarray(0, rawBytes.length - 1);
    }
    if (rawBytes.length > this.maxRecordBytes) {
      this.raiseTooLarge(rawBytes.length);
    }

    let rawJson: string;
    try {
      rawJson = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(rawBytes);
    } catch {
      this.raise(new PiFramingError("PI_RECORD_MALFORMED", "Pi record is not valid UTF-8"));
    }

    let value: unknown;
    try {
      value = JSON.parse(rawJson);
    } catch {
      this.raise(new PiFramingError("PI_RECORD_MALFORMED", "Pi record is not valid JSON"));
    }
    if (!isJsonObject(value)) {
      this.raise(new PiFramingError("PI_RECORD_MALFORMED", "Pi record must be a JSON object"));
    }

    return { rawBytes: Buffer.from(rawBytes), rawJson, value };
  }

  private raiseTooLarge(size: number): never {
    return this.raise(
      new PiFramingError("PI_RECORD_TOO_LARGE", "Pi record exceeds the configured byte limit", {
        limitBytes: this.maxRecordBytes,
        observedBytes: size,
      }),
    );
  }

  private raise(error: PiFramingError): never {
    this.faulted = true;
    this.pending.length = 0;
    this.pendingBytes = 0;
    throw error;
  }
}
