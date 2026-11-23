// SPDX-License-Identifier: Apache-2.0

// A project's walkthroughs: content/walkthroughs/<project>/<slug>.md, each a task done step by step,
// with its code from compiled examples and what the code printed. pages.mjs builds each one into
// /<project>/walkthroughs/<slug>/ and lists them on the landing page (::walkthroughs);
// reference.mjs lists them on the docs home in every language.
//
// A walkthrough's front matter has its title, a summary for the lists, a description for search
// engines and link previews, and its order among the project's walkthroughs.

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { parse } from './markdown.mjs';
import { repoDir, siteDir } from './site.mjs';

const cache = new Map();

/** The project's walkthroughs, in order: { slug, file, meta, sections }. */
export async function walkthroughsOf(project) {
	if (cache.has(project.id)) return cache.get(project.id);
	const dir = path.join(siteDir, 'content', 'walkthroughs', project.id);
	const list = [];
	for (const name of (await readdir(dir).catch(() => [])).filter((n) => n.endsWith('.md')).sort()) {
		const file = path.join(dir, name);
		let parsed;
		try {
			parsed = parse(await readFile(file, 'utf8'));
		} catch (e) {
			throw new Error(path.relative(repoDir, file) + ', ' + e.message, { cause: e });
		}
		const { meta, sections } = parsed;
		for (const key of ['title', 'summary', 'description', 'order']) {
			if (!meta[key]) throw new Error(path.relative(repoDir, file) + ' has no ' + key + ' in its front matter');
		}
		list.push({ slug: name.replace(/\.md$/, ''), file, meta, sections });
	}
	list.sort((a, b) => Number(a.meta.order) - Number(b.meta.order));
	cache.set(project.id, list);
	return list;
}
