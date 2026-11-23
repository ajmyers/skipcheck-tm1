// SPDX-License-Identifier: Apache-2.0

// What the docs site is made of: the projects, the languages, and how each language's examples
// and package are found in this repository. Shared by assemble.mjs (the Javadoc), guide.mjs (the
// docs pages), pages.mjs (each project's landing page) and check-links.mjs.
//
// A project's pages are served under /<id>/:
//
//   /<id>/                  the landing page (pages.mjs, from content/<id>.md)
//   /<id>/docs/             a page for each documented type, with examples in every language
//   /<id>/api/<language>/   the generated API reference (java: the themed Javadoc)

import { cp, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';

export const siteDir = path.resolve(import.meta.dirname, '..');
export const repoDir = path.resolve(siteDir, '..');
export const distDir = path.join(siteDir, 'dist');
export const generatedDir = path.join(siteDir, '.generated');

const snake = (name) =>
	name
		.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
		.replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
		.toLowerCase();

// highlight is the language's name in the site's highlighter (/highlight.js on skipcheck.io), which
// colours a block marked lang-<name>. examples is where a project's examples file for a class is.
export const LANGUAGES = [
	{
		id: 'java',
		label: 'Java',
		highlight: 'java',
		examples: (project, cls) => path.join('java', project.id, 'src', 'examples', 'java', 'examples', cls + '.java'),
	},
	{
		id: 'python',
		label: 'Python',
		highlight: 'python',
		examples: (project, cls) => path.join('python', project.id, 'examples', snake(cls) + '.py'),
	},
	{
		id: 'dotnet',
		label: '.NET',
		highlight: 'csharp',
		examples: (project, cls) => path.join('dotnet', 'examples', project.dotnet + '.Examples', cls + '.cs'),
	},
	{
		id: 'typescript',
		label: 'TypeScript',
		highlight: 'typescript',
		examples: (project, cls) => path.join('typescript', project.id, 'examples', cls + '.ts'),
	},
];
const ALL = LANGUAGES.map((language) => language.id);

// languages: the ones the project exists in. guide: it has docs pages (guide.mjs). landing: it has
// a landing page (content/<id>.md); one without is reached through another project's page, and
// /<id>/ goes to its API reference. test: it's a test dependency, which changes how it's installed.
export const PROJECTS = [
	{ id: 'tm1-client', title: 'TM1 client', languages: ALL, dotnet: 'Tm1.Client', guide: true, landing: true, test: false },
	{ id: 'tm1-testcontainers', title: 'TM1 Testcontainers', languages: ALL, dotnet: 'Tm1.Testcontainers', guide: true, landing: true, test: true },
	{ id: 'tm1-spring-boot-starter', title: 'TM1 Spring Boot starter', languages: ['java'], guide: false, landing: true, test: false },
	{ id: 'tm1-spring-boot-testcontainers', title: 'TM1 Spring Boot Testcontainers', languages: ['java'], guide: false, landing: false, test: true },
	{ id: 'tm1-csdl', title: 'TM1 CSDL', languages: ['java'], guide: false, landing: false, test: false },
];

export const languagesOf = (project) => LANGUAGES.filter((language) => project.languages.includes(language.id));

export const escape = (text) => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

// ---- examples ---------------------------------------------------------------

const regionCache = new Map();

/** The regions of an examples file, by name; an empty map if the file isn't there. A region is
 * marked "@start region = <name>" and "@end" in a comment, as Javadoc's snippets are. */
export async function regions(file) {
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

export function dedent(lines) {
	const text = lines.map((line) => line.replaceAll('\t', '    '));
	const indents = text.filter((line) => line.trim() !== '').map((line) => line.length - line.trimStart().length);
	const indent = indents.length > 0 ? Math.min(...indents) : 0;
	return text
		.map((line) => line.slice(indent).trimEnd())
		.join('\n')
		.trim();
}

// ---- tabs -------------------------------------------------------------------

/** A code block the site's highlighter colours. */
export const codeBlock = (code, highlight) => '<pre><code' + (highlight ? ' class="lang-' + highlight + '"' : '') + '>' + escape(code) + '</code></pre>';

/** One panel per language behind tabs (guide/guide.js switches them, everywhere at once). Each tab
 * is { language, html }. */
export function tabGroup(tabs) {
	const buttons = tabs.map((tab) => '<button type="button" role="tab" data-lang="' + tab.language.id + '">' + tab.language.label + '</button>').join('');
	const panels = tabs.map((tab) => '<div role="tabpanel" data-lang="' + tab.language.id + '">' + tab.html + '</div>').join('');
	return '<div class="tabs"><div class="tablist" role="tablist" aria-label="Language">' + buttons + '</div>' + panels + '</div>';
}

// ---- output -----------------------------------------------------------------

/** Puts the styles and script of the generated pages (assets/) at /<project>/assets/. */
export async function copyAssets(project) {
	const to = path.join(distDir, project.id, 'assets');
	await mkdir(to, { recursive: true });
	await cp(path.join(siteDir, 'assets'), to, { recursive: true });
}

/** The head of a generated page. styles and scripts are addresses, in order. */
export function head({ title, description, canonical, styles, scripts }) {
	return (
		'<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">' +
		'<title>' +
		escape(title) +
		'</title>' +
		(description ? '<meta name="description" content="' + escape(description) + '">' : '') +
		(canonical ? '<link rel="canonical" href="' + escape(canonical) + '">' : '') +
		'<link rel="icon" href="/favicon.svg" type="image/svg+xml"><meta name="color-scheme" content="light dark">' +
		styles.map((href) => '<link rel="stylesheet" href="' + href + '">').join('') +
		scripts.map((src) => '<script src="' + src + '" defer></script>').join('') +
		(description ? '<meta property="og:type" content="website"><meta property="og:title" content="' + escape(title) + '"><meta property="og:description" content="' + escape(description) + '">' : '') +
		(canonical ? '<meta property="og:url" content="' + escape(canonical) + '">' : '') +
		'</head>'
	);
}

/** The address the pages name as canonical: skipcheck.dev serves the same pages. */
export const CANONICAL_ORIGIN = 'https://skipcheck.io';
