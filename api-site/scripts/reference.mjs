// SPDX-License-Identifier: Apache-2.0

// Builds each project's docs: one reference, a page per type in each language, where the
// signatures, the descriptions and the examples are all that language's. Written into
// dist/<project>/docs/<language>/, beside dist/<project>/docs/model/ for TM1's own types.
//
// Where everything comes from:
//
//   a type's members, signatures and doc comments   each language's API, extracted from its sources
//                                                    (.generated/api/<project>.<language>.json):
//                                                    Java by java/tools/docs-doclet, Python by
//                                                    extract/python_api.py
//   a type's overview                                the Java type's Javadoc, with its {@snippet}s
//                                                    replaced by that region in the page's language
//   which member is which across languages           the parity rule each language's tests enforce:
//                                                    modelCubes is model_cubes in Python
//   the areas types are grouped in                   the Java packages (AREAS below)
//   TM1's own types (Cube, Dimension...)             spec/tm1-api.json
//
// It fails when a language lacks an example the Java documentation quotes (--allow-missing lists
// them instead), and writes .generated/site.json for the Worker.
//
//   node scripts/reference.mjs [--allow-missing]

import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { CANONICAL_ORIGIN, copyAssets, distDir, DOC_LANGUAGES, escape, generatedDir, head, LANGUAGES, PROJECTS, regions, repoDir } from './site.mjs';

const allowMissing = process.argv.includes('--allow-missing');

// Areas, by the end of the Java package name, in the order the docs home shows them
const AREAS = [
	{ id: 'client', title: 'The client', packages: ['client', 'testcontainers', 'autoconfigure', 'boot.testcontainers'] },
	{ id: 'session', title: 'Sessions and logins', packages: ['session', 'auth'] },
	{ id: 'dimensions', title: 'Dimensions and hierarchies', packages: ['service.dimension', 'service.attribute'] },
	{ id: 'cubes', title: 'Cubes and views', packages: ['service.cube'] },
	{ id: 'data', title: 'Reading and writing data', packages: ['service.data'] },
	{ id: 'processes', title: 'Processes and chores', packages: ['service.process'] },
	{ id: 'security', title: 'Security', packages: ['service.security'] },
	{ id: 'server', title: 'The server', packages: ['service.server', 'service.encryption'] },
	{ id: 'more', title: 'More services', packages: ['service.content', 'service.git', 'service.annotation', 'service'] },
	{ id: 'queries', title: 'Queries', packages: ['odata'] },
	{ id: 'errors', title: 'Errors', packages: ['error'] },
	{ id: 'versions', title: 'Versions and capabilities', packages: ['version'] },
	{ id: 'csdl', title: 'Metadata', packages: ['csdl'] },
];
const OTHER = { id: 'other', title: 'Other types', packages: [] };

function areaOf(pkg) {
	let best = null;
	for (const area of AREAS) {
		for (const suffix of area.packages) {
			if ((pkg === suffix || pkg.endsWith('.' + suffix)) && (!best || suffix.length > best.length)) best = { area, length: suffix.length };
		}
	}
	return best?.area ?? OTHER;
}

// ---- names across languages -------------------------------------------------

const snake = (name) =>
	name
		.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
		.replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
		.toLowerCase();
const capital = (name) => name.charAt(0).toUpperCase() + name.slice(1);

/** The names a Java type may have in another language, most likely first: a nested Builder is
 * Tm1Client.Builder in Java and Tm1ClientBuilder elsewhere, an exception is an Error in Python. */
function typeCandidates(language, javaName) {
	const last = javaName.split('.').at(-1);
	if (language === 'python') return [last.replace(/Exception$/, 'Error'), javaName.replace('.', '').replace(/Exception$/, 'Error')];
	return [javaName, javaName.replace('.', ''), last];
}

/** The names a Java member may have in another language, as each language's parity tests require:
 * modelCubes is model_cubes in Python, ModelCubesAsync (or ModelCubes) in .NET. */
function memberCandidates(language, javaMember, typeName) {
	const name = javaMember.name;
	if (javaMember.kind === 'constructor') return language === 'python' ? ['__init__'] : [typeName];
	// A Java getter (getUrl) is a property elsewhere (url, Url)
	const property = /^(?:get|is)([A-Z]\w*)$/.exec(name)?.[1];
	const lower = property ? property.charAt(0).toLowerCase() + property.slice(1) : null;
	if (language === 'python') return [snake(name), name.toLowerCase(), ...(lower ? [snake(lower)] : [])];
	if (language === 'dotnet') return [capital(name) + 'Async', capital(name), name, ...(property ? [property] : [])];
	return [name, ...(lower ? [lower] : [])];
}

function findMember(language, javaMember, counterpart) {
	if (language === 'java') return javaMember;
	for (const candidate of memberCandidates(language, javaMember, counterpart.name)) {
		const found = counterpart.members.find((m) => m.name === candidate);
		if (found) return found;
	}
	return null;
}

// ---- doc comments -------------------------------------------------------------

/** The end of the inline tag that opens at open: braces nest. */
function closing(text, open) {
	let depth = 0;
	for (let i = open; i < text.length; i++) {
		if (text[i] === '{') depth++;
		else if (text[i] === '}' && --depth === 0) return i;
	}
	return -1;
}

/** A Javadoc comment split into its description and its block tags. */
function javadocParts(doc) {
	const [description, ...rest] = doc.split(/^\s*(?=@(?:param|return|throws|exception|see|since|author|deprecated)\b)/m);
	const tags = { params: {}, returns: '', throws: [] };
	for (const block of rest) {
		const tag = /^@(\w+)\s*([\s\S]*)$/.exec(block.trim());
		if (!tag) continue;
		const [, name, body] = tag;
		if (name === 'param') {
			const param = /^(\S+)\s*([\s\S]*)$/.exec(body);
			if (param) tags.params[param[1]] = param[2];
		} else if (name === 'return') tags.returns = body;
		else if (name === 'throws' || name === 'exception') {
			const thrown = /^(\S+)\s*([\s\S]*)$/.exec(body);
			if (thrown) tags.throws.push({ type: thrown[1].replace(/^.*\./, ''), text: thrown[2] });
		}
	}
	return { description, tags };
}

/** Javadoc as HTML, for a page in one language: inline tags rendered, links to members named the
 * language's way and linked where there is a page, snippets as that language's example. */
function javadocHtml(doc, page) {
	let html = '';
	let at = 0;
	for (;;) {
		const open = doc.indexOf('{@', at);
		if (open < 0) break;
		const close = closing(doc, open);
		if (close < 0) break;
		html += doc.slice(at, open);
		const [, tag, rest = ''] = /^\{@(\w+)\s*([\s\S]*)$/.exec(doc.slice(open, close));
		const body = rest.trim();
		if (tag === 'snippet') {
			const cls = /class\s*=\s*"?examples\.(\w+)"?/.exec(body);
			const region = /region\s*=\s*"?([\w-]+)"?/.exec(body);
			html += cls && region ? '</p>' + page.example(cls[1], region[1]) + '<p>' : '';
		} else if (tag === 'code' || tag === 'literal') {
			html += tag === 'code' ? '<code>' + escape(body) + '</code>' : escape(body);
		} else if (tag === 'link' || tag === 'linkplain') {
			const [target, ...label] = body.split(/\s+/);
			html += page.link(target, label.join(' '), tag === 'link');
		} else if (tag === 'inheritDoc') {
			// nothing to inherit from here
		} else {
			html += escape(body);
		}
		at = close + 1;
	}
	return html + doc.slice(at);
}

/** A .NET XML doc comment as HTML: its summary (or the whole comment) and its tags. */
function xmlDocParts(doc) {
	const take = (tag) => {
		const found = new RegExp('<' + tag + '>([\\s\\S]*?)</' + tag + '>').exec(doc);
		return found ? found[1] : '';
	};
	const params = {};
	for (const [, name, text] of doc.matchAll(/<param name="([^"]+)">([\s\S]*?)<\/param>/g)) params[name] = xmlInline(text);
	const throws = [...doc.matchAll(/<exception cref="([^"]+)">([\s\S]*?)<\/exception>/g)].map(([, type, text]) => ({ type: type.replace(/^.*[.:]/, ''), text: xmlInline(text) }));
	const summary = take('summary') || doc.replace(/<(param|returns|exception|remarks|typeparam)\b[\s\S]*?<\/\1>/g, '');
	return { description: xmlInline(summary + (take('remarks') ? '<para>' + take('remarks') + '</para>' : '')), params, returns: xmlInline(take('returns')), throws };
}

function xmlInline(text) {
	return text
		.replace(/<code>([\s\S]*?)<\/code>/g, (m, body) => '</p>' + code(body.replace(/^\n|\n\s*$/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&'), 'csharp') + '<p>')
		.replace(/<para>/g, '</p><p>')
		.replace(/<\/para>/g, '</p><p>')
		.replace(/<c>([\s\S]*?)<\/c>/g, '<code>$1</code>')
		.replace(/<see cref="(?:\w:)?([^"]+)"\s*\/>/g, (m, target) => '<code>' + escape(target.replace(/\(.*$/, '').split('.').slice(-2).join('.').replace(/^[a-z][\w]*\./, '')) + '</code>')
		.replace(/<see langword="([^"]+)"\s*\/>/g, '<code>$1</code>')
		.replace(/<(?:paramref|typeparamref) name="([^"]+)"\s*\/>/g, '<code>$1</code>')
		.replace(/<see href="([^"]+)">([\s\S]*?)<\/see>/g, '<a href="$1">$2</a>')
		.replace(/<\/?(?:list|item|description|term|listheader)[^>]*>/g, ' ')
		.replace(/<p>\s*<\/p>/g, '')
		.trim();
}

/** A TSDoc comment as HTML: its description and its tags. Markdown code, fenced and inline. */
function tsDocParts(doc) {
	const [description, ...rest] = doc.split(/^\s*(?=@(?:param|returns?|throws|see|deprecated|internal|example)\b)/m);
	const params = {};
	let returns = '';
	const throws = [];
	for (const block of rest) {
		const tag = /^@(\w+)\s*([\s\S]*)$/.exec(block.trim());
		if (!tag) continue;
		if (tag[1] === 'param') {
			const p = /^(\S+)\s*(?:-\s*)?([\s\S]*)$/.exec(tag[2]);
			if (p) params[p[1]] = mdInline(p[2]);
		} else if (tag[1] === 'returns' || tag[1] === 'return') returns = mdInline(tag[2]);
		else if (tag[1] === 'throws') throws.push({ type: '', text: mdInline(tag[2]) });
	}
	return { description: mdBlocks(description), params, returns, throws };
}

function mdBlocks(text) {
	return text
		.split(/```(\w*)\n([\s\S]*?)```/)
		.map((part, i, parts) => {
			if (i % 3 === 1) return '';
			if (i % 3 === 2) return '</p>' + code(part.replace(/\n$/, ''), 'typescript') + '<p>';
			return part
				.split(/\n\s*\n/)
				.map((p) => mdInline(p))
				.join('</p><p>');
		})
		.join('');
}

function mdInline(text) {
	return escape(text.trim())
		.replace(/`([^`]+)`/g, '<code>$1</code>')
		.replace(/\{@link\s+([^}\s|]+)(?:\s*\|?\s*([^}]*))?\}/g, (m, target, label) => '<code>' + (label || target) + '</code>')
		.replace(/\s*\n\s*/g, ' ');
}

/** A Python docstring as HTML: paragraphs, ``code``, and the blocks after "::". */
function docstringHtml(doc) {
	const lines = doc.split('\n');
	const out = [];
	let paragraph = [];
	const flush = () => {
		if (paragraph.length > 0) out.push('<p>' + inlineRst(paragraph.join(' ')) + '</p>');
		paragraph = [];
	};
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i];
		if (line.trim() === '') {
			flush();
			continue;
		}
		if (/::\s*$/.test(line)) {
			paragraph.push(line.replace(/::\s*$/, ':').replace(/\s:$/, ''));
			flush();
			const block = [];
			let j = i + 1;
			while (j < lines.length && (lines[j].trim() === '' || /^\s/.test(lines[j]))) block.push(lines[j++]);
			const indent = Math.min(...block.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length));
			out.push(code(block.map((l) => l.slice(indent)).join('\n').trim(), 'python'));
			i = j - 1;
			continue;
		}
		paragraph.push(line.trim());
	}
	flush();
	return out.join('');
}

const inlineRst = (text) =>
	escape(text)
		.replace(/``([^`]+)``/g, '<code>$1</code>')
		.replace(/:(?:class|meth|func|mod|attr):`~?([^`]+)`/g, '<code>$1</code>');

// at: a key the same on every language's page (an example's region, a member's Java name), which
// docs.js uses to keep the reader's place when the language changes
const code = (text, highlight, at) => '<pre' + (at ? ' data-at="' + escape(at) + '"' : '') + '><code class="lang-' + highlight + '">' + escape(text) + '</code></pre>';

/** The first sentence of a description, as plain text, for summaries. */
function summary(html) {
	const text = html
		.replace(/<pre[\s\S]*?<\/pre>/g, ' ')
		.replace(/<[^>]+>/g, '')
		.replace(/&#125;/g, '}')
		.replace(/\s+/g, ' ')
		.trim();
	// A description that opens with what it calls ("ModelCubes(): the cubes that...", "GET Cubes: every
	// cube") is summed up by what follows
	const lead = /^(?:(?:GET|POST|PATCH|PUT|DELETE)\s+)?[^\s:]+(?:\(\S*?\))?:\s+(.+)$/s.exec(text);
	const rest = lead ? lead[1].charAt(0).toUpperCase() + lead[1].slice(1) : text;
	const end = /[.:](\s|$)/.exec(rest);
	return end ? rest.slice(0, end.index + 1).replace(/:$/, '.') : rest;
}

// ---- reading the APIs ---------------------------------------------------------

const readJson = async (file) => JSON.parse(await readFile(file, 'utf8'));

async function api(project, language) {
	return readJson(path.join(generatedDir, 'api', project.id + '.' + language + '.json')).catch(() => null);
}

const spec = await readJson(path.join(repoDir, 'spec', 'tm1-api.json'));
const specTypes = new Map([...spec.types, ...spec.enums].map((t) => [t.name, t]));
const packageNames = {
	typescript: async (project) => JSON.parse(await readFile(path.join(repoDir, 'typescript', project.id, 'package.json'), 'utf8')).name,
};

// ---- pages --------------------------------------------------------------------

const missing = new Set();
const gaps = new Map();
const site = { projects: PROJECTS.map((p) => ({ id: p.id, landing: p.landing, docs: true })), guide: {} };
let pageCount = 0;

for (const project of PROJECTS) {
	const java = await api(project, 'java');
	if (!java) throw new Error('No API for ' + project.id + ': run ./gradlew docsModel and the extractors first (npm run assemble does both)');
	// TM1's own types are pages of their own (docs/model/), not one per language
	const types = java.filter((t) => !t.package.endsWith('.model'));
	const languages = DOC_LANGUAGES.filter((id) => project.languages.includes(id));
	const apis = { java: types };
	for (const language of languages.filter((l) => l !== 'java')) {
		const found = await api(project, language);
		if (!found) throw new Error('No ' + language + ' API for ' + project.id);
		apis[language] = found;
	}
	const out = path.join(distDir, project.id, 'docs');
	await rm(out, { recursive: true, force: true });
	await copyAssets(project);
	const tsPackage = languages.includes('typescript') ? await packageNames.typescript(project) : '';

	// For each Java type, its counterpart in each language
	const counterparts = new Map();
	for (const type of types) {
		const each = { java: type };
		for (const language of languages.filter((l) => l !== 'java')) {
			each[language] = typeCandidates(language, type.name).map((n) => apis[language].find((t) => t.name === n)).find(Boolean) ?? null;
			if (each[language]) {
				for (const member of type.members) {
					// Java's Object and enum plumbing has no counterpart to find
					if (member.kind === 'constructor' || member.kind === 'field' || ['toString', 'hashCode', 'equals', 'values', 'valueOf'].includes(member.name)) continue;
					if (!findMember(language, member, each[language])) {
						const key = project.id + ' ' + language;
						gaps.set(key, [...(gaps.get(key) ?? []), type.name + '.' + member.name]);
					}
				}
			}
		}
		counterparts.set(type.name, each);
	}
	const documented = (language) => types.filter((t) => counterparts.get(t.name)[language]);
	const usesModel = new Set();

	for (const language of languages) {
		const label = LANGUAGES.find((l) => l.id === language).label;
		const highlight = LANGUAGES.find((l) => l.id === language).highlight;
		const here = documented(language);
		const counterpartOf = (javaType) => counterparts.get(javaType.name)[language];
		const nameOf = (javaType) => counterpartOf(javaType).name;
		const pageOf = (javaType) => nameOf(javaType) + '.html';
		const byJavaSimple = new Map(here.map((t) => [t.name.split('.').at(-1), t]));
		const byName = new Map(here.map((t) => [nameOf(t), t]));
		const nav = navHtml(project, language, here, pageOf, nameOf);

		for (const javaType of here) {
			const type = counterpartOf(javaType);
			// Examples are read up front, so rendering stays synchronous
			const exampleBlocks = new Map();
			for (const doc of [javaType.doc, ...javaType.members.map((m) => m.doc)]) {
				for (const [, cls, region] of doc.matchAll(/\{@snippet[^}]*class\s*=\s*"?examples\.(\w+)"?[^}]*region\s*=\s*"?([\w-]+)"?/g)) {
					const where = LANGUAGES.find((l) => l.id === language).examples(project, cls);
					const text = (await regions(where)).get(region);
					if (text === undefined) missing.add(language + ': ' + where + ' region ' + region);
					exampleBlocks.set(cls + '#' + region, text === undefined ? '' : code(text, highlight, 'x-' + cls + '-' + region));
				}
			}
			const render = {
				example: (cls, region) => exampleBlocks.get(cls + '#' + region) ?? '',
				link(target, linkLabel, asCode) {
					const [typePart, memberPart] = target.split('#');
					const simple = typePart.replace(/^.*\.(?=[A-Z])/, '');
					const owner = simple ? byJavaSimple.get(simple.split('.').at(-1)) : javaType;
					let member = '';
					if (memberPart) {
						const javaName = memberPart.replace(/\(.*$/, '');
						const javaMember = owner?.members.find((m) => m.name === javaName) ?? { name: javaName, kind: 'method' };
						member = owner ? (findMember(language, javaMember, counterpartOf(owner))?.name ?? '') : '';
						if (!member) member = memberCandidates(language, javaMember, '')[0];
					}
					const ownerName = owner ? nameOf(owner) : simple;
					const shown = linkLabel || [simple ? ownerName : '', member].filter(Boolean).join('.');
					const text = asCode ? '<code>' + escape(shown) + '</code>' : escape(shown);
					if (owner) {
						const has = member && counterpartOf(owner).members.some((m) => m.name === member);
						return '<a href="' + pageOf(owner) + (has ? '#' + member : '') + '">' + text + '</a>';
					}
					if (simple && specTypes.has(simple)) {
						usesModel.add(simple);
						return '<a href="../model/' + simple + '.html">' + text + '</a>';
					}
					return text;
				},
			};
			const linkTypes = (signature) =>
				escape(signature).replace(/\b[A-Z][A-Za-z0-9]*\b/g, (word) => {
					if (word === type.name.split('.').at(-1)) return word;
					const target = byName.get(word);
					if (target) return '<a href="' + pageOf(target) + '">' + word + '</a>';
					if (specTypes.has(word)) {
						usesModel.add(word);
						return '<a href="../model/' + word + '.html">' + word + '</a>';
					}
					return word;
				});

			const overview = javadocHtml(javadocParts(javaType.doc).description, render);

			// Members: this language's own, each with Java's examples where Java's has them
			const javaFor = new Map();
			for (const member of javaType.members) {
				const found = findMember(language, member, type);
				if (found && !javaFor.has(found)) javaFor.set(found, member);
			}
			const members = language === 'java' ? javaType.members : type.members;
			const rows = [];
			const details = [];
			const seen = new Map();
			for (const member of members) {
				const count = (seen.get(member.name) ?? 0) + 1;
				seen.set(member.name, count);
				const id = count === 1 ? member.name : member.name + '-' + count;
				const java = language === 'java' ? member : javaFor.get(member);
				const atKey = 'm-' + (java ? java.name : member.name) + (count > 1 ? '-' + count : '');
				let parts;
				if (language === 'java') {
					const p = javadocParts(member.doc);
					parts = { description: javadocHtml(p.description, render), params: Object.fromEntries(Object.entries(p.tags.params).map(([k, v]) => [k, javadocHtml(v, render)])), returns: p.tags.returns ? javadocHtml(p.tags.returns, render) : '', throws: p.tags.throws.map((t) => ({ type: t.type, text: javadocHtml(t.text, render) })) };
				} else if (language === 'dotnet') parts = xmlDocParts(member.doc);
				else if (language === 'typescript') parts = tsDocParts(member.doc);
				else parts = { description: docstringHtml(member.doc), params: {}, returns: '', throws: [] };
				let doc = parts.description;
				if (language !== 'java' && java) {
					if (!member.doc && java.doc) doc = javadocHtml(javadocParts(java.doc).description, render);
					// Java's examples for the same member, in this language
					doc += [...java.doc.matchAll(/\{@snippet[^}]*class\s*=\s*"?examples\.(\w+)"?[^}]*region\s*=\s*"?([\w-]+)"?[^}]*\}/g)].map(([, cls, region]) => exampleBlocks.get(cls + '#' + region) ?? '').join('');
				}
				const kindLabel = member.kind === 'property' ? 'property' : member.kind === 'constructor' ? 'constructor' : member.static ? 'static' : '';
				rows.push('<tr><td><a href="#' + escape(id) + '"><code>' + escape(member.name) + '</code></a>' + (kindLabel ? ' <span class="tag">' + kindLabel + '</span>' : '') + '</td><td>' + escape(summary(doc)) + '</td></tr>');
				const paramList = member.params.filter((p) => parts.params[p.name.replace(/^\.\.\.|^\*+/, '')]);
				const endpoints = member.endpoints.length > 0 ? member.endpoints : (java?.endpoints ?? []);
				// One card per member: its name, its signature, then what it does
				const fields = (label, items) => (items.length > 0 ? '<div class="fields"><p class="label">' + label + '</p><dl>' + items.join('') + '</dl></div>' : '');
				details.push(
					'<section class="member" id="' + escape(id) + '" data-at="' + escape(atKey) + '">' +
						'<header><h3><a href="#' + escape(id) + '">' + escape(member.name) + '</a></h3>' + (kindLabel ? '<span class="tag">' + kindLabel + '</span>' : '') + '</header>' +
						'<pre class="signature"><code class="lang-' + highlight + '">' + linkTypes(member.signature) + '</code></pre>' +
						'<div class="member-body">' +
						(doc ? '<div class="doc"><p>' + doc + '</p></div>' : '') +
						fields('Parameters', paramList.map((p) => '<dt><code>' + escape(p.name) + '</code></dt><dd>' + parts.params[p.name.replace(/^\.\.\.|^\*+/, '')] + '</dd>')) +
						fields('Returns', parts.returns ? ['<dd class="wide">' + parts.returns + '</dd>'] : []) +
						fields('Throws', parts.throws.map((t) => (t.type ? '<dt><code>' + escape(t.type) + '</code></dt><dd>' : '<dd class="wide">') + t.text + '</dd>')) +
						(endpoints.length > 0 ? '<p class="endpoints"><span class="label">TM1 REST</span> ' + endpoints.map((e) => '<code>' + escape(e) + '</code>').join(' ') + '</p>' : '') +
						'</div></section>',
				);
			}

			const name = type.name;
			const area = areaOf(javaType.package);
			const importLine = {
				java: 'import ' + javaType.package + '.' + javaType.name + ';',
				python: 'from ' + type.package + ' import ' + name,
				dotnet: 'using ' + type.package + ';',
				typescript: 'import { ' + name.split('.')[0] + ' } from "' + tsPackage + '";',
			}[language];
			const asyncNote =
				language === 'python' && /Service$|^Tm1Client$|^Tm1Session$/.test(name)
					? '<p class="note">The asyncio flavor, <code>skipcheck_tm1.aio</code>, has the same methods to <code>await</code>, on <code>Async' + escape(name) + '</code>.</p>'
					: '';
			const javadocLink =
				language === 'java'
					? '<p class="note">Also in <a href="/' + project.id + '/api/java/' + javaType.package.replaceAll('.', '/') + '/' + javaType.name + '.html">the Javadoc</a>.</p>'
					: '';
			const body =
				(project.landing ? '<p class="crumbs"><a href="../../">' + escape(project.title) + '</a> · <a href="./">Docs</a> · ' : '<p class="crumbs"><a href="./">' + escape(project.title) + ' docs</a> · ') + escape(area.title) + '</p>' +
				'<h1>' + escape(name) + '</h1>' +
				'<pre class="import"><code class="lang-' + highlight + '">' + escape(importLine) + '</code></pre>' +
				'<div class="doc"><p>' + overview + '</p></div>' +
				asyncNote +
				(rows.length > 0 ? '<h2 id="members">Members</h2><table class="members">' + rows.join('') + '</table>' + details.join('') : '') +
				javadocLink;
			await writePage(project, language, name + '.html', name, body, nav, javaType.name, languages, counterparts);
			pageCount++;
			if (project.id === 'tm1-client' || project.id === 'tm1-testcontainers') site.guide[javaType.name] ??= project.id;
		}

		// The docs home, in this language
		const grouped = groupByArea(here);
		const start = (project.start ?? []).map((n) => here.find((t) => t.name === n)).filter(Boolean);
		const summaries = new Map(here.map((t) => [t.name, summary(javadocHtml(javadocParts(t.doc).description, { example: () => '', link: (target, l) => escape(l || target.replace(/^.*[.#]/, '')) }))]));
		let home =
			(project.landing ? '<p class="crumbs"><a href="../../">' + escape(project.title) + '</a> · Docs</p>' : '') +
			'<h1>' + escape(project.title) + ' docs <span class="lang">' + escape(label) + '</span></h1>' +
			'<p class="lead">Every part of the library, with its members and examples in ' + escape(label) + '.' + (project.landing ? ' New here? Start with the <a href="../../#quick-start">quick start</a>.' : '') + '</p>';
		if (start.length > 0) {
			home += '<h2>Start here</h2><ul class="start">' + start.map((t) => '<li><a href="' + pageOf(t) + '"><strong>' + escape(nameOf(t)) + '</strong><span>' + escape(summaries.get(t.name)) + '</span></a></li>').join('') + '</ul>';
		}
		home += grouped
			.map(({ area, list }) => '<h2 id="' + area.id + '">' + escape(area.title) + '</h2><ul class="areas">' + list.map((t) => '<li><a href="' + pageOf(t) + '"><code>' + escape(nameOf(t)) + '</code></a><span>' + escape(summaries.get(t.name)) + '</span></li>').join('') + '</ul>')
			.join('');
		await writePage(project, language, 'index.html', 'Docs', home, nav, null, languages, counterparts);
	}

	// The chooser at /<project>/docs/: docs.js opens the language last chosen, else Java
	const chooser =
		(project.landing ? '<p class="crumbs"><a href="../">' + escape(project.title) + '</a> · Docs</p>' : '') + '<h1>' + escape(project.title) + ' docs</h1><p class="lead">Pick a language:</p><ul class="choose">' +
		languages.map((id) => '<li><a data-lang="' + id + '" href="' + id + '/">' + escape(LANGUAGES.find((l) => l.id === id).label) + '</a></li>').join('') +
		'</ul>';
	await mkdir(out, { recursive: true });
	await writeFile(
		path.join(out, 'index.html'),
		head({ title: project.title + ' docs · Skipcheck', canonical: CANONICAL_ORIGIN + '/' + project.id + '/docs/', styles: ['../assets/docs.css'], scripts: ['../assets/docs.js'] }) +
			'<body class="docs-choose" data-choose><main class="guide">' + chooser + '</main></body></html>\n',
	);

	// TM1's own types used by these pages
	for (const name of usesModel) await writeModel(project, name, usesModel);
}

function groupByArea(list) {
	const groups = new Map();
	for (const type of list) {
		const area = areaOf(type.package);
		if (!groups.has(area)) groups.set(area, []);
		groups.get(area).push(type);
	}
	return [...AREAS, OTHER].filter((area) => groups.has(area)).map((area) => ({ area, list: groups.get(area).sort((a, b) => a.name.localeCompare(b.name)) }));
}

function navHtml(project, language, list, pageOf, nameOf) {
	return (
		'<nav class="sidebar" aria-label="' + escape(project.title) + ' docs"><p><a href="./">' + escape(project.title) + ' docs</a></p>' +
		groupByArea(list)
			.map(
				({ area, list: types }) =>
					'<p class="area">' + escape(area.title) + '</p><ul>' +
					types.map((t) => '<li' + (nameOf(t).includes('.') ? ' class="nested"' : '') + '><a href="' + pageOf(t) + '">' + escape(nameOf(t).includes('.') ? '.' + nameOf(t).split('.').at(-1) : nameOf(t)) + '</a></li>').join('') +
					'</ul>',
			)
			.join('') +
		'</nav>'
	);
}

/** The language switch: the same page in each language, else that language's docs home. */
function switchHtml(project, language, javaName, languages, counterparts) {
	return (
		'<div class="langs" role="navigation" aria-label="Language">' +
		LANGUAGES.filter((l) => project.languages.includes(l.id))
			.map((l) => {
				if (!languages.includes(l.id)) return '<span class="soon" title="Coming soon">' + escape(l.label) + '</span>';
				const there = javaName ? counterparts.get(javaName)[l.id] : null;
				const href = '../' + l.id + '/' + (there ? there.name + '.html' : '');
				return '<a data-lang="' + l.id + '" href="' + href + '"' + (l.id === language ? ' aria-current="page"' : '') + (javaName && !there ? ' title="Not in ' + escape(l.label) + ': opens its docs"' : '') + '>' + escape(l.label) + '</a>';
			})
			.join('') +
		'</div>'
	);
}
async function writePage(project, language, file, title, body, nav, javaName, languages, counterparts) {
	const dir = path.join(distDir, project.id, 'docs', language);
	await mkdir(dir, { recursive: true });
	const html =
		head({
			title: title + ' · ' + project.title + ' · Skipcheck',
			canonical: CANONICAL_ORIGIN + '/' + project.id + '/docs/' + language + '/' + (file === 'index.html' ? '' : file),
			styles: ['../../assets/docs.css'],
			scripts: ['../../assets/docs.js', '/highlight.js'],
		}) +
		'<body data-lang="' + language + '"><div class="docs">' + nav + '<main class="guide"><div class="toolbar">' + switchHtml(project, language, javaName, languages, counterparts) + '</div>' + body + '</main></div></body></html>\n';
	await writeFile(path.join(dir, file), html);
}

async function writeModel(project, name, uses) {
	const type = specTypes.get(name);
	const dir = path.join(distDir, project.id, 'docs', 'model');
	await mkdir(dir, { recursive: true });
	const typeLink = (t) => {
		const simple = String(t).replace(/^.*\./, '');
		if (specTypes.has(simple)) {
			uses.add(simple);
			return '<a href="' + simple + '.html">' + escape(simple) + '</a>';
		}
		return escape(simple);
	};
	let body = (project.landing ? '<p class="crumbs"><a href="../../">' + escape(project.title) + '</a> · <a href="../">Docs</a> · TM1 types</p>' : '<p class="crumbs"><a href="../">' + escape(project.title) + ' docs</a> · TM1 types</p>') + '<h1>' + escape(name) + '</h1>';
	body += '<p class="lead">' + escape(type.description ?? (type.members ? 'A TM1 enumeration.' : 'A TM1 ' + (type.kind ?? 'type') + '.')) + '</p>';
	body += '<p class="note">A type of TM1 itself, from its REST API metadata, the same in every language. A property is a method in Java (<code>cube.name()</code>), and a property in Python (<code>cube.name</code>), .NET and TypeScript (<code>cube.Name</code>).</p>';
	if (type.members) {
		body += '<table class="members">' + type.members.map((m) => '<tr><td><code>' + escape(m.name) + '</code></td><td>' + escape(String(m.value)) + '</td></tr>').join('') + '</table>';
	} else {
		const props = [...(type.properties ?? []), ...(type.navigationProperties ?? []).map((p) => ({ ...p, navigation: true }))];
		if (type.baseType) body += '<p>Extends ' + typeLink(type.baseType) + '.</p>';
		body +=
			'<table class="members"><tr><th>Property</th><th>Type</th><th></th></tr>' +
			props
				.map(
					(p) =>
						'<tr><td><code>' + escape(p.name) + '</code></td><td>' + (p.collection ? 'list of ' : '') + typeLink(p.type) + (p.nullable ? '' : ' <span class="tag">required</span>') + '</td><td>' + escape(p.description ?? '') + '</td></tr>',
				)
				.join('') +
			'</table>';
	}
	await writeFile(
		path.join(dir, name + '.html'),
		head({ title: name + ' · TM1 types · Skipcheck', canonical: CANONICAL_ORIGIN + '/' + project.id + '/docs/model/' + name + '.html', styles: ['../../assets/docs.css'], scripts: [] }) +
			'<body><main class="guide model">' + body + '</main></body></html>\n',
	);
}

if (missing.size > 0) {
	const list = [...missing].sort();
	const summaryLine = list.length + ' example(s) the Java documentation quotes are missing in another language';
	if (!allowMissing) throw new Error(summaryLine + ':\n  ' + list.join('\n  '));
	console.warn(summaryLine + ':\n  ' + list.join('\n  '));
}

await mkdir(generatedDir, { recursive: true });
await writeFile(path.join(generatedDir, 'site.json'), JSON.stringify(site, null, 2) + '\n');
for (const [key, list] of gaps) console.warn('Not found in ' + key + ' (' + list.length + '): ' + list.slice(0, 12).join(', ') + (list.length > 12 ? ', ...' : ''));
console.log('Docs: ' + pageCount + ' type pages, in ' + DOC_LANGUAGES.join(', '));
