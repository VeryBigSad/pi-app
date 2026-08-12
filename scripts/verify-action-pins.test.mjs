import { describe, expect, it, vi } from "vitest";
import {
  expectedActionTags,
  extractActionPinsFromText,
  loadActionPins,
  resolveActionPins,
  verifyActionPins,
} from "./verify-action-pins.mjs";

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() {
      return body;
    },
  };
}

describe("action pin structure", () => {
  it("covers every external action in every workflow", async () => {
    const pins = await loadActionPins();
    const uniquePins = verifyActionPins(pins);

    expect(pins).toHaveLength(44);
    expect(uniquePins).toEqual(expectedActionTags);
    expect(pins.every((pin) => /^[0-9a-f]{40}$/.test(pin.ref))).toBe(true);
  });

  it("extracts named and anonymous action steps while ignoring local actions", () => {
    const pins = extractActionPinsFromText(`
steps:
  - uses: owner/action@${"a".repeat(40)}
  - name: Named
    uses: owner/action/subdirectory@${"b".repeat(40)}
  - uses: ./local-action
  - uses: docker://alpine:3.22
`);

    expect(pins.map(({ action, ref }) => ({ action, ref }))).toEqual([
      { action: "owner/action", ref: "a".repeat(40) },
      { action: "owner/action/subdirectory", ref: "b".repeat(40) },
    ]);
  });

  it.each([
    ["mutable tag", "v4"],
    ["short SHA", "a".repeat(39)],
    ["uppercase SHA", "A".repeat(40)],
  ])("rejects a %s", (_name, ref) => {
    const pins = extractActionPinsFromText(`steps:\n  - uses: owner/action@${ref}\n`);
    expect(() => verifyActionPins(pins, [])).toThrow("not a 40-character lowercase commit SHA");
  });

  it("rejects structurally valid but unreviewed pins", () => {
    const pins = extractActionPinsFromText(`steps:\n  - uses: owner/action@${"a".repeat(40)}\n`);
    expect(() => verifyActionPins(pins, [])).toThrow("unreviewed action pin");
  });
});

describe("GitHub API action resolution", () => {
  it("resolves both the commit and an annotated stable version tag", async () => {
    const sha = "a".repeat(40);
    const tagSha = "b".repeat(40);
    const pin = { repository: "owner/action", sha, tag: "v1.2.3" };
    const responses = new Map([
      [`/repos/owner/action/commits/${sha}`, { sha }],
      ["/repos/owner/action/git/ref/tags/v1.2.3", { object: { type: "tag", sha: tagSha } }],
      [`/repos/owner/action/git/tags/${tagSha}`, { object: { type: "commit", sha } }],
    ]);
    const fetchImplementation = vi.fn(async (url, options) => {
      expect(options.headers.Authorization).toBe("Bearer token");
      const path = new URL(url).pathname;
      return responses.has(path) ? jsonResponse(responses.get(path)) : jsonResponse({}, 404);
    });

    await expect(resolveActionPins([pin], { token: "token", fetchImplementation })).resolves.toEqual([pin]);
    expect(fetchImplementation).toHaveBeenCalledTimes(3);
  });

  it("rejects a stable version tag that points elsewhere", async () => {
    const sha = "a".repeat(40);
    const fetchImplementation = vi.fn(async (url) => {
      const path = new URL(url).pathname;
      if (path.includes("/commits/")) {
        return jsonResponse({ sha });
      }
      return jsonResponse({ object: { type: "commit", sha: "b".repeat(40) } });
    });

    await expect(resolveActionPins(
      [{ repository: "owner/action", sha, tag: "v1.2.3" }],
      { token: "token", fetchImplementation },
    )).rejects.toThrow("does not resolve");
  });

  it("requires authentication for the explicit resolution gate", async () => {
    await expect(resolveActionPins([], {})).rejects.toThrow("GITHUB_TOKEN or GH_TOKEN is required");
  });
});
