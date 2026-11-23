// SPDX-License-Identifier: Apache-2.0

// Tests of the prompts for coding agents (scripts/prompts.mjs): every prompt fills in from the
// build, has no code written into it, and installs what packages.mjs says.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { test } from 'node:test';
import { allPrompts, codeProblems, commandProblems, PROMPTED, promptHtml, promptText } from '../scripts/prompts.mjs';
import { PROJECTS, siteDir } from '../scripts/site.mjs';

const release = { version: '1.2.3', release: true };

test('every landing page has a prompt, and says where it goes', async () => {
	for (const project of PROMPTED) {
		const content = await readFile(path.join(siteDir, 'content', project.id + '.md'), 'utf8');
		assert.match(content, /^::prompt$/m, project.id + ' has no ::prompt');
	}
});

test('a prompt file has no code or command written into it', async () => {
	for (const project of PROMPTED) assert.deepEqual(codeProblems(await readFile(path.join(siteDir, 'content', 'prompts', project.id + '.md'), 'utf8')), [], project.id);
	assert.deepEqual(
		codeProblems(['Run this:', '```sh', 'pip install skipcheck-tm1', '```', '    tm1.cubes()', 'Then `npm test`.', 'npm install left-pad', 'password: ${TM1_PASSWORD}'].join('\n')).map((p) => p.replace(/^line \d+: /, '')),
		['a fenced code block', 'an install command', 'a fenced code block', 'an indented code block', 'inline code', 'an install command', 'a shell variable or command'],
	);
	const client = PROJECTS.find((p) => p.id === 'tm1-client');
	await assert.rejects(promptText(client, 'python', { template: 'Run `pip install x`.', version: release }), /has code written into it/);
});

test('a prompt is filled in from the build: package, install command, version and addresses', async () => {
	const client = PROJECTS.find((p) => p.id === 'tm1-client');
	const text = await promptText(client, 'python', { version: release });
	assert.ok(text.includes('skipcheck-tm1, version 1.2.3'), text);
	assert.ok(text.includes('pip install skipcheck-tm1==1.2.3'), text);
	assert.ok(text.includes('https://skipcheck.io/tm1-client/docs/python/'), text);
	assert.ok(text.includes('https://skipcheck.io/tm1-client/walkthroughs/connect/'), text);
	assert.ok(text.includes('https://raw.githubusercontent.com/ajmyers/skipcheck-tm1/v1.2.3/agent-plugin/skills/tm1-setup/SKILL.md'), text);
	assert.ok(!/\{\{/.test(text), text);
	await assert.rejects(promptText(client, 'python', { template: 'See {{nothing}}.', version: release }), /no \{\{nothing\}\}/);
	const docker = PROMPTED.find((p) => p.id === 'pa-docker');
	await assert.rejects(promptText(docker, null, { template: 'Install {{package}}.', version: release }), /without a language/);
});

test("every prompt's install commands are packages.mjs's, and its own is there", async () => {
	for (const prompt of await allPrompts()) assert.deepEqual(await commandProblems(prompt), [], prompt.project.id + ' ' + prompt.language);
	const client = PROJECTS.find((p) => p.id === 'tm1-client');
	const problems = await commandProblems({ project: client, language: 'python', text: 'pip install skipcheck-tm1-typo' }, release);
	assert.ok(problems.some((p) => p.includes('"pip install skipcheck-tm1-typo" is not an install command')), problems.join('; '));
	assert.ok(problems.some((p) => p.includes('no Terminal install command')), problems.join('; '));
});

test('a prompt on a page opens in Claude Desktop and Claude Code, filled in', () => {
	const html = promptHtml('Build "this" & that\n');
	assert.ok(html.includes('<pre class="prompt"><code>Build &quot;this&quot; &amp; that\n</code></pre>'), html);
	assert.ok(html.includes('href="claude://code/new?q=Build%20%22this%22%20%26%20that%0A"'), html);
	assert.ok(html.includes('href="claude-cli://open?q=Build%20%22this%22%20%26%20that%0A"'), html);
});
