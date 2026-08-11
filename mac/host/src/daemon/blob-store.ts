import { createHash, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { open, rename, unlink, type FileHandle } from "node:fs/promises";
import { join } from "node:path";
import type {
  BlobOutput,
  BlobRuntime,
  BlobStreamMetadata,
  BlobStreamUpload,
  OutboundMessage,
} from "../gateway/types.js";
import { StreamGatewayError } from "../gateway/streams.js";

const ALLOWED_MEDIA_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const BLOB_TTL_MS = 60 * 60 * 1000;
const MAX_OPEN_STREAMS = 32;

/** Bounded on-disk blob uploads; files are mode 0600 inside the private data directory. */
export class BlobStore implements BlobRuntime {
  private readonly openStreams = new Set<string>();

  constructor(private readonly directory: string) {}

  open(metadata: BlobStreamMetadata, output: BlobOutput, signal: AbortSignal): Promise<BlobStreamUpload> {
    void output;
    if (this.openStreams.size >= MAX_OPEN_STREAMS) {
      throw new StreamGatewayError("STREAM_INVALID", "blob stream capacity reached");
    }
    if (!ALLOWED_MEDIA_TYPES.has(metadata.mediaType)) {
      throw new StreamGatewayError("STREAM_INVALID", "blob media type is unsupported");
    }
    this.openStreams.add(metadata.streamId);
    const temporaryPath = join(this.directory, `${metadata.streamId}.part`);
    const state = { handle: undefined as FileHandle | undefined, failed: false };
    const openHandle = async (): Promise<FileHandle> => {
      state.handle ??= await open(temporaryPath, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
      return state.handle;
    };
    const cleanup = async (): Promise<void> => {
      this.openStreams.delete(metadata.streamId);
      if (state.handle !== undefined) {
        await state.handle.close().catch(() => undefined);
        state.handle = undefined;
      }
    };
    return Promise.resolve({
      write: async (sequence, offset, data, writeSignal) => {
        writeSignal.throwIfAborted();
        if (state.failed) throw new StreamGatewayError("STREAM_INVALID", "blob stream failed");
        const handle = await openHandle();
        await handle.write(data, 0, data.byteLength, Number(offset));
      },
      close: async (length, sha256, closeSignal): Promise<OutboundMessage | undefined> => {
        closeSignal.throwIfAborted();
        const handle = await openHandle();
        await handle.sync();
        const metadataBuffer = await handle.readFile();
        const digest = createHash("sha256").update(metadataBuffer).digest("hex");
        if (digest !== sha256 || BigInt(metadataBuffer.byteLength) !== length) {
          state.failed = true;
          await cleanup();
          await unlink(temporaryPath).catch(() => undefined);
          throw new StreamGatewayError("STREAM_INVALID", "blob digest mismatch");
        }
        const blobId = randomUUID();
        const finalPath = join(this.directory, `${blobId}.bin`);
        await cleanup();
        await rename(temporaryPath, finalPath);
        signal.throwIfAborted();
        return {
          type: "blob.ready",
          body: {
            blobId,
            size: length.toString(),
            sha256,
            mimeType: metadata.mediaType,
            expiresAt: new Date(Date.now() + BLOB_TTL_MS).toISOString(),
          },
        };
      },
      cancel: async (reason) => {
        void reason;
        state.failed = true;
        await cleanup();
        await unlink(temporaryPath).catch(() => undefined);
      },
    });
  }

  release(blobId: string, signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    if (!/^[0-9a-f-]{36}$/.test(blobId)) return Promise.resolve();
    return unlink(join(this.directory, `${blobId}.bin`)).then(
      () => undefined,
      () => undefined,
    );
  }
}
