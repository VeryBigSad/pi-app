#!/usr/bin/env node

import { readdir, readFile } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
export const defaultWorkflowsDirectory = resolve(scriptDirectory, "../.github/workflows");

export const expectedActionTags = Object.freeze([
  Object.freeze({ repository: "ReactiveCircus/android-emulator-runner", sha: "1dcd0090116d15e7c562f8db72807de5e036a4ed", tag: "v2.34.0" }),
  Object.freeze({ repository: "actions/checkout", sha: "11d5960a326750d5838078e36cf38b85af677262", tag: "v4.4.0" }),
  Object.freeze({ repository: "actions/checkout", sha: "3d3c42e5aac5ba805825da76410c181273ba90b1", tag: "v7.0.1" }),
  Object.freeze({ repository: "actions/download-artifact", sha: "d3f86a106a0bac45b974a628896c90dbdf5c8093", tag: "v4.3.0" }),
  Object.freeze({ repository: "actions/setup-go", sha: "40f1582b2485089dde7abd97c1529aa768e1baff", tag: "v5.6.0" }),
  Object.freeze({ repository: "actions/setup-go", sha: "b7ad1dad31e06c5925ef5d2fc7ad053ef454303e", tag: "v7.0.0" }),
  Object.freeze({ repository: "actions/setup-java", sha: "cf277c60eb25467037889841efdb72551f06f6c3", tag: "v4.9.1" }),
  Object.freeze({ repository: "actions/setup-node", sha: "49933ea5288caeca8642d1e84afbd3f7d6820020", tag: "v4.4.0" }),
  Object.freeze({ repository: "actions/upload-artifact", sha: "ea165f8d65b6e75b540449e92b4886f43607fa02", tag: "v4.6.2" }),
  Object.freeze({ repository: "android-actions/setup-android", sha: "9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407", tag: "v3.2.2" }),
  Object.freeze({ repository: "docker/build-push-action", sha: "53b7df96c91f9c12dcc8a07bcb9ccacbed38856a", tag: "v7.3.0" }),
  Object.freeze({ repository: "docker/login-action", sha: "dbcb813823bdd20940b903addbd779551569679f", tag: "v4.6.0" }),
  Object.freeze({ repository: "docker/setup-buildx-action", sha: "bb05f3f5519dd87d3ba754cc423b652a5edd6d2c", tag: "v4.2.0" }),
  Object.freeze({ repository: "gitleaks/gitleaks-action", sha: "ff98106e4c7b2bc287b24eaf42907196329070c7", tag: "v2.3.9" }),
  Object.freeze({ repository: "hashicorp/setup-terraform", sha: "b9cd54a3c349d3f38e8881555d616ced269862dd", tag: "v3.1.2" }),
  Object.freeze({ repository: "sigstore/cosign-installer", sha: "6f9f17788090df1f26f669e9d70d6ae9567deba6", tag: "v4.1.2" }),
]);

const actionNamePattern = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_./-]+)?$/;
const immutableShaPattern = /^[0-9a-f]{40}$/;

function parseUsesValue(rawValue, source, lineNumber) {
  let value = rawValue.trim();
  if (value.startsWith('"') || value.startsWith("'")) {
    const quote = value[0];
    const closingQuote = value.indexOf(quote, 1);
    if (closingQuote < 0) {
      throw new Error(`${source}:${lineNumber}: unterminated uses value`);
    }
    value = value.slice(1, closingQuote);
  } else {
    value = value.split(/\s+#/, 1)[0];
  }

  if (value.startsWith("./") || value.startsWith("docker://")) {
    return null;
  }

  const separator = value.lastIndexOf("@");
  if (separator < 1 || separator === value.length - 1) {
    throw new Error(`${source}:${lineNumber}: external action must use owner/repo@ref`);
  }

  const action = value.slice(0, separator);
  const ref = value.slice(separator + 1);
  if (!actionNamePattern.test(action)) {
    throw new Error(`${source}:${lineNumber}: unsupported external action ${action}`);
  }

  return {
    action,
    repository: action.split("/").slice(0, 2).join("/"),
    ref,
    source,
    line: lineNumber,
  };
}

export function extractActionPinsFromText(text, source = "workflow.yml") {
  const pins = [];
  for (const [index, line] of text.split(/\r?\n/).entries()) {
    const match = line.match(/^\s*(?:-\s+)?uses:\s*(.*?)\s*$/);
    if (!match) {
      continue;
    }
    const pin = parseUsesValue(match[1], source, index + 1);
    if (pin) {
      pins.push(pin);
    }
  }
  return pins;
}

export async function loadActionPins(workflowsDirectory = defaultWorkflowsDirectory) {
  const files = (await readdir(workflowsDirectory, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && /\.ya?ml$/.test(entry.name))
    .map((entry) => entry.name)
    .sort();
  const pins = [];
  for (const file of files) {
    const path = join(workflowsDirectory, file);
    const source = relative(resolve(workflowsDirectory, "../.."), path);
    pins.push(...extractActionPinsFromText(await readFile(path, "utf8"), source));
  }
  return pins;
}

function pinKey(repository, sha) {
  return `${repository}@${sha}`;
}

export function verifyActionPins(pins, expectations = expectedActionTags) {
  if (pins.length === 0) {
    throw new Error("no external action pins found");
  }

  const errors = [];
  const expectedByKey = new Map();
  for (const expectation of expectations) {
    const key = pinKey(expectation.repository, expectation.sha);
    if (expectedByKey.has(key)) {
      errors.push(`duplicate expected pin ${key}`);
    }
    expectedByKey.set(key, expectation);
  }

  const actualKeys = new Set();
  for (const pin of pins) {
    if (!immutableShaPattern.test(pin.ref)) {
      errors.push(`${pin.source}:${pin.line}: ${pin.action}@${pin.ref} is not a 40-character lowercase commit SHA`);
      continue;
    }
    const key = pinKey(pin.repository, pin.ref);
    actualKeys.add(key);
    if (!expectedByKey.has(key)) {
      errors.push(`${pin.source}:${pin.line}: unreviewed action pin ${key}`);
    }
  }

  for (const key of expectedByKey.keys()) {
    if (!actualKeys.has(key)) {
      errors.push(`expected action pin is unused: ${key}`);
    }
  }

  if (errors.length > 0) {
    throw new Error(["action pin verification failed", ...errors.sort()].join("\n"));
  }

  return [...actualKeys]
    .sort()
    .map((key) => expectedByKey.get(key));
}

async function githubApi(path, token, fetchImplementation) {
  const response = await fetchImplementation(`https://api.github.com${path}`, {
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "User-Agent": "pi-app-action-pin-verifier",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!response.ok) {
    throw new Error(`GitHub API ${path} returned ${response.status}`);
  }
  return response.json();
}

export async function resolveActionPins(pins, { token, fetchImplementation = fetch } = {}) {
  if (!token) {
    throw new Error("GITHUB_TOKEN or GH_TOKEN is required with --resolve");
  }

  await Promise.all(pins.map(async (pin) => {
    const repositoryPath = pin.repository.split("/").map(encodeURIComponent).join("/");
    const commit = await githubApi(`/repos/${repositoryPath}/commits/${pin.sha}`, token, fetchImplementation);
    if (commit.sha !== pin.sha) {
      throw new Error(`${pin.repository}: commit API resolved ${pin.sha} to ${commit.sha}`);
    }

    const reference = await githubApi(
      `/repos/${repositoryPath}/git/ref/tags/${encodeURIComponent(pin.tag)}`,
      token,
      fetchImplementation,
    );
    let object = reference.object;
    const visited = new Set();
    while (object?.type === "tag") {
      if (visited.has(object.sha) || visited.size >= 8) {
        throw new Error(`${pin.repository}: tag ${pin.tag} has an invalid tag chain`);
      }
      visited.add(object.sha);
      const tag = await githubApi(`/repos/${repositoryPath}/git/tags/${object.sha}`, token, fetchImplementation);
      object = tag.object;
    }
    if (object?.type !== "commit" || object.sha !== pin.sha) {
      throw new Error(`${pin.repository}: tag ${pin.tag} does not resolve to ${pin.sha}`);
    }
  }));

  return pins;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.some((argument) => argument !== "--resolve")) {
    throw new Error("usage: node scripts/verify-action-pins.mjs [--resolve]");
  }
  const pins = await loadActionPins();
  const uniquePins = verifyActionPins(pins);
  process.stdout.write(`verified ${pins.length} action uses with ${uniquePins.length} immutable pins\n`);
  if (args.includes("--resolve")) {
    const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
    await resolveActionPins(uniquePins, { token });
    process.stdout.write(`resolved ${uniquePins.length} action commits and version tags through GitHub API\n`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  });
}
