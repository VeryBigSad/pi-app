#!/usr/bin/env node

import { execFile as execFileCallback } from "node:child_process";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { promisify } from "node:util";

const execFile = promisify(execFileCallback);
const SPDX = new Map([
  ["apache license, version 2.0", "Apache-2.0"],
  ["the apache license, version 2.0", "Apache-2.0"],
  ["the apache software license, version 2.0", "Apache-2.0"],
  ["apache-2.0", "Apache-2.0"],
  ["apache 2.0", "Apache-2.0"],
  ["mit", "MIT"],
  ["mit license", "MIT"],
  ["isc", "ISC"],
  ["isc license", "ISC"],
  ["bsd 2-clause", "BSD-2-Clause"],
  ["bsd-2-clause", "BSD-2-Clause"],
  ["bsd 3-clause", "BSD-3-Clause"],
  ["bsd-3-clause", "BSD-3-Clause"],
  ["eclipse public license 1.0", "EPL-1.0"],
  ["eclipse public license 2.0", "EPL-2.0"],
  ["eclipse public license v2.0", "EPL-2.0"],
  ["epl-2.0", "EPL-2.0"],
  ["mozilla public license 2.0", "MPL-2.0"],
  ["mpl-2.0", "MPL-2.0"],
  ["the unicode license agreement - data files and software (2016)", "Unicode-DFS-2016"],
  ["unicode-dfs-2016", "Unicode-DFS-2016"],
  ["creative commons zero v1.0 universal", "CC0-1.0"],
  ["cc0-1.0", "CC0-1.0"],
]);
const expressionPart = /^[A-Za-z0-9.+-]+$/;

function usage() {
  throw new Error("usage: node scripts/supply-chain.mjs --node-modules DIR --go-dir DIR --gradle-components FILE --policy FILE --out-dir DIR");
}

function parseArguments(args) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!key?.startsWith("--") || !value || values.has(key)) usage();
    values.set(key, value);
  }
  const required = ["--node-modules", "--go-dir", "--gradle-components", "--policy", "--out-dir"];
  if (args.length !== required.length * 2 || required.some((key) => !values.has(key))) usage();
  return Object.fromEntries([...values.entries()].map(([key, value]) => [key.slice(2).replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase()), resolve(value)]));
}

function normalizeLicense(value) {
  if (typeof value !== "string" || value.trim() === "") return [];
  const normalized = value.trim();
  if (SPDX.has(normalized.toLowerCase())) return [SPDX.get(normalized.toLowerCase())];
  const parts = normalized.split(/\s+(?:OR|AND)\s+/);
  if (!parts.every((part) => expressionPart.test(part))) return [];
  return [...new Set(parts.map((part) => SPDX.get(part.toLowerCase()) ?? part))].sort();
}

function packageUrl(type, name, version) {
  return `pkg:${type}/${encodeURIComponent(name).replace(/%40/g, "@")}@${encodeURIComponent(version)}`;
}

function component(ecosystem, name, version, licenses) {
  return { ecosystem, name, version, purl: packageUrl(ecosystem, name, version), licenses };
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function nodeComponents(nodeModules) {
  const lock = await readJson(join(dirname(nodeModules), "package-lock.json"));
  const components = [];
  for (const [relativePath, entry] of Object.entries(lock.packages ?? {})) {
    if (!relativePath.startsWith("node_modules/") || !entry.version) continue;
    const manifest = await readJson(join(dirname(nodeModules), relativePath, "package.json")).catch((error) => {
      if (error?.code === "ENOENT") return entry;
      throw error;
    });
    const name = manifest.name ?? relativePath.slice("node_modules/".length);
    const rawLicenses = Array.isArray(manifest.licenses) ? manifest.licenses.map((license) => license?.type).join(" OR ") : manifest.license;
    components.push(component("npm", name, entry.version, normalizeLicense(rawLicenses)));
  }
  return uniqueComponents(components);
}

function parseJsonStream(text) {
  const values = [];
  let start = 0;
  let depth = 0;
  let quoted = false;
  let escaped = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      escaped = character === "\\" && !escaped;
      if (character === '"' && !escaped) quoted = false;
      if (character !== "\\") escaped = false;
      continue;
    }
    if (character === '"') quoted = true;
    if (character === "{") depth += 1;
    if (character === "}") {
      depth -= 1;
      if (depth === 0) {
        values.push(JSON.parse(text.slice(start, index + 1)));
        start = index + 1;
      }
    }
  }
  if (depth !== 0 || text.slice(start).trim() !== "") throw new Error("invalid Go module JSON stream");
  return values;
}

function licenseFromText(text) {
  const spdx = text.match(/SPDX-License-Identifier:\s*([^\s*]+)/i)?.[1];
  if (spdx) return normalizeLicense(spdx);
  if (/apache license[\s\S]{0,200}version 2\.0/i.test(text)) return ["Apache-2.0"];
  if (/permission is hereby granted, free of charge, to any person obtaining a copy/i.test(text)) return ["MIT"];
  if (/redistribution and use in source and binary forms/i.test(text)) return [/neither the name/i.test(text) ? "BSD-3-Clause" : "BSD-2-Clause"];
  if (/\bisc license\b/i.test(text) || /permission to use, copy, modify, and(?:\/or)? distribute this software/i.test(text)) return ["ISC"];
  return [];
}

async function goModuleLicense(module, goDirectory) {
  if (module.Dir) {
    const entries = await readdir(module.Dir, { withFileTypes: true });
    const candidate = entries.find((entry) => entry.isFile() && /^(license|copying)(\.|$)/i.test(entry.name));
    return candidate ? licenseFromText(await readFile(join(module.Dir, candidate.name), "utf8")) : [];
  }
  const { stdout } = await execFile("go", ["mod", "download", "-json", `${module.Path}@${module.Version}`], { cwd: goDirectory });
  const archive = JSON.parse(stdout).Zip;
  if (typeof archive !== "string") throw new Error(`missing source archive for ${module.Path}@${module.Version}`);
  const { stdout: files } = await execFile("unzip", ["-Z1", archive]);
  const license = files.split("\n").find((path) => /\/(license|copying)(\.|$)/i.test(path));
  if (!license) return [];
  const { stdout: text } = await execFile("unzip", ["-p", archive, license], { maxBuffer: 2 * 1024 * 1024 });
  return licenseFromText(text);
}

async function goComponents(goDirectory) {
  const { stdout } = await execFile("go", ["list", "-m", "-json", "all"], { cwd: goDirectory, maxBuffer: 16 * 1024 * 1024 });
  const modules = parseJsonStream(stdout);
  const components = [];
  for (const module of modules) {
    if (module.Main) continue;
    components.push(component("golang", module.Path, module.Version, await goModuleLicense(module, goDirectory)));
  }
  return uniqueComponents(components);
}

function decodeXml(value) {
  return value.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&#39;/g, "'").replace(/&quot;/g, '"').trim();
}

async function gradleLicense(coordinate) {
  const [group, name, version] = coordinate.split(":");
  const cacheRoot = join(process.env.GRADLE_USER_HOME ?? join(process.env.HOME ?? "", ".gradle"), "caches/modules-2/files-2.1", group, name, version);
  const paths = await readdir(cacheRoot, { recursive: true }).catch(() => []);
  const pom = paths.find((path) => path.endsWith(".pom"));
  if (!pom) return [];
  const text = await readFile(join(cacheRoot, pom), "utf8");
  return [...text.matchAll(/<license>\s*<name>([\s\S]*?)<\/name>[\s\S]*?<\/license>/g)]
    .flatMap((match) => normalizeLicense(decodeXml(match[1])));
}

async function gradleComponents(componentsPath) {
  const coordinates = await readJson(componentsPath);
  if (!Array.isArray(coordinates)) throw new Error("Gradle component report must be an array");
  const components = [];
  for (const coordinate of coordinates) {
    if (typeof coordinate !== "string" || coordinate.split(":").length !== 3) throw new Error(`invalid Gradle coordinate: ${coordinate}`);
    const [group, name, version] = coordinate.split(":");
    components.push(component("maven", `${group}:${name}`, version, await gradleLicense(coordinate)));
  }
  return uniqueComponents(components);
}

function uniqueComponents(components) {
  const unique = new Map(components.map((value) => [value.purl, value]));
  return [...unique.values()].sort((left, right) => left.purl.localeCompare(right.purl));
}

function cyclonedx(components) {
  return {
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    version: 1,
    components: components.map(({ name, version, purl, licenses }) => ({
      type: "library",
      name,
      version,
      purl,
      licenses: licenses.map((id) => ({ license: { id } })),
    })),
  };
}

function spdx(components, name) {
  return {
    SPDXID: "SPDXRef-DOCUMENT",
    SPDXVersion: "SPDX-2.3",
    creationInfo: { created: "1970-01-01T00:00:00Z", creators: ["Tool: pi-app-supply-chain"] },
    dataLicense: "CC0-1.0",
    documentNamespace: `https://github.com/VeryBigSad/pi-app/sbom/${name}`,
    name: `pi-app-${name}`,
    packages: components.map(({ name: packageName, version, purl, licenses }, index) => ({
      SPDXID: `SPDXRef-Package-${index + 1}`,
      downloadLocation: "NOASSERTION",
      licenseConcluded: licenses.length === 0 ? "NOASSERTION" : licenses.join(" OR "),
      licenseDeclared: licenses.length === 0 ? "NOASSERTION" : licenses.join(" OR "),
      name: packageName,
      versionInfo: version,
      externalRefs: [{ referenceCategory: "PACKAGE-MANAGER", referenceLocator: purl, referenceType: "purl" }],
    })),
  };
}

function applyLicenseOverrides(components, policy) {
  const overrides = policy.overrides ?? {};
  return components.map((entry) => {
    if (entry.licenses.length > 0) return entry;
    const override = overrides[entry.ecosystem]?.[`${entry.name}@${entry.version}`];
    return override ? { ...entry, licenses: normalizeLicense(override) } : entry;
  });
}

function checkLicenses(components, policy) {
  if (!Array.isArray(policy.allowed) || !Array.isArray(policy.forbidden)) throw new Error("license policy requires allowed and forbidden arrays");
  const allowed = new Set(policy.allowed);
  const forbidden = new Set(policy.forbidden);
  const violations = [];
  for (const entry of applyLicenseOverrides(components, policy)) {
    if (entry.licenses.length === 0) violations.push(`${entry.purl}: unknown license`);
    for (const license of entry.licenses) {
      if (forbidden.has(license)) violations.push(`${entry.purl}: forbidden license ${license}`);
      else if (!allowed.has(license)) violations.push(`${entry.purl}: unallowlisted license ${license}`);
    }
  }
  if (violations.length > 0) throw new Error(["license policy failed closed", ...violations.sort()].join("\n"));
}

async function main() {
  const paths = parseArguments(process.argv.slice(2));
  const [node, go, gradle, policy] = await Promise.all([
    nodeComponents(paths.nodeModules),
    goComponents(paths.goDir),
    gradleComponents(paths.gradleComponents),
    readJson(paths.policy),
  ]);
  const licensedNode = applyLicenseOverrides(node, policy);
  const licensedGo = applyLicenseOverrides(go, policy);
  const licensedGradle = applyLicenseOverrides(gradle, policy);
  const all = uniqueComponents([...licensedNode, ...licensedGo, ...licensedGradle]);
  checkLicenses(all, policy);
  await mkdir(paths.outDir, { recursive: true });
  await Promise.all([
    writeFile(join(paths.outDir, "node.cdx.json"), `${JSON.stringify(cyclonedx(licensedNode), null, 2)}\n`),
    writeFile(join(paths.outDir, "go.cdx.json"), `${JSON.stringify(cyclonedx(licensedGo), null, 2)}\n`),
    writeFile(join(paths.outDir, "gradle.cdx.json"), `${JSON.stringify(cyclonedx(licensedGradle), null, 2)}\n`),
    writeFile(join(paths.outDir, "node.spdx.json"), `${JSON.stringify(spdx(licensedNode, "node"), null, 2)}\n`),
    writeFile(join(paths.outDir, "go.spdx.json"), `${JSON.stringify(spdx(licensedGo, "go"), null, 2)}\n`),
    writeFile(join(paths.outDir, "gradle.spdx.json"), `${JSON.stringify(spdx(licensedGradle, "gradle"), null, 2)}\n`),
    writeFile(join(paths.outDir, "licenses.json"), `${JSON.stringify(all, null, 2)}\n`),
  ]);
  process.stdout.write(`generated deterministic CycloneDX SBOMs for ${node.length} npm, ${go.length} Go, and ${gradle.length} Gradle components\n`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  });
}

export { applyLicenseOverrides, checkLicenses, cyclonedx, normalizeLicense, parseJsonStream, spdx, uniqueComponents };
