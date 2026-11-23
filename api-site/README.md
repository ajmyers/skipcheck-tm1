# api-site

Builds and serves each project's pages on skipcheck.io (and on skipcheck.dev, which mirrors it):

| Address | What | Built by |
|---|---|---|
| `/<project>/` | The landing page: what it is, a quick start in every language it has, what's in it. | `scripts/pages.mjs`, from `content/<project>.md` |
| `/<project>/docs/<language>/` | The reference: a page per type in each language, where the signatures, the descriptions and the examples are all that language's, with a switch to the same page in another. `/<project>/docs/` opens the language last chosen. A search box finds a type or member by name in the page's language. | `scripts/reference.mjs`, from each language's API (below) |
| `/<project>/docs/model/` | TM1's own types (Cube, Dimension...), the same in every language. | `scripts/reference.mjs`, from `spec/tm1-api.json` |
| `/<project>/walkthroughs/<slug>/` | A task done step by step, such as connecting and listing cubes, with each step's code in every language and what it printed. Listed on the landing page and the docs home. | `scripts/pages.mjs`, from `content/walkthroughs/<project>/<slug>.md` |
| `/docs/` | The docs landing page: every project, its languages, and links to its docs in each. The masthead's Docs link opens it. | `scripts/pages.mjs`, from the same content files as the landing pages |
| `/pa-docker/` | Planning Analytics in Docker: a landing page, and a page per image and for `pa-setup` (`/pa-docker/tm1-server/`...). | `scripts/docker.mjs`, from `content/pa-docker.md` and the READMEs under `docker/` |
| `/llms.txt`, `/llms-full.txt` | The docs for coding agents, as the [llms.txt convention](https://llmstxt.org) has them: a list of the pages, each with a line saying what's there, and the full text of the landing pages, the walkthroughs and every type's overview in Markdown. | `scripts/llms.mjs`, from the same sources as the pages |
| `/<project>/release.json` | The version the pages describe, and whether it's a release: the site's home page reads it for each project's badge. | `scripts/pages.mjs` |
| `/<project>/api/java/` | The Javadoc, themed, linked from each Java page. | `./gradlew apiSiteJavadoc`, collected by `scripts/assemble.mjs` |

The projects are listed in `scripts/site.mjs`. The home page, the setup page and everything else on
skipcheck.io are in ajmyers/skipcheck.io; these pages take their look from it at view time.

Nothing here is copied by hand from the code. Words are written once, in a content file or in
Javadoc; everything else is read from the repository when the pages are built:

| On a page | Where it comes from |
|---|---|
| An example | A region of an examples file, with the same name in each language: `java/<project>/src/examples`, `python/<project>/examples`, `dotnet/examples/<Project>.Examples`, `typescript/<project>/examples`. Each language compiles or type-checks its examples with its own tests, and has a gate that its files and regions match Java's. A Javadoc can also quote a whole file of the Java examples that a test reads, such as the starter's `application.yml` (`{@snippet file = examples/application.yml}`). |
| How to install | Each language's own build files: the package's name, the version of the language it needs (`scripts/packages.mjs`). |
| The version | `DOCS_VERSION`, which the workflow sets from a release tag, or else `gradle.properties`. Before a release the install steps ask for the latest pre-release, and the page says it is one. |
| A type's members, signatures and doc comments | Each language's sources, read by an extractor into one JSON shape (`scripts/extract.mjs`): Java by the doclet in `java/tools/docs-doclet` (`./gradlew docsModel`), Python by `extract/python_api.py` (with `ast`), .NET and TypeScript by `extract/dotnet_api.mjs` and `extract/typescript_api.mjs` (reading the formatted sources). No build or install of the clients is needed. |
| Which member is which across languages | The rule each language's parity tests enforce: `modelCubes` is `model_cubes` in Python and `ModelCubesAsync` in .NET; a getter is a property; `NOT_FOUND` is `NotFound` in .NET. A member a type inherits counts as its own. What a language lacks by design, or names another way, is in its `coverage/members-excluded.txt` (`scripts/members.mjs`). |
| A type's overview | The Java type's Javadoc, with its examples in the page's language. There is one text, written for every language: a Java type or member it names, in a `{@link}` or a `{@code}`, is named the page's way (by the rule above, or as the language's code has it), and a Java literal or `Object` method the language's way (`null` is `None` in Python). |
| A Docker page | Its README under `docker/`, as GitHub renders it (`marked`, with GitHub's heading ids). A link to another of those READMEs goes to its page, a link to any other file goes to it on GitHub, and a link to a file that isn't there fails the build. `PA_DOCKER` in `scripts/site.mjs` lists the pages. |
| The text of a landing page | `content/<project>.md`: Markdown with no code in it. `::install`, `::example <Class> <region>` and `::file <path> <language>` are replaced with the above (`scripts/pages.mjs`, `scripts/markdown.mjs`); `::walkthroughs` lists the project's walkthroughs. |
| The text of a walkthrough | `content/walkthroughs/<project>/<slug>.md`, written the same way, with its title, summary, description and order in its front matter (`scripts/walkthroughs.mjs`). |
| A prompt for a coding agent | `content/prompts/<project>.md`: plain text with placeholders (`{{package}}`, `{{install}}`, `{{docs}}`...) filled in from the above, per language. `::prompt` shows it on the landing page, with a copy button and links that open it in Claude (`scripts/prompts.mjs`). A prompt file has no code or command of its own. |
| What an example printed | `example-output/<project>/<language>/<Class>.json`, written by the live tests (below). |

The build fails when a page names an example that isn't there, when a language lacks an example
the Java documentation quotes, when a language lacks a Java member that its
`coverage/members-excluded.txt` doesn't list (or lists one it has, or one Java no longer has), or
when an overview on another language's page names something only Java has (`Mono`, `JsonNode`, a
Java member name that the language's API and code don't have: `javaIsms` in `scripts/members.mjs`), or
when a link on a generated page goes nowhere (`scripts/check-links.mjs`), or when a doc comment the
reference reads has code typed into it rather than quoted from a region (`scripts/doc-code.mjs`). So a new Java example
needs its three counterparts before the build passes, and so does a new Java member.

## Walkthroughs, and what the examples print

A walkthrough is a task, step by step, for someone new to a library. Readers find a project's
walkthroughs on its landing page, under its quick start (the first one) and in their own section;
on its docs home, in every language; in the docs search, by title or by words of the summary; and
on the page of each type a walkthrough's examples use ("Used in"). That last list is worked out
from the walkthroughs' Java example regions: the documented types a region names, and the services
it reaches through one of `Tm1Client`'s accessors (`tm1.cellsets()` is `CellsetService`). Each step's code is a region
of a `<Task>Walkthrough` examples class (`ConnectWalkthrough`, in every language), and under the
code is what it printed when it ran against a real TM1. That output is never typed by hand:

1. The live tests run each walkthrough's steps against IBM's SData sample, on a TM1 they start
   for it: `WalkthroughExamplesTm1Test` in tm1-client (and its ports, `test_walkthrough_examples.py`,
   `WalkthroughExamplesLiveTests`, `WalkthroughExamples.live.test.ts`), and
   `HelloTm1ApplicationTm1Test` in the Spring Boot starter.
2. Each language's `ExampleOutput` captures what a step prints, and writes
   `example-output/<project>/<language>/<Class>.json`: each region's output, as lines, and a hash
   of its code (each line trimmed, blank ones left out, SHA-256), worked out the same way in every
   language and here (`scripts/output.mjs`).
3. The build shows a region's output under its code, and fails when a region's code has changed
   since its output was recorded, when the languages printed different things, when a
   walkthrough's region prints and has no recording, or when a recording names a file or region
   that's gone.

So after changing a walkthrough's example, run the live tests (`./gradlew liveTest --continue`,
with a TM1 source such as `TM1_HOME_DIR`; the starter's test runs in `./gradlew test` with one),
and commit the recordings with the change. On a landing page an example's output is shown when it
has one, and isn't required.

## How it fits together

| Piece | What |
|---|---|
| `apiSiteJavadoc` (Gradle, every library) | The library's Javadoc with `theme/javadoc-theme.css` added, into `<module>/build/docs/api-site`. A separate task, so the javadoc jar on Maven Central stays plain. Defined in `build-logic/.../tm1.java-library.gradle.kts`. |
| `theme/javadoc-theme.css` | Maps Javadoc's CSS variables onto the site's design tokens, which it imports from `/tokens.css` and `/chrome.css` on skipcheck.io at view time. |
| `scripts/assemble.mjs` | Copies each library's Javadoc into `dist/<project>/api/java/`, and hashes the inline scripts, event handlers and style attributes Javadoc writes, for the CSP. Empties `dist/` first, so it runs first. |
| `scripts/extract.mjs` | Each project's API per language, into `.generated/api/`. |
| `scripts/reference.mjs` | The reference pages and `.generated/site.json` for the Worker: the projects, and which one documents each type. |
| `scripts/members.mjs` | Which member is which across languages, and the gate that each language has every Java member or lists it with the reason. |
| `scripts/doc-code.mjs` | The gate that no doc comment the reference reads has code typed into it: a Javadoc `<pre>` or inline `{@snippet}`, a .NET `<code>`, a TypeScript fence or `@example`, a Python `::` block or doctest. |
| `scripts/search.mjs` | The search index of each docs language, `search.json` beside its pages: every type and member, a short summary, and where it is. `reference.mjs` writes it from the data it renders the pages with. |
| `scripts/pages.mjs` | The landing pages and the walkthroughs. |
| `scripts/sitemap.mjs` | `/docs/sitemap.xml`: every generated page but the Javadoc, for search engines. skipcheck.io's `robots.txt` names it. |
| `scripts/walkthroughs.mjs` | Each project's walkthroughs, from `content/walkthroughs/`. |
| `scripts/output.mjs` | What the examples printed (`example-output/`), and the checks on it. |
| `scripts/docker.mjs` | The Docker pages, from the READMEs. |
| `scripts/prompts.mjs` | The prompts for coding agents, one per landing page and language. |
| `scripts/llms.mjs` | `llms.txt` and `llms-full.txt`, from the content files, the examples and the overviews `reference.mjs` writes to `.generated/reference.json`. |
| `scripts/check-links.mjs` | Checks every link on the landing and docs pages, every entry of the search indexes, every address in `llms.txt`, `llms-full.txt` and the prompts, and that a prompt's install commands are `packages.mjs`'s. |
| `assets/` | The generated pages' own styles and script, copied to `/<project>/assets/`: `docs.css` (the reference's layout), `docs.js` (the reference's language memory), `search.js` and `search-rank.js` (the reference's search box, and how it matches and ranks), `tabs.css` and `tabs.js` (the landing pages' language tabs), `readme.css` (what the Docker pages add to `docs.css`). A language chosen anywhere is remembered and followed everywhere. |
| `src/worker.js` | The Cloudflare Worker that serves `dist/`. It inserts the site's masthead and footer (`/partials/` on skipcheck.io, cached for 5 minutes) into every page, names its skipcheck.io address as canonical, sets the security headers, serves the site's 404 page for missing paths, and sends the addresses these pages had under `/projects/` to the new ones. |
| `wrangler.jsonc` | The Worker's config: a route per project, for `/docs/` and for `/pa-docker/` on each zone, and one per zone for the old addresses. |
| `test/` | `npm test` here, or `./gradlew checkApiSite`: the Worker, the Markdown, the install steps against the real build files, that every project has its routes, the extractors and the member gate. |
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
./gradlew apiSiteJavadoc docsModel
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

A new README under `docker/` gets a page when it's added to `PA_DOCKER` in `scripts/site.mjs`.
Until then, links to it go to GitHub.

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
- **Search is in the page, not a service.** Each docs language has its own index, loaded when the
  reader first uses the box: the largest, the TM1 client's in .NET, is about 58 KB (16 KB
  gzipped). It finds names only, not the text of the pages. A name matches by its words, whatever
  the language's casing, so `cubes` finds `model_cubes` and `ModelCubesAsync`.
- **The theme follows the OS.** The site has no light/dark switch, so the theme hides Javadoc's.
- **Pages are never revalidated against the Javadoc file.** A page is the file plus the site's
  partials, so the file's `ETag` doesn't identify it. The Worker always fetches pages in full and
  sends them without validators (`Cache-Control: no-cache`). CSS, scripts and images keep theirs.
- **The Docker pages are the READMEs, rendered.** The READMEs stay written for GitHub, where
  people read them next to the code, and the pages follow them without a copy. That's why the
  Docker pages use a full Markdown parser (`marked`), not the small one the content files are
  written in. `/projects/pa-docker/setup/`, the setup page, stays the site's; the site sends
  `/projects/pa-docker/` to `/pa-docker/`.
- **The masthead is a `<div>` in the docs.** Javadoc's script measures the first `<header>` on the
  page to place its sticky navigation bar, so the Worker renames the site's masthead element.