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
//                                                    (or by the file of the Java examples it names),
//                                                    and the Java names in it named the page's way
//   which type and member is which across languages  the parity rule each language's tests enforce:
//                                                    modelCubes is model_cubes in Python, and each
//                                                    language's members-excluded.txt (members.mjs)
//   the areas types are grouped in                   the Java packages (AREAS below)
//   TM1's own types (Cube, Dimension...)             spec/tm1-api.json
//
// It fails when a language lacks an example the Java documentation quotes, or a Java type or member
// its members-excluded.txt doesn't list, or when an overview in another language names what only Java
// has (--allow-missing lists them instead), and writes .generated/site.json for the Worker and
// .generated/reference.json, each type's overview in each language, for llms-full.txt (llms.mjs).
// It also fails, always, when a doc comment it reads has code typed into it (doc-code.mjs): code
// comes only from example regions, which are compiled.
//
//   node scripts/reference.mjs [--allow-missing]

import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { gzipSync } from 'node:zlib';
import { typedCode } from './doc-code.mjs';
import { checkMembers, checkTypes, findMember, guessMember, javaIsms, javaOnlyNames, languageNames, listFile, readList, stale, typeCandidates, withoutComments } from './members.mjs';
import { searchIndex } from './search.mjs';
import { CANONICAL_ORIGIN, copyAssets, distDir, DOC_LANGUAGES, escape, generatedDir, head, LANGUAGES, PROJECTS, regions, repoDir, searchBox, SECTIONS } from './site.mjs';
import { typeHeader } from './type-page.mjs';
import { examplesOf, typesUsed, walkthroughsOf } from './walkthroughs.mjs';

const allowMissing = process.argv.includes('--allow-missing');

// Areas, by the end of the Java package name, in the order the docs home shows them. Where one
// package holds different kinds of type, names (a pattern on the type's name) splits it further.
const AREAS = [
	{ id: 'tests', title: 'Writing tests', packages: ['testcontainers'], names: /^Requires/ },
	{ id: 'containers', title: 'Containers', packages: ['testcontainers'], names: /(?:Container|Environment)(?:\.|$)/ },
	{ id: 'sources', title: 'Where TM1 comes from', packages: ['testcontainers'], names: /(?:Source|Seed)(?:\.|$)/ },
	{ id: 'testing-more', title: 'More', packages: ['testcontainers'] },
	{ id: 'client', title: 'The client', packages: ['client', 'autoconfigure', 'boot.testcontainers'] },
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

function areaOf(type) {
	let best = null;
	for (const area of AREAS) {
		if (area.names && !area.names.test(type.name)) continue;
		for (const suffix of area.packages) {
			const fits = type.package === suffix || type.package.endsWith('.' + suffix);
			// The longest package wins; a name pattern beats the package alone
			const score = suffix.length + (area.names ? 1000 : 0);
			if (fits && (!best || score > best.score)) best = { area, score };
		}
	}
	return best?.area ?? OTHER;
}

// ---- doc comments -------------------------------------------------------------

// Java's literals and Object's methods in a {@code}, as each language writes them
const LITERALS = {
	python: { null: 'None', true: 'True', false: 'False', equals: '__eq__', hashCode: '__hash__', toString: '__str__' },
	dotnet: { equals: 'Equals', hashCode: 'GetHashCode', toString: 'ToString' },
};

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

/** The file a {@snippet file = ...} quotes, under the project's Java examples (src/examples/java), or null. */
const snippetFile = (body) => /\bfile\s*=\s*"?([\w./-]+)"?/.exec(body)?.[1] ?? null;

// A quoted file's language in the site's highlighter, by its extension
const FILE_HIGHLIGHT = { yml: 'yaml', yaml: 'yaml', json: 'json', xml: 'xml', properties: 'properties' };

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
			const quoted = snippetFile(body);
			// Any other snippet is code typed into the comment, which the build refuses (doc-code.mjs)
			if (cls && region) html += '</p>' + page.example(cls[1], region[1]) + '<p>';
			else if (quoted) html += '</p>' + (page.file?.(quoted) ?? '') + '<p>';
		} else if (tag === 'code') {
			html += page.code ? page.code(body) : '<code>' + escape(body) + '</code>';
		} else if (tag === 'literal') {
			html += escape(body);
		} else if (tag === 'value' && body) {
			html += page.value ? page.value(body) : '<code>' + escape(body) + '</code>';
		} else if (tag === 'link' || tag === 'linkplain') {
			// The target ends at the first space outside its parameter list: #withCredentials(String, Tm1Authenticator)
			const [, target, label = ''] = /^([^\s(]*(?:\([^)]*\))?)\s*([\s\S]*)$/.exec(body);
			html += page.link(target, label.trim(), tag === 'link');
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

/** A TSDoc comment as HTML: its description and its tags, with Markdown's inline code. */
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
		.split(/\n\s*\n/)
		.map((p) => mdInline(p))
		.join('</p><p>');
}

function mdInline(text) {
	return escape(text.trim())
		.replace(/`([^`]+)`/g, '<code>$1</code>')
		.replace(/\{@link\s+([^}\s|]+)(?:\s*\|?\s*([^}]*))?\}/g, (m, target, label) => '<code>' + (label || target) + '</code>')
		.replace(/\s*\n\s*/g, ' ');
}

/** A Python docstring as HTML: paragraphs and ``code``. */
function docstringHtml(doc) {
	const lines = doc.split('\n');
	const out = [];
	let paragraph = [];
	const flush = () => {
		if (paragraph.length > 0) out.push('<p>' + inlineRst(paragraph.join(' ')) + '</p>');
		paragraph = [];
	};
	for (const line of lines) {
		if (line.trim() === '') {
			flush();
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
// A language's code for a project, its sources and its examples, without comments: the names it has
const CODE = {
	python: (project) => ({ dirs: [project.python, path.join('python', project.id, 'examples')], ext: '.py' }),
	dotnet: (project) => ({ dirs: [path.join('dotnet', 'src', project.dotnet), path.join('dotnet', 'examples', project.dotnet + '.Examples')], ext: '.cs' }),
	typescript: (project) => ({ dirs: [path.join('typescript', project.id, 'src'), path.join('typescript', project.id, 'examples')], ext: '.ts' }),
};
async function codeOf(project, language) {
	const { dirs, ext } = CODE[language](project);
	let text = '';
	for (const dir of dirs) {
		const files = await readdir(path.join(repoDir, dir), { recursive: true }).catch(() => []);
		for (const file of files.filter((f) => f.endsWith(ext)).sort()) text += withoutComments(await readFile(path.join(repoDir, dir, file), 'utf8'), language) + '\n';
	}
	return text;
}

const packageNames = {
	typescript: async (project) => JSON.parse(await readFile(path.join(repoDir, 'typescript', project.id, 'package.json'), 'utf8')).name,
};

// ---- pages --------------------------------------------------------------------

const missing = new Set();
// Java members a language lacks, unlisted, and lines of the lists that are wrong
const memberFaults = [];
const lists = new Map();
// Code typed into a doc comment the pages read, overviews with fewer examples than Java's, and
// overviews naming what only Java has
const typed = [];
const fewer = [];
const javaWords = [];
// Every path the Worker serves: the projects, the Docker pages and the docs landing page
const site = { projects: SECTIONS, guide: {} };
// Each type's overview in each language, for llms-full.txt (llms.mjs)
const overviews = [];
let pageCount = 0;
const indexSizes = [];

// The walkthroughs that use each Java type, by its name, for "Used in" on the type's page in every
// language: worked out from the walkthroughs' Java example regions, which name a type or reach a
// service through one of Tm1Client's accessors (tm1.cellsets() is CellsetService)
const usedIn = new Map();
{
	const documentedTypes = [];
	for (const project of PROJECTS) documentedTypes.push(...((await api(project, 'java')) ?? []).filter((t) => !t.package.endsWith('.model')));
	const names = documentedTypes.map((t) => t.name).filter((name) => !name.includes('.'));
	const client = documentedTypes.find((t) => t.name === 'Tm1Client');
	const accessors = new Map((client?.members ?? []).filter((m) => m.kind === 'method' && !m.static && m.params.length === 0 && names.includes(m.returns)).map((m) => [m.name, m.returns]));
	const java = LANGUAGES.find((l) => l.id === 'java');
	for (const project of PROJECTS) {
		for (const walkthrough of await walkthroughsOf(project)) {
			const used = new Set();
			for (const { cls, region } of examplesOf(walkthrough)) {
				for (const name of typesUsed((await regions(java.examples(project, cls))).get(region) ?? '', names, accessors)) used.add(name);
			}
			for (const name of used) usedIn.set(name, [...(usedIn.get(name) ?? []), { project, walkthrough }]);
		}
	}
}

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
	// Each language's names, and the Java names it doesn't have
	const names = {};
	const javaOnly = {};
	for (const language of languages.filter((l) => l !== 'java')) {
		names[language] = languageNames(apis[language], await codeOf(project, language));
		javaOnly[language] = javaOnlyNames(types, names[language]);
	}
	const out = path.join(distDir, project.id, 'docs');
	await rm(out, { recursive: true, force: true });
	await copyAssets(project);
	const tsPackage = languages.includes('typescript') ? await packageNames.typescript(project) : '';

	// Each language's list of the Java members it lacks or names another way
	const listOf = {};
	for (const language of languages.filter((l) => l !== 'java')) {
		const file = listFile(project, language);
		if (!lists.has(file)) lists.set(file, { entries: await readList(repoDir, file), used: new Set() });
		listOf[language] = lists.get(file);
	}
	const renamedIn = (language, key) => (language === 'java' ? null : (listOf[language].entries.get(key)?.renamed ?? null));

	// For each Java type, its counterpart in each language
	const counterparts = new Map();
	for (const type of types) {
		const each = { java: type };
		for (const language of languages.filter((l) => l !== 'java')) {
			each[language] = typeCandidates(language, type.name, renamedIn(language, type.name)).map((n) => apis[language].find((t) => t.name === n)).find(Boolean) ?? null;
		}
		counterparts.set(type.name, each);
	}
	// Every Java type and member has its counterpart, or a line in the list
	for (const language of languages.filter((l) => l !== 'java')) {
		const inLanguage = new Map(types.map((t) => [t.name, counterparts.get(t.name)[language]]));
		const lacking = checkTypes({ javaTypes: types, counterparts: inLanguage, list: listOf[language].entries, used: listOf[language].used });
		for (const name of lacking.missing) memberFaults.push(project.id + ' ' + language + ': type ' + name);
		for (const fault of lacking.wrong) memberFaults.push(listFile(project, language) + ': ' + fault);
		const { missing, wrong } = checkMembers({ language, javaTypes: types, counterparts: inLanguage, list: listOf[language].entries, used: listOf[language].used });
		for (const key of missing) memberFaults.push(project.id + ' ' + language + ': ' + key);
		for (const fault of wrong) memberFaults.push(listFile(project, language) + ': ' + fault);
	}
	const documented = (language) => types.filter((t) => counterparts.get(t.name)[language]);
	const usesModel = new Set();
	const searchIndexes = [];

	for (const language of languages) {
		const label = LANGUAGES.find((l) => l.id === language).label;
		const highlight = LANGUAGES.find((l) => l.id === language).highlight;
		const here = documented(language);
		const counterpartOf = (javaType) => counterparts.get(javaType.name)[language];
		const nameOf = (javaType) => counterpartOf(javaType).name;
		const pageOf = (javaType) => nameOf(javaType) + '.html';
		const byJavaSimple = new Map(here.map((t) => [t.name.split('.').at(-1), t]));
		const simpleCount = new Map();
		for (const t of here) simpleCount.set(t.name.split('.').at(-1), (simpleCount.get(t.name.split('.').at(-1)) ?? 0) + 1);
		const byJavaName = new Map(here.map((t) => [t.name, t]));
		const byName = new Map(here.map((t) => [nameOf(t), t]));
		const nav = navHtml(project, language, here, pageOf, nameOf);
		// Each page's members, for the search index (search.mjs)
		const searchable = new Map();

		for (const javaType of here) {
			const type = counterpartOf(javaType);
			// A Java type a comment names: as written (Tm1Client.Builder), else nested in this type or its
			// outer type (Builder, in PlanningAnalyticsEnvironment), else by its simple name if one type has it
			const outer = javaType.name.split('.')[0];
			const javaTypeNamed = (name) =>
				byJavaName.get(name) ?? byJavaName.get(javaType.name + '.' + name) ?? byJavaName.get(outer + '.' + name) ?? (simpleCount.get(name.split('.').at(-1)) === 1 ? byJavaSimple.get(name.split('.').at(-1)) : null) ?? null;
			// A Java member's name in this language, its kind there, and whether the page has it
			const memberName = (owner, javaName) => {
				const javaMember = owner?.members.find((m) => m.name === javaName) ?? { name: javaName, kind: 'method' };
				const found = owner ? findMember(language, javaMember, counterpartOf(owner), renamedIn(language, owner.name + '.' + javaName)) : null;
				// has: the page has an anchor for it. Java's findMember answers even for a member Java's model
				// lacks (Testcontainers' withReuse, AutoCloseable's close), which has none
				return found ? { name: found.name, kind: found.kind, has: counterpartOf(owner).members.some((m) => m.name === found.name) } : { name: guessMember(language, javaMember, names[language]), kind: javaMember.kind, has: false };
			};
			// Examples are read up front, so rendering stays synchronous
			const exampleBlocks = new Map();
			for (const doc of [javaType.doc, ...javaType.members.map((m) => m.doc)]) {
				for (const [, cls, region] of doc.matchAll(/\{@snippet[^}]*class\s*=\s*"?examples\.(\w+)"?[^}]*region\s*=\s*"?([\w-]+)"?/g)) {
					const where = LANGUAGES.find((l) => l.id === language).examples(project, cls);
					const text = (await regions(where)).get(region);
					if (text === undefined) missing.add(language + ': ' + where + ' region ' + region);
					exampleBlocks.set(cls + '#' + region, text === undefined ? '' : code(text, highlight, 'x-' + cls + '-' + region));
				}
				// A whole file of the Java examples, such as a Spring Boot application.yml, the same in every language
				for (const [snippet] of doc.matchAll(/\{@snippet[^}]*\}/g)) {
					const quoted = snippetFile(snippet);
					if (!quoted) continue;
					const where = path.join('java', project.id, 'src', 'examples', 'java', quoted);
					const text = await readFile(path.join(repoDir, where), 'utf8').catch(() => undefined);
					if (text === undefined) missing.add(language + ': ' + where);
					exampleBlocks.set('file:' + quoted, text === undefined ? '' : code(text.trim(), FILE_HIGHLIGHT[quoted.split('.').at(-1)] ?? 'plaintext', 'x-' + quoted));
				}
			}
			// Every comment this page reads: Java's for the type and its members, else this language's
			// members' own (the overview is always Java's). Code typed into any of them fails the build
			const read = language === 'java' ? [[javaType.name, javaType.doc], ...javaType.members.map((m) => [javaType.name + '.' + m.name, m.doc])] : type.members.map((m) => [type.name + '.' + m.name, m.doc]);
			for (const [where, doc] of read) for (const block of typedCode(doc, language)) typed.push(project.id + ' ' + language + ' ' + where + ': ' + block);
			const render = {
				example: (cls, region) => exampleBlocks.get(cls + '#' + region) ?? '',
				file: (quoted) => exampleBlocks.get('file:' + quoted) ?? '',
				link(target, linkLabel, asCode) {
					const [typePart, memberPart] = target.split('#');
					const simple = typePart.replace(/^.*\.(?=[A-Z])/, '');
					const owner = simple ? javaTypeNamed(simple) : javaType;
					const member = memberPart ? memberName(owner, memberPart.replace(/\(.*$/, '')) : null;
					const ownerName = owner ? nameOf(owner) : simple;
					const shown = linkLabel || [simple ? ownerName : '', member?.name].filter(Boolean).join('.');
					const text = asCode ? '<code>' + escape(shown) + '</code>' : escape(shown);
					if (owner) return '<a href="' + pageOf(owner) + (member?.has ? '#' + member.name : '') + '">' + text + '</a>';
					if (simple && specTypes.has(simple)) {
						usesModel.add(simple);
						return '<a href="../model/' + simple + '.html">' + text + '</a>';
					}
					return text;
				},
				// {@code ...}: a Java type or member named in it is named the page's way (Tm1Client.withSandbox
				// is Tm1Client.with_sandbox in Python), a literal the language's (null is None in Python), and
				// anything else (a TM1 name, a path, a value) is shown as written
				code(text) {
					const plain = '<code>' + escape(text) + '</code>';
					if (language === 'java') return plain;
					if (LITERALS[language]?.[text]) return '<code>' + LITERALS[language][text] + '</code>';
					const ref = /^((?:[A-Za-z_$][\w$]*[.#])*)([A-Za-z_$][\w$]*)(\([^)]*\))?$/.exec(text);
					if (!ref) return plain;
					const [, qualifier, last, args = ''] = ref;
					const path = qualifier.replace(/[.#]$/, '');
					let owner = path ? javaTypeNamed(path) : null;
					let javaName = last;
					if (!path) {
						// A type, or a member of this type or of a type nested in it (a builder's)
						owner = /^[A-Z]/.test(last) && !args ? javaTypeNamed(last) : null;
						if (owner) javaName = null;
						else owner = [javaType, ...here.filter((t) => t.name.startsWith(javaType.name + '.'))].find((t) => t.members.some((m) => m.name === last && m.kind !== 'constructor')) ?? null;
						if (!owner) return plain;
					} else if (!owner || !owner.members.some((m) => m.name === last)) {
						// Not a member: a nested type (Tm1Source.PrivateImage), or not Java's at all
						owner = javaTypeNamed(path + '.' + last);
						if (!owner) {
							// A member of a type with no page here, such as a model type's builder
							// (ChoreTaskPatch.Builder#bindProcess): named by the rule, if the language has that name
							const guessed = /^[A-Z]/.test(path) && /^[a-z]/.test(last) ? guessMember(language, { name: last, kind: 'method' }, names[language]) : null;
							return guessed && names[language].has(guessed) ? '<code>' + escape(path.replaceAll('#', '.') + '.' + guessed + args) + '</code>' : plain;
						}
						javaName = null;
					}
					const member = javaName ? memberName(owner, javaName) : null;
					// A Java method that's a property in this language loses its parentheses
					const shownArgs = member && member.kind !== 'method' && member.kind !== 'constructor' ? '' : args;
					const shown = [path || !javaName ? nameOf(owner) : '', member?.name].filter(Boolean).join('.') + shownArgs;
					// A member with no anchor on the page (one inherited from outside the library) stays plain code
					if (member && !member.has) return '<code>' + escape(shown) + '</code>';
					return '<a href="' + pageOf(owner) + (member ? '#' + member.name : '') + '"><code>' + escape(shown) + '</code></a>';
				},
				// {@value Type#CONSTANT}: the constant's value, as Javadoc shows it, or its name if the model has no value
				value(target) {
					const [typePart, memberPart = ''] = target.split('#');
					const owner = typePart ? javaTypeNamed(typePart.replace(/^.*\.(?=[A-Z])/, '')) : javaType;
					const constant = owner?.members.find((m) => m.name === memberPart);
					if (constant?.value !== undefined) return '<code>' + escape(constant.value) + '</code>';
					return this.link(target, '', true);
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

			// The overview is Java's in every language, with its examples in this one. So the other
			// languages' own type comments are never read here: what they say, examples typed into them
			// included, is for their IDEs and API docs, and can't reach a page
			const javaDescription = javadocParts(javaType.doc).description;
			const overview = javadocHtml(javaDescription, render);
			const blocks = (html) => (html.match(/<pre[ >]/g) ?? []).length;
			if (language !== 'java') {
				const javaBlocks = (javaDescription.match(/\{@snippet/g) ?? []).length;
				if (blocks(overview) < javaBlocks) fewer.push(project.id + ' ' + language + ' ' + javaType.name + ' (' + blocks(overview) + ' of ' + javaBlocks + ')');
				const words = javaIsms(overview, javaOnly[language]);
				if (words.length > 0) javaWords.push(project.id + ' ' + language + ' ' + javaType.name + ': ' + words.join(', '));
			}

			// Members: this language's own, each with Java's examples where Java's has them
			const javaFor = new Map();
			for (const member of javaType.members) {
				const found = findMember(language, member, type, renamedIn(language, javaType.name + '.' + member.name));
				if (found && !javaFor.has(found)) javaFor.set(found, member);
			}
			const members = language === 'java' ? javaType.members : type.members;
			const rows = [];
			const details = [];
			const seen = new Map();
			searchable.set(javaType.name, []);
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
					if (!member.doc && java.doc) {
						// Java's words where this language has none
						doc = javadocHtml(javadocParts(java.doc).description, render);
					}
					// Java's examples for the same member, in this language
					doc += [...java.doc.matchAll(/\{@snippet[^}]*class\s*=\s*"?examples\.(\w+)"?[^}]*region\s*=\s*"?([\w-]+)"?[^}]*\}/g)].map(([, cls, region]) => exampleBlocks.get(cls + '#' + region) ?? '').join('');
				}
				if (language !== 'java' && java) {
					const javaBlocks = (javadocParts(java.doc).description.match(/\{@snippet/g) ?? []).length;
					if (blocks(doc) < javaBlocks) fewer.push(project.id + ' ' + language + ' ' + javaType.name + '.' + java.name + ' (' + blocks(doc) + ' of ' + javaBlocks + ')');
				}
				const kindLabel = member.kind === 'property' ? 'property' : member.kind === 'constructor' ? 'constructor' : member.static ? 'static' : '';
				rows.push('<tr><td><a href="#' + escape(id) + '"><code>' + escape(member.name) + '</code></a>' + (kindLabel ? ' <span class="tag">' + kindLabel + '</span>' : '') + '</td><td>' + escape(summary(doc)) + '</td></tr>');
				searchable.get(javaType.name).push({ name: member.name, anchor: id, summary: summary(doc), kind: member.kind });
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
			const area = areaOf(javaType);
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
				typeHeader({ name, importLine, highlight, uses: usedIn.get(javaType.name) }) +
				'<div class="doc"><p>' + overview + '</p></div>' +
				asyncNote +
				(rows.length > 0 ? '<h2 id="members">Members</h2><table class="members">' + rows.join('') + '</table>' + details.join('') : '') +
				javadocLink;
			await writePage(project, language, name + '.html', name, body, nav, javaType.name, languages, counterparts);
			pageCount++;
			overviews.push({ project: project.id, language, java: javaType.name, name, page: pageOf(javaType), area: area.title, overview });
			if (project.id === 'tm1-client' || project.id === 'tm1-testcontainers') site.guide[javaType.name] ??= project.id;
		}

		// The docs home, in this language
		const grouped = groupByArea(here);
		const start = (project.start ?? []).map((n) => here.find((t) => t.name === n)).filter(Boolean);
		const summaries = new Map(here.map((t) => [t.name, summary(javadocHtml(javadocParts(t.doc).description, { example: () => '', link: (target, l) => escape(l || target.replace(/^.*[.#]/, '')) }))]));
		for (const entry of overviews) if (entry.project === project.id && entry.language === language) entry.summary = summaries.get(entry.java);
		// The walkthroughs, which pages.mjs builds: their examples are in every language, behind tabs
		// that open in the language last chosen, this one when it's read here
		const walkthroughs = await walkthroughsOf(project);
		const walkthroughPage = (w) => '../../walkthroughs/' + w.slug + '/';
		// Written once the TM1 types are known, below
		searchIndexes.push({
			language,
			types: here.map((t) => ({ name: nameOf(t), page: pageOf(t), summary: summaries.get(t.name), members: searchable.get(t.name) })),
			walkthroughs: walkthroughs.map((w) => ({ title: w.meta.title, page: walkthroughPage(w), summary: w.meta.summary })),
		});
		let home =
			(project.landing ? '<p class="crumbs"><a href="../../">' + escape(project.title) + '</a> · Docs</p>' : '') +
			'<h1>' + escape(project.title) + ' docs <span class="lang">' + escape(label) + '</span></h1>' +
			'<p class="lead">Every part of the library, with its members and examples in ' + escape(label) + '.' + (project.landing ? ' New here? Start with the <a href="../../#quick-start">quick start</a>' + (walkthroughs.length > 0 ? ', then the walkthrough <a href="' + walkthroughPage(walkthroughs[0]) + '">' + escape(walkthroughs[0].meta.title) + '</a>' : '') + '.' : '') + '</p>';
		if (start.length > 0) {
			home += '<h2>Start here</h2><ul class="start">' + start.map((t) => '<li><a href="' + pageOf(t) + '"><strong>' + escape(nameOf(t)) + '</strong><span>' + escape(summaries.get(t.name)) + '</span></a></li>').join('') + '</ul>';
		}
		if (walkthroughs.length > 0) {
			home += '<h2 id="walkthroughs">Walkthroughs</h2><ul class="start">' + walkthroughs.map((w) => '<li><a href="' + walkthroughPage(w) + '"><strong>' + escape(w.meta.title) + '</strong><span>' + escape(w.meta.summary) + '</span></a></li>').join('') + '</ul>';
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

	// Each language's search index: its types, members and walkthroughs, and the TM1 types the pages
	// link to, the same in every language (writeModel adds those they link to in turn)
	const model = [...usesModel].sort().map((name) => ({ name, page: '../model/' + name + '.html', summary: modelLead(specTypes.get(name)) }));
	for (const { language, types: listed, walkthroughs } of searchIndexes) {
		const index = JSON.stringify(searchIndex(listed, walkthroughs, model));
		await writeFile(path.join(distDir, project.id, 'docs', language, 'search.json'), index + '\n');
		indexSizes.push({ at: project.id + ' ' + language, bytes: Buffer.byteLength(index), gzip: gzipSync(index).length });
	}
}

/** What a TM1 type is: its description in the metadata, else its kind. */
function modelLead(type) {
	return type.description ?? (type.members ? 'A TM1 enumeration.' : 'A TM1 ' + (type.kind ?? 'type') + '.');
}

function groupByArea(list) {
	const groups = new Map();
	for (const type of list) {
		const area = areaOf(type);
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
					// A type nested in Java sits under its outer type, in every language
					types.map((t) => '<li' + (t.name.includes('.') ? ' class="nested"' : '') + '><a href="' + pageOf(t) + '">' + escape(nameOf(t).includes('.') ? '.' + nameOf(t).split('.').at(-1) : nameOf(t)) + '</a></li>').join('') +
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
/** The search box of a page in one language, over that language's index beside it. */
function searchHtml(project, language) {
	const label = LANGUAGES.find((l) => l.id === language).label;
	return searchBox({ label: 'Search the ' + project.title + ' docs in ' + label, placeholder: 'Search ' + label, index: 'search.json' });
}

async function writePage(project, language, file, title, body, nav, javaName, languages, counterparts) {
	// The breadcrumbs share the language bar, on its left
	const crumbs = /^<p class="crumbs">[\s\S]*?<\/p>/.exec(body)?.[0] ?? null;
	const rest = crumbs ? body.slice(crumbs.length) : body;
	const dir = path.join(distDir, project.id, 'docs', language);
	await mkdir(dir, { recursive: true });
	const html =
		head({
			title: title + ' · ' + project.title + ' · Skipcheck',
			canonical: CANONICAL_ORIGIN + '/' + project.id + '/docs/' + language + '/' + (file === 'index.html' ? '' : file),
			styles: ['../../assets/docs.css'],
			scripts: ['../../assets/docs.js', '/highlight.js'],
			modules: ['../../assets/search.js'],
		}) +
		'<body data-lang="' + language + '"><div class="docs">' + nav + '<main class="guide"><div class="toolbar">' + (crumbs ?? '<span></span>') + searchHtml(project, language) + switchHtml(project, language, javaName, languages, counterparts) + '</div>' + rest + '</main></div></body></html>\n';
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
	body += '<p class="lead">' + escape(modelLead(type)) + '</p>';
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
await writeFile(path.join(generatedDir, 'reference.json'), JSON.stringify(overviews) + '\n');
if (fewer.length > 0) {
	const line = fewer.length + " page(s) have fewer examples than Java's: quote each example from an examples region, which every language has";
	if (!allowMissing) throw new Error(line + ':\n  ' + fewer.join('\n  '));
	console.warn(line + ':\n  ' + fewer.join('\n  '));
}
if (typed.length > 0) {
	throw new Error(
		typed.length +
			' doc comment block(s) have code typed into them. Put the code in an examples region in every language, and quote it in the Javadoc with {@snippet class = examples.<Class> region = <name>}, so it is compiled:\n  ' +
			typed.join('\n  '),
	);
}
if (javaWords.length > 0) {
	const line = javaWords.length + " overview(s) name what only Java has: word the Javadoc for every language, or name it in a {@code} or {@link} the page can translate (members.mjs, javaIsms)";
	if (!allowMissing) throw new Error(line + ':\n  ' + javaWords.join('\n  '));
	console.warn(line + ':\n  ' + javaWords.join('\n  '));
}
for (const [file, { entries, used }] of lists) for (const fault of stale(entries, used)) memberFaults.push(file + ': ' + fault);
if (memberFaults.length > 0) {
	const line = memberFaults.length + ' Java type(s) or member(s) without a counterpart, or list line(s) that are wrong: give the language the type or member, or list it with the reason in its members-excluded.txt (scripts/members.mjs)';
	if (!allowMissing) throw new Error(line + ':\n  ' + memberFaults.join('\n  '));
	console.warn(line + ':\n  ' + memberFaults.join('\n  '));
}
const largest = indexSizes.sort((a, b) => b.bytes - a.bytes)[0];
if (largest) console.log('Search: ' + indexSizes.length + ' indexes, the largest ' + largest.at + ', ' + Math.round(largest.bytes / 1024) + ' KB (' + Math.round(largest.gzip / 1024) + ' KB gzipped)');
console.log('Docs: ' + pageCount + ' type pages, in ' + DOC_LANGUAGES.join(', '));
