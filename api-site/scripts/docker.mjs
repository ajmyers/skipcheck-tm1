// SPDX-License-Identifier: Apache-2.0

// Builds the Docker pages at /pa-docker/ (PA_DOCKER in site.mjs): a landing page from
// content/pa-docker.md, and a page per README under docker/, which is the only copy of their text.
// Run after pages.mjs.
//
// The READMEs are written for GitHub, and render here as GitHub renders them (GitHub Flavored
// Markdown, headings with GitHub's ids), so their links work on both:
//
//   a link to another README of PA_DOCKER    that README's page (and anchor)
//   a link to any other file in the repository the file on GitHub, at the release's tag (else
//                                            master)
//   #anchor, or another site                 as written
//
// It fails when a README links to a file that isn't in the repository. check-links.mjs then checks
// every link on the pages, anchors included.
//
//   node scripts/docker.mjs

import { existsSync, statSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { Marked } from 'marked';
import { inline, parse } from './markdown.mjs';
import { docsVersion } from './packages.mjs';
import { CANONICAL_ORIGIN, codeBlock, copyAssets, distDir, escape, head, PA_DOCKER, PROJECTS, repoDir, siteDir } from './site.mjs';

const version = await docsVersion();
const github = 'https://github.com/' + /^githubRepo=(.+)$/m.exec(await readFile(path.join(repoDir, 'gradle.properties'), 'utf8'))[1].trim();
// The files the pages describe: those of the release they're built for, else master's
const ref = version.release ? 'v' + version.version : 'master';
const root = '/' + PA_DOCKER.id + '/';
const pageBySource = new Map(PA_DOCKER.pages.map((page) => [page.source, page]));
const addressOf = (page) => root + page.path + '/';

/** GitHub's heading ids (as github-slugger makes them): lower case, punctuation dropped, spaces as
 * hyphens, and -1, -2... on a repeat within the page. */
export function slugger() {
	const seen = new Map();
	return (text) => {
		const base = text
			.toLowerCase()
			.replace(/[^\p{L}\p{M}\p{N}\p{Pc} -]/gu, '')
			.replaceAll(' ', '-');
		let id = base;
		while (seen.has(id)) {
			seen.set(base, seen.get(base) + 1);
			id = base + '-' + seen.get(base);
		}
		seen.set(id, 0);
		return id;
	};
}

/** Where a README's link goes on the site. source is the README's path in the repository. */
export function linkTarget(href, source, { pages = pageBySource, exists = (file) => existsSync(path.join(repoDir, file)), isDirectory = (file) => statSync(path.join(repoDir, file)).isDirectory() } = {}) {
	if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('#') || href.startsWith('/')) return href;
	const [file, hash] = href.split(/(?=#)/);
	const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(source), decodeURI(file))).replace(/\/$/, '');
	if (resolved.startsWith('../')) throw new Error(source + ': ' + href + ' is outside the repository');
	const page = pages.get(resolved);
	if (page) return addressOf(page) + (hash ?? '');
	if (!exists(resolved)) throw new Error(source + ': ' + href + ' links to ' + resolved + ', which is not in the repository');
	return github + '/' + (isDirectory(resolved) ? 'tree' : 'blob') + '/' + ref + '/' + encodeURI(resolved) + (hash ?? '');
}

/** A README as { title, summary, html }: its heading, its first paragraph (inline HTML), and the
 * rest of it, without the heading. */
export function render(markdown, source, options) {
	const id = slugger();
	let title = null;
	const marked = new Marked({ gfm: true });
	marked.use({
		renderer: {
			heading({ tokens, depth }) {
				const text = this.parser.parseInline(tokens);
				const plain = this.parser.parseInline(tokens, this.parser.textRenderer);
				const anchor = id(decode(plain));
				if (depth === 1 && title === null) {
					title = decode(plain);
					return '';
				}
				return '<h' + depth + ' id="' + escape(anchor) + '">' + text + '</h' + depth + '>\n';
			},
			link({ href, title: linkTitle, tokens }) {
				const text = this.parser.parseInline(tokens);
				return '<a href="' + escape(linkTarget(href, source, options)) + '"' + (linkTitle ? ' title="' + escape(linkTitle) + '"' : '') + '>' + text + '</a>';
			},
			// Coloured by the site's highlighter, which knows sh, powershell and yaml
			code({ text, lang }) {
				return codeBlock(text, lang?.split(/\s/)[0] || null) + '\n';
			},
		},
	});
	const tokens = marked.lexer(markdown);
	const html = marked.parser(tokens);
	if (title === null) throw new Error(source + ' has no # heading');
	const first = tokens.find((token) => token.type === 'paragraph');
	const summary = first ? marked.parser([first]).replace(/^<p>|<\/p>\s*$/g, '') : '';
	return { title, summary, html };
}

const decode = (text) => text.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&#39;', "'").replaceAll('&amp;', '&');
const plainText = (html) => decode(html.replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim();

// The script runs when started, and only lends its functions when imported (by the tests)
if (import.meta.main) await build();

async function build() {
	const pages = [];
	for (const page of PA_DOCKER.pages) {
		pages.push({ ...page, ...render(await readFile(path.join(repoDir, page.source), 'utf8'), page.source) });
	}
	const childrenOf = (page) => pages.filter((other) => path.posix.dirname(other.path) === page.path);
	const tops = pages.filter((page) => !page.path.includes('/'));

	await copyAssets(PA_DOCKER);

	// The list of pages beside each one: the images and the setup tool, each with its pages under it
	const nav = (current) =>
		'<nav class="sidebar" aria-label="' + escape(PA_DOCKER.title) + '"><p><a href="' + root + '">' + escape(PA_DOCKER.title) + '</a></p><ul>' +
		tops
			.flatMap((page) => [page, ...childrenOf(page)])
			.map((page) => '<li' + (page.path.includes('/') ? ' class="nested"' : '') + '><a href="' + addressOf(page) + '"' + (page === current ? ' aria-current="page"' : '') + '>' + escape(page.label ?? page.title) + '</a></li>')
			.join('') +
		'</ul></nav>';

	for (const page of pages) {
		const parent = pages.find((other) => other.path === path.posix.dirname(page.path));
		const crumbs = '<p class="crumbs"><a href="' + root + '">' + escape(PA_DOCKER.title) + '</a> · ' + (parent ? '<a href="' + addressOf(parent) + '">' + escape(parent.title) + '</a> · ' : '') + escape(page.label ?? page.title) + '</p>';
		const body =
			crumbs +
			'<h1>' + escape(page.title) + '</h1>' +
			page.html +
			'<p class="note">This page is <a href="' + github + '/blob/' + ref + '/' + page.source + '"><code>' + escape(page.source) + '</code></a> on GitHub.</p>';
		const out = path.join(distDir, PA_DOCKER.id, ...page.path.split('/'));
		await mkdir(out, { recursive: true });
		await writeFile(
			path.join(out, 'index.html'),
			head({
				title: page.title + ' · ' + PA_DOCKER.title + ' · Skipcheck',
				description: plainText(page.summary),
				canonical: CANONICAL_ORIGIN + addressOf(page),
				styles: [root + 'assets/docs.css', root + 'assets/readme.css'],
				scripts: ['/highlight.js'],
			}) +
				'<body><div class="docs">' + nav(page) + '<main class="guide readme">' + body + '</main></div></body></html>\n',
		);
	}

	// The landing page, in the site's classes as the libraries' are (pages.mjs): the words from the
	// content file, then a card per image or tool, from its README's first paragraph
	const file = path.join(siteDir, 'content', PA_DOCKER.id + '.md');
	const { meta, sections } = parse(await readFile(file, 'utf8'));
	for (const key of ['tagline', 'description', 'card', 'kicker', 'callout']) {
		if (!meta[key]) throw new Error(path.relative(repoDir, file) + ' has no ' + key + ' in its front matter');
	}
	const [lead, ...rest] = sections;
	const blocks = (list) =>
		list
			.map((block) => {
				if (block.type === 'p') return '<p class="more">' + inline(block.text) + '</p>';
				if (block.type === 'directive') throw new Error(path.relative(repoDir, file) + ': no directives on this page (::' + block.name + ')');
				const items = block.items.map((item) => '<li>' + item.map((part) => (part.type === 'p' ? inline(part.text) : '')).join(' ') + '</li>').join('');
				return block.type === 'ol' ? '<ol class="numbered">' + items + '</ol>' : '<ul class="points">' + items + '</ul>';
			})
			.join('');
	const section = (id, title, html) => '<section id="' + id + '" class="section wrap" aria-labelledby="' + id + '-heading"><h2 id="' + id + '-heading" class="section-label">' + escape(title) + '</h2>' + html + '</section>';
	const card = (title, text, links) => '<li class="card"><div class="card-head"><h3>' + escape(title) + '</h3></div><p>' + text + '</p><p class="card-links">' + links.map(([href, label]) => '<a href="' + href + '">' + escape(label) + '</a>').join(' · ') + '</p></li>';

	let body =
		'<section class="hero wrap" aria-labelledby="title"><p class="kicker">' + escape(meta.kicker) + '</p><h1 id="title">' + escape(PA_DOCKER.title) + '</h1>' +
		'<p class="role-line">' + inline(meta.tagline) + '</p>' +
		lead.blocks.map((block) => '<p class="lead">' + inline(block.text) + '</p>').join('') +
		'<p class="actions"><a class="button primary" href="/projects/pa-docker/setup/">Set it up</a><a class="button" href="' + github + '/tree/' + ref + '/docker">Source on GitHub</a></p>' +
		'<div class="callout" role="note"><p>' + inline(meta.callout) + '</p></div></section>';
	for (const { title, blocks: list } of rest) body += section(title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''), title, '<div class="steps">' + blocks(list) + '</div>');
	body += section(
		'whats-in-it',
		"What's in it",
		'<ul class="cards">' + tops.map((page) => card(page.title, page.summary, [[addressOf(page), 'Read more'], ...childrenOf(page).map((child) => [addressOf(child), child.label ?? child.title])])).join('') + '</ul>',
	);
	// The libraries that run these images in tests, and the client to talk to them
	const related = [];
	for (const project of PROJECTS.filter((p) => p.landing)) {
		const { meta: theirs } = parse(await readFile(path.join(siteDir, 'content', project.id + '.md'), 'utf8'));
		related.push(card(project.title, inline(theirs.card), [['/' + project.id + '/', 'Overview'], ['/' + project.id + '/docs/', 'Docs']]));
	}
	body += section('related', 'Related', '<ul class="cards">' + related.join('') + '</ul>');

	await writeFile(
		path.join(distDir, PA_DOCKER.id, 'index.html'),
		head({ title: PA_DOCKER.title + ' · Skipcheck', description: meta.description, canonical: CANONICAL_ORIGIN + root, styles: ['/styles.css', 'assets/landing.css'], scripts: [] }) +
			'<body><main id="main" class="project">' + body + '</main></body></html>\n',
	);
	await writeFile(path.join(distDir, PA_DOCKER.id, 'release.json'), JSON.stringify(version) + '\n');
	console.log('Docker pages: ' + PA_DOCKER.id + '/ and ' + pages.map((page) => page.path).join(', '));
}
