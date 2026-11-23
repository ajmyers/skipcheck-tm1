# api-site

Publishes the Javadoc of each library at `https://skipcheck.io/projects/<module>/api/`, styled to
match the rest of skipcheck.io (and the same on skipcheck.dev, which mirrors it). Only the latest version lives here; every page links to javadoc.io
for older ones.

## How it fits together

| Piece | What |
|---|---|
| `apiSiteJavadoc` (Gradle, every library) | The library's Javadoc with `theme/javadoc-theme.css` added, into `<module>/build/docs/api-site`. A separate task, so the javadoc jar on Maven Central stays plain. Defined in `build-logic/.../tm1.java-library.gradle.kts`. |
| `theme/javadoc-theme.css` | Maps Javadoc's CSS variables onto the site's design tokens, which it imports from `/tokens.css` and `/chrome.css` on skipcheck.io at view time. |
| `scripts/assemble.mjs` | Copies each module's output into `dist/projects/<module>/api/`, and hashes the inline scripts, event handlers and style attributes Javadoc writes, for the CSP. |
| `src/worker.js` | The Cloudflare Worker that serves `dist/`. It inserts the site's masthead and footer (`/partials/` on skipcheck.io, cached for 5 minutes) into every page, names its skipcheck.io address as canonical, sets the security headers, and serves the site's 404 page for missing paths. |
| `wrangler.jsonc` | The Worker's config. Its routes (one per module and zone) are the list of published modules. |
| `.github/workflows/api-docs.yml` | Builds on pull requests; deploys on a release tag or a manual run. |

Changes to the site's palette, fonts, masthead or footer reach the docs without a rebuild. The site
side is in ajmyers/skipcheck.io: `public/tokens.css`, `public/chrome.css` and `public/partials/`.

## The guide

`scripts/guide.mjs` builds a second set of pages, at `/projects/tm1/guide/`: one for each type of the
client and of tm1-testcontainers that has examples, with every example in Java, Python, .NET and
TypeScript behind tabs. Choosing a language on one example chooses it everywhere, and the choice is
remembered (`guide/guide.js`).

| Piece | Where it comes from |
|---|---|
| The text | The Java type's Javadoc, where it quotes its examples with `{@snippet}`. There is one text, so names in it are Java's. |
| An example | A region of an examples file, with the same name in each language: `java/<module>/src/examples`, `python/<module>/examples`, `dotnet/examples/<Module>.Examples`, `typescript/<module>/examples`. |

Each language compiles or type-checks its examples with its own tests, and each has a gate that its
examples files and regions match Java's. `guide.mjs` fails if a language lacks an example the Java
documentation quotes, so a new Java example needs its three counterparts before the docs build
passes. Java's Spring Boot modules exist only in Java; their Javadoc is their documentation.

The pages have no inline script or style, so they need no entry in the CSP hashes.

## Preview locally

```sh
./gradlew apiSiteJavadoc
cd api-site
npm ci
npm run assemble   # the Javadoc, then the guide
npm run dev        # http://localhost:8787/projects/tm1-client/api/ and /projects/tm1/guide/
```

The local Worker passes any path outside `/projects/` through to skipcheck.io, so the site's styles,
masthead and footer come from the live site. Opening `build/docs/api-site` straight from disk shows
Javadoc's own look, since `/tokens.css` isn't there.

## Publishing a new module

Add a route for it on each zone to `wrangler.jsonc`. `assemble.mjs` picks it up from there, and fails if the
module has no `apiSiteJavadoc` output.

## Deploying

The workflow deploys with `wrangler deploy`. It needs two repository secrets:

- `CLOUDFLARE_API_TOKEN`: a Cloudflare API token with **Workers Scripts: Edit** on the account and
  **Workers Routes: Edit** on the skipcheck.io and skipcheck.dev zones.
- `CLOUDFLARE_ACCOUNT_ID`: the Cloudflare account ID.

## Limits and decisions

- **Only the latest version is published.** A Worker can serve a limited number of static files
  (about 20,000 on the free plan), and each version is around 1,000. javadoc.io serves every
  released version from Maven Central, so the docs link there instead.
- **Script hashes, not `'unsafe-inline'`.** Javadoc still writes an inline script into every page
  and `onclick` handlers into its tables. The CSP allows exactly those, by hash, with
  `'unsafe-hashes'` for the handlers.
- **The theme follows the OS.** The site has no light/dark switch, so the theme hides Javadoc's.
- **Pages are never revalidated against the Javadoc file.** A page is the file plus the site's
  partials, so the file's `ETag` doesn't identify it. The Worker always fetches pages in full and
  sends them without validators (`Cache-Control: no-cache`). CSS, scripts and images keep theirs.
- **The masthead is a `<div>` in the docs.** Javadoc's script measures the first `<header>` on the
  page to place its sticky navigation bar, so the Worker renames the site's masthead element.
