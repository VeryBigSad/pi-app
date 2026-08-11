import { createHash } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApprovalBroker, classify, type ApprovalOffer } from "../src/index.js";

const hash = createHash("sha256").update("operation").digest("hex");

function request(index: number, deadline = 150_000) {
  return {
    operationId: `operation-${String(index)}`,
    connectionId: "connection",
    argumentHash: hash,
    operation: `rm target-${String(index)}`,
    reasons: ["destructive_shell_operation"],
    hookDeadlineMs: deadline,
  };
}

describe("ApprovalBroker", () => {
  const offers: ApprovalOffer[] = [];
  let monotonic = 0;
  let wall = Date.UTC(2026, 7, 9);

  beforeEach(() => {
    offers.length = 0;
    monotonic = 0;
    wall = Date.UTC(2026, 7, 9);
    vi.useFakeTimers();
  });

  afterEach(() => vi.useRealTimers());

  function broker(): ApprovalBroker {
    let id = 0;
    return new ApprovalBroker({
      onOffer: (offer) => offers.push(offer),
      clock: { monotonicMs: () => monotonic, wallMs: () => wall },
      randomId: () => `offer-${String(++id)}`,
    });
  }

  it("allows once only for the exact originating tuple", async () => {
    const subject = broker();
    const result = subject.request(request(1));
    expect(subject.decide({
      offerId: "offer-1",
      operationId: "operation-1",
      argumentHash: hash,
      connectionId: "connection",
      decision: "allow_once",
    })).toBe(true);
    await expect(result).resolves.toEqual({ allowed: true, offerId: "offer-1" });
    expect(subject.decide({
      offerId: "offer-1",
      operationId: "operation-1",
      argumentHash: hash,
      connectionId: "connection",
      decision: "allow_once",
    })).toBe(false);
  });

  it("queues eight, rejects the ninth waiting request, and times out FIFO wait", async () => {
    const subject = broker();
    void subject.request(request(0));
    const queued = Array.from({ length: 8 }, (_, index) => subject.request(request(index + 1)));
    await expect(subject.request(request(9))).resolves.toEqual({ allowed: false, code: "APPROVAL_BUSY" });
    expect(subject.queuedCount()).toBe(8);
    monotonic = 30_000;
    await vi.advanceTimersByTimeAsync(30_000);
    for (const result of queued) await expect(result).resolves.toEqual({ allowed: false, code: "APPROVAL_BUSY" });
  });

  it("expires the visible offer at 120 seconds", async () => {
    const subject = broker();
    const first = subject.request(request(1));
    monotonic = 120_000;
    wall += 120_000;
    await vi.advanceTimersByTimeAsync(120_000);
    await expect(first).resolves.toEqual({ allowed: false, code: "APPROVAL_EXPIRED" });
  });

  it("rejects a decision after the 120 second deadline when the expiry timer is delayed", async () => {
    const subject = broker();
    const result = subject.request(request(1));
    expect(offers).toHaveLength(1);
    expect(offers[0]?.expiresAt).toBe(new Date(wall + 120_000).toISOString());
    monotonic = 119_999;
    wall += 119_999;
    expect(subject.decide({
      offerId: "offer-1",
      operationId: "operation-1",
      argumentHash: hash,
      connectionId: "connection",
      decision: "allow_once",
    })).toBe(true);
    await expect(result).resolves.toEqual({ allowed: true, offerId: "offer-1" });

    const second = subject.request(request(2, 150_000 + 120_000));
    expect(offers.map((offer) => offer.operationId)).toEqual(["operation-1", "operation-2"]);
    monotonic = 119_999 + 120_000;
    wall += 1;
    expect(subject.decide({
      offerId: "offer-2",
      operationId: "operation-2",
      argumentHash: hash,
      connectionId: "connection",
      decision: "allow_once",
    })).toBe(false);
    await vi.advanceTimersByTimeAsync(120_000);
    await expect(second).resolves.toEqual({ allowed: false, code: "APPROVAL_EXPIRED" });
  });

  it("promotes FIFO when the visible offer resolves within queue wait", async () => {
    const subject = broker();
    const first = subject.request(request(1));
    const second = subject.request(request(2));
    monotonic = 20_000;
    wall += 20_000;
    expect(subject.decide({
      offerId: "offer-1",
      operationId: "operation-1",
      argumentHash: hash,
      connectionId: "connection",
      decision: "deny",
    })).toBe(true);
    await expect(first).resolves.toEqual({ allowed: false, code: "APPROVAL_DENIED" });
    expect(offers.map((offer) => offer.operationId)).toEqual(["operation-1", "operation-2"]);
    expect(subject.decide({
      offerId: "offer-2",
      operationId: "operation-2",
      argumentHash: hash,
      connectionId: "connection",
      decision: "deny",
    })).toBe(true);
    await expect(second).resolves.toEqual({ allowed: false, code: "APPROVAL_DENIED" });
  });

  it("cancels active and queued work for a disconnected origin", async () => {
    const subject = broker();
    const first = subject.request(request(1));
    const second = subject.request(request(2));
    subject.cancelConnection("connection");
    await expect(first).resolves.toEqual({ allowed: false, code: "APPROVAL_CANCELLED" });
    await expect(second).resolves.toEqual({ allowed: false, code: "APPROVAL_CANCELLED" });
    expect(subject.activeOffer()).toBeUndefined();
  });
});

describe("classifier", () => {
  it("allows a single read and gates shell composition or deletion", () => {
    expect(classify({ kind: "bash", command: "git status", cwd: "/tmp" })).toEqual({ disposition: "allow" });
    expect(classify({ kind: "bash", command: "git status && rm -rf target", cwd: "/tmp" })).toMatchObject({ disposition: "approval" });
    expect(classify({ kind: "bash", command: "rm -rf target", cwd: "/tmp" })).toMatchObject({ disposition: "approval" });
  });

  it("keeps the safe-read fast path for plain invocations", () => {
    expect(classify({ kind: "bash", command: "find . -name '*.ts'", cwd: "/tmp" })).toEqual({ disposition: "allow" });
    expect(classify({ kind: "bash", command: "sed 's/alpha/beta/' notes.txt", cwd: "/tmp" })).toEqual({ disposition: "allow" });
    expect(classify({ kind: "bash", command: "grep -i error app.log", cwd: "/tmp" })).toEqual({ disposition: "allow" });
    expect(classify({ kind: "bash", command: "cat /var/log/system.log", cwd: "/tmp" })).toEqual({ disposition: "allow" });
    expect(classify({ kind: "bash", command: "npm test 2>/dev/null", cwd: "/tmp" })).toEqual({ disposition: "allow" });
    expect(classify({ kind: "bash", command: "awk '{print $1}' data.txt", cwd: "/tmp" })).toEqual({ disposition: "allow" });
  });

  it("gates find with mutating flags", () => {
    for (const command of [
      "find . -name '*.tmp' -delete",
      "find . -type f -exec rm {} +",
      "find . -type f -execdir rm {} +",
      "find . -name '*.o' -ok rm {} +",
      "find . -fprintf results.txt '%p'",
    ]) {
      expect(classify({ kind: "bash", command, cwd: "/tmp" })).toMatchObject({ disposition: "approval", reasons: ["mutating_command_flag"] });
    }
  });

  it("gates sed in-place editing variants", () => {
    for (const command of [
      "sed -i '' 's/a/b/' file.txt",
      "sed -i.bak 's/a/b/' file.txt",
      "sed -ni.bak 's/a/b/p' file.txt",
      "sed --in-place 's/a/b/' file.txt",
      "sed --in-place=.orig 's/a/b/' file.txt",
      "sed -Ei 's/a/b/' file.txt",
    ]) {
      expect(classify({ kind: "bash", command, cwd: "/tmp" })).toMatchObject({ disposition: "approval", reasons: ["mutating_command_flag"] });
    }
  });

  it("gates output redirection write side effects", () => {
    for (const command of [
      "cat /dev/null > target",
      "cat /dev/null >target",
      "echo done >> build.log",
      "git diff > out.txt",
      "npm test > results.txt",
      "ls -la /tmp > listing",
    ]) {
      expect(classify({ kind: "bash", command, cwd: "/tmp" })).toMatchObject({ disposition: "approval", reasons: ["shell_write_redirection"] });
    }
  });

  it("gates awk programs that shell out", () => {
    expect(classify({ kind: "bash", command: "awk 'BEGIN{system(\"rm -rf /tmp/x\")}'", cwd: "/tmp" }))
      .toMatchObject({ disposition: "approval", reasons: ["mutating_command_flag"] });
  });

  it("gates quoted separators and substitutions rather than allowing them", () => {
    expect(classify({ kind: "bash", command: "sed -e 's/;/x/' -i.bak file.txt", cwd: "/tmp" })).toMatchObject({ disposition: "approval" });
    expect(classify({ kind: "bash", command: "find . -name ';' -delete", cwd: "/tmp" })).toMatchObject({ disposition: "approval" });
    expect(classify({ kind: "bash", command: "cat $(which notes)", cwd: "/tmp" })).toMatchObject({ disposition: "approval" });
  });
});
