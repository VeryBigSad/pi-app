#!/usr/bin/env node
import { mkdtempSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { rm } from "node:fs/promises";
import { HostDaemon } from "./daemon/daemon.js";
import { adminCall } from "./daemon/admin-server.js";

const root = mkdtempSync(join(tmpdir(), "pimobile-daemon-smoke-"));
writeFileSync(join(root, "config.json"), `${JSON.stringify({ ports: { direct: 0, provisional: 0 } })}\n`, { mode: 0o600 });

process.env["PI_OFFLINE"] = "1";
process.env["PI_CODING_AGENT_DIR"] = join(root, "agent");

const daemon = new HostDaemon(root);
const sessionId = randomUUID();

try {
  await daemon.start();
  const status = (await adminCall(daemon.paths().adminSocketPath, { method: "status" })) as {
    ok?: unknown;
    piVersion?: unknown;
    listeners?: { directPort?: unknown };
  };
  if (status.ok !== true) throw new Error("daemon status is not ok");
  process.stdout.write(`daemon up: pi ${String(status.piVersion)}, direct port ${String(status.listeners?.directPort)}\n`);

  const result = (await adminCall(daemon.paths().adminSocketPath, {
    method: "sessions.run",
    params: { sessionId, operation: "get_state" },
  }, 120_000)) as { success?: unknown; command?: unknown };
  if (result.success !== true) {
    throw new Error("scripted get_state through the real composition failed");
  }
  process.stdout.write("scripted command through supervisor + patched Pi RPC succeeded\n");

  const sessions = (await adminCall(daemon.paths().adminSocketPath, { method: "status" })) as { sessions?: unknown };
  if (!Array.isArray(sessions.sessions) || !sessions.sessions.includes(sessionId)) {
    throw new Error("session was not tracked by the daemon");
  }
  process.stdout.write("Pi Mobile daemon smoke passed\n");
} finally {
  await daemon.stop().catch(() => undefined);
  await rm(root, { recursive: true, force: true }).catch(() => undefined);
}
