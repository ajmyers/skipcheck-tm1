// SPDX-License-Identifier: Apache-2.0

// Which member is which across languages, and the gate that every Java member has its
// counterpart in each language, as the clients' parity tests hold every Java method to one.
//
// A Java member is found in another language by the rule the parity tests enforce (modelCubes is
// model_cubes in Python, ModelCubesAsync in .NET; a getter is a property; NOT_FOUND is NotFound
// in .NET). What a language lacks by design, or names another way, is listed in its
// members-excluded.txt, one line each with the reason:
//
//   Tm1Session.Builder.jsonMapper | Jackson is Java's: Python reads JSON with the standard library
//   Tm1Source.PRIVATE_IMAGE -> PrivateImageSetting | the PrivateImage record has the name
//
// The build fails on a Java member that is neither found nor listed, on a listed member the
// language has after all, on a renamed member that isn't there under its new name, and on a line Java no longer
// has a member for. A renamed member is looked for by its new name only.

import { readFile } from 'node:fs/promises';
import path from 'node:path';

export const snake = (name) =>
	name
		.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
		.replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
		.toLowerCase();
const pascal = (name) => name.toLowerCase().replace(/(?:^|_)([a-z0-9])/g, (m, c) => c.toUpperCase());
const capital = (name) => name.charAt(0).toUpperCase() + name.slice(1);
const PYTHON_KEYWORDS = new Set(['and', 'as', 'assert', 'async', 'await', 'break', 'class', 'continue', 'def', 'del', 'elif', 'else', 'except', 'finally', 'for', 'from', 'global', 'if', 'import', 'in', 'is', 'lambda', 'nonlocal', 'not', 'or', 'pass', 'raise', 'return', 'try', 'while', 'with', 'yield']);

/** The names a Java type may have in another language, most likely first: a nested type keeps its
 * outer type (Tm1Timeouts.Builder), or is joined to it (Tm1ClientBuilder), or stands alone; an
 * exception is an Error in Python. renamed is the name the language's list gives it, if any. */
export function typeCandidates(language, javaName, renamed) {
	if (renamed) return [javaName.split('.').slice(0, -1).concat(renamed).join('.'), renamed];
	const names = [javaName, javaName.replaceAll('.', ''), javaName.split('.').at(-1)];
	return language === 'python' ? names.map((name) => name.replace(/Exception(?=\.|$)/g, 'Error')) : names;
}

/** The names a Java member may have in another language, as each language's parity tests require:
 * modelCubes is model_cubes in Python, ModelCubesAsync (or ModelCubes) in .NET. */
export function memberCandidates(language, javaMember, typeName) {
	const name = javaMember.name;
	if (javaMember.kind === 'constructor') return language === 'python' ? ['__init__'] : [typeName];
	// A constant (an enum's NOT_FOUND, a static final) keeps its name, but in .NET (NotFound); in
	// TypeScript an enum's is a string union's value, also NotFound
	if (/^[A-Z][A-Z0-9_]*$/.test(name) && (javaMember.kind === 'constant' || javaMember.kind === 'field')) return language === 'dotnet' ? [pascal(name)] : language === 'typescript' ? [name, pascal(name)] : [name];
	// A Python keyword (and, from) takes a trailing underscore: and_
	if (language === 'python' && PYTHON_KEYWORDS.has(snake(name))) return [snake(name) + '_'];
	// A Java getter (getUrl) is a property elsewhere (url, Url)
	const property = /^(?:get|is)([A-Z]\w*)$/.exec(name)?.[1];
	const lower = property ? property.charAt(0).toLowerCase() + property.slice(1) : null;
	if (language === 'python') return [snake(name), name.toLowerCase(), ...(lower ? [snake(lower)] : [])];
	if (language === 'dotnet') return [capital(name) + 'Async', capital(name), name, ...(property ? [property] : [])];
	return [name, ...(lower ? [lower] : [])];
}

/** A Java member's counterpart in another language's type, or null. renamed is the name the
 * language's list gives it, if any. .NET compares without case: odataLiteral is ODataLiteral,
 * ETAG is ETag. */
export function findMember(language, javaMember, counterpart, renamed) {
	if (language === 'java') return javaMember;
	const same = language === 'dotnet' ? (a, b) => a.toLowerCase() === b.toLowerCase() : (a, b) => a === b;
	for (const candidate of renamed ? [renamed] : memberCandidates(language, javaMember, counterpart.name)) {
		const found = counterpart.members.find((m) => same(m.name, candidate));
		if (found) return found;
	}
	return null;
}

/** Whether the gate holds a Java member to a counterpart: not a constructor or an instance field,
 * and not Java's Object and enum plumbing, which every language has its own way. */
export function checked(member) {
	return member.kind !== 'constructor' && !(member.kind === 'field' && !member.static) && !['toString', 'hashCode', 'equals', 'values', 'valueOf'].includes(member.name);
}

// ---- the lists ------------------------------------------------------------------

/** Where a language lists what it lacks of a project's Java members, from the repository's root.
 * .NET's one solution has one list for its projects, as it has one coverage/ folder. */
export function listFile(project, language) {
	if (language === 'dotnet') return 'dotnet/coverage/members-excluded.txt';
	return language + '/' + project.id + '/coverage/members-excluded.txt';
}

/** A list's lines, by Java name (Type.member, or a type's name): what it is instead, if anything
 * (renamed), and why. */
export function parseList(text, file) {
	const entries = new Map();
	text.split('\n').forEach((raw, i) => {
		const line = raw.trim();
		if (line === '' || line.startsWith('#')) return;
		const m = /^([\w.]+)\s*(?:->\s*(\w+)\s*)?\|\s*(\S.*)$/.exec(line);
		if (!m) throw new Error(file + ':' + (i + 1) + ': expected "Type.member | reason" or "Type.member -> name | reason", got: ' + line);
		if (entries.has(m[1])) throw new Error(file + ':' + (i + 1) + ': ' + m[1] + ' is listed twice');
		entries.set(m[1], { renamed: m[2] ?? null, reason: m[3], line: i + 1 });
	});
	return entries;
}

/** A list, read from its file; none (an empty list) when there is no file. */
export async function readList(repoDir, file) {
	const text = await readFile(path.join(repoDir, file), 'utf8').catch((e) => (e.code === 'ENOENT' ? '' : Promise.reject(e)));
	return parseList(text, file);
}

/** Checks one project's Java members against one language's: counterparts maps each Java type's
 * name to its type in the language (null where there's none), list is the language's list, and
 * used collects the list's lines that name something in Java. Returns the faults, as text. */
export function checkMembers({ language, javaTypes, counterparts, list, used }) {
	const missing = [];
	const wrong = [];
	for (const type of javaTypes) {
		const counterpart = counterparts.get(type.name);
		if (list.has(type.name) && counterpart) used.add(type.name);
		if (!counterpart) continue;
		for (const member of type.members.filter(checked)) {
			const key = type.name + '.' + member.name;
			const entry = list.get(key);
			if (entry) used.add(key);
			// A renamed member is found by its new name alone: under its usual one there may be
			// something else (PawContainer.ACCEPT_LICENSE is AcceptLicenseSetting, beside an AcceptLicense())
			if (entry?.renamed) {
				if (!findMember(language, member, counterpart, entry.renamed)) wrong.push(key + ' is listed as ' + entry.renamed + ', which ' + counterpart.name + ' lacks (line ' + entry.line + ')');
				continue;
			}
			const plain = findMember(language, member, counterpart);
			if (entry) {
				if (plain) wrong.push(key + ' is listed as missing, but ' + counterpart.name + ' has ' + plain.name + ': take its line out (line ' + entry.line + ')');
			} else if (!plain && !missing.includes(key)) {
				missing.push(key);
			}
		}
	}
	return { missing, wrong };
}

/** The lines of a list that name nothing Java has (for a type the language has). */
export function stale(list, used) {
	return [...list].filter(([key]) => !used.has(key)).map(([key, entry]) => key + ' (line ' + entry.line + '): Java has no such member, or the language no such type: take its line out');
}

// ---- a language's names, and the Java names it lacks ---------------------------------

const identifiers = (text) => text.match(/[A-Za-z_$][\w$]*/g) ?? [];

/** Every name an API has: its types' names (each part of a nested one), its members' names, and
 * the names its signatures use. */
export function apiNames(api) {
	const names = new Set();
	for (const type of api) {
		for (const part of type.name.split('.')) names.add(part);
		for (const name of identifiers(type.signature ?? '')) names.add(name);
		for (const member of type.members) {
			names.add(member.name);
			for (const name of identifiers(member.signature ?? '')) names.add(name);
		}
	}
	return names;
}

/** Every name a language has: its API's (apiNames), and every name in its code (its sources and
 * examples, without comments), which has what the extractor doesn't see (TypeScript's Tuples
 * object) and TM1's own names (ID, in a string). */
export function languageNames(types, code) {
	const names = apiNames(types);
	for (const name of identifiers(code)) names.add(name);
	return names;
}

/** The names Java has and a language doesn't: Java's type and member names and the types its
 * signatures use (Mono, Flux, JsonNode), less the language's names (languageNames). */
export function javaOnlyNames(javaTypes, names) {
	return new Set([...apiNames(javaTypes)].filter((name) => !names.has(name)));
}

/** A Java member's likely name in a language that has no counterpart of its type to look in: the
 * first of its candidates the language's names have, a constant's PascalCase too (whatever kind the
 * caller took it for), or else the first candidate. */
export function guessMember(language, javaMember, names) {
	const candidates = memberCandidates(language, javaMember, '');
	if (/^[A-Z][A-Z0-9_]*$/.test(javaMember.name)) candidates.push(pascal(javaMember.name));
	return candidates.find((name) => names?.has(name)) ?? candidates[0];
}

/** A language's source without its comments, which may name Java's types ("as Java's Mono"). */
export function withoutComments(text, language) {
	if (language === 'python') return text.replace(/"""[\s\S]*?"""|'''[\s\S]*?'''/g, ' ').replace(/#.*$/gm, '');
	return text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

const ENTITIES = { lt: '<', gt: '>', amp: '&', quot: '"', '#39': "'", '#123': '{', '#125': '}' };
const decode = (text) => text.replace(/&(lt|gt|amp|quot|#39|#123|#125);/g, (m, name) => ENTITIES[name]);
// A file name or a path in a code fragment (paw.sh, docker/paw/paw-local.sh, git://) isn't a name of the API
const FILE = /\/|^[\w.*<>-]*\.(?:sh|zip|txt|json|cfg|properties|env|gz|exe|html|xml|ya?ml|blob|extr|jar)$/;

/** The Java-only names in a page's text (HTML), outside its code blocks, which are the page's own
 * examples. In a code fragment, any name counts. In the prose, only a capitalized name that doesn't
 * start a sentence (Mono, Flux): so English words ("close", "List cubes...") don't count. */
export function javaIsms(html, javaOnly) {
	const text = html.replace(/<pre[\s\S]*?<\/pre>/g, ' ');
	const found = new Set();
	for (const [, fragment] of text.matchAll(/<code>([\s\S]*?)<\/code>/g)) {
		const code = decode(fragment);
		if (FILE.test(code)) continue;
		for (const name of identifiers(code)) if (javaOnly.has(name)) found.add(name);
	}
	const prose = decode(text.replace(/<code>[\s\S]*?<\/code>/g, ' x ').replace(/<\/?(?:p|li|ul|ol|dt|dd|dl|h\d|div|table|tr|td|th)\b[^>]*>/g, '. ').replace(/<[^>]+>/g, ' '));
	for (const match of prose.matchAll(/[A-Za-z_$][\w$]*/g)) {
		const name = match[0];
		const before = prose.slice(0, match.index).trimEnd();
		if (/^[A-Z]/.test(name) && /[a-z]/.test(name) && javaOnly.has(name) && before !== '' && !/[.!?:]$/.test(before)) found.add(name);
	}
	return [...found].sort();
}