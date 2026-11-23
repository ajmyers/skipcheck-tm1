// SPDX-License-Identifier: Apache-2.0

// Tests of the docs for agents (scripts/llms.mjs): llms.txt in the llms.txt convention's shape, and
// the docs pages' HTML as Markdown in llms-full.txt. They read this repository's content files.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { llmsTexts, markdownOf } from '../scripts/llms.mjs';
import { PROJECTS } from '../scripts/site.mjs';

test("a docs page's HTML is Markdown: code fenced, links absolute, lists kept, other tags dropped", () => {
	const html =
		'Cubes: <code>/Cubes</code>, and\n <a href="ViewService.html#create"><code>create</code></a> on <strong>views</strong>.<p>A list:<ul><li>one</li><li>two &amp; more</li></ul>' +
		'<pre data-at="x"><code class="lang-java">if (a &lt; b) {\n    go();\n}</code></pre><span class="tag">x</span>';
	assert.equal(
		markdownOf(html, 'https://skipcheck.io/tm1-client/docs/java/CubeService.html'),
		'Cubes: `/Cubes`, and [`create`](https://skipcheck.io/tm1-client/docs/java/ViewService.html#create) on **views**.\n\nA list:\n- one\n- two & more\n\n```java\nif (a < b) {\n    go();\n}\n```\n\nx',
	);
});

test('llms.txt is a title, a summary, then sections of links each saying what is there', async () => {
	// Each project's start types, as reference.mjs writes them
	const overviews = PROJECTS.flatMap((project) =>
		(project.start ?? []).map((name) => ({ project: project.id, language: 'java', java: name, name, page: name + '.html', area: 'Area', summary: 'What ' + name + ' is.', overview: '<p>About ' + name + '.</p>' })),
	);
	const { short, full } = await llmsTexts(overviews, { version: '1.2.3', release: true });
	const lines = short.trimEnd().split('\n');
	assert.match(lines[0], /^# \S/);
	assert.equal(lines[1], '');
	assert.match(lines[2], /^> \S/);
	const sections = lines.filter((line) => line.startsWith('## '));
	assert.deepEqual(sections, ['## TM1 client', '## TM1 Testcontainers', '## TM1 Spring Boot starter', '## Planning Analytics in Docker', '## Optional']);
	for (const line of lines.slice(lines.indexOf(sections[0]))) {
		if (line !== '' && !line.startsWith('## ')) assert.match(line, /^- \[[^\]]+\]\(https:\/\/skipcheck\.io\/[^)]*\): \S/, line);
	}
	assert.ok(short.includes('version 1.2.3.'), 'the version');
	assert.ok(short.includes('(https://skipcheck.io/tm1-client/docs/java/CubeService.html): What CubeService is.'), 'a start type, with its summary');

	assert.match(full, /^# Skipcheck docs, in full\n\n> /);
	assert.ok(full.includes('\n# TM1 client\n') && full.includes('\n## Walkthrough: Connect and list your cubes\n'), 'a landing page and a walkthrough');
	assert.ok(full.includes('pip install skipcheck-tm1==1.2.3'), 'the install steps, at the version');
	assert.ok(full.includes('\n### CubeService\n') && full.includes('About CubeService.'), "a type's overview");
	assert.ok(!/^\s*::\w+/m.test(full), 'every directive is replaced');
});
