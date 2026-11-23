// SPDX-License-Identifier: Apache-2.0

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import ts from "typescript";

/** A public member of a TypeScript class or const object: each signature's parameter names
 * (empty for a property). */
export interface TsMember {
  name: string;
  isStatic: boolean;
  signatures: string[][];
  /** Each signature's declared return type, as written. */
  returns: string[];
}

/** An exported class (nested ones, in a namespace, named `Outer.Inner`) or const object. */
export interface TsDeclaration {
  name: string;
  kind: "class" | "const";
  members: Map<string, TsMember[]>;
  /** The constructor's signatures, overloads included. */
  constructors: string[][];
}

const modifiers = (node: ts.Node): readonly ts.ModifierLike[] => (ts.canHaveModifiers(node) ? (ts.getModifiers(node) ?? []) : []);
const has = (node: ts.Node, kind: ts.SyntaxKind): boolean => modifiers(node).some((m) => m.kind === kind);

/** Public, and not marked `@internal` (which is what Java has package-private). */
function isApi(node: ts.Node): boolean {
  if (has(node, ts.SyntaxKind.PrivateKeyword) || has(node, ts.SyntaxKind.ProtectedKeyword)) return false;
  const name = (node as { name?: ts.Node }).name;
  if (name && ts.isPrivateIdentifier(name)) return false;
  return !ts.getJSDocTags(node).some((t) => t.tagName.text === "internal");
}

function params(list: ts.NodeArray<ts.ParameterDeclaration>, source: ts.SourceFile): string[] {
  return list.map((p) => p.name.getText(source));
}

function add(d: TsDeclaration, name: string, isStatic: boolean, signature: string[] | undefined, returns = ""): void {
  const list = d.members.get(name) ?? [];
  let m = list.find((x) => x.isStatic === isStatic);
  if (!m) {
    m = { name, isStatic, signatures: [], returns: [] };
    list.push(m);
  }
  if (signature) {
    m.signatures.push(signature);
    m.returns.push(returns);
  }
  d.members.set(name, list);
}

/** Every exported class and const object in `dir`'s `.ts` files, by name. */
export function declarations(dir: string): Map<string, TsDeclaration> {
  const out = new Map<string, TsDeclaration>();
  for (const file of readdirSync(dir).filter((f) => f.endsWith(".ts"))) {
    const source = ts.createSourceFile(file, readFileSync(join(dir, file), "utf8"), ts.ScriptTarget.Latest, true);
    const visit = (statements: ts.NodeArray<ts.Statement>, prefix: string): void => {
      for (const s of statements) {
        if (!has(s, ts.SyntaxKind.ExportKeyword)) continue;
        if (ts.isClassDeclaration(s) && s.name) {
          const d: TsDeclaration = { name: prefix + s.name.text, kind: "class", members: new Map(), constructors: [] };
          out.set(d.name, d);
          for (const m of s.members) {
            if (ts.isConstructorDeclaration(m)) {
              if (!has(m, ts.SyntaxKind.PrivateKeyword)) d.constructors.push(params(m.parameters, source));
              // Parameter properties: `constructor(readonly image: string)`
              for (const p of m.parameters) {
                if ((has(p, ts.SyntaxKind.ReadonlyKeyword) || has(p, ts.SyntaxKind.PublicKeyword)) && isApi(p))
                  add(d, p.name.getText(source), false, undefined);
              }
              continue;
            }
            if (!isApi(m)) continue;
            const name = m.name && (ts.isIdentifier(m.name) || ts.isStringLiteral(m.name)) ? m.name.text : undefined;
            if (!name) continue;
            add(
              d,
              name,
              has(m, ts.SyntaxKind.StaticKeyword),
              ts.isMethodDeclaration(m) ? params(m.parameters, source) : undefined,
              ts.isMethodDeclaration(m) ? (m.type?.getText(source) ?? "") : "",
            );
          }
        } else if (ts.isVariableStatement(s)) {
          for (const v of s.declarationList.declarations) {
            if (!ts.isIdentifier(v.name) || !v.initializer || !ts.isObjectLiteralExpression(v.initializer)) continue;
            const d: TsDeclaration = { name: prefix + v.name.text, kind: "const", members: new Map(), constructors: [] };
            out.set(d.name, d);
            for (const p of v.initializer.properties) {
              const name = p.name && ts.isIdentifier(p.name) ? p.name.text : undefined;
              if (name) add(d, name, true, ts.isMethodDeclaration(p) ? params(p.parameters, source) : undefined);
            }
          }
        } else if (ts.isModuleDeclaration(s) && s.body && ts.isModuleBlock(s.body)) {
          visit(s.body.statements, prefix + s.name.getText(source) + ".");
        }
      }
    };
    visit(source.statements, "");
  }
  return out;
}
