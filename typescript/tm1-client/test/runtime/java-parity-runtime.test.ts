import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Repo } from "../support/repo.js";
import { allMembers, declarations } from "../support/sources.js";

/** Java class (nested ones as `Outer.Inner`) → its file, and the TypeScript declarations that hold
 * its members. */
const CLASSES: Record<string, [file: string, ts: string[]]> = {
  Tm1Session: ["session/Tm1Session.java", ["Tm1Session"]],
  "Tm1Session.Builder": ["session/Tm1Session.java", ["Tm1SessionBuilder"]],
  Tm1SessionBatch: ["session/Tm1SessionBatch.java", ["Tm1SessionBatch"]],
  Tm1SessionPool: ["session/Tm1SessionPool.java", ["Tm1SessionPool"]],
  "Tm1SessionPool.Builder": ["session/Tm1SessionPool.java", ["Tm1SessionPoolBuilder"]],
  Tm1Timeouts: ["session/Tm1Timeouts.java", ["Tm1Timeouts"]],
  "Tm1Timeouts.Builder": ["session/Tm1Timeouts.java", ["Tm1Timeouts.Builder"]],
  Tm1Identity: ["session/Tm1Identity.java", ["Tm1Identity"]],
  Tm1Client: ["Tm1Client.java", ["Tm1Client"]],
  "Tm1Client.Builder": ["Tm1Client.java", ["Tm1ClientBuilder"]],
  Tm1Batch: ["Tm1Batch.java", ["Tm1Batch"]],
  Tm1Request: ["Tm1Request.java", ["Tm1Request"]],
  Tm1Operation: ["Tm1Operation.java", ["Tm1Operation"]],
  ODataQuery: ["odata/ODataQuery.java", ["ODataQuery"]],
  ODataPath: ["odata/ODataPath.java", ["ODataPath"]],
  Filter: ["odata/Filter.java", ["Filter"]],
  "Filter.Variable": ["odata/Filter.java", ["Filter.Variable"]],
  ODataLiterals: ["odata/ODataLiterals.java", ["ODataLiterals"]],
  "ODataLiterals.Raw": ["odata/ODataLiterals.java", ["ODataLiterals.Raw"]],
  ODataJson: ["odata/ODataJson.java", ["ODataJson"]],
  Tm1Version: ["version/Tm1Version.java", ["Tm1Version"]],
  Tm1Capability: ["version/Tm1Capability.java", ["Tm1Capability"]],
  Tm1Capabilities: ["version/Tm1Capabilities.java", ["Tm1Capabilities"]],
  Tm1Dialect: ["version/Tm1Dialect.java", ["Tm1Dialect"]],
  Tm1ErrorClassifier: ["error/Tm1ErrorClassifier.java", ["Tm1ErrorClassifier"]],
  "Tm1ErrorClassifier.Rule": ["error/Tm1ErrorClassifier.java", ["Tm1ErrorClassifier.Rule"]],
  Tm1Exception: ["error/Tm1Exception.java", ["Tm1Exception"]],
  Tm1ApiException: ["error/Tm1ApiException.java", ["Tm1ApiException"]],
  Tm1TransportException: ["error/Tm1TransportException.java", ["Tm1TransportException"]],
  Tm1ProtocolException: ["error/Tm1ProtocolException.java", ["Tm1ProtocolException"]],
  Tm1UnsupportedOperationException: ["error/Tm1UnsupportedOperationException.java", ["Tm1UnsupportedOperationException"]],
  ODataError: ["error/ODataError.java", ["ODataError"]],
  Tm1Authenticator: ["auth/Tm1Authenticator.java", ["Tm1Authenticator"]],
  CognosPassports: ["auth/CognosPassports.java", ["CognosPassports"]],
};

/** Java members with no TypeScript counterpart, and why. */
const JAVA_ONLY: Record<string, string> = {
  "Tm1Session.Builder.jsonMapper": "Jackson; TypeScript reads TM1's JSON as is (readAs), with TM1's own property names",
  "Tm1Session.Builder.observationRegistry": "Micrometer; wrap the transport to observe requests",
  "Tm1Session.Builder.webClient": "Spring's WebClient; TypeScript sends through a Tm1Transport: transport",
  "Tm1Session.Builder.connector": "Reactor Netty's connector; transport",
  "Tm1Client.Builder.jsonMapper": "Jackson; see Tm1Session.Builder.jsonMapper",
  "Tm1Client.Builder.observationRegistry": "Micrometer; see Tm1Session.Builder.observationRegistry",
  "Tm1Client.Builder.webClient": "Spring's WebClient; transport",
  "Tm1Client.Builder.connector": "Reactor Netty's connector; transport",
  "Tm1SessionPool.Builder.scheduler": "Reactor's Scheduler; TypeScript takes a clock",
  "Tm1Capabilities.of": "Built from a CsdlDocument, which TypeScript doesn't model; Tm1Capabilities.parse reads $metadata",
  "Tm1Capabilities.metadata": "The CsdlDocument, which TypeScript doesn't model",
};

/** The Java runtime classes not yet ported: each comes off as it's done, and must fall short until then. */
const PENDING = new Set<string>([]);

const JAVA = Repo.javaClient;

/** The public methods of a Java class, or of its nested class `nested`. An interface's members
 * count as public. */
export function javaMembers(file: string, nested: string | undefined): string[] {
  const lines = readFileSync(file, "utf8").split("\n");
  const isInterface = lines.some((l) => /^public\s+(sealed\s+)?interface\s+/.test(l));
  const result: string[] = [];
  let current: string | undefined;
  for (const line of lines) {
    const type = /^\t(public\s+)?(static\s+)?(final\s+)?(sealed\s+)?(class|record|interface|enum)\s+(?<name>\w+)/.exec(line);
    if (type) {
      current = type.groups!["name"];
      continue;
    }
    if (line === "\t}" || line === "\t};") {
      current = undefined;
      continue;
    }
    const depth = line.length - line.replace(/^\t+/, "").length;
    const wanted = nested === undefined ? depth === 1 && current === undefined : depth === 2 && current === nested;
    if (!wanted) continue;
    const trimmed = line.trim();
    if (trimmed.startsWith("private ") || trimmed.startsWith("protected ")) continue;
    const m =
      /^(?<public>public\s+)?(static\s+)?(default\s+)?(synchronized\s+)?(<[^>]+>\s+)?(?<ret>[\w.<>[\]?, @]+?)\s+(?<name>\w+)\s*\([^)]*\)?\s*(throws [\w., ]+)?(\{|;)?$/.exec(
        trimmed,
      );
    if (!m || (!m.groups!["public"] && !isInterface) || ["if", "for", "while", "switch", "return", "new"].includes(m.groups!["name"]!)) continue;
    result.push(m.groups!["name"]!);
  }
  return result;
}

const sources = declarations(join(Repo.typescript, "src"));

function missing(name: string): string[] {
  const [file, types] = CLASSES[name]!;
  const absent = types.filter((t) => !sources.has(t));
  if (absent.length) return [`no ${absent.join(" or ")}`];
  const nested = name.includes(".") ? name.slice(name.indexOf(".") + 1) : undefined;
  const out: string[] = [];
  for (const member of javaMembers(join(JAVA, file), nested)) {
    if (["toString", "equals", "hashCode"].includes(member) || JAVA_ONLY[`${name}.${member}`]) continue;
    if (!types.some((t) => allMembers(sources, t).has(member))) out.push(member);
  }
  return [...new Set(out)];
}

/** The TypeScript runtime offers what the Java client's does, read from the Java sources: every
 * public method of the session, version, error, auth, OData and request classes (and their nested
 * builders) has a counterpart of the same name; a property stands for a no-argument accessor. What
 * has no TypeScript counterpart is in {@link JAVA_ONLY}, with the reason. */
describe("Java runtime parity", () => {
  it.each(Object.keys(CLASSES).sort())("%s has every public Java member", (name) => {
    const found = missing(name);
    if (PENDING.has(name)) {
      expect(found.length, `${name} is complete: take it off PENDING`).toBeGreaterThan(0);
      return;
    }
    expect(found, `${name} lacks`).toEqual([]);
  });

  it("lists only Java members that exist", () => {
    for (const key of Object.keys(JAVA_ONLY)) {
      const member = key.slice(key.lastIndexOf(".") + 1);
      const name = key.slice(0, key.lastIndexOf("."));
      const nested = name.includes(".") ? name.slice(name.indexOf(".") + 1) : undefined;
      expect(javaMembers(join(JAVA, CLASSES[name]![0]), nested), key).toContain(member);
    }
  });
});
