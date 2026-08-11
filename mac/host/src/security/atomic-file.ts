import { constants } from "node:fs";
import { chmod, lstat, mkdir, open, rename, unlink, type FileHandle } from "node:fs/promises";
import { dirname, basename, join } from "node:path";
import { randomBytes } from "node:crypto";
import { SecurityError } from "./security-error.js";

export async function atomicWriteFile(path: string, data: Uint8Array | string, mode: number): Promise<void> {
  if ((mode & ~0o777) !== 0 || (mode & 0o077) !== 0) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "atomic file mode must be private");
  }
  const directory = dirname(path);
  try {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const directoryMetadata = await lstat(directory);
    if (!directoryMetadata.isDirectory() || directoryMetadata.isSymbolicLink() || (directoryMetadata.mode & 0o077) !== 0) {
      throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "atomic file directory permissions are unsafe");
    }
  } catch (error) {
    if (error instanceof SecurityError) throw error;
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "atomic file directory unavailable", { cause: error });
  }
  const temporaryPath = join(directory, `.${basename(path)}.${String(process.pid)}.${randomBytes(12).toString("hex")}.tmp`);
  let handle: FileHandle | undefined;
  try {
    handle = await open(
      temporaryPath,
      constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW,
      mode,
    );
    await handle.writeFile(data);
    await handle.sync();
    await handle.close();
    handle = undefined;
    await chmod(temporaryPath, mode);
    await rename(temporaryPath, path);
    const directoryHandle = await open(directory, constants.O_RDONLY);
    try {
      await directoryHandle.sync();
    } finally {
      await directoryHandle.close();
    }
  } catch (error) {
    if (handle !== undefined) await handle.close().catch(() => undefined);
    await unlink(temporaryPath).catch(() => undefined);
    throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "atomic file write failed", { cause: error });
  }
}
