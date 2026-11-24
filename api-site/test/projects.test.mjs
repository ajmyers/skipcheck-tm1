// SPDX-License-Identifier: Apache-2.0

// The project store's entries (scripts/projects.mjs), from this repository's content and examples.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { projectEntries } from '../scripts/projects.mjs';

const entries = await projectEntries();
const byId = (id) => entries.find((e) => e.id === id);

test('there is an entry for the Docker images and for each library with a landing page', () => {
	assert.deepEqual(entries.map((e) => e.id), ['pa-docker', 'tm1-client', 'tm1-testcontainers', 'tm1-spring-boot-starter']);
	for (const e of entries) {
		assert.ok(e.summary && e.description, e.id);
		assert.ok(e.links.every((l) => l.href.startsWith('https://')), e.id);
		assert.deepEqual(e.sites, ['skipcheck.io', 'ajmyers.net']);
	}
});

test("a library's card is in the works until there's a release", () => {
	for (const id of ['tm1-client', 'tm1-testcontainers', 'tm1-spring-boot-starter']) assert.equal(byId(id).status, byId(id).release ? 'available' : 'in-the-works', id);
	assert.equal(byId('pa-docker').status, 'available');
});

test("a library in several languages shows its page's first example behind a tab for each", () => {
	const visual = byId('tm1-client').visual;
	assert.equal(visual.kind, 'code-tabs');
	assert.deepEqual(visual.tabs.map((t) => t.label), ['Java', 'Python', '.NET', 'TypeScript']);
	assert.deepEqual(visual.tabs.map((t) => t.lang), ['java', 'python', 'csharp', 'typescript']);
	assert.ok(visual.tabs.every((t) => t.code.trim().length > 0 && !t.code.includes('@start')));
});

test('the Spring Boot starter, in one language, shows the file its page shows first', () => {
	const visual = byId('tm1-spring-boot-starter').visual;
	assert.equal(visual.kind, 'code');
	assert.equal(visual.title, 'application.yml');
	assert.equal(visual.lang, 'yaml');
	assert.ok(!visual.code.includes('SPDX'));
});
