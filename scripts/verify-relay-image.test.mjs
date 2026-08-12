import { afterEach, describe, expect, it } from "vitest";
import { chmod, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const verifyScript = fileURLToPath(new URL("../infra/local/verify-relay-image.sh", import.meta.url));
const image = "ghcr.io/verybigsad/pi-app/relay@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const temporaryDirectories = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { force: true, recursive: true })));
});

describe("verify-relay-image", () => {
  it("uses the caller's registry credentials for private image verification", async () => {
    const directory = await mkdtemp(join(tmpdir(), "verify-relay-image-"));
    temporaryDirectories.push(directory);
    const bin = join(directory, "bin");
    const log = join(directory, "commands.log");
    const dockerConfig = join(directory, "docker-config");
    await mkdir(bin);
    await mkdir(dockerConfig);
    await Promise.all([
      writeFile(join(bin, "docker"), "#!/bin/sh\nprintf 'docker|%s|%s\\n' \"$DOCKER_CONFIG\" \"$*\" >> \"$MOCK_LOG\"\n"),
      writeFile(join(bin, "cosign"), "#!/bin/sh\nprintf 'cosign|%s|%s\\n' \"$DOCKER_CONFIG\" \"$*\" >> \"$MOCK_LOG\"\n"),
    ]);
    await Promise.all([chmod(join(bin, "docker"), 0o755), chmod(join(bin, "cosign"), 0o755)]);

    const { stdout } = await execFileAsync("bash", [verifyScript, image], {
      env: {
        ...process.env,
        DOCKER_CONFIG: dockerConfig,
        MOCK_LOG: log,
        PATH: `${bin}:${process.env.PATH ?? ""}`,
      },
    });

    expect(stdout).toContain("verified pull and signature");
    await expect(readFile(log, "utf8")).resolves.toBe(
      `docker|${dockerConfig}|pull --platform linux/amd64 ${image}\n` +
        `cosign|${dockerConfig}|verify --certificate-identity https://github.com/VeryBigSad/pi-app/.github/workflows/relay-image.yml@refs/heads/main --certificate-oidc-issuer https://token.actions.githubusercontent.com ${image}\n`,
    );
  });

  it("requires native cosign before pulling a private image", async () => {
    const directory = await mkdtemp(join(tmpdir(), "verify-relay-image-"));
    temporaryDirectories.push(directory);
    const bin = join(directory, "bin");
    await mkdir(bin);

    await expect(
      execFileAsync("/bin/bash", [verifyScript, image], {
        env: { ...process.env, PATH: bin },
      }),
    ).rejects.toMatchObject({ stderr: expect.stringContaining("cosign is required") });
  });
});
