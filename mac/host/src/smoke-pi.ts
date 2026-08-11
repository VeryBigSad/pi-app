#!/usr/bin/env node
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PiRpcProcess } from "./pi/rpc-process.js";

const root = mkdtempSync(join(tmpdir(), "pimobile-pi-smoke-"));
const host = new PiRpcProcess({
  executable: process.env["PI_MOBILE_PI"] ?? "pi",
  args: [
    "--mode", "rpc",
    "--no-session",
    "--no-extensions",
    "--no-skills",
    "--no-prompt-templates",
    "--no-themes",
    "--no-context-files",
    "--offline",
  ],
  cwd: root,
  env: {
    PI_CODING_AGENT_DIR: join(root, "agent"),
    PI_OFFLINE: "1",
  },
});

try {
  host.start();
  const response = await host.call({ type: "get_state" });
  if (response["success"] !== true || response["command"] !== "get_state") {
    throw new Error("Pi RPC smoke returned an unexpected response");
  }
  process.stdout.write("Pi RPC smoke passed\n");
} finally {
  await host.stop();
}
