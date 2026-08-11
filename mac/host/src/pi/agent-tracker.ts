export const MAX_AGENTS_PER_SESSION = 256;
export const MAX_AGENT_DESCRIPTION_CHARS = 256;
const MAX_OPAQUE_ID_CHARS = 128;
const MAX_AGENT_TYPE_CHARS = 128;
const MAX_MODEL_CHARS = 128;

const SPAWN_TOOL = "Agent";
const REFERENCING_TOOLS = new Set(["get_subagent_result", "steer_subagent"]);
const TERMINAL_STATUSES = new Set<AgentStatus>(["completed", "failed", "stopped"]);
const OPAQUE_ID = /^[A-Za-z0-9._:-]{1,128}$/;
const STATUS_PATTERN = /"(?:status|state)"\s*:\s*"(completed|failed|stopped|waiting|running)"/i;
const STATUS_WORD = /\b(completed|failed|stopped|waiting|running)\b/i;

export type AgentStatus = "running" | "waiting" | "completed" | "failed" | "stopped";

/**
 * Privacy-bounded agent view: identifiers, description, type, status, timestamps,
 * tool-use count, and model only. Never carries tool arguments or tool output.
 */
export interface TrackedAgent {
  readonly agentId: string;
  readonly parentAgentId?: string;
  readonly description: string;
  readonly agentType: string;
  readonly status: AgentStatus;
  readonly startedAt: string;
  readonly endedAt?: string;
  readonly toolUses?: number;
  readonly model?: string;
}

interface MutableAgent {
  agentId: string;
  parentAgentId?: string;
  description: string;
  agentType: string;
  status: AgentStatus;
  startedAt: string;
  endedAt?: string;
  toolUses: number;
  model?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function boundedOpaqueId(value: string, fallback: string): string {
  const sanitized = value.replace(/[^A-Za-z0-9._:-]+/g, "-").replace(/^-+|-+$/g, "");
  const candidate = sanitized.length > 0 ? sanitized : fallback;
  return candidate.slice(0, MAX_OPAQUE_ID_CHARS);
}

function boundedText(value: string, maxChars: number): string {
  return value.length <= maxChars ? value : value.slice(0, maxChars);
}

function firstString(source: Record<string, unknown>, keys: readonly string[]): string | undefined {
  for (const key of keys) {
    const value = source[key];
    if (typeof value === "string" && value.length > 0) return value;
  }
  return undefined;
}

function collectText(value: unknown, sink: string[], depth: number): void {
  if (depth > 8 || sink.length >= 64) return;
  if (typeof value === "string") {
    sink.push(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value.slice(0, 64)) collectText(item, sink, depth + 1);
    return;
  }
  if (isRecord(value)) {
    for (const key of Object.keys(value).slice(0, 64)) collectText(value[key], sink, depth + 1);
  }
}

/** Extracts a status token from tool-result payload text without retaining the payload. */
function resultStatus(result: unknown): AgentStatus | undefined {
  const texts: string[] = [];
  collectText(result, texts, 0);
  let fallback: AgentStatus | undefined;
  for (const text of texts) {
    const bounded = text.length > 8192 ? text.slice(0, 8192) : text;
    const exact = STATUS_PATTERN.exec(bounded);
    if (exact !== null && typeof exact[1] === "string") return exact[1].toLowerCase() as AgentStatus;
    fallback ??= STATUS_WORD.exec(bounded)?.[1]?.toLowerCase() as AgentStatus | undefined;
  }
  return fallback;
}

/**
 * Tracks subagent lifecycle for one Pi RPC session from tool_call/tool_result
 * events. Bounded to MAX_AGENTS_PER_SESSION entries; oldest terminal agents are
 * evicted first, then oldest overall. Emits full upsert snapshots on change.
 */
export class AgentTracker {
  private readonly agents = new Map<string, MutableAgent>();
  private readonly pendingCalls = new Map<string, string>();
  private clock: () => number = () => Date.now();

  setClock(clock: () => number): void {
    this.clock = clock;
  }

  /** Applies one raw Pi RPC record; returns the upserted agent when it changed. */
  apply(record: unknown, atMs?: number): TrackedAgent | undefined {
    if (!isRecord(record) || typeof record["type"] !== "string") return undefined;
    const now = new Date(atMs ?? this.clock()).toISOString();
    const toolName = record["toolName"];
    const toolCallId = record["toolCallId"];
    if (typeof toolName !== "string" || typeof toolCallId !== "string") return undefined;

    switch (record["type"]) {
      case "tool_execution_start":
        return this.onToolStart(toolName, toolCallId, isRecord(record["args"]) ? record["args"] : {}, now);
      case "tool_execution_end":
        return this.onToolEnd(toolName, toolCallId, record["isError"] === true, record["result"], now);
      default:
        return undefined;
    }
  }

  /** Deterministic catalog view in first-seen order. */
  catalog(): readonly TrackedAgent[] {
    return [...this.agents.values()].map((agent) => ({ ...agent }));
  }

  get size(): number {
    return this.agents.size;
  }

  private onToolStart(toolName: string, toolCallId: string, args: Record<string, unknown>, now: string): TrackedAgent | undefined {
    if (toolName === SPAWN_TOOL) {
      const agentId = boundedOpaqueId(toolCallId, "agent");
      const description = firstString(args, ["description", "prompt", "task"]);
      const agent: MutableAgent = {
        agentId,
        description: boundedText(description ?? `agent ${agentId}`, MAX_AGENT_DESCRIPTION_CHARS),
        agentType: boundedText(boundedOpaqueId(firstString(args, ["subagent_type", "agent_type", "agentType"]) ?? "custom", "custom"), MAX_AGENT_TYPE_CHARS),
        status: "running",
        startedAt: now,
        toolUses: 0,
      };
      const parent = firstString(args, ["parentAgentId", "parent_agent_id"]);
      if (parent !== undefined && OPAQUE_ID.test(parent)) agent.parentAgentId = parent;
      const model = firstString(args, ["model"]);
      if (model !== undefined) agent.model = boundedText(model, MAX_MODEL_CHARS);
      this.upsert(agent);
      return { ...agent };
    }

    if (REFERENCING_TOOLS.has(toolName)) {
      const rawAgentId = firstString(args, ["agentId", "agent_id", "id"]);
      if (rawAgentId === undefined) return undefined;
      const agentId = boundedOpaqueId(rawAgentId, "agent");
      this.pendingCalls.set(toolCallId, agentId);
      const existing = this.agents.get(agentId);
      if (existing === undefined) {
        const agent: MutableAgent = {
          agentId,
          description: boundedText(`agent ${agentId}`, MAX_AGENT_DESCRIPTION_CHARS),
          agentType: "custom",
          status: "running",
          startedAt: now,
          toolUses: 1,
        };
        this.upsert(agent);
        return { ...agent };
      }
      existing.toolUses += 1;
      if (toolName === "steer_subagent" && existing.status !== "running" && !TERMINAL_STATUSES.has(existing.status)) {
        existing.status = "running";
        delete existing.endedAt;
      }
      return { ...existing };
    }

    return undefined;
  }

  private onToolEnd(toolName: string, toolCallId: string, isError: boolean, result: unknown, now: string): TrackedAgent | undefined {
    if (toolName === SPAWN_TOOL) {
      const agentId = boundedOpaqueId(toolCallId, "agent");
      const agent = this.agents.get(agentId);
      if (agent === undefined) return undefined;
      agent.status = isError ? "failed" : "completed";
      agent.endedAt = now;
      return { ...agent };
    }

    if (toolName === "get_subagent_result") {
      const agentId = this.pendingCalls.get(toolCallId);
      this.pendingCalls.delete(toolCallId);
      if (agentId === undefined) return undefined;
      const agent = this.agents.get(agentId);
      if (agent === undefined) return undefined;
      const status = isError ? "failed" : resultStatus(result) ?? "completed";
      agent.status = status;
      if (TERMINAL_STATUSES.has(status)) agent.endedAt = now;
      else delete agent.endedAt;
      return { ...agent };
    }

    if (toolName === "steer_subagent") {
      const agentId = this.pendingCalls.get(toolCallId);
      this.pendingCalls.delete(toolCallId);
      if (agentId === undefined) return undefined;
      const agent = this.agents.get(agentId);
      if (agent === undefined) return undefined;
      if (isError) {
        agent.status = "failed";
        agent.endedAt = now;
      }
      return { ...agent };
    }

    return undefined;
  }

  private upsert(agent: MutableAgent): void {
    this.agents.delete(agent.agentId);
    this.agents.set(agent.agentId, agent);
    while (this.agents.size > MAX_AGENTS_PER_SESSION) this.evictOldest();
  }

  private evictOldest(): void {
    for (const [agentId, agent] of this.agents) {
      if (TERMINAL_STATUSES.has(agent.status)) {
        this.agents.delete(agentId);
        return;
      }
    }
    const oldest = this.agents.keys().next();
    if (!oldest.done) this.agents.delete(oldest.value);
  }
}
