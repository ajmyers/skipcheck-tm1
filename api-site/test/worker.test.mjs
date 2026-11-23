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
// and ../.generated/site.json, which scripts/guide.mjs writes; here it's three projects and two types
const siteLine = "import site from '../.generated/site.json';";
assert.ok(source.includes(siteLine), 'worker.js no longer imports the projects as this test expects');
const projects = {
	projects: [
		{ id: 'tm1-client', landing: true },
		{ id: 'tm1-testcontainers', landing: true },
		{ id: 'tm1-csdl', landing: false },
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
		assert.equal(selector, 'body');
		this.#handlers.push(handlers);
		return this;
	}
	transform(response) {
		const added = { before: '', after: '' };
		const body = { prepend: (html) => (added.before += html), append: (html) => (added.after += html) };
		for (const handlers of this.#handlers) handlers.element(body);
		const rewritten = response.text().then((html) => html.replace('<body>', '<body>' + added.before).replace('</body>', added.after + '</body>'));
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

test("the site's own pages under /projects/ go to the site too", async () => {
	site['/projects/pa-docker/'] = () => new Response('Docker');
	assert.equal(await (await get('/projects/pa-docker/')).text(), 'Docker');
	assert.deepEqual(assetRequests, []);
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
		'/projects/tm1/guide/CubeService.html': '/tm1-client/docs/CubeService.html',
		'/projects/tm1/guide/RequiresTm1.html': '/tm1-testcontainers/docs/RequiresTm1.html',
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

test('a project without a landing page opens at its API reference', async () => {
	for (const path of ['/tm1-csdl', '/tm1-csdl/']) {
		const response = await get(path);
		assert.equal(response.status, 302, path);
		assert.equal(response.headers.get('Location'), ORIGIN + '/tm1-csdl/api/java/', path);
	}
});

test('a landing page is served at the project, dressed like any page', async () => {
	assets['/tm1-client/index.html'] = () => new Response('<html><body><main>Client</main></body></html>', { headers: { 'Content-Type': 'text/html' } });
	const response = await get('/tm1-client/');
	assert.equal(await response.text(), '<html><body><div class="masthead"><nav>Home</nav></div><main>Client</main><footer>The end</footer></body></html>');
	assert.equal(response.headers.get('Link'), '<' + SITE + '/tm1-client/>; rel="canonical"');
});
