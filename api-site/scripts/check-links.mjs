// SPDX-License-Identifier: Apache-2.0

// Checks the links of the pages this site generates: every landing page and docs page in dist/.
// A link to a path this site serves (a project's, the Docker pages', the docs landing page's)
// must be a file in dist/; any other local link must be one of the site's own addresses listed
// below, since those pages live in ajmyers/skipcheck.io and can't be seen from here. Run after the pages are built; it fails with the list of broken links.
//
// The Javadoc is left out: its links are javadoc's own, and there are thousands of them.
//
//   node scripts/check-links.mjs

import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { distDir, SECTIONS } from './site.mjs';

// What these pages may link to on the site itself. Add an address here when a page starts to use
// it, after checking that the site serves it.
const SITE_PATHS = new Set(['/', '/styles.css', '/tokens.css', '/chrome.css', '/code.css', '/highlight.js', '/favicon.svg', '/projects/pa-docker/', '/projects/pa-docker/setup/']);

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
		const section = SECTIONS.find((s) => target.pathname === '/' + s.id || target.pathname.startsWith('/' + s.id + '/'));
		if (!section) {
			if (!SITE_PATHS.has(target.pathname)) broken.push(page + ': ' + link + " isn't one of the site's addresses in check-links.mjs");
			continue;
		}
		const asset = path.join(distDir, target.pathname.endsWith('/') ? target.pathname + 'index.html' : target.pathname);
		if (!(await exists(asset))) {
			broken.push(page + ': ' + link + ' (no ' + path.relative(distDir, asset) + ' in dist)');
		} else if (target.hash && asset.endsWith('.html')) {
			const there = await readFile(asset, 'utf8');
			if (!there.includes(' id="' + decodeURIComponent(target.hash.slice(1)) + '"')) broken.push(page + ': ' + link + ' (no such id on that page)');
		}
	}
}
if (broken.length > 0) throw new Error(broken.length + ' broken link(s):\n  ' + broken.join('\n  '));
console.log('Links: ' + checked + ' checked');
