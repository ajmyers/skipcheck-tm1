// SPDX-License-Identifier: Apache-2.0

// Builds the guide: one page for each documented type of the client and the testcontainers
// library, with each of its examples shown in Java, Python, .NET and TypeScript behind tabs that
// switch together. Written into dist/projects/tm1/guide/, beside the Javadoc assemble.mjs puts in
// dist/ (run assemble first: it empties dist/).
//
// There is one source for the prose and one for each example:
//
//   prose      the Javadoc of the Java type, where it quotes its examples with {@snippet}
//   examples   a region of an examples file, the same name in every language:
//                java        java/<module>/src/examples/java/examples/<Type>Examples.java
//                python      python/<module>/examples/<type_examples>.py
//                dotnet      dotnet/examples/<Module>.Examples/<Type>Examples.cs
//                typescript  typescript/<module>/examples/<Type>Examples.ts
//              marked "@start region = <name>" and "@end" in a comment, as Javadoc's snippets are
//
// Each language builds or type-checks its examples with its own tests, so an example that no
// longer compiles fails there, not here. This script fails when a language lacks an example the
// Java type quotes (--allow-missing lists them instead).
//
//   node scripts/guide.mjs [--allow-missing]

import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

const siteDir = path.resolve(import.meta.dirname, '..');
const repoDir = path.resolve(siteDir, '..');
const out = path.join(siteDir, 'dist', 'projects', 'tm1', 'guide');
const allowMissing = process.argv.includes('--allow-missing');

// The libraries that exist in every language. Java's Spring Boot modules have no counterpart, and
// their Javadoc is their documentation.
const MODULES = [
	{ id: 'tm1-client', title: 'Client', dotnet: 'Tm1.Client.Examples' },
	{ id: 'tm1-testcontainers', title: 'Testcontainers', dotnet: 'Tm1.Testcontainers.Examples' },
];

const snake = (name) =>
	name
		.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
		.replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
		.toLowerCase();

const LANGUAGES = [
	{ id: 'java', label: 'Java', file: (m, cls) => path.join('java', m.id, 'src', 'examples', 'java', 'examples', cls + '.java') },
	{ id: 'python', label: 'Python', file: (m, cls) => path.join('python', m.id, 'examples', snake(cls) + '.py') },
	{ id: 'dotnet', label: '.NET', file: (m, cls) => path.join('dotnet', 'examples', m.dotnet, cls + '.cs') },
	{ id: 'typescript', label: 'TypeScript', file: (m, cls) => path.join('typescript', m.id, 'examples', cls + '.ts') },
];

const escape = (text) => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

// Where a snippet goes in the rendered Javadoc, until its tabs are built
const placeholder = (index) => '@@snippet-' + index + '@@';

// ---- examples ---------------------------------------------------------------

const regionCache = new Map();

/** The regions of an examples file, by name; an empty map if the file isn't there. */
async function regions(file) {
	if (regionCache.has(file)) return regionCache.get(file);
	const found = new Map();
	const text = await readFile(path.join(repoDir, file), 'utf8').catch(() => null);
	if (text !== null) {
		let name = null;
		let lines = [];
		for (const line of text.split('\n')) {
			const start = /@start\s+region\s*=\s*"?([\w-]+)"?/.exec(line);
			if (start) {
				name = start[1];
				lines = [];
			} else if (/@end\b/.test(line) && name !== null) {
				found.set(name, dedent(lines));
				name = null;
			} else if (name !== null) {
				lines.push(line);
			}
		}
	}
	regionCache.set(file, found);
	return found;
}

function dedent(lines) {
	const text = lines.map((line) => line.replaceAll('\t', '    '));
	const indents = text.filter((line) => line.trim() !== '').map((line) => line.length - line.trimStart().length);
	const indent = indents.length > 0 ? Math.min(...indents) : 0;
	return text
		.map((line) => line.slice(indent).trimEnd())
		.join('\n')
		.trim();
}

// ---- Javadoc ----------------------------------------------------------------

/** The Javadoc before a file's top-level type, without the comment's stars. */
function typeJavadoc(source) {
	const type = /^(?:public\s+)?(?:(?:final|abstract|sealed|non-sealed|static)\s+)*(?:class|interface|record|enum|@interface)\s+(\w+)/m.exec(source);
	if (!type) return null;
	const before = source.slice(0, type.index);
	const start = before.lastIndexOf('/**');
	if (start < 0) return null;
	const body = before.slice(start + 3, before.indexOf('*/', start));
	return {
		name: type[1],
		doc: body
			.split('\n')
			.map((line) => line.replace(/^\s*\* ?/, ''))
			.join('\n'),
	};
}

/** The end of the inline tag that opens at open: braces nest. */
function closing(text, open) {
	let depth = 0;
	for (let i = open; i < text.length; i++) {
		if (text[i] === '{') depth++;
		else if (text[i] === '}' && --depth === 0) return i;
	}
	return -1;
}

/** Javadoc as HTML: its inline tags rendered, its block tags dropped, its snippets as placeholders. */
function render(doc, snippets) {
	// Block tags (@param, @see, @since, ...) end the description
	const description = doc.split(/^\s*@(?:param|return|see|since|throws|author|deprecated)\b/m)[0];
	let html = '';
	let at = 0;
	for (;;) {
		const open = description.indexOf('{@', at);
		if (open < 0) break;
		const close = closing(description, open);
		if (close < 0) break;
		html += description.slice(at, open);
		const [, tag, rest = ''] = /^\{@(\w+)\s*([\s\S]*)$/.exec(description.slice(open, close));
		const body = rest.trim();
		if (tag === 'snippet') {
			const cls = /class\s*=\s*"?examples\.(\w+)"?/.exec(body);
			const region = /region\s*=\s*"?([\w-]+)"?/.exec(body);
			if (!cls || !region) throw new Error('A snippet without a class and a region: ' + body);
			snippets.push({ cls: cls[1], region: region[1] });
			html += placeholder(snippets.length - 1);
		} else if (tag === 'code') {
			html += '<code>' + escape(body) + '</code>';
		} else if (tag === 'link' || tag === 'linkplain') {
			const [target, ...label] = body.split(/\s+/);
			const named = target
				.replace(/^#/, '')
				.replace('#', '.')
				.replace(/\(.*$/, '')
				.replace(/^.*\.(?=[A-Z])/, '');
			const shown = label.length > 0 ? label.join(' ') : named;
			html += tag === 'link' ? '<code>' + escape(shown) + '</code>' : escape(shown);
		} else {
			html += escape(body);
		}
		at = close + 1;
	}
	return html + description.slice(at);
}

// ---- pages ------------------------------------------------------------------

async function* javaFiles(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true }).catch(() => [])) {
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) yield* javaFiles(full);
		else if (entry.name.endsWith('.java') && entry.name !== 'package-info.java') yield full;
	}
}

function tabGroup(tabs) {
	const buttons = tabs.map((tab) => '<button type="button" role="tab" data-lang="' + tab.language.id + '">' + tab.language.label + '</button>').join('');
	const panels = tabs
		.map((tab) => '<div role="tabpanel" data-lang="' + tab.language.id + '"><pre><code class="language-' + tab.language.id + '">' + escape(tab.code) + '</code></pre></div>')
		.join('');
	// Javadoc puts a snippet inside the paragraph that introduces it: end that paragraph first
	return '</p><div class="tabs"><div class="tablist" role="tablist" aria-label="Language">' + buttons + '</div>' + panels + '</div><p>';
}

function page(title, body) {
	return (
		'<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">' +
		'<title>' +
		escape(title) +
		' · TM1 client guide</title><link rel="stylesheet" href="guide.css"><script src="guide.js" defer></script></head>' +
		'<body><main class="guide">' +
		body +
		'</main></body></html>\n'
	);
}

const missing = new Set();
const pages = [];
let examples = 0;
for (const module of MODULES) {
	const main = path.join(repoDir, 'java', module.id, 'src', 'main', 'java');
	const files = [];
	for await (const file of javaFiles(main)) files.push(file);
	for (const file of files.sort()) {
		const source = await readFile(file, 'utf8');
		const type = source.includes('{@snippet') ? typeJavadoc(source) : null;
		if (!type || !type.doc.includes('{@snippet')) continue;
		const snippets = [];
		let html = render(type.doc, snippets);
		for (const [index, snippet] of snippets.entries()) {
			const tabs = [];
			for (const language of LANGUAGES) {
				const where = language.file(module, snippet.cls);
				const code = (await regions(where)).get(snippet.region);
				if (code === undefined) missing.add(language.id + ': ' + where + ' region ' + snippet.region);
				else tabs.push({ language, code });
			}
			html = html.replace(placeholder(index), () => tabGroup(tabs));
			examples++;
		}
		const pkg = path.relative(main, path.dirname(file)).split(path.sep).join('.');
		pages.push({ module, name: type.name, pkg, html });
	}
}

if (missing.size > 0) {
	const list = [...missing].sort();
	const summary = list.length + ' example(s) the Java documentation quotes are missing in another language';
	if (!allowMissing) throw new Error(summary + ':\n  ' + list.join('\n  '));
	console.warn(summary + ' (their tabs are left out):\n  ' + list.join('\n  '));
}

await rm(out, { recursive: true, force: true });
await mkdir(out, { recursive: true });
for (const p of pages) {
	const body =
		'<p class="crumbs"><a href="./">Guide</a> · ' +
		escape(p.module.title) +
		'</p><h1>' +
		escape(p.name) +
		'</h1><p class="package"><code>' +
		escape(p.pkg) +
		'</code></p><p>' +
		p.html +
		'</p>';
	await writeFile(path.join(out, p.name + '.html'), page(p.name, body));
}
const index = MODULES.map((module) => {
	const items = pages
		.filter((p) => p.module === module)
		.sort((a, b) => a.name.localeCompare(b.name))
		.map((p) => '<li><a href="' + p.name + '.html">' + escape(p.name) + '</a></li>')
		.join('');
	return '<h2>' + escape(module.title) + '</h2><ul class="types">' + items + '</ul>';
}).join('');
const intro =
	'<h1>TM1 client guide</h1><p>How to use the client and the testcontainers library, with every example in Java, Python, .NET and TypeScript. ' +
	'Pick a language on any example and the rest follow. The text is the Java documentation, so names in it are the Java ones: ' +
	'<code>modelCubes</code> is <code>model_cubes</code> in Python and <code>ModelCubesAsync</code> in .NET.</p>';
await writeFile(path.join(out, 'index.html'), page('Guide', intro + index));
await cp(path.join(siteDir, 'guide', 'guide.css'), path.join(out, 'guide.css'));
await cp(path.join(siteDir, 'guide', 'guide.js'), path.join(out, 'guide.js'));
console.log('Guide: ' + pages.length + ' pages, ' + examples + ' examples');
