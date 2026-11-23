// SPDX-License-Identifier: Apache-2.0

// Writes the docs for coding agents, at the site's root, as the llms.txt convention has them
// (https://llmstxt.org):
//
//   dist/llms.txt       a title, a summary, then a section per project of links, each with a line
//                       saying what's there: the landing page, the docs in each language, the
//                       walkthroughs and the types to start with
//   dist/llms-full.txt  the full text of the landing pages, the walkthroughs and each type's
//                       overview, in Markdown
//
// Both come from the same sources as the pages: the content files, the examples and what they
// printed (pages.mjs), and the overviews reference.mjs rendered (.generated/reference.json). Every
// address in them is absolute, and check-links.mjs checks each one against dist/. Run after
// reference.mjs, pages.mjs and docker.mjs.
//
// In llms-full.txt an example is in every language, one after the other. A type's overview is
// Java's, as on its Java page, followed by the examples of the same overview in each other
// language: the prose is the same text in every language, so it's there once.
//
//   node scripts/llms.mjs

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { render as renderReadme } from './docker.mjs';
import { parse } from './markdown.mjs';
import { exampleOutput } from './output.mjs';
import { docsVersion, installCommands, installNote, packageOf } from './packages.mjs';
import { CANONICAL_ORIGIN, distDir, DOC_LANGUAGES, generatedDir, LANGUAGES, languagesOf, PA_DOCKER, PROJECTS, regions, repoDir, siteDir } from './site.mjs';
import { walkthroughsOf } from './walkthroughs.mjs';

const ORIGIN = CANONICAL_ORIGIN;
const SETUP = ORIGIN + '/projects/pa-docker/setup/';

const decode = (text) =>
	text
		.replaceAll('&lt;', '<')
		.replaceAll('&gt;', '>')
		.replaceAll('&quot;', '"')
		.replaceAll('&#39;', "'")
		.replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
		.replaceAll('&amp;', '&');

const fence = (code, language = '') => '```' + language + '\n' + code + '\n```';

/** HTML, as the docs pages write it, as Markdown: code blocks fenced, links absolute (base is the
 * page's address), lists as lists, every other tag dropped. */
export function markdownOf(html, base) {
	const blocks = [];
	let text = html.replace(/<pre[^>]*>\s*<code(?:\s+class="lang-([\w-]+)")?[^>]*>([\s\S]*?)<\/code>\s*<\/pre>/g, (_, language, code) => {
		blocks.push(fence(decode(code.replace(/<[^>]+>/g, '')), language ?? ''));
		return '\n\n\x00' + (blocks.length - 1) + '\x00\n\n';
	});
	text = text
		.replace(/<a\s[^>]*?href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/g, (_, href, label) => '[' + label + '](' + new URL(decode(href), base).href + ')')
		.replace(/<code[^>]*>([\s\S]*?)<\/code>/g, '`$1`')
		.replace(/<\/?(?:strong|b)>/g, '**')
		.replace(/<\/?(?:em|i)>/g, '*')
		.replace(/<li[^>]*>/g, '\n- ')
		.replace(/<h\d[^>]*>/g, '\n\n#### ')
		.replace(/<\/(?:h\d|ul|ol|p|div|dl|table|tr|dd)>|<(?:p|div|dl|table|br|dt|dd)\b[^>]*>/g, '\n\n')
		.replace(/<[^>]+>/g, '');
	text = decode(text);
	// A paragraph's lines are one line; a list's items stay one per line
	const paragraphs = text
		.split(/\n\s*\n/)
		.map((chunk) =>
			chunk
				.split('\n')
				.map((line) => line.trim())
				.filter(Boolean)
				.reduce((lines, line) => (line.startsWith('- ') || line.startsWith('\x00') || lines.length === 0 ? [...lines, line] : [...lines.slice(0, -1), lines.at(-1) + ' ' + line]), [])
				.join('\n'),
		)
		.filter(Boolean);
	return paragraphs.join('\n\n').replace(/\x00(\d+)\x00/g, (_, n) => blocks[Number(n)]);
}

/** A content file's inline Markdown, with its links absolute. A link into the docs goes to Java's,
 * as on the page before a language is chosen. */
function inlineText(text, base) {
	return text.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, href) => '[' + label + '](' + new URL(href.replace(/^docs\/(?=[A-Z])/, 'docs/java/'), base).href + ')');
}

const indent = (text, by) => text.replace(/^(?=.)/gm, by);

/** A directive of a content file, as Markdown. */
async function directive(project, { name, args }, base, version) {
	if (name === 'install') {
		const parts = [];
		for (const language of languagesOf(project)) {
			const pkg = await packageOf(project, language.id);
			const commands = installCommands(language.id, pkg.name, version, project.test).map((block) => block.caption + ':\n\n' + fence(block.code, block.highlight ?? 'xml'));
			const note = installNote(language.id, version);
			parts.push('**' + language.label + '** (needs ' + pkg.needs + '):\n\n' + commands.join('\n\n') + (note ? '\n\n' + note : ''));
		}
		return parts.join('\n\n');
	}
	if (name === 'example') {
		const [cls, region] = args;
		const { outputs } = await exampleOutput(project, cls, region, { required: false });
		const parts = [];
		for (const language of languagesOf(project)) {
			const code = (await regions(language.examples(project, cls))).get(region);
			if (code === undefined) throw new Error(project.id + ': no region ' + region + ' in ' + language.examples(project, cls));
			const printed = outputs.get(language.id) ?? [];
			parts.push(language.label + ':\n\n' + fence(code, language.highlight) + (printed.length > 0 ? '\n\nOutput:\n\n' + fence(printed.join('\n')) : ''));
		}
		return parts.join('\n\n');
	}
	if (name === 'file') {
		const [file, highlight] = args;
		const text = await readFile(path.join(repoDir, file), 'utf8');
		return path.basename(file) + ':\n\n' + fence(text.replace(/^[^\n]*SPDX-License-Identifier[^\n]*\n\s*/, '').trimEnd(), highlight);
	}
	if (name === 'walkthroughs') {
		return (await walkthroughsOf(project)).map((w) => '- [' + w.meta.title + '](' + ORIGIN + '/' + project.id + '/walkthroughs/' + w.slug + '/): ' + inlineText(w.meta.summary, base)).join('\n');
	}
	if (name === 'prompt') return 'A prompt to hand to a coding agent, which builds a first prototype, is on [the page](' + base + ').';
	throw new Error(project.id + ': no such directive ::' + name);
}

/** Blocks of a content file (markdown.mjs's parse), as Markdown. */
async function blocksText(project, blocks, base, version) {
	const parts = [];
	for (const block of blocks) {
		if (block.type === 'p') parts.push(inlineText(block.text, base));
		else if (block.type === 'directive') parts.push(await directive(project, block, base, version));
		else {
			const items = [];
			for (const [index, item] of block.items.entries()) {
				const marker = block.type === 'ol' ? index + 1 + '. ' : '- ';
				items.push(marker + indent(await blocksText(project, item, base, version), ' '.repeat(marker.length)).slice(marker.length));
			}
			parts.push(items.join('\n'));
		}
	}
	return parts.join('\n\n');
}

/** A content file's sections, each under a heading of the given level. */
async function sectionsText(project, sections, base, level, version) {
	const [lead, ...rest] = sections;
	const parts = [await blocksText(project, lead.blocks, base, version)];
	for (const section of rest) parts.push('#'.repeat(level) + ' ' + section.title + '\n\n' + (await blocksText(project, section.blocks, base, version)));
	return parts.filter(Boolean).join('\n\n');
}

const docsHome = (project, language) => ORIGIN + '/' + project.id + '/docs/' + language + '/';
const label = (id) => LANGUAGES.find((language) => language.id === id).label;
const plain = (html) => decode(html.replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim();

/** llms.txt and llms-full.txt, as { short, full }. overviews is .generated/reference.json. */
export async function llmsTexts(overviews, version) {
	const landings = [];
	for (const project of [...PROJECTS.filter((p) => p.landing), PA_DOCKER]) {
		landings.push({ project, ...parse(await readFile(path.join(siteDir, 'content', project.id + '.md'), 'utf8')) });
	}
	const pre = version.release ? '' : ', a pre-release: the API can still change';

	const short = [
		'# Skipcheck docs',
		'',
		'> Developer tools for IBM Planning Analytics (TM1): a typed client for the TM1 REST API in Java, Python, .NET and TypeScript, Testcontainers to test against a real TM1 in Docker, a Spring Boot starter, and Docker images that run Planning Analytics on your own computer.',
		'',
		'These docs describe version ' + version.version + pre + '. The four clients have the same services and methods, each named its language\'s way: modelCubes in Java and TypeScript, model_cubes in Python, ModelCubesAsync in .NET. ' +
			'Where TM1 behaves in a surprising way, the method it affects says so ("TM1 (11.8) ..."). The landing pages, the walkthroughs and every type\'s overview, in one file: ' + ORIGIN + '/llms-full.txt',
	];
	for (const { project, meta } of landings.filter((l) => l.project !== PA_DOCKER)) {
		const lines = ['- [' + project.title + '](' + ORIGIN + '/' + project.id + '/): ' + meta.card];
		for (const language of DOC_LANGUAGES.filter((id) => project.languages.includes(id))) {
			lines.push('- [Docs in ' + label(language) + '](' + docsHome(project, language) + '): every type, with its members and examples in ' + label(language));
		}
		for (const w of await walkthroughsOf(project)) lines.push('- [' + w.meta.title + '](' + ORIGIN + '/' + project.id + '/walkthroughs/' + w.slug + '/): ' + w.meta.summary);
		for (const name of project.start ?? []) {
			const entry = overviews.find((o) => o.project === project.id && o.language === 'java' && o.java === name);
			if (!entry) throw new Error(project.id + ': its start type ' + name + ' has no overview');
			lines.push('- [' + name + '](' + docsHome(project, 'java') + entry.page + '): ' + entry.summary);
		}
		short.push('', '## ' + project.title, '', ...lines);
	}
	{
		const { meta } = landings.find((l) => l.project === PA_DOCKER);
		const lines = ['- [' + PA_DOCKER.title + '](' + ORIGIN + '/' + PA_DOCKER.id + '/): ' + meta.card, '- [Setup page](' + SETUP + '): choose the parts, and get the one command that starts them, or a prompt for a coding agent'];
		for (const page of PA_DOCKER.pages) {
			const { title, summary } = renderReadme(await readFile(path.join(repoDir, page.source), 'utf8'), page.source);
			lines.push('- [' + title + '](' + ORIGIN + '/' + PA_DOCKER.id + '/' + page.path + '/): ' + plain(summary));
		}
		short.push('', '## ' + PA_DOCKER.title, '', ...lines);
	}
	{
		const lines = PROJECTS.filter((p) => !p.landing).map((project) => '- [' + project.title + '](' + docsHome(project, 'java') + '): ' + project.summary);
		for (const project of PROJECTS) lines.push('- [' + project.title + ' Javadoc](' + ORIGIN + '/' + project.id + '/api/java/): the Java API, as Javadoc');
		short.push('', '## Optional', '', ...lines);
	}

	const full = [
		'# Skipcheck docs, in full',
		'',
		'> The landing pages, the walkthroughs and every type\'s overview of the Skipcheck developer tools for IBM Planning Analytics (TM1), version ' + version.version + pre + '. Each type\'s members are on its page; ' + ORIGIN + '/llms.txt lists the pages.',
	];
	for (const { project, meta, sections } of landings) {
		const base = ORIGIN + '/' + project.id + '/';
		full.push('', '# ' + project.title, '', inlineText(meta.tagline, base) + '. From ' + base, '', await sectionsText(project, sections, base, 2, version));
		if (project === PA_DOCKER) continue;
		for (const walkthrough of await walkthroughsOf(project)) {
			const at = base + 'walkthroughs/' + walkthrough.slug + '/';
			full.push('', '## Walkthrough: ' + walkthrough.meta.title, '', 'From ' + at, '', await sectionsText(project, walkthrough.sections, base, 3, version));
		}
		full.push('', '## ' + project.title + ': the types', '', "Each type's overview, as on its page. Its members are on the page, in each language.");
		for (const java of overviews.filter((o) => o.project === project.id && o.language === 'java')) {
			const others = overviews.filter((o) => o.project === project.id && o.java === java.java && o.language !== 'java');
			const where = [java, ...others].map((o) => '[' + label(o.language) + '](' + docsHome(project, o.language) + o.page + ')').join(' · ');
			const parts = ['### ' + java.name, '', java.area + '. ' + where, '', markdownOf(java.overview, docsHome(project, 'java') + java.page)];
			for (const other of others) {
				const code = [...markdownOf(other.overview, docsHome(project, other.language) + other.page).matchAll(/^```[\s\S]*?^```$/gm)].map((m) => m[0]);
				if (code.length > 0) parts.push('', 'In ' + label(other.language) + (other.name === java.name ? '' : ', as `' + other.name + '`') + ':', '', code.join('\n\n'));
			}
			full.push('', parts.join('\n'));
		}
	}
	return { short: short.join('\n') + '\n', full: full.join('\n') + '\n' };
}

// The script runs when started, and only lends its functions when imported (by the tests)
if (import.meta.main) {
	const overviews = JSON.parse(await readFile(path.join(generatedDir, 'reference.json'), 'utf8'));
	const { short, full } = await llmsTexts(overviews, await docsVersion());
	await writeFile(path.join(distDir, 'llms.txt'), short);
	await writeFile(path.join(distDir, 'llms-full.txt'), full);
	const size = (text) => Math.round(Buffer.byteLength(text) / 1024) + ' KB';
	console.log('For agents: llms.txt (' + size(short) + '), llms-full.txt (' + size(full) + ')');
}
