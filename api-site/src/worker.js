// SPDX-License-Identifier: Apache-2.0

// Serves the Java API docs (built by scripts/assemble.mjs into dist/) at
// ajmyers.net/projects/<module>/api/, dressed as part of the site: every HTML page gets the site's
// masthead and footer, fetched from ajmyers.net/partials/ and cached, so a change to the site's nav
// reaches the docs without a rebuild. The theme stylesheet imports the site's tokens and chrome
// styles the same way (api-site/theme/javadoc-theme.css).

import csp from '../.generated/csp.json';

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

		// In production only /projects/<module>/api* is routed here. Under `wrangler dev` everything
		// is, so pass the rest (the site's /tokens.css, /chrome.css, links home) through to the site.
		if (!url.pathname.startsWith('/projects/')) {
			return fetch(new URL(url.pathname + url.search, env.SITE_ORIGIN), request);
		}

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
