// SPDX-License-Identifier: Apache-2.0

// Writes a .NET project's public API as JSON, in the shape the Java doclet writes
// (java/tools/docs-doclet), for the docs site's reference pages (scripts/reference.mjs).
//
// It reads the C# sources as text: they are laid out by CSharpier, one declaration to a line (a
// parameter list may run on), with /// doc comments and [Tm1Endpoint] attributes above. That
// needs no build and no SDK; a declaration it can't read is left out, and the reference build
// fails when a Java member then has no counterpart, so a miss doesn't go unnoticed.
//
//   node dotnet_api.mjs <src/Project> <out.json>

import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

async function* files(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true })) {
		if (entry.name === 'bin' || entry.name === 'obj') continue;
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) yield* files(full);
		else if (entry.name.endsWith('.cs')) yield full;
	}
}

/** A line without its string and character literals and comments, for counting braces. */
function code(line) {
	return line
		.replace(/\/\/.*$/, '')
		.replace(/@?\$?"(?:\\.|""|[^"\\])*"/g, '""')
		.replace(/'(?:\\.|[^'\\])'/g, "''");
}

/** Splits at commas outside brackets. */
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

const MODIFIERS = /\b(?:public|static|sealed|abstract|partial|readonly|override|virtual|async|new|required|unsafe|extern)\s+/g;

function parseFile(text) {
	const lines = text.split('\n');
	const types = [];
	const stack = []; // { type, depth }
	let depth = 0;
	let namespace = '';
	let doc = [];
	let attributes = [];
	for (let i = 0; i < lines.length; i++) {
		const raw = lines[i];
		const line = raw.trim();
		const ns = /^namespace\s+([\w.]+)\s*;/.exec(line);
		if (ns) namespace = ns[1];
		if (line.startsWith('///')) {
			doc.push(line.replace(/^\/\/\/ ?/, ''));
			continue;
		}
		if (/^\[[\w.]+(\(.*\))?\]$/.test(line)) {
			attributes.push(line);
			continue;
		}
		const current = stack.at(-1);
		const atBody = current && depth === current.depth + 1;
		const typeDecl = /^(?:public|internal)\s+((?:(?:static|sealed|abstract|partial|readonly|ref)\s+)*)(class|record class|record struct|record|interface|enum|struct)\s+(\w+)(<[^>]*>)?/.exec(line);
		if (typeDecl && line.startsWith('public') && (!current || atBody)) {
			const name = (current ? current.type.name + '.' : '') + typeDecl[3];
			const kind = typeDecl[2].startsWith('record') ? 'record' : typeDecl[2];
			const type = { name, package: namespace, kind, signature: line.replace(/\s*[{;]?\s*$/, '').replace(/\s*:\s*.*$/, (m) => m), doc: doc.join('\n'), deprecated: false, members: [] };
			types.push(type);
			// A positional record's parameters are its properties
			const positional = /^[^(]*\(([^)]*)\)/.exec(line);
			if (kind === 'record' && positional) {
				for (const p of splitTop(positional[1])) {
					const m = /^(.*\S)\s+(\w+)(?:\s*=.*)?$/.exec(p);
					if (m) type.members.push({ kind: 'property', name: m[2], static: false, signature: m[1] + ' ' + m[2], returns: m[1], params: [], endpoints: [], doc: '', deprecated: false });
				}
			}
			stack.push({ type, depth, pending: !line.includes('{') && !line.endsWith(';') });
		} else if (atBody && current.type.kind === 'enum') {
			const member = /^(\w+)(?:\s*=\s*[^,]+)?,?$/.exec(line);
			if (member) current.type.members.push({ kind: 'constant', name: member[1], static: true, signature: member[1], returns: '', params: [], endpoints: [], doc: doc.join('\n'), deprecated: false });
		} else if (atBody && (line.startsWith('public ') || (current.type.kind === 'interface' && /^[\w<>\[\]?, .]+\s+\w+\s*[({]/.test(line) && !line.startsWith('private')))) {
			// Gather the declaration up to its body, its => or its ;
			let decl = line;
			let j = i;
			while (!/[{};]\s*$|=>/.test(decl) && j + 1 < lines.length) decl += ' ' + lines[++j].trim();
			// A constructor's call to base(...) or this(...) isn't part of its signature
			decl = decl.replace(/\)\s*:\s*(?:base|this)\(.*$/, ') {');
			const head = decl.split(/\s*=>|\s*\{\s*(?:get|set|init)|\s*\{\s*$|\s*;\s*$/)[0].trim();
			const endpoints = attributes.flatMap((a) => [...a.matchAll(/Tm1Endpoint\("([^"]+)"\)/g)].map((m) => m[1]));
			const deprecated = attributes.some((a) => /Obsolete/.test(a));
			const isStatic = /\bstatic\b/.test(head);
			const bare = head.replace(MODIFIERS, '').trim();
			const call = /^(.*?)\b(\w+)\s*(<[^()]*>)?\s*\((.*)\)\s*(?:where .*)?$/.exec(bare);
			let member = null;
			if (call && !/\boperator\b/.test(bare)) {
				const [, ret, name, , params] = call;
				const constructor = name === current.type.name.split('.').at(-1) && !ret.trim();
				member = {
					kind: constructor ? 'constructor' : 'method',
					name,
					static: isStatic,
					signature: bare,
					returns: ret.trim(),
					params: splitTop(params).map((p) => {
						const m = /^(?:(?:params|this|ref|out|in)\s+)?(.*\S)\s+(\w+)(?:\s*=\s*(.*))?$/.exec(p);
						return m ? { name: m[2], type: m[1], ...(m[3] ? { default: m[3] } : {}) } : { name: p, type: '' };
					}),
				};
			} else {
				const prop = /^(.*\S)\s+(\w+)$/.exec(bare);
				if (prop && !/\b(?:class|record|struct|interface|enum|delegate|event|const)\b/.test(prop[1])) {
					member = { kind: 'property', name: prop[2], static: isStatic, signature: bare, returns: prop[1], params: [] };
				} else if (/\bconst\b/.test(bare)) {
					const c = /const\s+(.*\S)\s+(\w+)\s*=/.exec(decl);
					if (c) member = { kind: 'constant', name: c[2], static: true, signature: 'const ' + c[1] + ' ' + c[2], returns: c[1], params: [] };
				}
			}
			if (member) current.type.members.push({ ...member, endpoints, doc: doc.join('\n'), deprecated });
		}
		// Braces, for knowing which type a line is in
		const counted = code(raw);
		for (const ch of counted) {
			if (ch === '{') depth++;
			else if (ch === '}') {
				depth--;
				while (stack.length > 0 && depth <= stack.at(-1).depth && !stack.at(-1).pending) stack.pop();
			}
		}
		if (stack.length > 0 && stack.at(-1).pending && counted.includes('{')) stack.at(-1).pending = false;
		// A type declared on one line with ; ends there (a record with no body)
		if (typeDecl && line.endsWith(';') && stack.at(-1)?.type.name.endsWith(typeDecl[3])) stack.pop();
		if (!line.startsWith('[')) {
			doc = [];
			attributes = [];
		}
	}
	return types;
}

const [source, out] = process.argv.slice(2);
const types = [];
for await (const file of files(source)) types.push(...parseFile(await readFile(file, 'utf8')));
types.sort((a, b) => a.name.localeCompare(b.name));
await writeFile(out, JSON.stringify(types));
