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
// prints matches an example's code that prints, which then needs its output recorded (output.mjs).
export const LANGUAGES = [
	{
		id: 'java',
		label: 'Java',
		highlight: 'java',
		examples: (project, cls) => path.join('java', project.id, 'src', 'examples', 'java', 'examples', cls + '.java'),
		prints: /\bSystem\.out\.print/,
	},
	{
		id: 'python',
		label: 'Python',
		highlight: 'python',
		examples: (project, cls) => path.join('python', project.id, 'examples', snake(cls) + '.py'),
		prints: /(?<![\w.])print\(/,
	},
	{
		id: 'dotnet',
		label: '.NET',
		highlight: 'csharp',
		examples: (project, cls) => path.join('dotnet', 'examples', project.dotnet + '.Examples', cls + '.cs'),
		prints: /\bConsole\.Write/,
	},
	{
		id: 'typescript',
		label: 'TypeScript',
		highlight: 'typescript',
		examples: (project, cls) => path.join('typescript', project.id, 'examples', cls + '.ts'),
		prints: /\bconsole\.log\(/,
	},
];
const ALL = LANGUAGES.map((language) => language.id);

// The languages the docs pages are written in so far: those with an API extractor (extract.mjs).
// The others show in the pages' language switch, not yet linked.
export const DOC_LANGUAGES = ['java', 'python', 'dotnet', 'typescript'];

// languages: the ones the project exists in. python: its package's source folder, for the API
// extractor. landing: it has a landing page (content/<id>.md); one without is reached through
// another project's page, and /<id>/ opens its docs. test: it's a test dependency, which changes
// how it's installed. start: the types its docs home suggests first, in order. summary: for a
// project without a landing page, its line on the docs landing page (/docs/).
export const PROJECTS = [
	{
		id: 'tm1-client',
		title: 'TM1 client',
		languages: ALL,
		dotnet: 'Tm1.Client',
		python: 'python/tm1-client/src/skipcheck_tm1',
		landing: true,
		test: false,
		start: ['Tm1Client', 'CubeService', 'CellsetService', 'ProcessService', 'ODataQuery'],
	},
	{
		id: 'tm1-testcontainers',
		title: 'TM1 Testcontainers',
		languages: ALL,
		dotnet: 'Tm1.Testcontainers',
		python: 'python/tm1-testcontainers/src/skipcheck_tm1_testcontainers',
		landing: true,
		test: true,
		start: ['RequiresTm1', 'Tm1Source', 'Tm1ServerContainer', 'PlanningAnalyticsEnvironment'],
	},
	{ id: 'tm1-spring-boot-starter', title: 'TM1 Spring Boot starter', languages: ['java'], landing: true, test: false, start: ['Tm1Properties', 'Tm1AutoConfiguration', 'Tm1ConnectionDetails'] },
	{
		id: 'tm1-spring-boot-testcontainers',
		title: 'TM1 Spring Boot Testcontainers',
		languages: ['java'],
		landing: false,
		test: true,
		summary: "A TM1 container in your Spring Boot tests, wired to the starter's client with @ServiceConnection.",
	},
	{
		id: 'tm1-csdl',
		title: 'TM1 CSDL',
		languages: ['java'],
		landing: false,
		test: false,
		summary: "Reads TM1's $metadata (OData CSDL), which the clients' models are generated from.",
	},
];

// The Docker images and their setup tool (docker/ in this repository), at /pa-docker/: a landing
// page (content/pa-docker.md) and a page per README, built by docker.mjs. Not a library: it has no
// languages, Javadoc or reference. path is the page's address under /pa-docker/; a README links to
// another one's page wherever it links to its file. A page's title is its README's heading; label,
// if given, is its shorter name in the pages' list. A page under another's path is listed under it.
export const PA_DOCKER = {
	id: 'pa-docker',
	title: 'Planning Analytics in Docker',
	pages: [
		{ path: 'pa-setup', source: 'docker/pa-setup/README.md' },
		{ path: 'tm1-server', source: 'docker/tm1-server/README.md' },
		{ path: 'tm1-server/getting-started', source: 'docker/tm1-server/GETTING-STARTED.md', label: 'Getting started' },
		{ path: 'tm1-web', source: 'docker/tm1-web/README.md' },
		{ path: 'paw', source: 'docker/paw/README.md' },
		{ path: 'cognos-server', source: 'docker/cognos-server/README.md' },
	],
};

// Every first path segment the Worker serves, and what's there: the libraries, the Docker pages,
// the docs landing page, and the docs for coding agents (llms.mjs). reference.mjs writes it into
// .generated/site.json for the Worker; wrangler.jsonc has a route for each (test/site.test.mjs
// checks), and check-links.mjs looks for their links in dist/. landing: /<id>/ is a page; a library
// without one opens at its docs. file: /<id> is a file, served as it is. site: the paths under
// /<id>/ that are ajmyers/skipcheck.io's own pages, not built here: the Worker passes them, and
// everything under them, through to the site, and check-links.mjs takes them for site addresses.
export const SECTIONS = [
	...PROJECTS.map((project) => ({ id: project.id, landing: project.landing, docs: true })),
	// The setup page, /pa-docker/setup/, is the site's: it writes pa-environment.yaml in the browser
	{ id: PA_DOCKER.id, landing: true, docs: false, site: ['setup'] },
	{ id: 'docs', landing: true, docs: false },
	{ id: 'llms.txt', landing: false, docs: false, file: true },
	{ id: 'llms-full.txt', landing: false, docs: false, file: true },
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

/**
 * A search box (assets/search.js), hidden until its script shows it: label names it for a screen
 * reader, and index is the address of its index (search.mjs), loaded when the reader first uses it.
 */
export function searchBox({ label, placeholder, index }) {
	return (
		'<div class="search" role="search" hidden>' +
		'<label class="visually-hidden" for="docs-search">' + escape(label) + '</label>' +
		'<input id="docs-search" type="search" role="combobox" aria-expanded="false" aria-controls="docs-search-results" aria-autocomplete="list" aria-keyshortcuts="/" autocomplete="off" spellcheck="false" placeholder="' + escape(placeholder) + '" data-index="' + escape(index) + '">' +
		'<kbd aria-hidden="true">/</kbd>' +
		'<ul id="docs-search-results" role="listbox" aria-label="Results" hidden></ul>' +
		'<p class="none" aria-hidden="true" hidden></p>' +
		'<p class="visually-hidden" role="status" aria-live="polite"></p>' +
		'</div>'
	);
}

/**
 * The head of a generated page. styles, scripts and modules (scripts that are ES modules) are addresses, in order. data, if
 * given, is the page's structured data for search engines (softwareSourceCode, techArticle), written as JSON-LD.
 */
export function head({ title, description, canonical, styles, scripts, modules = [], data }) {
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
		modules.map((src) => '<script type="module" src="' + src + '"></script>').join('') +
		(description ? '<meta property="og:type" content="website"><meta property="og:title" content="' + escape(title) + '"><meta property="og:description" content="' + escape(description) + '">' : '') +
		(canonical ? '<meta property="og:url" content="' + escape(canonical) + '">' : '') +
		(description ? SOCIAL_IMAGE_TAGS : '') +
		(data ? '<script type="application/ld+json">' + jsonLd(data) + '</script>' : '') +
		'</head>'
	);
}

/** The address the pages name as canonical, and their structured data as theirs. */
export const CANONICAL_ORIGIN = 'https://skipcheck.io';

// The structured data (schema.org, as JSON-LD) of the landing pages and the walkthroughs, from what their heads
// already say. A browser doesn't run a script of type application/ld+json, so the CSP's script-src doesn't apply to it.
// The site (ajmyers/skipcheck.io) describes itself on its home page, as the WebSite these pages are part of, by its @id.
const WEBSITE = { '@type': 'WebSite', '@id': CANONICAL_ORIGIN + '/#website', url: CANONICAL_ORIGIN + '/' };
const AUTHOR = { '@type': 'Person', name: 'Andrew Myers', url: 'https://ajmyers.net/', sameAs: ['https://github.com/ajmyers', 'https://www.linkedin.com/in/andrew-myers-netflix/'] };

/** The JSON of a script element: a < is escaped, so a description with </script> in it can't end the element. */
const jsonLd = (data) => JSON.stringify(data).replace(/</g, '\\u003c');

/** A project's landing page, as the code it's about: its languages, where the code is, its license and who wrote it. */
export function softwareSourceCode(project, { description, url }) {
	return {
		'@context': 'https://schema.org',
		'@type': 'SoftwareSourceCode',
		'@id': url + '#code',
		name: project.title,
		description,
		url,
		codeRepository: 'https://github.com/ajmyers/skipcheck-tm1',
		// .NET is a platform: the code its pages show is C#
		programmingLanguage: languagesOf(project).map((language) => (language.id === 'dotnet' ? 'C#' : language.label)),
		license: 'https://www.apache.org/licenses/LICENSE-2.0',
		author: AUTHOR,
		isPartOf: WEBSITE,
	};
}

/** A walkthrough, as an article about its project's code (the landing page's softwareSourceCode, by its @id). */
export function techArticle(project, { headline, description, url }) {
	const landing = CANONICAL_ORIGIN + '/' + project.id + '/';
	return {
		'@context': 'https://schema.org',
		'@type': 'TechArticle',
		headline,
		description,
		url,
		author: AUTHOR,
		about: { '@type': 'SoftwareSourceCode', '@id': landing + '#code', name: project.title, url: landing },
		isPartOf: WEBSITE,
	};
}

/** The link preview image of the pages with a description, as og:image: the site's own, which
 * ajmyers/skipcheck.io renders and serves (social/ there). Absolute, as link previews need. */
const SOCIAL_IMAGE_TAGS =
	'<meta property="og:image" content="https://skipcheck.io/og.png"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">' +
	'<meta property="og:image:alt" content="Skipcheck: developer tools for IBM Planning Analytics, for Java, Python, .NET, TypeScript and Docker">' +
	'<meta name="twitter:card" content="summary_large_image">';
