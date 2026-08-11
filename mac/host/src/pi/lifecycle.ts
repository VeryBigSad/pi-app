export interface LifecycleClock {
  nowMs(): number;
}

export interface LifecycleCursor {
  readonly streamEpoch: string;
  readonly sequence: string;
}

export type LifecyclePhase = "idle" | "working";

export interface SettlementTrigger {
  readonly key: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly settledAtMs: number;
}

export interface LifecycleSnapshot {
  readonly phase: LifecyclePhase;
  readonly pendingSteering: number;
  readonly pendingFollowUp: number;
  readonly lastAgentWillRetry: boolean | undefined;
  readonly retrying: boolean;
  readonly compacting: boolean;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function queueLength(value: unknown): number {
  return Array.isArray(value) ? value.length : 0;
}

/** Converts Pi lifecycle records into one idempotent durable-key trigger per agent_settled event. */
export class LifecycleTracker {
  private readonly clock: LifecycleClock;
  private phase: LifecyclePhase = "idle";
  private pendingSteering = 0;
  private pendingFollowUp = 0;
  private lastAgentWillRetry: boolean | undefined;
  private retrying = false;
  private compacting = false;
  private lastSettlementKey: string | undefined;

  constructor(clock: LifecycleClock = { nowMs: () => Date.now() }) {
    this.clock = clock;
  }

  apply(event: unknown, cursor: LifecycleCursor): SettlementTrigger | undefined {
    if (!isRecord(event) || typeof event["type"] !== "string") {
      return undefined;
    }

    switch (event["type"]) {
      case "agent_start":
        this.phase = "working";
        break;
      case "agent_end":
        this.phase = "working";
        this.lastAgentWillRetry = typeof event["willRetry"] === "boolean" ? event["willRetry"] : undefined;
        break;
      case "auto_retry_start":
      case "summarization_retry_scheduled":
      case "summarization_retry_attempt_start":
        this.phase = "working";
        this.retrying = true;
        break;
      case "auto_retry_end":
      case "summarization_retry_finished":
        this.phase = "working";
        this.retrying = false;
        break;
      case "compaction_start":
        this.phase = "working";
        this.compacting = true;
        break;
      case "compaction_end":
        this.phase = "working";
        this.compacting = false;
        break;
      case "queue_update":
        this.pendingSteering = queueLength(event["steering"]);
        this.pendingFollowUp = queueLength(event["followUp"]);
        if (this.pendingSteering + this.pendingFollowUp > 0) {
          this.phase = "working";
        }
        break;
      case "agent_settled": {
        const key = `${cursor.streamEpoch}:${cursor.sequence}`;
        this.phase = "idle";
        this.retrying = false;
        this.compacting = false;
        this.pendingSteering = 0;
        this.pendingFollowUp = 0;
        this.lastAgentWillRetry = false;
        if (key === this.lastSettlementKey) {
          return undefined;
        }
        this.lastSettlementKey = key;
        return {
          key,
          streamEpoch: cursor.streamEpoch,
          sequence: cursor.sequence,
          settledAtMs: this.clock.nowMs(),
        };
      }
      default:
        break;
    }
    return undefined;
  }

  snapshot(): LifecycleSnapshot {
    return {
      phase: this.phase,
      pendingSteering: this.pendingSteering,
      pendingFollowUp: this.pendingFollowUp,
      lastAgentWillRetry: this.lastAgentWillRetry,
      retrying: this.retrying,
      compacting: this.compacting,
    };
  }
}
