import { describe, expect, it } from "vitest";
import { PROTOCOL_MAJOR, PROTOCOL_MINOR } from "../src/index.js";

describe("protocol version", () => {
  it("is frozen at 1.0", () => {
    expect([PROTOCOL_MAJOR, PROTOCOL_MINOR]).toEqual([1, 0]);
  });
});
