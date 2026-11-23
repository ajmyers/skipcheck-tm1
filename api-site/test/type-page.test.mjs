// SPDX-License-Identifier: Apache-2.0

// Tests of the top of a type's page (scripts/type-page.mjs): the walkthroughs that use the type are
// named right under its import line, before the overview, so a reader sees them on arriving.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { typeHeader } from '../scripts/type-page.mjs';

const project = { id: 'tm1-client' };
const walkthrough = (slug, title) => ({ project, walkthrough: { slug, meta: { title } } });

test('the walkthroughs that use a type come right after its heading and import line', () => {
	const html = typeHeader({ name: 'CellsetService', importLine: 'from skipcheck_tm1.services import CellsetService', highlight: 'python', uses: [walkthrough('read-and-write-cells', 'Read & write cells')] });
	assert.equal(
		html,
		'<h1>CellsetService</h1>' +
			'<pre class="import"><code class="lang-python">from skipcheck_tm1.services import CellsetService</code></pre>' +
			'<p class="note">Used in the walkthrough <a href="/tm1-client/walkthroughs/read-and-write-cells/">Read &amp; write cells</a>.</p>',
	);
});

test('more than one walkthrough is listed in the order given', () => {
	const html = typeHeader({ name: 'Tm1Client', importLine: 'import { Tm1Client } from "@skipcheck/tm1-client";', highlight: 'typescript', uses: [walkthrough('connect', 'Connect'), walkthrough('run-a-process', 'Run a process')] });
	assert.match(html, /<\/pre><p class="note">Used in the walkthroughs <a href="\/tm1-client\/walkthroughs\/connect\/">Connect<\/a>, <a href="\/tm1-client\/walkthroughs\/run-a-process\/">Run a process<\/a>\.<\/p>$/);
});

test('a type no walkthrough uses has no note', () => {
	assert.equal(typeHeader({ name: 'Tm1Version', importLine: 'using Skipcheck.Tm1;', highlight: 'csharp' }), '<h1>Tm1Version</h1><pre class="import"><code class="lang-csharp">using Skipcheck.Tm1;</code></pre>');
	assert.doesNotMatch(typeHeader({ name: 'X', importLine: 'x', highlight: 'java', uses: [] }), /Used in/);
});
