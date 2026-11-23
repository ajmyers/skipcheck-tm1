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
import { judge, sourceHash } from '../scripts/output.mjs';
import { head, languagesOf, PROJECTS, regions, SECTIONS } from '../scripts/site.mjs';
import { walkthroughsOf } from '../scripts/walkthroughs.mjs';

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
	assert.deepEqual(code('typescript', '@skipcheck/tm1-client', snapshot, false), ['npm install @skipcheck/tm1-client@dev']);
	assert.deepEqual(code('typescript', '@skipcheck/tm1-testcontainers', snapshot, true), ['npm install --save-dev @skipcheck/tm1-testcontainers@dev']);
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

test('every project, the Docker pages and the docs landing page have a route on each zone, and the old addresses theirs', () => {
	const wrangler = readFileSync(fileURLToPath(new URL('../wrangler.jsonc', import.meta.url)), 'utf8');
	const patterns = [...wrangler.matchAll(/"pattern":\s*"([^"]+)"/g)].map((match) => match[1]);
	for (const zone of ['skipcheck.io', 'skipcheck.dev']) {
		for (const section of SECTIONS) assert.ok(patterns.includes('*' + zone + '/' + section.id + '*'), 'no route for ' + section.id + ' on ' + zone);
		assert.ok(patterns.includes('*' + zone + '/projects/tm1*'), 'no route for the old addresses on ' + zone);
	}
	assert.ok(['tm1-client', 'pa-docker', 'docs'].every((id) => SECTIONS.some((section) => section.id === id)), SECTIONS.map((section) => section.id).join(', '));
	assert.equal(patterns.length, 2 * (SECTIONS.length + 1), 'a route that SECTIONS in scripts/site.mjs does not account for');
});

test('a page with a description has the link preview image, and one without has none', () => {
	const page = head({ title: 'T', description: 'D', canonical: 'https://skipcheck.io/x/', styles: [], scripts: [] });
	assert.match(page, /<meta property="og:image" content="https:\/\/skipcheck\.io\/og\.png">/);
	assert.match(page, /<meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">/);
	assert.match(page, /<meta property="og:image:alt" content="[^"]+">/);
	assert.match(page, /<meta name="twitter:card" content="summary_large_image">/);
	const bare = head({ title: 'T', canonical: 'https://skipcheck.io/x/docs/', styles: [], scripts: [] });
	assert.doesNotMatch(bare, /og:image|twitter:card/);
});

// The file every language's recorder hashes, with this result (java/tm1-client/src/test/java/examples/ExampleOutputTest.java)
const SOURCE = 'class Example {\n\tvoid other() {\n\t\t// @start region = other\n\t\tignored();\n\t\t// @end\n\t}\n\n\tvoid list(List<String> names) {\n\t\t// @start region = list\n\t\tfor (String name : names) {\n\n\t\t\tSystem.out.println(name);\n\t\t}\n\t\t// @end\n\t}\n}\n';
const HASH = '674465729e3fa78a6fe01513b135a8aeaad2efe66b046efc5cd13eb55235eebc';

test("a region's code is hashed as every language's recorder hashes it", () => {
	assert.equal(sourceHash(SOURCE, 'list'), HASH);
	assert.equal(sourceHash(SOURCE.replaceAll('\t', '  ').replaceAll('\n', '\r\n'), 'list'), HASH, 'indented and ended another way');
	assert.notEqual(sourceHash(SOURCE.replace('println(name)', 'println(name.strip())'), 'list'), HASH);
	assert.throws(() => sourceHash(SOURCE, 'missing'), /no region missing/);
});

test('a recorded output is shown when it is current, and fails the build when stale, missing or different', () => {
	const at = (id, entry, prints = true) => ({ id, where: id + '/Example', hash: 'h', prints, entry });
	const ok = (output) => ({ source: 'h', output });
	assert.deepEqual(judge('x', [at('java', ok(['a'])), at('python', ok(['a']))], true), { outputs: new Map([['java', ['a']], ['python', ['a']]]), problems: [] });
	const stale = judge('x', [at('java', { source: 'old', output: ['a'] }), at('python', ok(['a']))], false);
	assert.deepEqual([...stale.outputs.keys()], ['python']);
	assert.match(stale.problems[0], /^java\/Example: x has changed since its output was recorded/);
	assert.match(judge('x', [at('java', ok(['a'])), at('python', undefined)], true).problems[0], /^python\/Example: x prints, but its output was never recorded/);
	assert.deepEqual(judge('x', [at('java', ok(['a'])), at('python', undefined)], false).problems, [], 'only a walkthrough requires it');
	assert.deepEqual(judge('x', [at('java', undefined, false), at('python', undefined, false)], true).problems, [], 'an example that prints nothing needs no recording');
	assert.match(judge('x', [at('java', ok([]), false), at('python', undefined, false)], true).problems[0], /has no recorded output, and another language's has/);
	assert.match(judge('x', [at('java', ok(['a'])), at('python', ok(['b']))], false).problems[0], /^x printed something else in python than in java/);
});

test('every walkthrough has its front matter, and a lead before its steps', async () => {
	let count = 0;
	for (const project of PROJECTS) {
		for (const walkthrough of await walkthroughsOf(project)) {
			count++;
			assert.ok(walkthrough.sections[0].blocks.length > 0 && walkthrough.sections[0].blocks.every((block) => block.type === 'p'), walkthrough.file + ' starts with its lead');
			assert.ok(walkthrough.sections.some((section) => section.title === 'Steps'), walkthrough.file + ' has its steps');
		}
	}
	assert.ok(count >= 3, 'the walkthroughs were not found');
});
