// SPDX-License-Identifier: Apache-2.0

// Writes dist/docs/sitemap.xml: the address of every page these scripts generate, for search
// engines. It lists what check-links.mjs checks: the landing pages, the walkthroughs, the docs in
// every language, the TM1 types, the docs landing page and the Docker pages. Not the Javadoc,
// whose thousands of pages javadoc.io serves for every release, nor a docs chooser
// (/<project>/docs/), which only sends the reader on to a language.
//
// It's served at /docs/sitemap.xml, under a route the Worker already has. A sitemap may list
// addresses outside its own folder when the site's robots.txt names it, so skipcheck.io's
// robots.txt (in ajmyers/skipcheck.io) needs the line "Sitemap: https://skipcheck.io/docs/sitemap.xml".
// Run after the pages are built.
//
//   node scripts/sitemap.mjs

import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { CANONICAL_ORIGIN, distDir, escape } from './site.mjs';

/** A page's address, from its file's path in dist/: a folder's index.html is the folder. */
export const addressOf = (file) => CANONICAL_ORIGIN + '/' + file.split(path.sep).join('/').replace(/(^|\/)index\.html$/, '$1');

/** The sitemap of a list of addresses, in the order given. */
export const sitemapXml = (addresses) =>
	'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
	addresses.map((address) => '  <url><loc>' + escape(address) + '</loc></url>\n').join('') +
	'</urlset>\n';

async function* pages(dir) {
	for (const entry of (await readdir(dir, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
		const full = path.join(dir, entry.name);
		// The Javadoc: /<project>/api/
		if (entry.isDirectory() && !(entry.name === 'api' && path.dirname(full) !== distDir)) yield* pages(full);
		else if (entry.isFile() && entry.name.endsWith('.html')) yield full;
	}
}

if (import.meta.main) {
	const addresses = [];
	for await (const file of pages(distDir)) {
		if ((await readFile(file, 'utf8')).includes(' data-choose')) continue;
		addresses.push(addressOf(path.relative(distDir, file)));
	}
	await writeFile(path.join(distDir, 'docs', 'sitemap.xml'), sitemapXml(addresses));
	console.log('Sitemap: ' + addresses.length + ' pages, at /docs/sitemap.xml');
}
