// SPDX-License-Identifier: Apache-2.0

// Tests of the Docker pages (scripts/docker.mjs): that a README renders with GitHub's heading ids,
// and that its links go to the right page, to GitHub, or fail the build.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { linkTarget, render, slugger } from '../scripts/docker.mjs';
import { PA_DOCKER } from '../scripts/site.mjs';

test("headings get GitHub's ids, numbered when they repeat", () => {
	const id = slugger();
	assert.equal(id('Step 1: Install Docker'), 'step-1-install-docker');
	assert.equal(id('R. Use the published image (recommended)'), 'r-use-the-published-image-recommended');
	assert.equal(id("2. Start from an existing server's config"), '2-start-from-an-existing-servers-config');
	assert.equal(id('Ports'), 'ports');
	assert.equal(id('Ports'), 'ports-1');
	assert.equal(id('Ports'), 'ports-2');
});

const source = 'docker/tm1-server/README.md';
const repo = { exists: (file) => ['docker/tm1-server/setup.sh', 'docker/tm1-server/bundles', '.github/workflows/docker.yml'].includes(file), isDirectory: (file) => file.endsWith('bundles') };
const github = /^https:\/\/github\.com\/[^/]+\/[^/]+\/(blob|tree)\/master\/(.+)$/;

test("a link to another README is that README's page, anchor kept", () => {
	assert.equal(linkTarget('../pa-setup/README.md', source, repo), '/pa-docker/pa-setup/');
	assert.equal(linkTarget('../cognos-server/README.md#oidc', source, repo), '/pa-docker/cognos-server/#oidc');
	assert.equal(linkTarget('GETTING-STARTED.md#step-1-install-docker', source, repo), '/pa-docker/tm1-server/getting-started/#step-1-install-docker');
	assert.equal(linkTarget('README.md', 'docker/tm1-server/GETTING-STARTED.md', repo), '/pa-docker/tm1-server/');
});

test('a link to any other file in the repository is that file on GitHub', () => {
	assert.deepEqual(github.exec(linkTarget('setup.sh', source, repo)).slice(1), ['blob', 'docker/tm1-server/setup.sh']);
	assert.deepEqual(github.exec(linkTarget('../../.github/workflows/docker.yml', source, repo)).slice(1), ['blob', '.github/workflows/docker.yml']);
	assert.deepEqual(github.exec(linkTarget('bundles/', source, repo)).slice(1), ['tree', 'docker/tm1-server/bundles']);
});

test('anchors, other sites and site paths are left as they are', () => {
	for (const href of ['#roles', 'https://www.keycloak.org', 'http://localhost:8010', '/pa-docker/setup/']) assert.equal(linkTarget(href, source, repo), href);
});

test("a link to a file that isn't in the repository fails the build", () => {
	assert.throws(() => linkTarget('missing.md', source, repo), /missing\.md links to docker\/tm1-server\/missing\.md, which is not in the repository/);
	assert.throws(() => linkTarget('../../../elsewhere.md', source, repo), /outside the repository/);
});

test('a README renders as its title, its first paragraph and the rest, with ids, links and code marked up', () => {
	const markdown = [
		'# tm1-web',
		'',
		'> **No IBM software.** You bring your own.',
		'',
		'Runs **TM1 Web** next to [tm1-server](../tm1-server/README.md).',
		'',
		'## Ports',
		'',
		'| Port | What |',
		'|---|---|',
		'| 9510 | HTTP |',
		'',
		'```sh',
		'docker run -p 9510:9510 tm1-web',
		'```',
		'',
	].join('\n');
	const { title, summary, html } = render(markdown, 'docker/tm1-web/README.md', repo);
	assert.equal(title, 'tm1-web');
	assert.equal(summary, 'Runs <strong>TM1 Web</strong> next to <a href="/pa-docker/tm1-server/">tm1-server</a>.');
	assert.ok(!html.includes('<h1'), html);
	assert.ok(html.includes('<h2 id="ports">Ports</h2>'), html);
	assert.ok(html.includes('<blockquote>'), html);
	assert.ok(html.includes('<table>'), html);
	assert.ok(html.includes('<pre><code class="lang-sh">docker run -p 9510:9510 tm1-web</code></pre>'), html);
});

test('every Docker page has a README, and a page under another sits under one that exists', () => {
	for (const page of PA_DOCKER.pages) {
		assert.match(page.source, /^docker\/[\w-]+\/[\w-]+\.md$/, page.path);
		if (page.path.includes('/')) assert.ok(PA_DOCKER.pages.some((other) => other.path === page.path.replace(/\/[^/]+$/, '')), page.path);
	}
});
