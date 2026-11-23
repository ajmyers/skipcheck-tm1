// SPDX-License-Identifier: Apache-2.0

// Writes a TypeScript package's public API as JSON, in the shape the Java doclet writes
// (java/tools/docs-doclet), for the docs site's reference pages (scripts/reference.mjs).
//
// It reads the sources as text: they are laid out by Prettier, one member to a line (a parameter
// list may run on), with /** */ comments above. Each service's endpoints are its static
// endpoints table. That needs no compiler and no install; a declaration it can't read is left out,
// and the reference build fails when a Java member then has no counterpart.
//
//   node typescript_api.mjs <src> <out.json>

import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

async function* files(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true })) {
		if (entry.name === 'generated' || entry.name === 'node_modules') continue;
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) yield* files(full);
		else if (entry.name.endsWith('.ts') && !entry.name.endsWith('.d.ts') && !entry.name.endsWith('.test.ts')) yield full;
	}
}

function splitTop(text) {
	const parts = [];
	let depth = 0;
	let current = '';
	for (const ch of text) {
		if ('<([{'.includes(ch)) depth++;
		else if ('>)]}'.includes(ch)) depth--;
		if (ch === ',' && depth === 0) {
			parts.push(current.trim());
			current = '';
		} else current += ch;
	}
	if (current.trim()) parts.push(current.trim());
	return parts;
}

const docText = (lines) =>
	lines
		.join('\n')
		.replace(/^\/\*\*\s?/, '')
		.replace(/\s*\*\/$/, '')
		.split('\n')
		.map((l) => l.replace(/^\s*\* ?/, ''))
		.join('\n')
		.trim();

const member = (fields) => ({ static: true, signature: fields.name, returns: '', params: [], endpoints: [], doc: '', deprecated: false, ...fields });

/** The line that closes a block opened at indent: the next one starting with } there. */
function closing(lines, from, indent) {
	const end = new RegExp('^ {' + indent + '}\\}');
	for (let j = from + 1; j < lines.length; j++) if (end.test(lines[j])) return j;
	return lines.length;
}

/** The types declared in lines from..to at indent, named after prefix (Outer., in a namespace).
 * A namespace's types are nested in its name (namespace Tm1Source { class PrivateImage } is
 * Tm1Source.PrivateImage), and its functions are members of the type it shares its name with. A
 * union of strings (type Scope = "Public" | "Private") is a type whose members are its strings, and
 * an object exported as a const (Tuples = { of(...) {...} }) is one whose members are its
 * properties: a union and a const of the same name (Java enums, as values) are one type. */
function parseBlock(lines, from, to, indent, prefix, pkg, types) {
	const pad = ' '.repeat(indent);
	let doc = [];
	let inDoc = false;
	const typeNamed = (name, fields) => {
		let type = types.find((t) => t.name === name);
		if (!type) types.push((type = { name, package: pkg, kind: 'class', signature: name, doc: '', deprecated: false, members: [], bases: [], ...fields }));
		return type;
	};
	for (let i = from; i < to; i++) {
		const raw = lines[i];
		const line = raw.trim();
		if (line.startsWith('/**')) {
			inDoc = !line.endsWith('*/');
			doc = [line];
			continue;
		}
		if (inDoc) {
			doc.push(line);
			if (line.endsWith('*/')) inDoc = false;
			continue;
		}
		// Only declarations at this block's indent
		if (!raw.startsWith(pad) || raw[indent] === ' ') continue;
		const at = raw.slice(indent);
		const decl = /^export\s+(?:default\s+)?(?:declare\s+)?(?:abstract\s+)?(class|interface|enum)\s+(\w+)(<[^{]*>)?/.exec(at);
		if (decl) {
			// What it extends, for complete() to follow: class Tm1ApiException extends Tm1Exception
			const ext = /\bextends\s+([^{]*?)\s*(?:\bimplements\b.*)?(?:\{\}?)?\s*$/.exec(line.slice(decl[0].length));
			const bases = ext ? splitTop(ext[1]).map((b) => b.replace(/<.*$/, '').trim()) : [];
			const type = { name: prefix + decl[2], package: pkg, kind: decl[1], signature: line.replace(/\s*\{\s*$/, ''), doc: docText(doc), deprecated: false, members: [], bases, prefix };
			types.push(type);
			const end = /\{\}\s*$/.test(line) ? i : closing(lines, i, indent);
			parseMembers(lines, i + 1, end, indent + 2, type);
			i = end;
			doc = [];
			continue;
		}
		const namespace = /^export\s+(?:declare\s+)?namespace\s+(\w+)\s*\{\s*$/.exec(at);
		if (namespace) {
			const end = closing(lines, i, indent);
			const name = prefix + namespace[1];
			const owner = () => typeNamed(name, { kind: 'namespace', signature: 'namespace ' + name, doc: docText(doc) });
			// Its functions are its members: ODataLiterals.string(value)
			for (let j = i + 1; j < end; j++) {
				const fn = new RegExp('^ {' + (indent + 2) + '}export\\s+(?:async\\s+)?function\\s*(\\*?)\\s*(\\w+)');
				if (fn.test(lines[j])) {
					const [, , fnName] = fn.exec(lines[j]);
					const head = signatureFrom(lines, j).replace(/^export\s+(?:async\s+)?function\s*\*?\s*/, '');
					const fnDoc = docBefore(lines, j);
					const m = /^\w+\s*(<[^(]*>)?\s*\((.*)\)\s*(?::\s*(.+))?$/.exec(head);
					owner().members.push(member({ kind: 'method', name: fnName, signature: 'static ' + head, returns: m?.[3] ?? '', params: m ? params(m[2]) : [], doc: docText(fnDoc), deprecated: /@deprecated/.test(fnDoc.join('\n')) }));
				}
			}
			parseBlock(lines, i + 1, end, indent + 2, name + '.', pkg, types);
			i = end;
			doc = [];
			continue;
		}
		const union = /^export\s+type\s+(\w+)\s*=/.exec(at);
		if (union) {
			let end = i;
			while (!/;\s*$/.test(lines[end]) && end + 1 < to && end < i + 60) end++;
			const text = lines.slice(i, end + 1).join('\n').slice(lines[i].indexOf('=') + 1).replace(/;\s*$/, '');
			const values = splitTop(text.replace(/\/\*\*[\s\S]*?\*\//g, '').replace(/\|/g, ',')).filter(Boolean);
			// Only a union of strings is a type of its own; other aliases name another type
			if (values.length > 0 && values.every((v) => /^"[^"]*"$/.test(v))) {
				const name = prefix + union[1];
				const type = typeNamed(name, { kind: 'enum' });
				type.signature = 'type ' + name + ' = ' + values.join(' | ');
				type.doc ||= docText(doc);
				for (const [, comment, value] of text.matchAll(/(\/\*\*[\s\S]*?\*\/)?\s*\|?\s*"([^"]*)"/g)) {
					if (!type.members.some((m) => m.name === value)) type.members.push(member({ kind: 'constant', name: value, signature: JSON.stringify(value), doc: comment ? docText(comment.split('\n').map((l) => l.trim())) : '' }));
				}
			}
			i = end;
			doc = [];
			continue;
		}
		const object = /^export\s+const\s+(\w+)(?:\s*:\s*[^=]+)?\s*=\s*\{\s*$/.exec(at);
		if (object) {
			const end = closing(lines, i, indent);
			const name = prefix + object[1];
			const type = typeNamed(name, { kind: 'const', signature: 'const ' + name });
			type.doc ||= docText(doc);
			parseMembers(lines, i + 1, end, indent + 2, type, true);
			i = end;
			doc = [];
			continue;
		}
		if (line && !line.startsWith('//')) doc = [];
	}
}

/** The parameters of a parameter list, without their modifiers. */
function params(text) {
	return splitTop(text.replace(/\x00\d+\x00\s*/g, '')).map((p) => {
		p = p.replace(/^(?:(?:public|private|protected|readonly|override)\s+)+/, '');
		const m = /^(\.\.\.)?(\w+)(\?)?\s*(?::\s*(.*?))?(?:\s*=\s*(.*))?$/.exec(p);
		return m ? { name: (m[1] ?? '') + m[2], type: m[4] ?? '', ...(m[5] ? { default: m[5] } : {}) } : { name: p, type: '' };
	});
}

/** A declaration from line i, up to its body ({, or {} when it's empty) or its ;, on one line. */
function signatureFrom(lines, i) {
	let text = lines[i].trim();
	for (let j = i; !/(?:[{;]|\{\})\s*$/.test(text) && j + 1 < lines.length && j < i + 20; ) text += ' ' + lines[++j].trim();
	return text.replace(/\s*\{\}?\s*$/, '').replace(/;\s*$/, '').trim();
}

/** The doc comment right above line i, as lines. */
function docBefore(lines, i) {
	if (!lines[i - 1]?.trim().endsWith('*/')) return [];
	let j = i - 1;
	while (j > 0 && !lines[j].trim().startsWith('/**')) j--;
	return lines.slice(j, i).map((l) => l.trim());
}

/** The members of a class, an interface, an enum or an exported object (object: its properties
 * are static, and a constant's name is in capitals or the type is a union's values), from lines
 * from..to at indent. */
function parseMembers(lines, from, to, indent, type, object = false) {
	const pad = ' '.repeat(indent);
	const memberLine = new RegExp('^ {' + indent + '}\\S');
	let doc = [];
	let inDoc = false;
	let endpoints = null;
	for (let i = from; i < to; i++) {
		const raw = lines[i];
		const line = raw.trim();
		if (line.startsWith('/**')) {
			inDoc = !line.endsWith('*/');
			doc = [line];
			continue;
		}
		if (inDoc) {
			doc.push(line);
			if (line.endsWith('*/')) inDoc = false;
			continue;
		}
		// Members are at the type's first indent
		if (!memberLine.test(raw) || line.startsWith('//')) continue;
		if (/^static readonly endpoints\b/.test(line)) {
			endpoints = {};
			for (let j = i + 1; j < to && !raw.startsWith(pad + '}') && !lines[j].startsWith(pad + '}'); j++) {
				const e = /^\s*"?(\w+)"?:\s*\[(.*)\]/.exec(lines[j]);
				if (e) endpoints[e[1]] = [...e[2].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
			}
			doc = [];
			continue;
		}
		// A private constructor's parameter properties can still be public: private constructor(readonly path: string)
		const hidden = /^(?:private|protected)\s+constructor\b/.test(line);
		if (!hidden && (/^(?:private|protected|#)/.test(line) || /^(?:static\s+)?(?:private|protected)\b/.test(line))) {
			doc = [];
			continue;
		}
		if (type.kind === 'enum' && !object) {
			const m = /^(\w+)\s*(?:=\s*[^,]+)?,?$/.exec(line);
			if (m) type.members.push(member({ kind: 'constant', name: m[1], doc: docText(doc) }));
			doc = [];
			continue;
		}
		if (/^[}\])]/.test(line)) {
			doc = [];
			continue;
		}
		// An object's value (NotFound: "NotFound", CONTEXT: "@odata.context"): a constant if it's
		// in capitals or one of a union's values
		const value = object ? /^"?(\w+)"?:\s*(.*?),?$/.exec(line) : null;
		if (value) {
			const constant = /^[A-Z][A-Z0-9_]*$/.test(value[1]) || type.kind === 'enum';
			if (!type.members.some((m) => m.name === value[1])) type.members.push(member({ kind: constant ? 'constant' : 'property', name: value[1], doc: docText(doc) }));
			doc = [];
			continue;
		}
		let declText = line;
		let j = i;
		// Up to the body ({, or {} when it's empty) or the ;
		while (!/(?:[{;]|\{\})\s*$/.test(declText) && j + 1 < to && j < i + 20) declText += '\n' + lines[++j].trim();
		// Doc comments in a parameter list document the parameter properties they precede
		const comments = [];
		declText = declText.replace(/\/\*\*[\s\S]*?\*\//g, (c) => '\x00' + (comments.push(c) - 1) + '\x00').replace(/\n/g, ' ');
		const head = declText.replace(/\s*\{\}?\s*$/, '').replace(/;\s*$/, '').trim();
		const isStatic = object || /^(?:public\s+)?static\b/.test(head);
		const bare = head.replace(/^(?:public\s+|private\s+(?=constructor)|protected\s+(?=constructor))?(?:static\s+)?(?:readonly\s+)?(?:override\s+)?(?:abstract\s+)?/, '');
		const method = /^(async\s+)?(\*\s*)?(get\s+|set\s+)?(\[?[\w.]+\]?|constructor)(\?)?\s*(<[^(]*>)?\s*\((.*)\)\s*(?::\s*(.+))?$/.exec(bare);
		let found = null;
		if (method) {
			const [, , , accessor, name, , , list, ret = ''] = method;
			if (accessor) found = { kind: 'property', name, static: isStatic, signature: name + (ret ? ': ' + ret : ''), returns: ret, params: [] };
			else
				found = {
					kind: name === 'constructor' ? 'constructor' : 'method',
					name: name === 'constructor' ? type.name.split('.').at(-1) : name,
					static: isStatic,
					signature: (isStatic ? 'static ' : '') + bare.replace(/^async\s+/, ''),
					returns: ret,
					params: params(list),
				};
		} else {
			// A property, typed (connect: number) or set (static readonly DEFAULTS = new Tm1Timeouts(...))
			const prop = /^(\w+)(\?)?\s*(?::\s*(.+))?$/.exec(bare.replace(/\s*=.*$/, ''));
			if (prop) found = { kind: 'property', name: prop[1], static: isStatic, signature: (isStatic ? 'static ' : '') + bare.replace(/\s*=.*$/, ''), returns: prop[3] ?? '', params: [] };
		}
		if (found && !hidden && !(found.name === 'endpoints' && isStatic)) type.members.push({ ...found, signature: found.signature.replace(/\x00\d+\x00\s*/g, ''), endpoints: [], doc: docText(doc), deprecated: /@deprecated/.test(doc.join('\n')) });
		// A constructor's public parameter properties (constructor(readonly status: number)) are the
		// class's properties
		if (found?.kind === 'constructor' && method) {
			for (const p of splitTop(method[7])) {
				const property = /^(?:\x00(\d+)\x00\s*)?((?:(?:public|readonly|override)\s+)+)(\w+)\??\s*(?::\s*(.*?))?(?:\s*=.*)?$/.exec(p);
				if (!property) continue;
				const own = property[1] === undefined ? '' : docText(comments[Number(property[1])].split('\n'));
				const shown = property[3] + (property[4] ? ': ' + property[4] : '');
				type.members.push({ kind: 'property', name: property[3], static: false, signature: (/readonly/.test(property[2]) ? 'readonly ' : '') + shown, returns: property[4] ?? '', params: [], endpoints: [], doc: own, deprecated: false });
			}
		}
		doc = [];
		if (found) i = Math.max(i, line.endsWith('{') || line.endsWith(';') ? i : j);
	}
	// The endpoints table names each method's endpoints
	if (endpoints) for (const m of type.members) m.endpoints = endpoints[m.name] ?? [];
}

function parseFile(text, pkg) {
	const types = [];
	parseBlock(text.split('\n'), 0, text.split('\n').length, 0, '', pkg, types);
	return types;
}

/** Gives each type the members of the types it extends in the package, marked with where they
 * come from (inherited): Tm1NotFoundException has Tm1ApiException's status. */
function complete(types) {
	const byName = new Map(types.map((t) => [t.name, t]));
	// A base is named as written where the type is declared: Base, or Outer.Base, in a namespace
	const named = (type, name) => byName.get((type.prefix ?? '') + name) ?? byName.get(name) ?? byName.get(name.split('.').at(-1));
	const done = new Set();
	const inherit = (type, seen) => {
		if (done.has(type) || seen.has(type)) return;
		const names = new Set(type.members.map((m) => m.name));
		for (const name of type.bases ?? []) {
			const base = named(type, name);
			if (!base || base === type) continue;
			inherit(base, new Set([...seen, type]));
			for (const member of base.members) {
				if (member.kind === 'constructor' || names.has(member.name)) continue;
				type.members.push({ ...member, inherited: member.inherited ?? base.name });
			}
			for (const member of base.members) names.add(member.name);
		}
		done.add(type);
	};
	for (const type of types) inherit(type, new Set());
	for (const type of types) {
		delete type.bases;
		delete type.prefix;
	}
	return types.sort((a, b) => a.name.localeCompare(b.name));
}

const [source, out, pkg] = process.argv.slice(2);
const types = [];
const seen = new Set();
for await (const file of files(source)) {
	for (const type of parseFile(await readFile(file, 'utf8'), pkg)) {
		if (!seen.has(type.name)) types.push(type);
		seen.add(type.name);
	}
}
await writeFile(out, JSON.stringify(complete(types)));
