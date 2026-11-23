// SPDX-License-Identifier: Apache-2.0

// Serves each project's pages (built into dist/ by scripts/assemble.mjs, guide.mjs and pages.mjs)
// at skipcheck.io/<project>/: its landing page, its docs at /<project>/docs/ and its API reference
// at /<project>/api/java/. They are dressed as part of the site: every HTML page gets the site's
// masthead and footer, fetched from skipcheck.io/partials/ and cached, so a change to the site's
// nav reaches these pages without a rebuild. skipcheck.dev serves the same pages, each naming its
// skipcheck.io address as canonical.
//
// The addresses these pages had before, under /projects/, are sent to the new ones. The theme stylesheet imports the site's tokens and chrome
// styles the same way (api-site/theme/javadoc-theme.css).

import csp from '../.generated/csp.json';
// The projects, and which one documents each type (scripts/guide.mjs)
import site from '../.generated/site.json';

// The site's CSP, plus the hashes of the inline code Javadoc writes (scripts/assemble.mjs).
// 'unsafe-hashes' lets those hashes match event handler and style attributes; it allows nothing
// that isn't hashed.
const quoted = (hashes) => hashes.map((hash) => `'${hash}'`).join(' ');
const SECURITY_HEADERS = {
	'Content-Security-Policy': [
		"default-src 'self'",
		"img-src 'self' data:",
		`style-src 'self' 'unsafe-hashes' ${quoted(csp.styles)}`,
		`script-src 'self' 'unsafe-hashes' ${quoted(csp.scripts)}`,
		"frame-ancestors 'none'",
		"base-uri 'self'",
		"form-action 'none'",
	].join('; '),
	'X-Content-Type-Options': 'nosniff',
	'Referrer-Policy': 'strict-origin-when-cross-origin',
	'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};

// How long the site's partials are cached at the edge before the docs pick up a change
const PARTIAL_TTL_SECONDS = 300;

export default {
	async fetch(request, env) {
		const url = new URL(request.url);

		const moved = formerAddress(url.pathname);
		if (moved) return Response.redirect(url.origin + moved + url.search, 301);

		// In production only the projects' paths are routed here. Under `wrangler dev` everything is,
		// so pass the rest (the site's styles, its other pages, links home) through to the site.
		const project = site.projects.find((p) => url.pathname === '/' + p.id || url.pathname.startsWith('/' + p.id + '/'));
		if (!project) return fetch(new URL(url.pathname + url.search, env.SITE_ORIGIN), request);

		// A project without a landing page opens at its docs
		if (!project.landing && (url.pathname === '/' + project.id || url.pathname === '/' + project.id + '/')) {
			return Response.redirect(url.origin + '/' + project.id + '/docs/' + url.search, 302);
		}

		// A docs page from before the docs had a page per language is the Java one
		const unversioned = new RegExp('^/' + project.id + '/docs/([A-Z][\\w.]*\\.html)$').exec(url.pathname);
		if (unversioned) return Response.redirect(url.origin + '/' + project.id + '/docs/java/' + unversioned[1] + url.search, 301);

		// A page is the Javadoc file plus the site's partials, so the file's ETag doesn't identify it:
		// answering a browser's revalidation from the file alone would keep an old masthead or
		// footer in its cache after the site changes. Pages are always fetched in full, and sent
		// without the file's validators.
		// A page is an .html file or a directory (served as its index.html); a path with no extension
		// is taken for a directory and gets its trailing slash.
		const isPage = !/\.(?!html$)[^/.]+$/.test(url.pathname);
		if (isPage && !url.pathname.endsWith('/') && !url.pathname.endsWith('.html')) {
			return Response.redirect(`${url.origin}${url.pathname}/${url.search}`, 301);
		}
		const asset = new URL(url);
		if (asset.pathname.endsWith('/')) asset.pathname += 'index.html';
		const assetRequest = new Request(asset, request);
		const response = await env.ASSETS.fetch(isPage ? unconditional(assetRequest) : assetRequest);
		if (response.status === 404) return notFound(env);
		if (!(response.headers.get('Content-Type') ?? '').startsWith('text/html')) {
			return withSecurityHeaders(response);
		}

		const [masthead, footer] = await Promise.all([partial(env, 'masthead'), partial(env, 'footer')]);
		const page = new HTMLRewriter()
			// The site's colour mode (system, light or dark), set before the page is drawn, and its Copy
			// buttons
			.on('head', {
				element(head) {
					head.prepend('<script src="/theme.js"></script>', { html: true });
					// Copy buttons on code blocks, but not in the Javadoc, which has its own
					if (!url.pathname.includes('/api/java/')) head.append('<script src="/copy.js" defer></script>', { html: true });
				},
			})
			.on('body', {
				element(body) {
					if (masthead) body.prepend(masthead, { html: true });
					if (footer) body.append(footer, { html: true });
				},
			})
			.transform(response);
		const secured = withSecurityHeaders(page);
		secured.headers.delete('ETag');
		secured.headers.delete('Last-Modified');
		secured.headers.set('Cache-Control', 'no-cache');
		// The pages are generated, so the canonical address goes in a header, not in each file
		secured.headers.set('Link', `<${new URL(url.pathname, env.SITE_ORIGIN)}>; rel="canonical"`);
		return secured;
	},
};

// The site's masthead or footer as HTML, or null if the site can't be reached: the docs still
// work without them.
async function partial(env, name) {
	try {
		const response = await fetch(new URL(`/partials/${name}`, env.SITE_ORIGIN), {
			cf: { cacheEverything: true, cacheTtl: PARTIAL_TTL_SECONDS },
		});
		if (!response.ok) return null;
		const html = await response.text();
		// Javadoc's script measures the first <header> on the page to place its sticky navigation
		// bar, so the masthead mustn't be one. The site styles it by class, not element.
		return name === 'masthead' ? html.replace(/<header\b/, '<div').replace(/<\/header>/, '</div>') : html;
	} catch {
		return null;
	}
}

// Where a page that used to be under /projects/ is now, or null for any other path:
//
//   /projects/<project>/api/...         /<project>/api/java/...
//   /projects/tm1/guide/<Type>.html     /<the project that documents Type>/docs/java/<Type>.html
//   /projects/tm1/guide/                /tm1-client/docs/
//   /projects/<project>/                /<project>/
//
// The site's own pages under /projects/ (pa-docker) are not projects here, and are left alone.
function formerAddress(pathname) {
	const match = /^\/projects\/([^/]+)(\/.*)?$/.exec(pathname);
	if (!match) return null;
	const [, name, rest = ''] = match;
	if (name === 'tm1') {
		const guide = /^\/guide(?:\/(.*))?$/.exec(rest);
		if (!guide) return null;
		const owner = site.guide[(guide[1] ?? '').replace(/\.html$/, '')];
		return owner ? '/' + owner + '/docs/java/' + guide[1] : '/tm1-client/docs/';
	}
	if (!site.projects.some((project) => project.id === name)) return null;
	const api = /^\/api(?:\/(.*))?$/.exec(rest);
	return api ? '/' + name + '/api/java/' + (api[1] ?? '') : '/' + name + '/';
}

function unconditional(request) {
	const headers = new Headers(request.headers);
	headers.delete('If-None-Match');
	headers.delete('If-Modified-Since');
	return new Request(request, { headers });
}

async function notFound(env) {
	const page = await fetch(new URL('/404', env.SITE_ORIGIN)).catch(() => null);
	const body = page?.ok ? page.body : 'Not found';
	return withSecurityHeaders(
		new Response(body, { status: 404, headers: { 'Content-Type': page?.headers.get('Content-Type') ?? 'text/plain' } }),
	);
}

function withSecurityHeaders(response) {
	const secured = new Response(response.body, response);
	for (const [name, value] of Object.entries(SECURITY_HEADERS)) secured.headers.set(name, value);
	return secured;
}
