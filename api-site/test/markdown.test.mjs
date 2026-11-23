// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { test } from 'node:test';
import { parse } from '../scripts/markdown.mjs';
import { siteDir } from '../scripts/site.mjs';

test('refuses a fenced code block, naming its line', () => {
	for (const fence of ['```', '```java', '   ~~~']) {
		assert.throws(() => parse('---\ntitle: t\n---\nText.\n\n' + fence + '\nvar x = 1;\n'), /line 6: a code block/);
	}
});

test('takes code from directives, and names in code spans', () => {
	const { sections } = parse('1. **Step.** Call `Tm1Client`:\n   ::example ClientExamples build\n');
	assert.deepEqual(sections[0].blocks[0].items[0].at(-1), { type: 'directive', name: 'example', args: ['ClientExamples', 'build'] });
});

test('no content file has a code block', async () => {
	const files = [];
	const walk = async (dir) => {
		for (const entry of await readdir(dir, { withFileTypes: true })) {
			const file = path.join(dir, entry.name);
			if (entry.isDirectory()) await walk(file);
			else if (entry.name.endsWith('.md')) files.push(file);
		}
	};
	await walk(path.join(siteDir, 'content'));
	assert.ok(files.length >= 9, 'the content files were not found');
	for (const file of files) parse(await readFile(file, 'utf8'));
});
