// SPDX-License-Identifier: Apache-2.0

// Tests of the Worker (src/worker.js) in Node: npm test. What the Workers
// runtime provides is stood in for here: the bundler's JSON import, HTMLRewriter, the assets
// binding and the site the Worker fetches its masthead, footer and 404 page from.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { beforeEach, test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

// wrangler bundles ../.generated/csp.json, which scripts/assemble.mjs writes; here it's two hashes
const source = readFileSync(fileURLToPath(new URL('../src/worker.js', import.meta.url)), 'utf8');
const importLine = "import csp from '../.generated/csp.json';";
assert.ok(source.includes(importLine), 'worker.js no longer imports the CSP hashes as this test expects');
// and ../.generated/site.json, which scripts/reference.mjs writes; here it's three projects, the
// Docker pages, the docs for agents and two types
const siteLine = "import site from '../.generated/site.json';";
assert.ok(source.includes(siteLine), 'worker.js no longer imports the projects as this test expects');
const projects = {
	projects: [
		{ id: 'tm1-client', landing: true, docs: true },
		{ id: 'tm1-testcontainers', landing: true, docs: true },
		{ id: 'tm1-csdl', landing: false, docs: true },
		{ id: 'pa-docker', landing: true, docs: false, site: ['setup'] },
		{ id: 'llms.txt', landing: false, docs: false, file: true },
		{ id: 'llms-full.txt', landing: false, docs: false, file: true },
	],
	guide: { CubeService: 'tm1-client', RequiresTm1: 'tm1-testcontainers' },
};
const module = join(mkdtempSync(join(tmpdir(), 'worker-test-')), 'worker.mjs');
writeFileSync(
	module,
	source.replace(importLine, "const csp = { styles: ['sha256-style'], scripts: ['sha256-script'] };").replace(siteLine, 'const site = ' + JSON.stringify(projects) + ';'),
);
const worker = (await import(pathToFileURL(module).href)).default;

// HTMLRewriter, for the one thing the Worker does with it: prepend to and append to <body>
globalThis.HTMLRewriter = class {
	#handlers = [];
	on(selector, handlers) {
		assert.ok(selector === 'body' || selector === 'head', selector);
		this.#handlers.push({ selector, handlers });
		return this;
	}
	transform(response) {
		const added = { head: '', before: '', after: '' };
		for (const { selector, handlers } of this.#handlers) {
			const element =
				selector === 'head'
					? { prepend: (html) => (added.head += html), append: (html) => (added.head += html) }
					: { prepend: (html) => (added.before += html), append: (html) => (added.after += html) };
			handlers.element(element);
		}
		const rewritten = response.text().then((html) =>
			html.replace('<head>', '<head>' + added.head).replace('<body>', '<body>' + added.before).replace('</body>', added.after + '</body>'),
		);
		return new Response(streamOf(rewritten), response);
	}
};
function streamOf(text) {
	return new ReadableStream({
		async start(controller) {
			controller.enqueue(new TextEncoder().encode(await text));
			controller.close();
		},
	});
}

const SITE = 'https://site.example';
const ORIGIN = 'https://docs.example';
let site; // what the site answers, by path
let siteRequests;
let assetRequests;
const assets = {
	'/tm1-client/api/java/index.html': () =>
		new Response('<html><body><main>Overview</main></body></html>', {
			headers: { 'Content-Type': 'text/html; charset=utf-8', ETag: '"page"', 'Last-Modified': 'Mon, 23 Nov 2026 00:00:00 GMT' },
		}),
	'/tm1-client/api/java/script.js': () => new Response('let a;', { headers: { 'Content-Type': 'text/javascript', ETag: '"script"' } }),
};
const env = {
	SITE_ORIGIN: SITE,
	ASSETS: {
		async fetch(request) {
			assetRequests.push(request);
			return (assets[new URL(request.url).pathname] ?? (() => new Response('no such asset', { status: 404 })))();
		},
	},
};

beforeEach(() => {
	siteRequests = [];
	assetRequests = [];
	site = {
		'/partials/masthead': () => new Response('<header class="masthead"><nav>Home</nav></header>'),
		'/partials/footer': () => new Response('<footer>The end</footer>'),
		'/404': () => new Response('<h1>Not here</h1>', { headers: { 'Content-Type': 'text/html' } }),
	};
	globalThis.fetch = async (input) => {
		const url = new URL(input instanceof Request ? input.url : input);
		siteRequests.push(url.href);
		const answer = site[url.pathname];
		if (!answer) throw new TypeError('fetch failed');
		return answer();
	};
});

const get = (path, headers = {}) => worker.fetch(new Request(ORIGIN + path, { headers }), env);

test('a page gets the site masthead and footer around the Javadoc', async () => {
	const response = await get('/tm1-client/api/java/');
	assert.equal(response.status, 200);
	assert.equal(
		await response.text(),
		'<html><body><div class="masthead"><nav>Home</nav></div><main>Overview</main><footer>The end</footer></body></html>',
	);
	assert.equal(new URL(assetRequests[0].url).pathname, '/tm1-client/api/java/index.html');
});

test("the masthead isn't a <header>, which Javadoc's script would measure", async () => {
	const html = await (await get('/tm1-client/api/java/index.html')).text();
	assert.ok(!html.includes('<header') && !html.includes('</header>'), html);
});

test("a page is never revalidated from the file alone: the site's parts aren't in its ETag", async () => {
	const response = await get('/tm1-client/api/java/', { 'If-None-Match': '"page"', 'If-Modified-Since': 'Mon, 23 Nov 2026 00:00:00 GMT' });
	assert.equal(assetRequests[0].headers.get('If-None-Match'), null);
	assert.equal(assetRequests[0].headers.get('If-Modified-Since'), null);
	assert.equal(response.headers.get('ETag'), null);
	assert.equal(response.headers.get('Last-Modified'), null);
	assert.equal(response.headers.get('Cache-Control'), 'no-cache');
});

test('a file that is not a page is served as it is, validators and all', async () => {
	const response = await get('/tm1-client/api/java/script.js', { 'If-None-Match': '"script"' });
	assert.equal(assetRequests[0].headers.get('If-None-Match'), '"script"');
	assert.equal(await response.text(), 'let a;');
	assert.equal(response.headers.get('ETag'), '"script"');
	assert.deepEqual(siteRequests, []);
});

test('every answer carries the security headers, with the hashes of the inline code', async () => {
	for (const path of ['/tm1-client/api/java/', '/tm1-client/api/java/script.js', '/tm1-client/api/java/missing.html']) {
		const headers = (await get(path)).headers;
		const policy = headers.get('Content-Security-Policy');
		assert.ok(policy.includes("style-src 'self' 'unsafe-hashes' 'sha256-style'"), policy);
		assert.ok(policy.includes("script-src 'self' 'unsafe-hashes' 'sha256-script'"), policy);
		assert.ok(policy.includes("frame-ancestors 'none'"), policy);
		assert.equal(headers.get('X-Content-Type-Options'), 'nosniff');
	}
});

test('a folder without its slash is sent to the one with it, query kept', async () => {
	const response = await get('/tm1-client/api/java?q=cube');
	assert.equal(response.status, 301);
	assert.equal(response.headers.get('Location'), ORIGIN + '/tm1-client/api/java/?q=cube');
	assert.deepEqual(assetRequests, []);
});

test("what isn't there is the site's 404 page, with a 404", async () => {
	const response = await get('/tm1-client/api/java/missing.html');
	assert.equal(response.status, 404);
	assert.equal(await response.text(), '<h1>Not here</h1>');
	assert.equal(response.headers.get('Content-Type'), 'text/html');
});

test('the docs still work when the site cannot be reached', async () => {
	site = {};
	const page = await get('/tm1-client/api/java/');
	assert.equal(page.status, 200);
	assert.equal(await page.text(), '<html><body><main>Overview</main></body></html>');
	const missing = await get('/tm1-client/api/java/missing.html');
	assert.equal(missing.status, 404);
	assert.equal(await missing.text(), 'Not found');
});

test("anything that isn't a project's goes to the site, as under wrangler dev", async () => {
	site['/tokens.css'] = () => new Response(':root {}');
	const response = await get('/tokens.css?v=2');
	assert.equal(await response.text(), ':root {}');
	assert.deepEqual(siteRequests, [SITE + '/tokens.css?v=2']);
	assert.deepEqual(assetRequests, []);
});

test("the site's own addresses under /projects/ go to the site too, which redirects them", async () => {
	site['/projects/pa-docker/'] = () => new Response('Docker');
	site['/projects/pa-docker/setup/'] = () => new Response('Setup');
	assert.equal(await (await get('/projects/pa-docker/')).text(), 'Docker');
	assert.equal(await (await get('/projects/pa-docker/setup/')).text(), 'Setup');
	assert.deepEqual(assetRequests, []);
});

test("the site's setup page under /pa-docker/, and everything under it, goes to the site as it is", async () => {
	site['/pa-docker/setup/'] = () => new Response('<html><body>Setup</body></html>', { headers: { 'Content-Type': 'text/html' } });
	site['/pa-docker/setup/wizard.js'] = () => new Response('let wizard;', { headers: { 'Content-Type': 'text/javascript' } });
	site['/pa-docker/setup'] = () => new Response(null, { status: 307, headers: { Location: '/pa-docker/setup/' } });
	const page = await get('/pa-docker/setup/?sample=1');
	assert.equal(page.status, 200);
	assert.equal(await page.text(), '<html><body>Setup</body></html>', 'no second masthead or footer: the site page has its own');
	assert.equal(await (await get('/pa-docker/setup/wizard.js')).text(), 'let wizard;');
	const bare = await get('/pa-docker/setup');
	assert.equal(bare.headers.get('Location'), '/pa-docker/setup/', "the site's own redirect, not this Worker's");
	assert.deepEqual(siteRequests, [SITE + '/pa-docker/setup/?sample=1', SITE + '/pa-docker/setup/wizard.js', SITE + '/pa-docker/setup']);
	assert.deepEqual(assetRequests, []);
});

test('a Docker page whose path only starts like the setup page is still served here', async () => {
	assets['/pa-docker/setups/index.html'] = () => new Response('<html><body>Here</body></html>', { headers: { 'Content-Type': 'text/html' } });
	assert.equal(await (await get('/pa-docker/setups/')).text(), '<html><body><div class="masthead"><nav>Home</nav></div>Here<footer>The end</footer></body></html>');
	assert.deepEqual(siteRequests.filter((url) => !url.includes('/partials/')), []);
});

test('a path that only starts like a project is not that project', async () => {
	site['/tm1-clients/'] = () => new Response('the site answers');
	assert.equal(await (await get('/tm1-clients/')).text(), 'the site answers');
});

test('the addresses the pages had under /projects/ are sent to the new ones, query kept', async () => {
	const moved = {
		'/projects/tm1-client/': '/tm1-client/',
		'/projects/tm1-client': '/tm1-client/',
		'/projects/tm1-client/api/': '/tm1-client/api/java/',
		'/projects/tm1-client/api': '/tm1-client/api/java/',
		'/projects/tm1-client/api/io/skipcheck/Cube.html?q=1': '/tm1-client/api/java/io/skipcheck/Cube.html?q=1',
		'/projects/tm1/guide/': '/tm1-client/docs/',
		'/projects/tm1/guide': '/tm1-client/docs/',
		'/projects/tm1/guide/CubeService.html': '/tm1-client/docs/java/CubeService.html',
		'/projects/tm1/guide/RequiresTm1.html': '/tm1-testcontainers/docs/java/RequiresTm1.html',
		'/tm1-client/docs/CubeService.html': '/tm1-client/docs/java/CubeService.html',
		'/projects/tm1/guide/Gone.html': '/tm1-client/docs/',
		'/projects/tm1-csdl/': '/tm1-csdl/',
	};
	for (const [from, to] of Object.entries(moved)) {
		const response = await get(from);
		assert.equal(response.status, 301, from);
		assert.equal(response.headers.get('Location'), ORIGIN + to, from);
	}
	assert.deepEqual(assetRequests, []);
});

test('a project without a landing page opens at its docs', async () => {
	for (const path of ['/tm1-csdl', '/tm1-csdl/']) {
		const response = await get(path);
		assert.equal(response.status, 302, path);
		assert.equal(response.headers.get('Location'), ORIGIN + '/tm1-csdl/docs/', path);
	}
});

test('a landing page is served at the project, dressed like any page', async () => {
	assets['/tm1-client/index.html'] = () => new Response('<html><body><main>Client</main></body></html>', { headers: { 'Content-Type': 'text/html' } });
	const response = await get('/tm1-client/');
	assert.equal(await response.text(), '<html><body><div class="masthead"><nav>Home</nav></div><main>Client</main><footer>The end</footer></body></html>');
	assert.equal(response.headers.get('Link'), '<' + SITE + '/tm1-client/>; rel="canonical"');
});

test("every page loads the site's colour mode before it's drawn", async () => {
	assets['/tm1-client/api/java/mode.html'] = () => new Response('<html><head><title>T</title></head><body>x</body></html>', { headers: { 'Content-Type': 'text/html' } });
	const html = await (await get('/tm1-client/api/java/mode.html')).text();
	assert.ok(html.startsWith('<html><head><script src="/theme.js"></script><title>'), html);
	// The Javadoc has its own copy buttons
	assert.ok(!html.includes('/copy.js'), html);
});

test('pages other than the Javadoc get the site\'s Copy buttons', async () => {
	assets['/tm1-client/index.html'] = () => new Response('<html><head></head><body>x</body></html>', { headers: { 'Content-Type': 'text/html' } });
	const html = await (await get('/tm1-client/')).text();
	assert.ok(html.includes('<script src="/copy.js" defer></script>'), html);
});

test('the Docker pages are served at /pa-docker/, dressed like any page, with their folders given a slash', async () => {
	assets['/pa-docker/index.html'] = () => new Response('<html><body><main>Docker</main></body></html>', { headers: { 'Content-Type': 'text/html' } });
	assets['/pa-docker/tm1-server/getting-started/index.html'] = () => new Response('<html><body><main>Getting started</main></body></html>', { headers: { 'Content-Type': 'text/html' } });
	const landing = await get('/pa-docker/');
	assert.equal(await landing.text(), '<html><body><div class="masthead"><nav>Home</nav></div><main>Docker</main><footer>The end</footer></body></html>');
	const page = await get('/pa-docker/tm1-server/getting-started/');
	assert.equal(await page.text(), '<html><body><div class="masthead"><nav>Home</nav></div><main>Getting started</main><footer>The end</footer></body></html>');
	assert.equal(page.headers.get('Link'), '<' + SITE + '/pa-docker/tm1-server/getting-started/>; rel="canonical"');
	const bare = await get('/pa-docker/tm1-server');
	assert.equal(bare.status, 301);
	assert.equal(bare.headers.get('Location'), ORIGIN + '/pa-docker/tm1-server/');
});

test('the docs for agents are served at the root as they are: no masthead, no redirect, the security headers', async () => {
	for (const path of ['/llms.txt', '/llms-full.txt']) {
		assets[path] = () => new Response('# Skipcheck docs\n', { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
		const response = await get(path);
		assert.equal(response.status, 200, path);
		assert.equal(await response.text(), '# Skipcheck docs\n', path);
		assert.equal(response.headers.get('Content-Type'), 'text/plain; charset=utf-8', path);
		assert.ok(response.headers.get('Content-Security-Policy'), path);
	}
	assert.deepEqual(siteRequests, [], 'no masthead or footer for a text file');
});
