import { request, type RequestOptions } from "node:https";
import { RelayError, type RelayFetch } from "./types.js";

const MAX_ADMIN_BODY_BYTES = 4 << 10;

export const TLS13_RELAY_FETCH: RelayFetch = (input, init) => {
  let url: URL;
  try {
    url = new URL(input);
  } catch {
    return Promise.reject(new RelayError("RELAY_TLS_REQUIRED", "relay admin URL is invalid"));
  }
  if (url.protocol !== "https:") {
    return Promise.reject(new RelayError("RELAY_TLS_REQUIRED", "relay admin requires HTTPS"));
  }
  if (init.method !== "POST" || typeof init.body !== "string") {
    return Promise.reject(new RelayError("RELAY_TRANSPORT", "relay admin request shape is invalid"));
  }
  const bodyBytes = Buffer.byteLength(init.body, "utf8");
  if (bodyBytes === 0 || bodyBytes > MAX_ADMIN_BODY_BYTES) {
    return Promise.reject(new RelayError("RELAY_RESOURCE_EXHAUSTED", "relay admin request body exceeds bounds"));
  }
  const headers = new Headers(init.headers);
  headers.set("Content-Length", String(bodyBytes));
  const options: RequestOptions = {
    method: "POST",
    headers: Object.fromEntries(headers.entries()),
    minVersion: "TLSv1.3",
    rejectUnauthorized: true,
    agent: false,
  };
  if (init.signal !== undefined && init.signal !== null) options.signal = init.signal;
  return new Promise<Response>((resolve, reject) => {
    const outgoing = request(url, options, (incoming) => {
      incoming.resume();
      const status = incoming.statusCode;
      if (status === undefined || status < 200 || status > 599) {
        reject(new RelayError("RELAY_TRANSPORT", "relay admin response status is invalid"));
        return;
      }
      resolve(new Response(null, { status }));
    });
    outgoing.once("error", () => reject(new RelayError("RELAY_TRANSPORT", "relay admin HTTPS request failed")));
    outgoing.end(init.body);
  });
};
