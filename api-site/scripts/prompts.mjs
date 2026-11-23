// SPDX-License-Identifier: Apache-2.0

// The prompts a reader hands to a coding agent to build a first prototype: one per project with a
// landing page, and one for the Docker pages, in content/prompts/<project>.md. A prompt is plain
// text with placeholders, which are filled in from the build, so a prompt never names a package, a
// version or an address by hand:
//
//   {{site}}          the site's address (https://skipcheck.io)
//   {{overview}}      the project's landing page
//   {{docs}}          its docs: in the reader's language for a library, the Docker pages for pa-docker
//   {{llms}}          /llms.txt, and {{llms-full}}, /llms-full.txt: the docs for agents (llms.mjs)
//   {{version}}       the version the pages describe, and whether it's a pre-release
//   {{repository}}    the repository on GitHub
//   {{source}}        the repository's files, raw, at the release's tag (else master)
//   {{walkthroughs}}  the project's walkthroughs, as a list of addresses
//   {{skills}}        the agent plugin's skills (agent-plugin/skills), as a list of addresses
//
// and, in a library's prompt, for the reader's language:
//
//   {{language}}      its name (Java, Python...)
//   {{package}}       the package's name, from the language's build files (packages.mjs)
//   {{needs}}         what it needs, such as Java 21 or later
//   {{install}}       the commands that install it, as the landing page shows them
//
// A prompt file has no code or command written into it: what a reader runs comes from the build
// ({{install}}, from packages.mjs), as code on the pages comes from compiled examples. A fenced or
// indented block, inline code or an install command in the file fails the build (codeProblems).
//
// pages.mjs and docker.mjs show a prompt where its content file says ::prompt, collapsed, with a
// copy button and the links that open it in Claude, as the setup page (ajmyers/skipcheck.io) does.
// check-links.mjs checks every address in every prompt, and that every install command in one is
// what packages.mjs gives.

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { docsVersion, installCommands, installNote, packageOf } from './packages.mjs';
import { CANONICAL_ORIGIN, codeBlock, escape, languagesOf, PA_DOCKER, PROJECTS, repoDir, siteDir } from './site.mjs';
import { walkthroughsOf } from './walkthroughs.mjs';

const repository = /^githubRepo=(.+)$/m.exec(await readFile(path.join(repoDir, 'gradle.properties'), 'utf8'))[1].trim();

/** The projects that have a prompt: the libraries with a landing page, and the Docker pages. */
export const PROMPTED = [...PROJECTS.filter((project) => project.landing), PA_DOCKER];

const promptFile = (project) => path.join(siteDir, 'content', 'prompts', project.id + '.md');

/** The agent plugin's skills, in order of name: { name, summary, address }. summary is the first
 * sentence of the skill's description. */
export async function skills(ref) {
	const dir = path.join(repoDir, 'agent-plugin', 'skills');
	const list = [];
	for (const name of (await readdir(dir)).sort()) {
		const file = path.join('agent-plugin', 'skills', name, 'SKILL.md');
		const text = await readFile(path.join(repoDir, file), 'utf8');
		const description = /^description:\s*(.+)$/m.exec(text)?.[1];
		if (!description) throw new Error(file + ' has no description in its front matter');
		list.push({ name, summary: /^.*?\.(?=\s|$)/.exec(description)[0], address: rawAddress(ref, file) });
	}
	return list;
}

const rawAddress = (ref, file) => 'https://raw.githubusercontent.com/' + repository + '/' + ref + '/' + file;

/** The commands that install a package, as a prompt shows them: each under its caption, fenced. */
function installText(language, pkg, version, test) {
	const fence = { kotlin: 'kotlin', sh: 'sh', null: 'xml' };
	const blocks = installCommands(language, pkg.name, version, test).map((block) => block.caption + ':\n\n```' + fence[block.highlight] + '\n' + block.code + '\n```');
	const note = installNote(language, version);
	return blocks.join('\n\n') + (note ? '\n\n' + note + ' The source is at https://github.com/' + repository + '.' : '');
}

/** A project's prompt for one language (null for the Docker pages, which have none), as text. */
export async function promptText(project, language, { template, version } = {}) {
	version ??= await docsVersion();
	template ??= await readFile(promptFile(project), 'utf8');
	const ref = version.release ? 'v' + version.version : 'master';
	const library = PROJECTS.includes(project);
	const values = {
		site: () => CANONICAL_ORIGIN,
		overview: () => CANONICAL_ORIGIN + '/' + project.id + '/',
		docs: () => CANONICAL_ORIGIN + '/' + project.id + '/' + (library ? 'docs/' + language + '/' : ''),
		llms: () => CANONICAL_ORIGIN + '/llms.txt',
		'llms-full': () => CANONICAL_ORIGIN + '/llms-full.txt',
		version: () => version.version + (version.release ? '' : ' (a pre-release: the API can still change, and the install commands get the latest development build)'),
		repository: () => 'https://github.com/' + repository,
		source: () => 'https://raw.githubusercontent.com/' + repository + '/' + ref,
		walkthroughs: async () => {
			const list = library ? await walkthroughsOf(project) : [];
			if (list.length === 0) throw new Error(project.id + ' has no walkthroughs');
			return list.map((w) => '- ' + w.meta.title + ': ' + CANONICAL_ORIGIN + '/' + project.id + '/walkthroughs/' + w.slug + '/').join('\n');
		},
		skills: async () => (await skills(ref)).map((skill) => '- ' + skill.name + ': ' + skill.address + '\n  ' + skill.summary).join('\n'),
	};
	const label = library && language ? languagesOf(project).find((l) => l.id === language)?.label : null;
	if (library && !label) throw new Error(project.id + ' is not in ' + language);
	if (label) {
		const pkg = await packageOf(project, language);
		Object.assign(values, {
			language: () => label,
			package: () => pkg.name,
			needs: () => pkg.needs,
			install: () => installText(language, pkg, version, project.test),
		});
	}
	const written = codeProblems(template);
	if (written.length > 0) throw new Error(path.relative(repoDir, promptFile(project)) + ' has code written into it, which must come from the build:\n  ' + written.join('\n  '));
	let text = template;
	for (const [, name] of template.matchAll(/\{\{([\w-]+)\}\}/g)) {
		if (!values[name]) throw new Error(path.relative(repoDir, promptFile(project)) + ': no {{' + name + '}}' + (library ? '' : ' in a prompt without a language'));
		text = text.replaceAll('{{' + name + '}}', await values[name]());
	}
	return text.trim() + '\n';
}

/** Every prompt: { project, language, text }, one per language a library has, one for the Docker pages. */
export async function allPrompts() {
	const all = [];
	for (const project of PROMPTED) {
		const languages = PROJECTS.includes(project) ? languagesOf(project).map((l) => l.id) : [null];
		for (const language of languages) all.push({ project, language, text: await promptText(project, language) });
	}
	return all;
}

// What reads as an install command, in any language, Maven's lines included
const INSTALL = /\b(?:pip3? install|npm (?:install|i|add)|pnpm add|yarn add|dotnet add package|(?:test)?[Ii]mplementation\(|<(?:groupId|artifactId|version|scope)>)/;

/** The code written into a prompt file, by line: a fenced or indented block, inline code, an
 * install command or a shell variable. Empty when there is none. */
export function codeProblems(template) {
	const problems = [];
	for (const [index, line] of template.split('\n').entries()) {
		const at = 'line ' + (index + 1) + ': ';
		if (/^\s*(```|~~~)/.test(line)) problems.push(at + 'a fenced code block');
		else if (/^( {4}|\t)\S/.test(line)) problems.push(at + 'an indented code block');
		else if (/`[^`]+`/.test(line)) problems.push(at + 'inline code');
		else if (INSTALL.test(line)) problems.push(at + 'an install command');
		else if (/\$\{|\$\(/.test(line)) problems.push(at + 'a shell variable or command');
	}
	return problems;
}

/** What's wrong with a prompt's install commands: one that isn't the one packages.mjs gives for a
 * project and language, or, in a library's prompt, its own command missing. */
export async function commandProblems({ project, language, text }, version) {
	version ??= await docsVersion();
	const known = new Set();
	for (const other of PROJECTS) {
		for (const l of languagesOf(other)) {
			for (const block of installCommands(l.id, (await packageOf(other, l.id)).name, version, other.test)) for (const line of block.code.split('\n')) known.add(line.trim());
		}
	}
	const where = project.id + (language ? ' (' + language + ')' : '');
	const problems = text
		.split('\n')
		.map((line) => line.trim().replace(/^`|`$/g, ''))
		.filter((line) => INSTALL.test(line) && !known.has(line))
		.map((line) => where + ': "' + line + '" is not an install command packages.mjs gives');
	if (language) {
		const own = installCommands(language, (await packageOf(project, language)).name, version, project.test);
		for (const block of own) if (!text.includes(block.code)) problems.push(where + ': no ' + block.caption + ' install command');
	}
	return problems;
}

/** A prompt on a page: the text, collapsed until the reader opens it, which the site's copy.js gives
 * a Copy button that copies it whole while closed, and the links that open it in Claude. The text is
 * in the site's <details class="prompt"> (ajmyers/skipcheck.io, styles.css), as on the setup page,
 * so it opens without a script, and shows its first lines while closed where the browser can. They are links with custom schemes, as on the setup page (ajmyers/skipcheck.io,
 * public/pa-docker/setup/): claude://code/new?q= opens Claude Desktop's Code tab, and
 * claude-cli://open?q= the Claude Code CLI, which works once claude has run interactively. Both
 * fill the prompt in without sending it. */
export function promptHtml(text) {
	const q = encodeURIComponent(text);
	return (
		'<details class="prompt"><summary>Prompt <span class="prompt-toggle"><span class="prompt-show">Show the full prompt</span><span class="prompt-hide">Hide the prompt</span></span></summary>' +
		codeBlock(text, null) +
		'</details>' +
		'<p class="actions"><a class="button primary" href="' + escape('claude://code/new?q=' + q) + '">Open in Claude Desktop</a>' +
		'<a class="button" href="' + escape('claude-cli://open?q=' + q) + '">Open in Claude Code</a></p>' +
		'<p class="needs">Either link fills in the prompt, and you press Enter to send it. "Open in Claude Code" is for the terminal app, and works once you\'ve run <code>claude</code> on this computer. ' +
		'Another agent, such as Codex, Cursor or Gemini CLI? Copy the prompt.</p>'
	);
}
