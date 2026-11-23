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

function parseFile(text, pkg) {
	const lines = text.split('\n');
	const types = [];
	let type = null;
	let doc = [];
	let inDoc = false;
	let endpoints = null;
	for (let i = 0; i < lines.length; i++) {
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
		const decl = /^export\s+(?:default\s+)?(?:abstract\s+)?(class|interface|enum)\s+(\w+)(<[^{]*>)?/.exec(raw);
		if (decl) {
			// What it extends, for complete() to follow: class Tm1ApiException extends Tm1Exception
			const ext = /\bextends\s+([^{]*?)\s*(?:\bimplements\b.*)?(?:\{\}?)?\s*$/.exec(line.slice(decl[0].length));
			const bases = ext ? splitTop(ext[1]).map((b) => b.replace(/<.*$/, '').split('.').at(-1).trim()) : [];
			type = { name: decl[2], package: pkg, kind: decl[1], signature: line.replace(/\s*\{\s*$/, ''), doc: docText(doc), deprecated: false, members: [], bases };
			types.push(type);
			endpoints = null;
			doc = [];
			continue;
		}
		if (/^\}/.test(raw)) {
			// The endpoints table names each method's endpoints
			if (type && endpoints) for (const m of type.members) m.endpoints = endpoints[m.name] ?? [];
			type = null;
			doc = [];
			continue;
		}
		if (!type) {
			if (line && !line.startsWith('//')) doc = [];
			continue;
		}
		// Members are at the class's first indent
		if (!/^ {2}\S/.test(raw) || line.startsWith('//')) continue;
		if (/^static readonly endpoints\b/.test(line)) {
			endpoints = {};
			for (let j = i + 1; j < lines.length && !/^ {2}\}/.test(lines[j]); j++) {
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
		if (type.kind === 'enum') {
			const m = /^(\w+)\s*(?:=\s*[^,]+)?,?$/.exec(line);
			if (m) type.members.push({ kind: 'constant', name: m[1], static: true, signature: m[1], returns: '', params: [], endpoints: [], doc: docText(doc), deprecated: false });
			doc = [];
			continue;
		}
		if (/^[}\])]/.test(line)) {
			doc = [];
			continue;
		}
		let declText = line;
		let j = i;
		// Up to the body ({, or {} when it's empty) or the ;
		while (!/(?:[{;]|\{\})\s*$/.test(declText) && j + 1 < lines.length && j < i + 20) declText += '\n' + lines[++j].trim();
		// Doc comments in a parameter list document the parameter properties they precede
		const comments = [];
		declText = declText.replace(/\/\*\*[\s\S]*?\*\//g, (c) => '\x00' + (comments.push(c) - 1) + '\x00').replace(/\n/g, ' ');
		const head = declText.replace(/\s*\{\}?\s*$/, '').replace(/;\s*$/, '').trim();
		const isStatic = /^(?:public\s+)?static\b/.test(head);
		const bare = head.replace(/^(?:public\s+|private\s+(?=constructor)|protected\s+(?=constructor))?(?:static\s+)?(?:readonly\s+)?(?:override\s+)?(?:abstract\s+)?/, '');
		const method = /^(async\s+)?(\*\s*)?(get\s+|set\s+)?(\[?[\w.]+\]?|constructor)(\?)?\s*(<[^(]*>)?\s*\((.*)\)\s*(?::\s*(.+))?$/.exec(bare);
		let member = null;
		if (method) {
			const [, , , accessor, name, , , params, ret = ''] = method;
			if (accessor) member = { kind: 'property', name, static: isStatic, signature: name + (ret ? ': ' + ret : ''), returns: ret, params: [] };
			else
				member = {
					kind: name === 'constructor' ? 'constructor' : 'method',
					name: name === 'constructor' ? type.name : name,
					static: isStatic,
					signature: (isStatic ? 'static ' : '') + bare.replace(/^async\s+/, ''),
					returns: ret,
					params: splitTop(params.replace(/\x00\d+\x00\s*/g, '')).map((p) => {
						p = p.replace(/^(?:(?:public|private|protected|readonly|override)\s+)+/, '');
						const m = /^(\.\.\.)?(\w+)(\?)?\s*(?::\s*(.*?))?(?:\s*=\s*(.*))?$/.exec(p);
						return m ? { name: (m[1] ?? '') + m[2], type: m[4] ?? '', ...(m[5] ? { default: m[5] } : {}) } : { name: p, type: '' };
					}),
				};
		} else {
			// A property, typed (connect: number) or set (static readonly DEFAULTS = new Tm1Timeouts(...))
			const prop = /^(\w+)(\?)?\s*(?::\s*(.+))?$/.exec(bare.replace(/\s*=.*$/, ''));
			if (prop) member = { kind: 'property', name: prop[1], static: isStatic, signature: (isStatic ? 'static ' : '') + bare.replace(/\s*=.*$/, ''), returns: prop[3] ?? '', params: [] };
		}
		if (member && !hidden && !(member.name === 'endpoints' && isStatic)) type.members.push({ ...member, signature: member.signature.replace(/\x00\d+\x00\s*/g, ''), endpoints: [], doc: docText(doc), deprecated: /@deprecated/.test(doc.join('\n')) });
		// A constructor's public parameter properties (constructor(readonly status: number)) are the
		// class's properties
		if (member?.kind === 'constructor' && method) {
			for (const p of splitTop(method[7])) {
				const property = /^(?:\x00(\d+)\x00\s*)?((?:(?:public|readonly|override)\s+)+)(\w+)\??\s*(?::\s*(.*?))?(?:\s*=.*)?$/.exec(p);
				if (!property) continue;
				const own = property[1] === undefined ? '' : docText(comments[Number(property[1])].split('\n'));
				const shown = property[3] + (property[4] ? ': ' + property[4] : '');
				type.members.push({ kind: 'property', name: property[3], static: false, signature: (/readonly/.test(property[2]) ? 'readonly ' : '') + shown, returns: property[4] ?? '', params: [], endpoints: [], doc: own, deprecated: false });
			}
		}
		doc = [];
		if (member) i = Math.max(i, line.endsWith('{') || line.endsWith(';') ? i : j);
	}
	return types;
}

/** Gives each type the members of the types it extends in the package, marked with where they
 * come from (inherited): Tm1NotFoundException has Tm1ApiException's status. */
function complete(types) {
	const byName = new Map(types.map((t) => [t.name, t]));
	const done = new Set();
	const inherit = (type, seen) => {
		if (done.has(type) || seen.has(type)) return;
		const names = new Set(type.members.map((m) => m.name));
		for (const name of type.bases ?? []) {
			const base = byName.get(name);
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
	for (const type of types) delete type.bases;
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
