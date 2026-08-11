import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { SqliteCommandJournal } from "../src/journal/sqlite-journal.js";
import type { JournalRecord } from "../src/journal/types.js";

const commandId = "550e8400-e29b-41d4-a716-446655440000";
const sessionId = "550e8400-e29b-41d4-a716-446655440001";
const hash = "a".repeat(64);

function received(id = commandId, payloadHash = hash): JournalRecord {
  return {
    command: {
      commandId: id,
      sessionId,
      operation: "prompt",
      payload: { text: "hello" },
      payloadHash,
      expectedLeafId: "deadbeef",
    },
    state: "RECEIVED",
    dormant: false,
    receivedAtMs: 10,
    updatedAtMs: 10,
    revision: 0,
  };
}

describe("SqliteCommandJournal", () => {
  it("commits ARMED before a terminal transition and persists mode 0600", async () => {
    const path = join(mkdtempSync(join(tmpdir(), "pimobile-journal-")), "commands.sqlite");
    const journal = new SqliteCommandJournal(path);
    expect((statSync(path).mode & 0o777).toString(8)).toBe("600");
    expect((await journal.insertReceived(received())).inserted).toBe(true);
    expect((await journal.transition(commandId, hash, { kind: "arm", atMs: 20 })).record.state).toBe("ARMED");
    expect((await journal.transition(commandId, hash, { kind: "ack", atMs: 30, result: { ok: true } })).record.state).toBe("ACKED");
    expect((await journal.transition(commandId, hash, { kind: "arm", atMs: 40 })).transitioned).toBe(false);
    journal.close();

    const reopened = new SqliteCommandJournal(path);
    expect((await reopened.get(commandId))?.state).toBe("ACKED");
    reopened.close();
    expect(readFileSync(path).subarray(0, 15).toString()).toBe("SQLite format 3");
  });

  it("recovers RECEIVED dormant and ARMED indeterminate", async () => {
    const path = join(mkdtempSync(join(tmpdir(), "pimobile-journal-")), "commands.sqlite");
    const journal = new SqliteCommandJournal(path);
    await journal.insertReceived(received());
    const second = "550e8400-e29b-41d4-a716-446655440002";
    await journal.insertReceived(received(second));
    await journal.transition(second, hash, { kind: "arm", atMs: 20 });

    expect(await journal.recover(50)).toEqual({ dormantReceived: 1, indeterminateArmed: 1 });
    expect(await journal.get(commandId)).toMatchObject({ state: "RECEIVED", dormant: true });
    expect(await journal.get(second)).toMatchObject({
      state: "INDETERMINATE",
      dormant: false,
      errorCode: "HOST_RECOVERED_AFTER_ARM",
    });
    journal.close();
  });

  it("rejects command ID reuse with a different hash", async () => {
    const path = join(mkdtempSync(join(tmpdir(), "pimobile-journal-")), "commands.sqlite");
    const journal = new SqliteCommandJournal(path);
    await journal.insertReceived(received());
    await expect(journal.insertReceived(received(commandId, "b".repeat(64)))).rejects.toThrow("COMMAND_ID_REUSE");
    journal.close();
  });
});
