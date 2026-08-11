export interface BashOperation {
  readonly kind: "bash";
  readonly command: string;
  readonly cwd: string;
}

export interface ToolOperation {
  readonly kind: "tool" | "bridge";
  readonly name: string;
  readonly arguments: unknown;
  readonly cwd?: string;
}

export type PolicyOperation = BashOperation | ToolOperation;

export type Classification =
  | { readonly disposition: "allow" }
  | { readonly disposition: "approval"; readonly reasons: readonly string[]; readonly normalized: string };

const destructiveBash = [
  /(?:^|[;&|]\s*)rm\s+(?:-[^\s]*[rRf][^\s]*\s+)+/u,
  /(?:^|[;&|]\s*)git\s+(?:reset\s+--hard|clean\s+-[^\s]*f|push\s+[^\n]*--force|branch\s+-D)\b/u,
  /(?:^|[;&|]\s*)terraform\s+(?:apply|destroy)\b/u,
  /(?:^|[;&|]\s*)(?:kubectl|helm)\s+(?:delete|uninstall)\b/u,
  /(?:^|[;&|]\s*)(?:dropdb|shutdown|reboot|halt|diskutil|mkfs)\b/u,
  /\b(?:DROP|TRUNCATE)\s+(?:DATABASE|SCHEMA|TABLE)\b/iu,
  /\bDELETE\s+FROM\b(?![^;\n]*\bWHERE\b)/iu,
  /(?:^|[;&|]\s*)(?:userdel|groupdel|dscl\s+[^\n]*-delete)\b/u,
  /(?:^|[;&|]\s*)sudo\b/u,
];

const writeRedirectionBash = />>?\s*(?!&[0-9]|\/dev\/null(?:\s|$))/u;

const mutatingFlagBash = [
  /(?:^|[;&|(]\s*)find\b[^;&|\n]*\s--?(?:delete|exec|execdir|ok|okdir|fls|fprintf)\b/u,
  /(?:^|[;&|(]\s*)sed\b[^;&|\n]*\s-(?:-in-place(?:=|\s|$)|[a-zA-Z]*i[a-zA-Z]*(?:[\s.'"]|$))/u,
  /(?:^|[;&|(]\s*)awk\b[^;&|\n]*\bsystem\s*\(/u,
];

const dynamicBash = [
  /[\n;&|]/u,
  /\beval\b/u,
  /\b(?:bash|sh|zsh)\s+-c\b/u,
  /\$\(|`/u,
  /\|\s*(?:bash|sh|zsh)\b/u,
  /\b(?:base64|openssl)\b[^|\n]*\|/u,
];

const safeBash = [
  /^(?:pwd|ls|find|rg|grep|sed|awk|head|tail|wc|stat|file|cat)(?:\s|$)/u,
  /^git\s+(?:status|diff|log|show|rev-parse|branch(?:\s+--show-current)?)(?:\s|$)/u,
  /^(?:npm|pnpm|yarn)\s+(?:test|run\s+(?:test|lint|typecheck|build|check))(?:\s|$)/u,
  /^(?:go\s+(?:test|vet|fmt)|gofmt|cargo\s+(?:test|check)|pytest|uv\s+run)(?:\s|$)/u,
  /^\.\/gradlew\s+(?![^\n]*(?:publish|upload))(?:[^;&|]*)$/u,
];

const destructiveTools = new Set([
  "delete",
  "remove",
  "write",
  "edit",
  "bash",
  "terraform_apply",
  "terraform_destroy",
  "cloud_delete",
  "database_execute",
]);

const safeTools = new Set(["read", "search", "grep", "find", "list", "status", "query"]);

export function classify(operation: PolicyOperation): Classification {
  if (operation.kind === "bash") return classifyBash(operation);
  const name = operation.name.trim().toLowerCase();
  const normalized = stableStringify({ name, arguments: operation.arguments, cwd: operation.cwd });
  if (safeTools.has(name)) return { disposition: "allow" };
  if (destructiveTools.has(name) || /(delete|remove|destroy|revoke|rotate|apply|write|edit|execute)/u.test(name)) {
    return { disposition: "approval", reasons: ["destructive_or_mutating_tool"], normalized };
  }
  return { disposition: "approval", reasons: ["unclassified_tool"], normalized };
}

function classifyBash(operation: BashOperation): Classification {
  const command = operation.command.trim();
  const normalized = `${operation.cwd}\n${command}`;
  const reasons: string[] = [];
  if (destructiveBash.some((pattern) => pattern.test(command))) reasons.push("destructive_shell_operation");
  if (mutatingFlagBash.some((pattern) => pattern.test(command))) reasons.push("mutating_command_flag");
  if (writeRedirectionBash.test(command)) reasons.push("shell_write_redirection");
  if (dynamicBash.some((pattern) => pattern.test(command))) reasons.push("dynamic_shell_evaluation");
  if (reasons.length > 0) return { disposition: "approval", reasons, normalized };
  if (safeBash.some((pattern) => pattern.test(command))) return { disposition: "allow" };
  return { disposition: "approval", reasons: ["unclassified_shell_operation"], normalized };
}

function stableStringify(value: unknown): string {
  return JSON.stringify(sortValue(value));
}

function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value === null || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(([key, item]) => [key, sortValue(item)]));
}
