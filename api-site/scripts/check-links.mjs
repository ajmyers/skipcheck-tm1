// SPDX-License-Identifier: Apache-2.0

// Checks the links of the pages this site generates: every landing page and docs page in dist/,
// every address in the docs for agents (llms.txt and llms-full.txt, from llms.mjs), and every
// address and install command in the prompts for coding agents (prompts.mjs).
// A link to a path this site serves (a project's, the Docker pages', the docs landing page's)
// must be a file in dist/; any other local link must be one of the site's own addresses listed
// below, since those pages live in ajmyers/skipcheck.io and can't be seen from here. Run after the pages are built; it fails with the list of broken links.
//
// In the docs for agents every address is absolute: one on the site is checked as a link would be,
// one to a file of this repository on GitHub must be a file here, and any other is left alone.
//
// Each docs language's search index (search.json, search.mjs) is checked the same way: every type
// and member it lists must be on a page there, and every TM1 type a page of its own. The Docker
// pages' index too: every page it lists, and every heading at its anchor.
//
// The Javadoc is left out: its links are javadoc's own, and there are thousands of them.
//
//   node scripts/check-links.mjs

import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { allPrompts, commandProblems } from './prompts.mjs';
import { CANONICAL_ORIGIN, distDir, PA_DOCKER, PROJECTS, repoDir, SECTIONS } from './site.mjs';

const repository = /^githubRepo=(.+)$/m.exec(await readFile(path.join(repoDir, 'gradle.properties'), 'utf8'))[1].trim();

// What these pages may link to on the site itself. Add an address here when a page starts to use
// it, after checking that the site serves it. One under a section's path must be among the
// section's site paths (site in site.mjs), or the Worker wouldn't pass it through to the site.
const SITE_PATHS = new Set(['/', '/styles.css', '/tokens.css', '/chrome.css', '/code.css', '/highlight.js', '/favicon.svg', '/pa-docker/setup/']);

const exists = (file) =>
	stat(file).then(
		(found) => found.isFile(),
		() => false,
	);

async function* htmlFiles(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true }).catch(() => [])) {
		const full = path.join(dir, entry.name);
		// The Javadoc: /<project>/api/
		if (entry.isDirectory() && !(entry.name === 'api' && path.dirname(full) !== distDir)) yield* htmlFiles(full);
		else if (entry.isFile() && entry.name.endsWith('.html')) yield full;
	}
}

const broken = [];
let checked = 0;
for await (const file of htmlFiles(distDir)) {
	const html = await readFile(file, 'utf8');
	const page = '/' + path.relative(distDir, file).split(path.sep).join('/');
	const ids = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]));
	for (const [, raw] of html.matchAll(/<(?:a|link|script|img)\b[^>]*?\s(?:href|src)="([^"]*)"/g)) {
		const link = raw.replaceAll('&amp;', '&');
		if (/^[a-z][a-z0-9+.-]*:/i.test(link)) continue; // another site
		checked++;
		const target = new URL(link, 'https://site.invalid' + page);
		if (target.pathname === page && target.hash) {
			if (!ids.has(decodeURIComponent(target.hash.slice(1)))) broken.push(page + ': no #' + target.hash.slice(1) + ' on the page');
			continue;
		}
		const problem = await onSite(target);
		if (problem) broken.push(page + ': ' + link + problem);
	}
}

/** What's wrong with a link to an address on the site (a URL), or null. */
async function onSite(target) {
	const section = SECTIONS.find((s) => target.pathname === '/' + s.id || target.pathname.startsWith('/' + s.id + '/'));
	const sitePage = section?.site?.some((page) => target.pathname === '/' + section.id + '/' + page || target.pathname.startsWith('/' + section.id + '/' + page + '/'));
	if (!section || sitePage) return SITE_PATHS.has(target.pathname) ? null : " isn't one of the site's addresses in check-links.mjs";
	const asset = path.join(distDir, target.pathname.endsWith('/') ? target.pathname + 'index.html' : target.pathname);
	if (!(await exists(asset))) return ' (no ' + path.relative(distDir, asset) + ' in dist)';
	if (target.hash && asset.endsWith('.html')) {
		const there = await readFile(asset, 'utf8');
		if (!there.includes(' id="' + decodeURIComponent(target.hash.slice(1)) + '"')) return ' (no such id on that page)';
	}
	return null;
}

/** What's wrong with an absolute address in a text, or null: one on the site must be there, one to
 * a file of this repository on GitHub (raw, or a blob or tree) must be a file or folder here. */
async function checkAddress(address) {
	const url = new URL(address);
	if (url.origin === CANONICAL_ORIGIN) return onSite(url);
	const github =
		(url.host === 'raw.githubusercontent.com' && new RegExp('^/' + repository + '/[^/]+/(.+)$').exec(url.pathname)) ||
		(url.host === 'github.com' && new RegExp('^/' + repository + '/(?:blob|tree)/[^/]+/(.+)$').exec(url.pathname));
	if (!github) return null;
	return (await stat(path.join(repoDir, decodeURIComponent(github[1]))).then(() => true, () => false)) ? null : ' (no ' + github[1] + ' in the repository)';
}

/** The absolute addresses in a text, without the punctuation that ends a sentence or a Markdown link. */
const addressesIn = (text) => [...text.matchAll(/https?:\/\/[^\s<>"'`)\]]+/g)].map((match) => match[0].replace(/[.,;:!?]+$/, ''));

// The prompts: their addresses, and that every install command is packages.mjs's
for (const prompt of await allPrompts()) {
	const where = 'the ' + prompt.project.id + ' prompt' + (prompt.language ? ' in ' + prompt.language : '');
	for (const address of new Set(addressesIn(prompt.text))) {
		checked++;
		const problem = await checkAddress(address);
		if (problem) broken.push(where + ': ' + address + problem);
	}
	broken.push(...(await commandProblems(prompt)));
}

// The docs for agents
for (const name of ['llms.txt', 'llms-full.txt']) {
	const text = await readFile(path.join(distDir, name), 'utf8').catch(() => null);
	if (text === null) {
		broken.push('/' + name + ' is not in dist: run scripts/llms.mjs');
		continue;
	}
	for (const address of new Set(addressesIn(text))) {
		checked++;
		const problem = await checkAddress(address);
		if (problem) broken.push('/' + name + ': ' + address + problem);
	}
}
for (const project of PROJECTS) {
	for (const entry of await readdir(path.join(distDir, project.id, 'docs'), { withFileTypes: true }).catch(() => [])) {
		const file = path.join(distDir, project.id, 'docs', entry.name, 'search.json');
		if (!entry.isDirectory() || !(await exists(file))) continue;
		const where = '/' + path.relative(distDir, file).split(path.sep).join('/');
		const index = JSON.parse(await readFile(file, 'utf8'));
		const pages = new Map();
		const idsOf = async (page) => {
			if (!pages.has(page)) {
				const html = await readFile(path.join(path.dirname(file), page), 'utf8').catch(() => null);
				pages.set(page, html === null ? null : new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1])));
			}
			return pages.get(page);
		};
		for (const [name, page] of index.types) {
			checked++;
			if (!(await idsOf(page))) broken.push(where + ': ' + name + ' is on ' + page + ', which is not there');
		}
		for (const [name, page] of index.model ?? []) {
			checked++;
			if (!(await idsOf(page))) broken.push(where + ': the TM1 type ' + name + ' is on ' + page + ', which is not there');
		}
		for (const [name, type, , anchor = name] of index.members) {
			checked++;
			const page = index.types[type][1];
			if (!(await idsOf(page))?.has(anchor)) broken.push(where + ': ' + name + ' is at ' + page + '#' + anchor + ', which is not there');
		}
	}
}
// The Docker pages' index (headingIndex in search.mjs): every page there, every heading on it
{
	const where = '/' + PA_DOCKER.id + '/search.json';
	const index = JSON.parse(await readFile(path.join(distDir, PA_DOCKER.id, 'search.json'), 'utf8').catch(() => 'null'));
	if (!index) broken.push(where + ' is not in dist: run scripts/docker.mjs');
	else {
		const ids = [];
		for (const [title, address] of index.pages) {
			checked++;
			const html = address.startsWith(path.posix.join('/', PA_DOCKER.id, '/')) ? await readFile(path.join(distDir, ...address.split('/'), 'index.html'), 'utf8').catch(() => null) : null;
			if (html === null) broken.push(where + ': ' + title + ' is at ' + address + ', which is not a Docker page');
			ids.push(new Set([...(html ?? '').matchAll(/\sid="([^"]+)"/g)].map((match) => match[1])));
		}
		for (const [text, page, id] of index.headings) {
			checked++;
			if (!ids[page]?.has(id)) broken.push(where + ': ' + text + ' is at ' + index.pages[page]?.[1] + '#' + id + ', which is not there');
		}
	}
}
if (broken.length > 0) throw new Error(broken.length + ' broken link(s):\n  ' + broken.join('\n  '));
console.log('Links: ' + checked + ' checked');
