// SPDX-License-Identifier: Apache-2.0

// Tests of what builds the pages (scripts/): the content files' Markdown, the install steps read
// from each language's build files, and that every project has its routes. They read this
// repository's own files, so a package renamed in its build, or a project added without a route,
// fails here.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { inline, parse, slug } from '../scripts/markdown.mjs';
import { docsVersion, installCommands, packageOf } from '../scripts/packages.mjs';
import { languagesOf, PROJECTS, regions } from '../scripts/site.mjs';

test('inline Markdown is escaped, then marked up', () => {
	assert.equal(inline('**Bold** and `a<b>` with [a link](docs/X.html) & more'), '<strong>Bold</strong> and <code>a&lt;b&gt;</code> with <a href="docs/X.html">a link</a> &amp; more');
});

test('a content file is front matter, a lead and sections of paragraphs, lists and directives', () => {
	const { meta, sections } = parse(
		['---', 'tagline: Short', 'card: For the cards', '---', 'The lead,', 'on two lines.', '', '## Quick start', '', '1. **First.** Do this.', '   ::install', '   Then this.', '2. Second', '   ::example HelloWorld hello-world', '', 'After the list.', '', '## Parts', '- one', '- two', ''].join('\n'),
	);
	assert.deepEqual(meta, { tagline: 'Short', card: 'For the cards' });
	assert.deepEqual(sections, [
		{ title: null, blocks: [{ type: 'p', text: 'The lead, on two lines.' }] },
		{
			title: 'Quick start',
			blocks: [
				{
					type: 'ol',
					items: [
						[
							{ type: 'p', text: '**First.** Do this.' },
							{ type: 'directive', name: 'install', args: [] },
							{ type: 'p', text: 'Then this.' },
						],
						[
							{ type: 'p', text: 'Second' },
							{ type: 'directive', name: 'example', args: ['HelloWorld', 'hello-world'] },
						],
					],
				},
				{ type: 'p', text: 'After the list.' },
			],
		},
		{ title: 'Parts', blocks: [{ type: 'ul', items: [[{ type: 'p', text: 'one' }], [{ type: 'p', text: 'two' }]] }] },
	]);
	assert.equal(slug("What's in it"), 'what-s-in-it');
});

test('every project is installed under the name its own build gives it', async () => {
	const client = PROJECTS.find((project) => project.id === 'tm1-client');
	const names = {};
	for (const language of languagesOf(client)) names[language.id] = (await packageOf(client, language.id)).name;
	assert.deepEqual(names, { java: 'io.skipcheck:tm1-client', python: 'skipcheck-tm1', dotnet: 'Skipcheck.Tm1.Client', typescript: '@skipcheck/tm1-client' });
	for (const project of PROJECTS) {
		for (const language of languagesOf(project)) {
			const pkg = await packageOf(project, language.id);
			assert.match(pkg.needs, /^(Java|Python|\.NET|Node) \d/, project.id + ' in ' + language.id);
		}
	}
});

test('a release is installed at its version, a pre-release as the latest pre-release', async () => {
	const release = { version: '1.2.3', release: true };
	const snapshot = { version: '0.1.0-SNAPSHOT', release: false };
	const code = (language, name, version, test) => installCommands(language, name, version, test).map((block) => block.code);
	assert.deepEqual(code('python', 'skipcheck-tm1', release, false), ['pip install skipcheck-tm1==1.2.3']);
	assert.deepEqual(code('python', 'skipcheck-tm1', snapshot, false), ['pip install --pre skipcheck-tm1']);
	assert.deepEqual(code('dotnet', 'Skipcheck.Tm1.Client', release, false), ['dotnet add package Skipcheck.Tm1.Client --version 1.2.3']);
	assert.deepEqual(code('dotnet', 'Skipcheck.Tm1.Client', snapshot, false), ['dotnet add package Skipcheck.Tm1.Client --prerelease']);
	assert.deepEqual(code('typescript', '@skipcheck/tm1-client', release, false), ['npm install @skipcheck/tm1-client@1.2.3']);
	assert.deepEqual(code('typescript', '@skipcheck/tm1-testcontainers', snapshot, true), ['npm install --save-dev @skipcheck/tm1-testcontainers']);
	const [gradle, maven] = code('java', 'io.skipcheck:tm1-testcontainers', release, true);
	assert.equal(gradle, 'testImplementation("io.skipcheck:tm1-testcontainers:1.2.3")');
	assert.ok(maven.includes('<artifactId>tm1-testcontainers</artifactId>') && maven.includes('<version>1.2.3</version>') && maven.includes('<scope>test</scope>'), maven);
	assert.deepEqual(await docsVersion({ DOCS_VERSION: '1.2.3' }), release);
	assert.equal((await docsVersion({})).release, false, 'gradle.properties holds a snapshot between releases');
});

test("an example's regions are read from its file, without their indentation", async () => {
	const client = PROJECTS.find((project) => project.id === 'tm1-client');
	for (const language of languagesOf(client)) {
		const hello = (await regions(language.examples(client, 'HelloWorld'))).get('hello-world');
		assert.ok(hello, 'no hello-world region in ' + language.examples(client, 'HelloWorld'));
		assert.ok(!hello.startsWith(' ') && !hello.includes('@start'), hello);
	}
});

test('every project has a route on each zone, and the old addresses have theirs', () => {
	const wrangler = readFileSync(fileURLToPath(new URL('../wrangler.jsonc', import.meta.url)), 'utf8');
	const patterns = [...wrangler.matchAll(/"pattern":\s*"([^"]+)"/g)].map((match) => match[1]);
	for (const zone of ['skipcheck.io', 'skipcheck.dev']) {
		for (const project of PROJECTS) assert.ok(patterns.includes('*' + zone + '/' + project.id + '*'), 'no route for ' + project.id + ' on ' + zone);
		assert.ok(patterns.includes('*' + zone + '/projects/tm1*'), 'no route for the old addresses on ' + zone);
	}
	assert.equal(patterns.length, 2 * (PROJECTS.length + 1), 'a route that no project in scripts/site.mjs accounts for');
});
