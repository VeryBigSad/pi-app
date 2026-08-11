import { once } from "node:events";
import { describe, expect, it } from "vitest";
import { PiRpcProcess } from "../src/pi/rpc-process.js";

const echoScript = `
process.stderr.write("diagnostic\\n");
const event = JSON.stringify({type:"agent_start"}) + "\\n";
process.stdout.write(event.slice(0, 5));
process.stdout.write(event.slice(5));
let input = "";
process.stdin.on("data", chunk => {
  input += chunk;
  for (;;) {
    const at = input.indexOf("\\n");
    if (at < 0) break;
    const line = input.slice(0, at); input = input.slice(at + 1);
    const command = JSON.parse(line);
    process.stdout.write(JSON.stringify({type:"response",id:command.id,command:command.type,success:true,data:{ok:true}}) + "\\n");
  }
});
`;

describe("PiRpcProcess", () => {
  it("frames events and correlates responses", async () => {
    const processHost = new PiRpcProcess({
      executable: process.execPath,
      args: ["-e", echoScript],
      cwd: process.cwd(),
    });
    processHost.start();
    const recordPromise = once(processHost, "record");
    const response = await processHost.call({ type: "get_state" });
    const [record] = (await recordPromise) as [{ value: unknown }];
    expect(response).toMatchObject({ type: "response", command: "get_state", success: true });
    expect(record.value).toEqual({ type: "agent_start" });
    expect(processHost.stderrText()).toContain("diagnostic");
    await processHost.stop();
    expect(processHost.state()).toBe("stopped");
  });

  it("times out a missing response", async () => {
    const processHost = new PiRpcProcess({
      executable: process.execPath,
      args: ["-e", "process.stdin.resume()"],
      cwd: process.cwd(),
      responseTimeoutMs: 20,
    });
    processHost.start();
    await expect(processHost.call({ type: "get_state" })).rejects.toMatchObject({
      name: "RpcProcessFault",
      message: "RPC response timed out",
    });
    await processHost.stop();
  });
});
