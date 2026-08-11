/** Minimal daemon logger: timestamped, bounded, code-only lines to stderr (launchd captures to daemon.err.log). */

const MAX_DETAIL = 160;

function sanitize(detail: string): string {
  // Codes and paths only: strip control chars, cap length, never log payloads.
  // eslint-disable-next-line no-control-regex -- stripping control chars is the point
  return detail.replace(/[\u0000-\u001f\u007f-\u009f]/g, " ").slice(0, MAX_DETAIL);
}

export function logWarn(component: string, message: string): void {
  console.error(`${new Date().toISOString()} [${component}] ${sanitize(message)}`);
}

export function logError(component: string, context: string, error: unknown): void {
  const detail = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  logWarn(component, `${context} failed: ${detail}`);
}
