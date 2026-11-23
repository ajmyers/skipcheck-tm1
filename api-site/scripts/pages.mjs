// SPDX-License-Identifier: Apache-2.0

// Builds each project's landing page, dist/<project>/index.html, from content/<project>.md. Run
// after assemble.mjs and guide.mjs.
//
// The content file holds the words and nothing else. What can be read from the repository is:
//
//   ::install                    how to install the project, per language, from each language's
//                                build files (packages.mjs)
//   ::example <Class> <region>   an example, in every language the project has: a region of that
//                                language's examples file, which its tests compile or type-check
//   ::file <path> <language>     a whole file from the repository, such as a configuration example
//
// A project in several languages shows these behind language tabs; one in a single language shows
// them plain. The page's look is the site's own (its /styles.css, loaded at view time, as its
// tokens are elsewhere), so a landing page here matches the pages the site writes by hand.
//
// It fails when an example or a file a page names isn't there.
//
//   node scripts/pages.mjs

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { inline, parse, slug } from './markdown.mjs';
import { docsVersion, installHtml, packageOf } from './packages.mjs';
import { CANONICAL_ORIGIN, codeBlock, copyAssets, distDir, DOC_LANGUAGES, escape, head, languagesOf, PROJECTS, regions, repoDir, siteDir, tabGroup } from './site.mjs';

const version = await docsVersion();
const github = 'https://github.com/' + /^githubRepo=(.+)$/m.exec(await readFile(path.join(repoDir, 'gradle.properties'), 'utf8'))[1].trim();

const figure = (caption, code, highlight) => '<figure class="code"><figcaption>' + escape(caption) + '</figcaption>' + codeBlock(code, highlight) + '</figure>';

/** One block per language: behind tabs when the project has several, plain when it has one. */
function perLanguage(project, tabs, plain) {
	return languagesOf(project).length > 1 ? tabGroup(tabs) : plain(tabs[0]);
}

async function directive(project, { name, args }) {
	if (name === 'install') {
		const tabs = [];
		for (const language of languagesOf(project)) {
			tabs.push({ language, html: installHtml(language.id, await packageOf(project, language.id), version, project.test) });
		}
		return perLanguage(project, tabs, (tab) => '<div class="tabs">' + tab.html + '</div>');
	}
	if (name === 'example') {
		const [cls, region] = args;
		if (!cls || !region) throw new Error(project.id + ': ::example needs a class and a region');
		const tabs = [];
		for (const language of languagesOf(project)) {
			const where = language.examples(project, cls);
			const code = (await regions(where)).get(region);
			if (code === undefined) throw new Error(project.id + ': no region ' + region + ' in ' + where);
			tabs.push({ language, code, html: codeBlock(code, language.highlight) });
		}
		return perLanguage(project, tabs, (tab) => figure(tab.language.label, tab.code, tab.language.highlight));
	}
	if (name === 'file') {
		const [file, highlight] = args;
		if (!file || !highlight) throw new Error(project.id + ': ::file needs a path and a language');
		const text = await readFile(path.join(repoDir, file), 'utf8');
		// Without the license line every source file starts with
		const code = text.replace(/^[^\n]*SPDX-License-Identifier[^\n]*\n\s*/, '').trimEnd();
		return figure(path.basename(file), code, highlight);
	}
	throw new Error(project.id + ': no such directive ::' + name);
}

/** Inline Markdown, with links into the docs pointed at a language: Java's, until tabs.js follows
 * the visitor's choice (data-docs lists the languages the docs have). */
function inlineDocs(project, text) {
	return inline(text).replace(/href="docs\/([A-Z][\w.]*\.html(?:#[\w-]+)?)"/g, (match, file) => 'href="docs/java/' + file + '" data-docs="' + DOC_LANGUAGES.filter((l) => project.languages.includes(l)).join(' ') + '"');
}

async function blocks(project, list, { inItem = false } = {}) {
	let html = '';
	for (const block of list) {
		if (block.type === 'p') html += '<p' + (inItem ? '' : ' class="more"') + '>' + inlineDocs(project, block.text) + '</p>';
		else if (block.type === 'directive') html += await directive(project, block);
		else {
			let items = '';
			for (const item of block.items) {
				// A bullet that is one paragraph is just its text, as the site writes its lists
				const single = item.length === 1 && item[0].type === 'p';
				items += '<li>' + (single ? inlineDocs(project, item[0].text) : await blocks(project, item, { inItem: true })) + '</li>';
			}
			html += block.type === 'ol' ? '<ol class="numbered">' + items + '</ol>' : '<ul class="points">' + items + '</ul>';
		}
	}
	return html;
}

const landings = [];
for (const project of PROJECTS.filter((p) => p.landing)) {
	const file = path.join(siteDir, 'content', project.id + '.md');
	const content = parse(await readFile(file, 'utf8'));
	for (const key of ['tagline', 'description', 'card']) {
		if (!content.meta[key]) throw new Error(path.relative(repoDir, file) + ' has no ' + key + ' in its front matter');
	}
	landings.push({ project, content });
}

/** The links every mention of a project carries: its page, its docs if it has them, its reference. */
function links(project) {
	const several = project.languages.length > 1;
	return [
		{ href: '/' + project.id + '/', label: 'Overview' },
		{ href: '/' + project.id + '/docs/', label: several ? 'Docs, in every language' : 'Docs' },
	];
}

function card(project, text) {
	const anchors = links(project)
		.map((link) => '<a href="' + link.href + '">' + link.label.replace(', in every language', '') + '</a>')
		.join(' · ');
	return '<li class="card"><div class="card-head"><h3>' + escape(project.title) + '</h3></div><p>' + inline(text) + '</p><p class="card-links">' + anchors + '</p></li>';
}

for (const { project, content } of landings) {
	const { meta, sections } = content;
	const [lead, ...rest] = sections;
	const kicker = [...languagesOf(project).map((language) => language.label), ...(meta.kicker ? [meta.kicker] : [])].join(' · ');
	const actions = [
		...links(project)
			.slice(1)
			.map((link, index) => '<a class="button' + (index === 0 ? ' primary' : '') + '" href="' + link.href + '">' + link.label + '</a>'),
		'<a class="button" href="' + github + '">Source on GitHub</a>',
	].join('');
	const callout = version.release
		? ''
		: '<p class="release-note" role="note"><span class="badge">Pre-release</span><span>No stable release yet, and the API can still change. These pages describe version <code>' +
			escape(version.version) +
			'</code>.</span></p>';

	let body =
		'<section class="hero wrap" aria-labelledby="title"><p class="kicker">' +
		escape(kicker) +
		'</p><h1 id="title">' +
		escape(project.title) +
		'</h1><p class="role-line">' +
		inline(meta.tagline) +
		'</p>' +
		lead.blocks.map((block) => '<p class="lead">' + inline(block.text) + '</p>').join('') +
		'<p class="actions">' +
		actions +
		'</p>' +
		callout +
		'</section>';
	for (const section of rest) {
		const id = slug(section.title);
		body +=
			'<section id="' + id + '" class="section wrap" aria-labelledby="' + id + '-heading"><h2 id="' + id + '-heading" class="section-label">' + escape(section.title) + '</h2><div class="steps">' +
			(await blocks(project, section.blocks)) +
			'</div></section>';
	}
	const related = landings.filter((other) => other.project !== project).map((other) => card(other.project, other.content.meta.card));
	body +=
		'<section id="related" class="section wrap" aria-labelledby="related-heading"><h2 id="related-heading" class="section-label">Related</h2><ul class="cards compact">' +
		related.join('') +
		'</ul></section>';

	const out = path.join(distDir, project.id);
	await mkdir(out, { recursive: true });
	await copyAssets(project);
	const several = languagesOf(project).length > 1;
	const page =
		head({
			title: project.title + ' · Skipcheck',
			description: meta.description,
			canonical: CANONICAL_ORIGIN + '/' + project.id + '/',
			styles: ['/styles.css', 'assets/tabs.css', 'assets/landing.css'],
			scripts: ['/highlight.js', ...(several ? ['assets/tabs.js'] : [])],
		}) +
		'<body><main id="main" class="project">' +
		body +
		'</main></body></html>\n';
	await writeFile(path.join(out, 'index.html'), page);
}
// Each project's release, for pages built elsewhere that show it (the site's home page reads
// /<project>/release.json): the version these pages describe, and whether it is a release
for (const project of PROJECTS) {
	await mkdir(path.join(distDir, project.id), { recursive: true });
	await writeFile(path.join(distDir, project.id, 'release.json'), JSON.stringify(version) + '\n');
}
console.log('Landing pages: ' + landings.map(({ project }) => project.id).join(', ') + ' (version ' + version.version + (version.release ? '' : ', pre-release') + ')');
