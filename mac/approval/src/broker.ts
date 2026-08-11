import { randomUUID } from "node:crypto";
import { performance } from "node:perf_hooks";

export interface ApprovalRequest {
  readonly operationId: string;
  readonly connectionId: string;
  readonly argumentHash: string;
  readonly operation: string;
  readonly cwd?: string;
  readonly resource?: string;
  readonly reasons: readonly string[];
  readonly hookDeadlineMs: number;
}

export interface ApprovalOffer extends ApprovalRequest {
  readonly offerId: string;
  readonly expiresAt: string;
}

export type ApprovalResult =
  | { readonly allowed: true; readonly offerId: string }
  | { readonly allowed: false; readonly code: "APPROVAL_DENIED" | "APPROVAL_EXPIRED" | "APPROVAL_BUSY" | "APPROVAL_CANCELLED" };

export interface BrokerClock {
  monotonicMs(): number;
  wallMs(): number;
}

export interface BrokerOptions {
  readonly onOffer: (offer: ApprovalOffer) => void;
  readonly clock?: BrokerClock;
  readonly randomId?: () => string;
  readonly queueCapacity?: number;
  readonly queueWaitMs?: number;
  readonly decisionMs?: number;
}

interface Pending {
  readonly request: ApprovalRequest;
  readonly resolve: (result: ApprovalResult) => void;
  timer: ReturnType<typeof setTimeout> | undefined;
}

interface Active extends Pending {
  readonly offer: ApprovalOffer;
  readonly expiresAtMonotonicMs: number;
}

const defaultClock: BrokerClock = {
  monotonicMs: () => performance.now(),
  wallMs: () => Date.now(),
};

export class ApprovalBroker {
  private readonly onOffer: (offer: ApprovalOffer) => void;
  private readonly clock: BrokerClock;
  private readonly randomId: () => string;
  private readonly queueCapacity: number;
  private readonly queueWaitMs: number;
  private readonly decisionMs: number;
  private readonly queue: Pending[] = [];
  private active: Active | undefined;

  constructor(options: BrokerOptions) {
    this.onOffer = options.onOffer;
    this.clock = options.clock ?? defaultClock;
    this.randomId = options.randomId ?? randomUUID;
    this.queueCapacity = options.queueCapacity ?? 8;
    this.queueWaitMs = options.queueWaitMs ?? 30_000;
    this.decisionMs = options.decisionMs ?? 120_000;
  }

  request(request: ApprovalRequest): Promise<ApprovalResult> {
    validateRequest(request, this.clock.monotonicMs());
    return new Promise((resolve) => {
      const pending: Pending = { request, resolve, timer: undefined };
      if (this.active === undefined) {
        this.promote(pending);
        return;
      }
      if (this.queue.length >= this.queueCapacity) {
        resolve({ allowed: false, code: "APPROVAL_BUSY" });
        return;
      }
      const remaining = Math.min(this.queueWaitMs, request.hookDeadlineMs - this.clock.monotonicMs());
      if (remaining <= 0) {
        resolve({ allowed: false, code: "APPROVAL_EXPIRED" });
        return;
      }
      pending.timer = setTimeout(() => {
        const index = this.queue.indexOf(pending);
        if (index >= 0) this.queue.splice(index, 1);
        resolve({ allowed: false, code: "APPROVAL_BUSY" });
      }, remaining);
      this.queue.push(pending);
    });
  }

  decide(input: {
    readonly offerId: string;
    readonly operationId: string;
    readonly argumentHash: string;
    readonly connectionId: string;
    readonly decision: "allow_once" | "deny";
  }): boolean {
    const active = this.active;
    const now = this.clock.monotonicMs();
    if (
      active === undefined ||
      now >= active.expiresAtMonotonicMs ||
      now >= active.request.hookDeadlineMs ||
      input.offerId !== active.offer.offerId ||
      input.operationId !== active.request.operationId ||
      input.argumentHash !== active.request.argumentHash ||
      input.connectionId !== active.request.connectionId
    ) return false;

    this.finish(active, input.decision === "allow_once"
      ? { allowed: true, offerId: active.offer.offerId }
      : { allowed: false, code: "APPROVAL_DENIED" });
    return true;
  }

  cancelConnection(connectionId: string): void {
    for (let index = this.queue.length - 1; index >= 0; index -= 1) {
      const pending = this.queue[index];
      if (pending?.request.connectionId !== connectionId) continue;
      this.queue.splice(index, 1);
      if (pending.timer !== undefined) clearTimeout(pending.timer);
      pending.resolve({ allowed: false, code: "APPROVAL_CANCELLED" });
    }
    const active = this.active;
    if (active?.request.connectionId === connectionId) {
      this.finish(active, { allowed: false, code: "APPROVAL_CANCELLED" });
    }
  }

  activeOffer(): ApprovalOffer | undefined {
    return this.active?.offer;
  }

  queuedCount(): number {
    return this.queue.length;
  }

  private promote(pending: Pending): void {
    if (pending.timer !== undefined) clearTimeout(pending.timer);
    const now = this.clock.monotonicMs();
    const duration = Math.min(this.decisionMs, pending.request.hookDeadlineMs - now);
    if (duration <= 0) {
      pending.resolve({ allowed: false, code: "APPROVAL_EXPIRED" });
      this.promoteNext();
      return;
    }
    const offer: ApprovalOffer = {
      ...pending.request,
      offerId: this.randomId(),
      expiresAt: new Date(this.clock.wallMs() + duration).toISOString(),
    };
    const active: Active = { ...pending, offer, timer: undefined, expiresAtMonotonicMs: now + duration };
    active.timer = setTimeout(() => this.finish(active, { allowed: false, code: "APPROVAL_EXPIRED" }), duration);
    this.active = active;
    this.onOffer(offer);
  }

  private finish(active: Active, result: ApprovalResult): void {
    if (this.active !== active) return;
    if (active.timer !== undefined) clearTimeout(active.timer);
    this.active = undefined;
    active.resolve(result);
    this.promoteNext();
  }

  private promoteNext(): void {
    if (this.active !== undefined) return;
    const next = this.queue.shift();
    if (next !== undefined) this.promote(next);
  }
}

function validateRequest(request: ApprovalRequest, now: number): void {
  if (request.operationId.length === 0 || request.connectionId.length === 0) throw new TypeError("approval identity is required");
  if (!/^[0-9a-f]{64}$/.test(request.argumentHash)) throw new TypeError("approval argument hash is invalid");
  if (request.reasons.length === 0 || request.reasons.length > 32) throw new TypeError("approval reasons are invalid");
  if (!Number.isFinite(request.hookDeadlineMs) || request.hookDeadlineMs <= now) throw new TypeError("approval deadline is expired");
}
