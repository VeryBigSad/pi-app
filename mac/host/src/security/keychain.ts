import { randomBytes, timingSafeEqual } from "node:crypto";
import { spawn } from "node:child_process";
import { SecurityError } from "./security-error.js";

const SECRET_BYTES = 32;
const OUTPUT_LIMIT = 16 * 1024;
const COMMAND_TIMEOUT_MS = 5_000;

export interface SecurityCommandResult {
  readonly exitCode: number;
  readonly stdout: string;
}

export type SecurityCommandRunner = (
  args: readonly string[],
  stdin: string | undefined,
) => Promise<SecurityCommandResult>;

export interface WrappingSecretProvider {
  getSecret(): Promise<Buffer>;
}

export class MacOsKeychainWrappingSecret implements WrappingSecretProvider {
  private readonly service: string;
  private readonly account: string;
  private readonly run: SecurityCommandRunner;

  constructor(
    service = "io.github.verybigsad.pimobile.mac-host.pki",
    account = "pkcs8-wrapping-key-v1",
    run: SecurityCommandRunner = runSecurityCommand,
  ) {
    validateKeychainLabel(service);
    validateKeychainLabel(account);
    this.service = service;
    this.account = account;
    this.run = run;
  }

  async getSecret(): Promise<Buffer> {
    const found = await this.find();
    if (found !== undefined) return found;

    const encoded = randomBytes(SECRET_BYTES).toString("base64url");
    const result = await this.run(
      ["add-generic-password", "-a", this.account, "-s", this.service, "-w"],
      `${encoded}\n${encoded}\n`,
    );
    if (result.exitCode === 0) {
      const stored = await this.find();
      const expected = decodeSecret(encoded);
      const matches = stored !== undefined && timingSafeEqual(stored, expected);
      expected.fill(0);
      if (!matches) {
        stored?.fill(0);
        throw new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "wrapping secret verification failed");
      }
      return stored;
    }

    const raced = await this.find();
    if (raced !== undefined) return raced;
    throw new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "wrapping secret could not be stored");
  }

  private async find(): Promise<Buffer | undefined> {
    const result = await this.run(
      ["find-generic-password", "-a", this.account, "-s", this.service, "-w"],
      undefined,
    );
    if (result.exitCode === 44) return undefined;
    if (result.exitCode !== 0) {
      throw new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "wrapping secret could not be read");
    }
    return decodeSecret(result.stdout.trim());
  }
}

export const runSecurityCommand: SecurityCommandRunner = async (args, stdin) =>
  await new Promise<SecurityCommandResult>((resolve, reject) => {
    const child = spawn("/usr/bin/security", [...args], {
      shell: false,
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = Buffer.alloc(0);
    let stderrBytes = 0;
    let outputExceeded = false;
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
    }, COMMAND_TIMEOUT_MS);

    child.stdout.on("data", (chunk: Buffer) => {
      if (stdout.length + chunk.length > OUTPUT_LIMIT) {
        outputExceeded = true;
        child.kill("SIGKILL");
        return;
      }
      stdout = Buffer.concat([stdout, chunk]);
    });
    child.stderr.on("data", (chunk: Buffer) => {
      stderrBytes += chunk.length;
      if (stderrBytes > OUTPUT_LIMIT) {
        outputExceeded = true;
        child.kill("SIGKILL");
      }
    });
    child.once("error", (error) => {
      clearTimeout(timer);
      reject(new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "security CLI could not start", { cause: error }));
    });
    child.once("close", (code) => {
      clearTimeout(timer);
      if (outputExceeded || code === null) {
        reject(new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "security CLI failed"));
        return;
      }
      resolve({ exitCode: code, stdout: stdout.toString("utf8") });
    });
    child.stdin.on("error", () => undefined);
    child.stdin.end(stdin);
  });

function validateKeychainLabel(value: string): void {
  if (value.length === 0 || value.length > 200 || value.includes("\0")) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "Keychain label is invalid");
  }
}

function decodeSecret(encoded: string): Buffer {
  if (!/^[A-Za-z0-9_-]{43}$/.test(encoded)) {
    throw new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "wrapping secret is malformed");
  }
  const secret = Buffer.from(encoded, "base64url");
  if (secret.length !== SECRET_BYTES) {
    throw new SecurityError("SECURITY_KEYCHAIN_UNAVAILABLE", "wrapping secret is malformed");
  }
  return secret;
}
