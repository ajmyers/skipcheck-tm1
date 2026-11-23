# api-site

Builds and serves each project's pages on skipcheck.io (and on skipcheck.dev, which mirrors it):

| Address | What | Built by |
|---|---|---|
| `/<project>/` | The landing page: what it is, a quick start in every language it has, what's in it. | `scripts/pages.mjs`, from `content/<project>.md` |
| `/<project>/docs/` | A page for each documented type, with every example in Java, Python, .NET and TypeScript. | `scripts/guide.mjs`, from the Java types' Javadoc |
| `/<project>/api/java/` | The API reference: the library's Javadoc, themed. | `./gradlew apiSiteJavadoc`, collected by `scripts/assemble.mjs` |

The projects are listed in `scripts/site.mjs`. The home page, the setup page and everything else on
skipcheck.io are in ajmyers/skipcheck.io; these pages take their look from it at view time.

Nothing here is copied by hand from the code. Words are written once, in a content file or in
Javadoc; everything else is read from the repository when the pages are built:

| On a page | Where it comes from |
|---|---|
| An example | A region of an examples file, with the same name in each language: `java/<project>/src/examples`, `python/<project>/examples`, `dotnet/examples/<Project>.Examples`, `typescript/<project>/examples`. Each language compiles or type-checks its examples with its own tests, and has a gate that its files and regions match Java's. |
| How to install | Each language's own build files: the package's name, the version of the language it needs (`scripts/packages.mjs`). |
| The version | `DOCS_VERSION`, which the workflow sets from a release tag, or else `gradle.properties`. Before a release the install steps ask for the latest pre-release, and the page says it is one. |
| The text of a docs page | The Java type's Javadoc, where it quotes its examples with `{@snippet}`. There is one text, so names in it are Java's. |
| The text of a landing page | `content/<project>.md`: Markdown with no code in it. `::install`, `::example <Class> <region>` and `::file <path> <language>` are replaced with the above (`scripts/pages.mjs`, `scripts/markdown.mjs`). |

The build fails when a page names an example that isn't there, when a language lacks an example
the Java documentation quotes, or when a link on a generated page goes nowhere
(`scripts/check-links.mjs`). So a new Java example needs its three counterparts before the build
passes.

## How it fits together

| Piece | What |
|---|---|
| `apiSiteJavadoc` (Gradle, every library) | The library's Javadoc with `theme/javadoc-theme.css` added, into `<module>/build/docs/api-site`. A separate task, so the javadoc jar on Maven Central stays plain. Defined in `build-logic/.../tm1.java-library.gradle.kts`. |
| `theme/javadoc-theme.css` | Maps Javadoc's CSS variables onto the site's design tokens, which it imports from `/tokens.css` and `/chrome.css` on skipcheck.io at view time. |
| `scripts/assemble.mjs` | Copies each library's Javadoc into `dist/<project>/api/java/`, and hashes the inline scripts, event handlers and style attributes Javadoc writes, for the CSP. Empties `dist/` first, so it runs first. |
| `scripts/guide.mjs` | The docs pages, and `.generated/site.json` for the Worker: the projects, and which one documents each type. |
| `scripts/pages.mjs` | The landing pages. |
| `scripts/check-links.mjs` | Checks every link on the landing and docs pages. |
| `assets/` | The generated pages' own styles and script, copied to `/<project>/assets/`: `docs.css` (the docs pages' layout), `tabs.css` and `tabs.js` (the language tabs: choosing a language on one example chooses it everywhere, and the choice is remembered). |
| `src/worker.js` | The Cloudflare Worker that serves `dist/`. It inserts the site's masthead and footer (`/partials/` on skipcheck.io, cached for 5 minutes) into every page, names its skipcheck.io address as canonical, sets the security headers, serves the site's 404 page for missing paths, and sends the addresses these pages had under `/projects/` to the new ones. |
| `wrangler.jsonc` | The Worker's config: a route per project and zone, and one per zone for the old addresses. |
| `test/` | `npm test` here, or `./gradlew checkApiSite`: the Worker, the Markdown, the install steps against the real build files, and that every project has its routes. |
| `.github/workflows/api-docs.yml` | Tests the Worker and the page builders, builds the site and bundles the Worker on every change, as part of CI. Deploys when the release workflow calls it for a tag, or when run by hand. |

What the pages take from the site (ajmyers/skipcheck.io, `public/`) at view time: `tokens.css`,
`chrome.css` and `code.css`; `highlight.js`, which colours the examples; the masthead and footer in
`partials/`; and, for the landing pages, `styles.css`, whose classes they are written in (`hero`,
`section`, `steps`, `numbered`, `points`, `code`, `cards`, `callout`). A change to the site's palette,
fonts, masthead or footer reaches these pages without a rebuild; renaming one of those classes
there changes the landing pages too.

The generated pages have no inline script or style, so they need no entry in the CSP hashes.

## Preview locally

```sh
./gradlew apiSiteJavadoc
cd api-site
npm ci
npm run assemble   # the Javadoc, the docs pages, the landing pages, then the link check
npm run dev        # http://localhost:8787/tm1-client/
```

`npm run pages` rebuilds the docs and landing pages alone, after a change to a content file or an
example. The local Worker passes any path that isn't a project's through to skipcheck.io, so the
site's styles, masthead and footer come from the live site. To see a change to the site as well,
run it locally (`npx wrangler dev` in ajmyers/skipcheck.io) and start this one with
`npx wrangler dev --port 8799 --var SITE_ORIGIN:http://localhost:8787`.

## Adding a project

Add it to `PROJECTS` in `scripts/site.mjs` and give it a route on each zone in `wrangler.jsonc`
(`npm test` fails without them). For a landing page, write `content/<project>.md`. `assemble.mjs`
fails if the library has no `apiSiteJavadoc` output.

## Deploying

The workflow deploys with `wrangler deploy`. It needs two repository secrets:

- `CLOUDFLARE_API_TOKEN`: a Cloudflare API token with **Workers Scripts: Edit** on the account and
  **Workers Routes: Edit** on the skipcheck.io and skipcheck.dev zones.
- `CLOUDFLARE_ACCOUNT_ID`: the Cloudflare account ID.

## Limits and decisions

- **The pages are published on a release, not on a merge.** They describe the packages people can
  install. Until the first release they are published from `master` by a manual run, and say they
  are a pre-release.
- **Landing pages take the site's stylesheet; they don't copy it.** They are written in the classes
  of the site's `styles.css`, loaded at view time, so they match the pages the site writes by hand
  and follow its design. The cost is the coupling noted above.
- **The old addresses redirect, permanently.** `/projects/<project>/...` and `/projects/tm1/guide/...`
  were published and linked, so the Worker keeps a route for them and sends each to its new place.
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
