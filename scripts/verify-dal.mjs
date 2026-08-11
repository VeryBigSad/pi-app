#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const packageName = "io.github.verybigsad.pimobile";
const fingerprint = "CC:36:66:F3:77:CE:4C:2B:D6:CE:19:A4:7F:3A:BE:47:19:AC:04:0F:D6:4F:FB:11:9F:02:E9:AC:4B:DD:D4:FE";
const relations = [
  "delegate_permission/common.get_login_creds",
  "delegate_permission/common.handle_all_urls"
];

export function validateDocument(value) {
  if (!Array.isArray(value) || value.length !== 1) throw new Error("DAL must contain one statement");
  const statement = value[0];
  if (!statement || typeof statement !== "object") throw new Error("DAL statement is invalid");
  if (JSON.stringify([...statement.relation].sort()) !== JSON.stringify([...relations].sort())) throw new Error("DAL relations differ");
  if (statement.target?.namespace !== "android_app") throw new Error("DAL namespace differs");
  if (statement.target?.package_name !== packageName) throw new Error("DAL package differs");
  if (JSON.stringify(statement.target?.sha256_cert_fingerprints) !== JSON.stringify([fingerprint])) throw new Error("DAL fingerprint differs");
}

async function verify() {
  const local = JSON.parse(await readFile(new URL("../web/.well-known/assetlinks.template.json", import.meta.url), "utf8"));
  validateDocument(local);

  const response = await fetch("https://verybigsad.github.io/.well-known/assetlinks.json", { redirect: "manual" });
  if (response.status !== 200) throw new Error(`DAL HTTP ${String(response.status)}`);
  if (response.headers.has("location")) throw new Error("DAL redirects");
  if (!response.headers.get("content-type")?.toLowerCase().startsWith("application/json")) throw new Error("DAL MIME differs");
  const remote = await response.json();
  validateDocument(remote);
  if (JSON.stringify(remote) !== JSON.stringify(local)) throw new Error("Live DAL differs from repository");

  for (const relation of relations) {
    const url = new URL("https://digitalassetlinks.googleapis.com/v1/statements:list");
    url.searchParams.set("source.web.site", "https://verybigsad.github.io");
    url.searchParams.set("relation", relation);
    const apiResponse = await fetch(url);
    if (!apiResponse.ok) throw new Error(`DAL API HTTP ${String(apiResponse.status)}`);
    const result = await apiResponse.json();
    const matched = result.statements?.some((statement) =>
      statement.target?.androidApp?.packageName === packageName &&
      statement.target.androidApp.certificate?.sha256Fingerprint === fingerprint
    );
    if (!matched) throw new Error(`DAL API missing ${relation}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await verify();
  process.stdout.write("DAL verified\n");
}
