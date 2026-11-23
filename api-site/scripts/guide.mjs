// SPDX-License-Identifier: Apache-2.0

// Builds the docs pages: for each project that has them (site.mjs), one page for each documented
// type, with each of its examples shown in Java, Python, .NET and TypeScript behind tabs that
// switch together. Written into dist/<project>/docs/, beside the Javadoc assemble.mjs puts in dist/
// (run assemble first: it empties dist/).
//
// There is one source for the prose and one for each example:
//
//   prose      the Javadoc of the Java type, where it quotes its examples with {@snippet}
//   examples   a region of an examples file, the same name in every language (site.mjs says where
//              each language keeps them), marked "@start region = <name>" and "@end" in a comment,
//              as Javadoc's snippets are
//
// Each language builds or type-checks its examples with its own tests, so an example that no
// longer compiles fails there, not here. This script fails when a language lacks an example the
// Java type quotes (--allow-missing lists them instead).
//
// It also writes .generated/site.json, which the Worker reads: the projects, and which project
// documents each type, for the addresses the docs had before (/projects/tm1/guide/<Type>.html).
//
//   node scripts/guide.mjs [--allow-missing]

import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { CANONICAL_ORIGIN, codeBlock, copyAssets, distDir, escape, generatedDir, head, languagesOf, PROJECTS, regions, repoDir, tabGroup } from './site.mjs';

const allowMissing = process.argv.includes('--allow-missing');

// Where a snippet goes in the rendered Javadoc, until its tabs are built
const placeholder = (index) => '@@snippet-' + index + '@@';

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

function page(project, name, file, body) {
	return (
		head({
			title: name + ' · ' + project.title + ' docs · Skipcheck',
			canonical: CANONICAL_ORIGIN + '/' + project.id + '/docs/' + file,
			styles: ['../assets/docs.css'],
			scripts: ['../assets/tabs.js', '/highlight.js'],
		}) +
		'<body><main class="guide">' +
		body +
		'</main></body></html>\n'
	);
}

const missing = new Set();
const owners = {};
let pageCount = 0;
let examples = 0;
for (const project of PROJECTS.filter((p) => p.guide)) {
	const main = path.join(repoDir, 'java', project.id, 'src', 'main', 'java');
	const files = [];
	for await (const file of javaFiles(main)) files.push(file);
	const pages = [];
	for (const file of files.sort()) {
		const source = await readFile(file, 'utf8');
		const type = source.includes('{@snippet') ? typeJavadoc(source) : null;
		if (!type || !type.doc.includes('{@snippet')) continue;
		const snippets = [];
		let html = render(type.doc, snippets);
		for (const [index, snippet] of snippets.entries()) {
			const tabs = [];
			for (const language of languagesOf(project)) {
				const where = language.examples(project, snippet.cls);
				const code = (await regions(where)).get(snippet.region);
				if (code === undefined) missing.add(language.id + ': ' + where + ' region ' + snippet.region);
				else tabs.push({ language, html: codeBlock(code, language.highlight) });
			}
			// Javadoc puts a snippet inside the paragraph that introduces it: end that paragraph first
			html = html.replace(placeholder(index), () => '</p>' + tabGroup(tabs) + '<p>');
			examples++;
		}
		const pkg = path.relative(main, path.dirname(file)).split(path.sep).join('.');
		pages.push({ name: type.name, pkg, html });
	}

	const out = path.join(distDir, project.id, 'docs');
	await rm(out, { recursive: true, force: true });
	await mkdir(out, { recursive: true });
	await copyAssets(project);
	const crumbs = '<p class="crumbs"><a href="../">' + escape(project.title) + '</a> · ';
	for (const p of pages) {
		const body = crumbs + '<a href="./">Docs</a></p><h1>' + escape(p.name) + '</h1><p class="package"><code>' + escape(p.pkg) + '</code></p><p>' + p.html + '</p>';
		await writeFile(path.join(out, p.name + '.html'), page(project, p.name, p.name + '.html', body));
		if (owners[p.name]) throw new Error(p.name + ' is documented by both ' + owners[p.name] + ' and ' + project.id);
		owners[p.name] = project.id;
	}
	const items = pages
		.sort((a, b) => a.name.localeCompare(b.name))
		.map((p) => '<li><a href="' + p.name + '.html">' + escape(p.name) + '</a></li>')
		.join('');
	const intro =
		crumbs +
		'Docs</p><h1>' +
		escape(project.title) +
		' docs</h1><p>New here? Start with the <a href="../#quick-start">quick start</a>. Below is a page for each part of the library, with every example in ' +
		'Java, Python, .NET and TypeScript: pick a language on any example and the rest follow. The text is the Java documentation, so names in it are ' +
		'the Java ones: <code>modelCubes</code> is <code>model_cubes</code> in Python and <code>ModelCubesAsync</code> in .NET.</p>';
	await writeFile(path.join(out, 'index.html'), page(project, 'Docs', '', intro + '<ul class="types">' + items + '</ul>'));
	pageCount += pages.length;
}

if (missing.size > 0) {
	const list = [...missing].sort();
	const summary = list.length + ' example(s) the Java documentation quotes are missing in another language';
	if (!allowMissing) throw new Error(summary + ':\n  ' + list.join('\n  '));
	console.warn(summary + ' (their tabs are left out):\n  ' + list.join('\n  '));
}

await mkdir(generatedDir, { recursive: true });
const site = { projects: PROJECTS.map((project) => ({ id: project.id, landing: project.landing })), guide: owners };
await writeFile(path.join(generatedDir, 'site.json'), JSON.stringify(site, null, 2) + '\n');
console.log('Docs: ' + pageCount + ' pages, ' + examples + ' examples');
