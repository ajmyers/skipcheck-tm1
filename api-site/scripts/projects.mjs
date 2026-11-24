// SPDX-License-Identifier: Apache-2.0

// Each project's entry for the project store, which skipcheck.io and ajmyers.net draw the projects'
// cards from (see ajmyers/skipcheck.io's README, "The project store"). Written to
// .generated/project-entries.json; the docs workflow checks them on every change and publishes
// them when it deploys (api-docs.yml).
//
// The entries are read from what the landing pages are built from, so a card says what the
// project's page says:
//
//   summary, description   content/<id>.md's front matter: card and description
//   visual                 the first example the page shows (::example), behind a tab per
//                          language, with what it printed if the live tests recorded it; for a
//                          project in one language, the first file it shows (::file), if any.
//                          The code is the same tested region the page shows.
//   version, release       the version the pages describe (docsVersion)
//
//   node scripts/projects.mjs

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parse } from './markdown.mjs';
import { exampleOutput } from './output.mjs';
import { docsVersion } from './packages.mjs';
import { generatedDir, languagesOf, PA_DOCKER, PROJECTS, regions, repoDir, siteDir } from './site.mjs';

const SITE = 'https://skipcheck.io';
const SITES = ['skipcheck.io', 'ajmyers.net'];

// Where each comes in the lists; the store holds other repos' projects too (tm1cm at 50)
const ORDER = { 'pa-docker': 10, 'tm1-client': 20, 'tm1-testcontainers': 30, 'tm1-spring-boot-starter': 40 };

// The command the setup page (skipcheck.io/pa-docker/setup/) ends with, without the file it writes
// first: keep the two in step
const PA_DOCKER_VISUAL = {
	kind: 'terminal',
	lines: [
		{ text: '# Start everything you picked', tone: 'comment' },
		{ text: 'curl -fsSL https://skipcheck.io/pa/setup.sh | sh -s -- up' },
	],
};

const directives = (source) => [...source.matchAll(/^\s*::(example|file)\s+(\S+)\s+(\S+)/gm)].map(([, name, a, b]) => ({ name, args: [a, b] }));

async function content(id) {
	const source = await readFile(path.join(siteDir, 'content', id + '.md'), 'utf8');
	const { meta } = parse(source);
	for (const key of ['card', 'description']) if (!meta[key]) throw new Error('content/' + id + '.md has no ' + key + ' in its front matter');
	return { meta, directives: directives(source) };
}

/** The visual of a library: its page's first example in each language, or the first file. */
async function visualOf(project, found) {
	const languages = languagesOf(project);
	const file = found.find((d) => d.name === 'file');
	const example = found.find((d) => d.name === 'example');
	if (languages.length === 1 && file) {
		const [where, lang] = file.args;
		const text = (await readFile(path.join(repoDir, where), 'utf8')).replace(/^[^\n]*SPDX-License-Identifier[^\n]*\n\s*/, '').trimEnd();
		return { kind: 'code', title: path.basename(where), lang, code: text };
	}
	if (!example) return undefined;
	const [cls, region] = example.args;
	const tabs = [];
	for (const language of languages) {
		const where = language.examples(project, cls);
		const code = (await regions(where)).get(region);
		if (code === undefined) throw new Error(project.id + ': no region ' + region + ' in ' + where);
		tabs.push({ label: language.label, title: path.basename(where), lang: language.highlight, code });
	}
	const { outputs } = await exampleOutput(project, cls, region);
	const printed = [...outputs.values()][0];
	const status = printed?.length ? { ok: true, text: printed[0], detail: 'recorded against a real TM1' } : undefined;
	if (tabs.length === 1) return { kind: 'code', title: tabs[0].title, lang: tabs[0].lang, code: tabs[0].code, ...(status && { status }) };
	return { kind: 'code-tabs', tabs, ...(status && { status }) };
}

/** Every project's entry, in list order. */
export async function projectEntries() {
	const version = await docsVersion();
	const github = 'https://github.com/' + /^githubRepo=(.+)$/m.exec(await readFile(path.join(repoDir, 'gradle.properties'), 'utf8'))[1].trim();
	const common = { version: version.version, release: version.release, sites: SITES };

	const docker = await content(PA_DOCKER.id);
	const entries = [
		{
			id: PA_DOCKER.id,
			name: PA_DOCKER.title,
			summary: docker.meta.card,
			description: docker.meta.description,
			kind: 'environment',
			status: 'available',
			...common,
			order: ORDER[PA_DOCKER.id],
			links: [
				{ label: 'Overview', href: SITE + '/pa-docker/' },
				{ label: 'Set it up', href: SITE + '/pa-docker/setup/' },
				{ label: 'Source', href: github },
			],
			visual: PA_DOCKER_VISUAL,
		},
	];
	for (const project of PROJECTS.filter((p) => p.landing)) {
		const page = await content(project.id);
		const visual = await visualOf(project, page.directives);
		entries.push({
			id: project.id,
			name: project.title,
			summary: page.meta.card,
			description: page.meta.description,
			kind: 'library',
			// Available once there's a release to install; until then, in the works
			status: version.release ? 'available' : 'in-the-works',
			...common,
			order: ORDER[project.id] ?? 100,
			links: [
				{ label: 'Overview', href: SITE + '/' + project.id + '/' },
				{ label: 'Docs', href: SITE + '/' + project.id + '/docs/' },
				{ label: 'Source', href: github },
			],
			...(visual && { visual }),
		});
	}
	return entries;
}

if (import.meta.url === 'file://' + process.argv[1]) {
	const entries = await projectEntries();
	await mkdir(generatedDir, { recursive: true });
	await writeFile(path.join(generatedDir, 'project-entries.json'), JSON.stringify(entries, null, '\t') + '\n');
	console.log('Project entries: ' + entries.map((e) => e.id + (e.visual ? ' (' + e.visual.kind + ')' : '')).join(', ') + ' (version ' + entries[0].version + (entries[0].release ? '' : ', pre-release') + ')');
}
