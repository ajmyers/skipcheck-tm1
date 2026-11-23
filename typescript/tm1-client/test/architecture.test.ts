// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";
import { Repo } from "./support/repo.js";

const SRC = join(Repo.typescript, "src");
const SERVICES = join(SRC, "services");
const ODATA = join(SRC, "odata");

function files(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...files(path));
    else if (name.endsWith(".ts") && !name.endsWith(".d.ts")) out.push(path);
  }
  return out;
}

const name = (file: string): string => relative(SRC, file).split(sep).join("/");
const generated = (file: string): boolean => name(file).startsWith("generated/");

/** The library's own code: what's written by hand, not the generated model. */
const LIBRARY = files(SRC).filter((f) => !generated(f));

const parsed = new Map<string, ts.SourceFile>();
function source(file: string): ts.SourceFile {
  let s = parsed.get(file);
  if (!s) parsed.set(file, (s = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true)));
  return s;
}

function walk(node: ts.Node, visit: (node: ts.Node) => void): void {
  visit(node);
  node.forEachChild((child) => walk(child, visit));
}

const at = (file: string, node: ts.Node): string => `${name(file)}:${source(file).getLineAndCharacterOfPosition(node.getStart()).line + 1}`;

/** What each module imports or re-exports from. */
function imports(file: string): string[] {
  const out: string[] = [];
  walk(source(file), (node) => {
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier))
      out.push(node.moduleSpecifier.text);
    if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && node.arguments[0] && ts.isStringLiteral(node.arguments[0]))
      out.push(node.arguments[0].text);
  });
  return out;
}

const modifiers = (node: ts.Node): ts.SyntaxKind[] => (ts.canHaveModifiers(node) ? (ts.getModifiers(node) ?? []).map((m) => m.kind) : []);
const has = (node: ts.Node, kind: ts.SyntaxKind): boolean => modifiers(node).includes(kind);
const isPrivate = (node: ts.ClassElement): boolean => has(node, ts.SyntaxKind.PrivateKeyword) || (node.name !== undefined && ts.isPrivateIdentifier(node.name));

interface Service {
  file: string;
  node: ts.ClassDeclaration;
  name: string;
}

/** The services: the classes named `*Service` under src/services, as Java's `*Service` classes in
 * the service packages. */
function services(): Service[] {
  const out: Service[] = [];
  for (const file of files(SERVICES))
    for (const node of source(file).statements)
      if (ts.isClassDeclaration(node) && node.name?.text.endsWith("Service") && node.name.text !== "Tm1Service") out.push({ file, node, name: node.name.text });
  expect(out.length, "the services weren't found").toBeGreaterThan(25);
  return out;
}

/** Whether a constructor takes the session and nothing else. */
function takesTheSession(constructor: ts.ConstructorDeclaration, file: string): boolean {
  return constructor.parameters.length === 1 && constructor.parameters[0]!.type?.getText(source(file)) === "Tm1Session";
}

/** Rules the library's code keeps, checked over its source: a port of the Java client's
 * `ArchitectureTest`, which checks the same over the compiled classes. Each one is a mistake that's
 * easy to make, compiles, passes most tests, and goes wrong in production; its `because` says why.
 * The rules are strict: nothing breaks them today, so any new break fails the tests.
 *
 * Every Java rule has its counterpart here, under its Java name where the meaning carries over. A
 * promise can't be blocked on, so "never blocks" is about what does stop a JavaScript thread;
 * Reactor's bare `subscribe()` is a promise nobody awaits; WebClient's URI templates are URLs
 * written into strings; and SLF4J, which this client has no counterpart of, is the console. */
describe("architecture", () => {
  it("never blocks the thread", () => {
    const because =
      "JavaScript runs on one thread, so a synchronous wait stalls every request on it (Java's neverBlocksOnAReactiveChain). " +
      "Node's built-in modules are where the synchronous calls are, and the client runs in browsers too: it uses none of them";
    const found: string[] = [];
    for (const file of LIBRARY) {
      for (const module of imports(file)) if (module.startsWith("node:")) found.push(`${name(file)} imports ${module}`);
      walk(source(file), (node) => {
        if (!ts.isCallExpression(node)) return;
        const called = node.expression.getText(source(file));
        if (/Sync$/.test(called) || called === "Atomics.wait") found.push(`${at(file, node)} calls ${called}`);
      });
    }
    expect(found, because).toEqual([]);
  });

  it("never starts work nobody awaits", () => {
    const because =
      "a promise the library drops runs outside the caller's call: its errors go nowhere (an unhandled rejection can end a " +
      "Node process) and the caller can't cancel it. Await it or return it (Java's neverStartsWorkNobodySubscribedTo). " +
      "Keeping a promise in a variable or a field isn't dropping it. Nor is handling the failure of one that's kept and awaited " +
      "elsewhere (`kept.catch(handler)`, `kept.then(onValue, onError)`): that starts nothing, and leaves no rejection unhandled";
    const config = ts.getParsedCommandLineOfConfigFile(
      join(Repo.typescript, "tsconfig.json"),
      {},
      { ...ts.sys, onUnRecoverableConfigFileDiagnostic: () => undefined },
    )!;
    const program = ts.createProgram(LIBRARY, { ...config.options, noEmit: true });
    const checker = program.getTypeChecker();
    const isPromise = (node: ts.Expression): boolean => {
      const type = checker.getTypeAtLocation(node);
      return (type.isUnion() ? type.types : [type]).some((t) => t.getProperty("then") !== undefined);
    };
    const kept = (node: ts.Expression): boolean => ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken;
    // kept.catch(handler), kept.then(onValue, onError): the promise is a value someone else awaits,
    // not a call started here, and its failure is handled
    const handlesAKeptOne = (node: ts.Expression): boolean => {
      if (!ts.isCallExpression(node) || !ts.isPropertyAccessExpression(node.expression)) return false;
      const receiver = node.expression.expression;
      const handled =
        (node.expression.name.text === "catch" && node.arguments.length === 1) || (node.expression.name.text === "then" && node.arguments.length === 2);
      return handled && (ts.isIdentifier(receiver) || ts.isPropertyAccessExpression(receiver));
    };
    const found: string[] = [];
    for (const file of LIBRARY) {
      const tree = program.getSourceFile(file)!;
      walk(tree, (node) => {
        const dropped = ts.isExpressionStatement(node) ? node.expression : ts.isVoidExpression(node) ? node.expression : undefined;
        if (dropped && isPromise(dropped) && !kept(dropped) && !handlesAKeptOne(dropped))
          found.push(`${name(file)}:${tree.getLineAndCharacterOfPosition(node.getStart()).line + 1} drops the promise ${dropped.getText().slice(0, 60)}`);
      });
    }
    expect(found, because).toEqual([]);
  });

  it("builds request URLs rather than writing them", () => {
    const because =
      "TM1 object names contain quotes, slashes and braces (control objects such as }Clients), which a path written into a " +
      "string doesn't encode; requests use a path and query already encoded by ODataPath and ODataQuery " +
      "(Java's buildsRequestUrisRatherThanUsingUriTemplates)";
    const url = (text: string): boolean => text.includes("('") || text.includes("?$") || text.includes("&$");
    const found: string[] = [];
    for (const file of files(SERVICES)) {
      walk(source(file), (node) => {
        // A key or a query option in a string that values are put into
        if (ts.isTemplateExpression(node)) {
          const text = node.head.text + node.templateSpans.map((s) => s.literal.text).join("");
          if (url(text)) found.push(`${at(file, node)} writes a value into the URL ${JSON.stringify(text)}`);
        } else if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
          for (const side of [node.left, node.right])
            if (ts.isStringLiteralLike(side) && url(side.text)) found.push(`${at(file, node)} writes a value into the URL ${JSON.stringify(side.text)}`);
        }
      });
    }
    expect(found, because).toEqual([]);
  });

  it("never writes to the console", () => {
    const because =
      "a library that writes to the console can't be silenced or redirected by the application (Java's logsThroughSlf4jOnly, " +
      "where logging goes through SLF4J for that reason). This client logs nothing: what it has to say is in the errors it throws";
    const found: string[] = [];
    for (const file of LIBRARY) {
      walk(source(file), (node) => {
        if (!ts.isPropertyAccessExpression(node)) return;
        const text = node.getText(source(file));
        if (/^console\.\w+$/.test(text) || /^process\.(stdout|stderr)$/.test(text)) found.push(`${at(file, node)} uses ${text}`);
      });
    }
    expect(found, because).toEqual([]);
  });

  it("keeps the OData layer free of TM1", () => {
    const because =
      "the odata layer is TM1-agnostic: OData correctness lives in one place and doesn't depend on sessions, requests or TM1 " +
      "versions (Java's keepsTheODataLayerFreeOfTm1). It imports from itself and from util.ts, which has no TM1 in it either";
    const found: string[] = [];
    for (const file of files(ODATA))
      for (const module of imports(file)) if (!/^\.\/[\w-]+\.js$/.test(module) && module !== "../util.js") found.push(`${name(file)} imports ${module}`);
    for (const module of imports(join(SRC, "util.ts"))) found.push(`util.ts imports ${module}`);
    expect(found, because).toEqual([]);
  });

  it("services are stateless classes built from a session", () => {
    const because =
      "services are free to create and safe to share: everything they need is the session " +
      "(Java's servicesAreStatelessFinalClassesBuiltFromASession; TypeScript has no final classes, so no service extends another)";
    const found: string[] = [];
    const all = services();
    const names = new Set(all.map((s) => s.name));
    for (const { file, node, name: service } of all) {
      const bases = (node.heritageClauses ?? [])
        .filter((h) => h.token === ts.SyntaxKind.ExtendsKeyword)
        .flatMap((h) => h.types.map((t) => t.expression.getText(source(file))));
      if (bases.some((b) => names.has(b))) found.push(`${service} extends another service`);
      if (bases.some((b) => b !== "Tm1Service")) found.push(`${service} extends ${bases.join(", ")}, not Tm1Service alone`);
      const constructors = node.members.filter(ts.isConstructorDeclaration).filter((c) => c.body !== undefined);
      // Tm1Service's constructor takes the session; a service that isn't one takes it itself
      if (bases.length === 1 ? constructors.length !== 0 : constructors.length !== 1 || !takesTheSession(constructors[0]!, file))
        found.push(`${service} isn't built from a session alone`);
      for (const member of node.members) {
        if (!ts.isPropertyDeclaration(member)) continue;
        const field = member.name.getText(source(file));
        if (!has(member, ts.SyntaxKind.ReadonlyKeyword)) found.push(`${service}.${field} can change`);
        else if (!has(member, ts.SyntaxKind.StaticKeyword) && member.type?.getText(source(file)) !== "Tm1Session")
          found.push(`${service} keeps state in ${field}`);
      }
    }
    const base = source(join(SERVICES, "service.ts")).statements.find(
      (s): s is ts.ClassDeclaration => ts.isClassDeclaration(s) && s.name?.text === "Tm1Service",
    )!;
    const constructors = base.members.filter(ts.isConstructorDeclaration);
    if (constructors.length !== 1 || !takesTheSession(constructors[0]!, join(SERVICES, "service.ts")))
      found.push("Tm1Service's constructor takes more than the session");
    if (base.members.some(ts.isPropertyDeclaration)) found.push("Tm1Service keeps more than the session");
    expect(found, because).toEqual([]);
  });

  it("service methods are asynchronous and declare their endpoints", () => {
    const because =
      "each service method is one REST call that never blocks, returning a Promise or a Tm1Stream, and the coverage gate counts " +
      "what's implemented from the class's endpoints table (Java's serviceMethodsAreReactiveAndDeclareTheirEndpoints)";
    const found: string[] = [];
    for (const { file, node, name: service } of services()) {
      const table = node.members.find(
        (m): m is ts.PropertyDeclaration => ts.isPropertyDeclaration(m) && m.name.getText(source(file)) === "endpoints" && has(m, ts.SyntaxKind.StaticKeyword),
      );
      let declared: ts.Expression | undefined = table?.initializer;
      while (declared && (ts.isAsExpression(declared) || ts.isSatisfiesExpression(declared))) declared = declared.expression;
      if (!declared || !ts.isObjectLiteralExpression(declared)) {
        found.push(`${service} has no endpoints table`);
        continue;
      }
      const endpoints = new Set(declared.properties.map((p) => p.name?.getText(source(file))));
      for (const member of node.members) {
        if (!ts.isMethodDeclaration(member) || isPrivate(member) || has(member, ts.SyntaxKind.ProtectedKeyword) || has(member, ts.SyntaxKind.StaticKeyword))
          continue;
        const method = member.name.getText(source(file));
        const returns = member.type?.getText(source(file)) ?? "";
        if (!/^(Promise|Tm1Stream)</.test(returns))
          found.push(`${at(file, member)} ${service}.${method} returns ${returns || "an unannotated type"}, not a Promise or a Tm1Stream`);
        if (!endpoints.has(method)) found.push(`${at(file, member)} ${service}.${method} isn't in ${service}.endpoints`);
      }
    }
    expect(found, because).toEqual([]);
  });
});
