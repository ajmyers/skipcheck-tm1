import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import ts from "typescript";

/** A public member of a TypeScript declaration: a method's signatures (parameter names), or a
 * property, getter or object key. */
export interface TsMember {
  name: string;
  isStatic: boolean;
  /** Each overload's parameter names, in order; empty for a property. */
  signatures: string[][];
}

/** A declaration that stands for a Java type: a class, an interface or type, a const object, or a
 * namespace's functions. Nested ones are named `Outer.Inner`. */
export interface TsDeclaration {
  name: string;
  kind: "class" | "interface" | "const" | "namespace" | "type";
  file: string;
  base: string | undefined;
  members: Map<string, TsMember>;
  /** A string union's members, for an enum: `type ViewScope = "Public" | "Private"`. */
  literals: string[];
}

function files(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...files(path));
    else if (name.endsWith(".ts") && !name.endsWith(".d.ts")) out.push(path);
  }
  return out;
}

function isPublic(node: ts.Node): boolean {
  const mods = ts.canHaveModifiers(node) ? (ts.getModifiers(node) ?? []) : [];
  if (mods.some((m) => m.kind === ts.SyntaxKind.PrivateKeyword || m.kind === ts.SyntaxKind.ProtectedKeyword)) return false;
  const name = (node as { name?: ts.Node }).name;
  if (name && ts.isPrivateIdentifier(name)) return false;
  // `@internal` members aren't the API
  return !ts.getJSDocTags(node).some((t) => t.tagName.text === "internal");
}

function isStatic(node: ts.Node): boolean {
  const mods = ts.canHaveModifiers(node) ? (ts.getModifiers(node) ?? []) : [];
  return mods.some((m) => m.kind === ts.SyntaxKind.StaticKeyword);
}

function nameOf(node: { name?: ts.PropertyName | ts.BindingName }, source: ts.SourceFile): string | undefined {
  const n = node.name;
  if (!n) return undefined;
  if (ts.isIdentifier(n) || ts.isStringLiteral(n) || ts.isNumericLiteral(n)) return n.text;
  if (ts.isComputedPropertyName(n)) return n.expression.getText(source);
  return undefined;
}

function params(parameters: ts.NodeArray<ts.ParameterDeclaration>, source: ts.SourceFile): string[] {
  return parameters.map((p) => p.name.getText(source).replace(/^\.\.\./, ""));
}

function add(members: Map<string, TsMember>, name: string, staticMember: boolean, signature: string[] | undefined): void {
  const m = members.get(name) ?? { name, isStatic: staticMember, signatures: [] };
  if (signature) m.signatures.push(signature);
  members.set(name, m);
}

/** Every exported declaration under `dir`, by name. */
export function declarations(dir: string): Map<string, TsDeclaration> {
  const out = new Map<string, TsDeclaration>();
  for (const file of files(dir)) {
    const source = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true);
    const visit = (statements: ts.NodeArray<ts.Statement>, prefix: string, exportedOnly: boolean): void => {
      for (const s of statements) {
        const exported = (ts.getModifiers(s as ts.HasModifiers) ?? []).some((m) => m.kind === ts.SyntaxKind.ExportKeyword);
        if (exportedOnly && !exported) continue;
        const get = (name: string, kind: TsDeclaration["kind"]): TsDeclaration => {
          const full = prefix + name;
          let d = out.get(full);
          if (!d) {
            d = { name: full, kind, file: basename(file), base: undefined, members: new Map(), literals: [] };
            out.set(full, d);
          } else if (kind === "class") d.kind = "class";
          return d;
        };
        if (ts.isClassDeclaration(s) && s.name) {
          const d = get(s.name.text, "class");
          const ext = s.heritageClauses?.find((h) => h.token === ts.SyntaxKind.ExtendsKeyword)?.types[0];
          if (ext) d.base = ext.expression.getText(source).replace(/<.*$/, "");
          for (const m of s.members) {
            if (!isPublic(m)) continue;
            if (ts.isConstructorDeclaration(m)) continue;
            const name = nameOf(m as { name?: ts.PropertyName }, source);
            if (!name) continue;
            if (ts.isMethodDeclaration(m)) add(d.members, name, isStatic(m), params(m.parameters, source));
            else add(d.members, name, isStatic(m), undefined);
          }
          // Constructor parameter properties are members too
          for (const m of s.members) {
            if (ts.isConstructorDeclaration(m)) {
              for (const p of m.parameters) {
                if (ts.getModifiers(p)?.some((x) => x.kind === ts.SyntaxKind.ReadonlyKeyword || x.kind === ts.SyntaxKind.PublicKeyword) && isPublic(p)) add(d.members, p.name.getText(source), false, undefined);
              }
            }
          }
        } else if (ts.isInterfaceDeclaration(s)) {
          const d = get(s.name.text, "interface");
          for (const m of s.members) {
            const name = nameOf(m as { name?: ts.PropertyName }, source);
            if (!name) continue;
            add(d.members, name, false, ts.isMethodSignature(m) ? params(m.parameters, source) : undefined);
          }
        } else if (ts.isTypeAliasDeclaration(s)) {
          const d = get(s.name.text, "type");
          if (ts.isUnionTypeNode(s.type)) {
            for (const t of s.type.types) if (ts.isLiteralTypeNode(t) && ts.isStringLiteral(t.literal)) d.literals.push(t.literal.text);
          }
          if (ts.isTypeLiteralNode(s.type)) {
            for (const m of s.type.members) {
              const name = nameOf(m as { name?: ts.PropertyName }, source);
              if (name) add(d.members, name, false, ts.isMethodSignature(m) ? params(m.parameters, source) : undefined);
            }
          }
        } else if (ts.isVariableStatement(s)) {
          for (const v of s.declarationList.declarations) {
            if (!ts.isIdentifier(v.name)) continue;
            const d = get(v.name.text, "const");
            let init = v.initializer;
            while (init && (ts.isAsExpression(init) || ts.isSatisfiesExpression(init) || ts.isParenthesizedExpression(init))) init = init.expression;
            if (init && ts.isObjectLiteralExpression(init)) {
              for (const p of init.properties) {
                const name = nameOf(p as { name?: ts.PropertyName }, source);
                if (!name) continue;
                add(d.members, name, true, ts.isMethodDeclaration(p) ? params(p.parameters, source) : ts.isPropertyAssignment(p) && (ts.isArrowFunction(p.initializer) || ts.isFunctionExpression(p.initializer)) ? params(p.initializer.parameters, source) : undefined);
              }
            }
          }
        } else if (ts.isFunctionDeclaration(s) && s.name && prefix) {
          const ns = out.get(prefix.slice(0, -1));
          if (ns) add(ns.members, s.name.text, true, params(s.parameters, source));
        } else if (ts.isModuleDeclaration(s) && s.body && ts.isModuleBlock(s.body)) {
          const d = get(s.name.getText(source), "namespace");
          void d;
          visit(s.body.statements, prefix + s.name.getText(source) + ".", true);
          // A namespace's nested classes are its members too (Filter.Variable)
          for (const inner of s.body.statements) {
            if ((ts.isClassDeclaration(inner) || ts.isInterfaceDeclaration(inner)) && inner.name) add(d.members, inner.name.text, true, undefined);
          }
        }
      }
    };
    visit(source.statements, "", true);
  }
  return out;
}

/** A declaration's members, with those of the classes it extends. */
export function allMembers(all: Map<string, TsDeclaration>, name: string): Map<string, TsMember> {
  const out = new Map<string, TsMember>();
  for (let d = all.get(name); d; d = d.base ? all.get(d.base) : undefined) {
    for (const [k, v] of d.members) if (!out.has(k)) out.set(k, v);
  }
  return out;
}
