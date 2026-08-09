import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov"]
    },
    include: ["protocol/**/*.test.ts", "mac/**/*.test.ts", "relay/**/*.test.ts"],
    testTimeout: 10_000
  }
});
