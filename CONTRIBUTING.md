# Contributing

Issues and pull requests are welcome.

The project is under the [Apache License 2.0](LICENSE), and so is what you contribute to it
(section 5 of the license). You keep the copyright to your work, and there's no separate
agreement to sign.

## Workflow

1. Every change starts with a GitHub issue that records the what, the why, the how, and any context.
2. Work on a branch named after the issue, e.g. `gh issue develop 12 --checkout`.
3. Before opening a PR, run `./gradlew build`, or `./gradlew checkAll` to check the Python, .NET
   and TypeScript clients too: a change to the Java client's API or to `spec/` can break their
   parity tests. CI runs the same tasks, and a PR merges only when all of them pass. Every commit
   must build on its own: the pre-push hook builds each commit you push that the remote doesn't
   have yet, in a temporary worktree, and refuses the push if one fails. It skips TM1 tests (as CI
   does), so run those by hand: `./gradlew liveTest --continue`. `scripts/test-summary.py` prints
   how many tests each suite ran, passed and skipped; CI puts it in each job's summary.

## Checks

Every codebase has tests, and one Gradle task runs each. They're the same tasks CI runs, on macOS,
Linux and Windows. Run what matches what you changed:

| You changed | Run |
|---|---|
| Java only, and no test or behaviour of the Java client or of `tm1-testcontainers` | `./gradlew build` |
| The Java client's public API, services, behaviour or tests, or anything in `spec/` | `./gradlew checkAll`: the other clients' parity and conformance tests read them |
| `java/tm1-testcontainers`: its public API or its tests | `./gradlew checkAll`: the other languages' Testcontainers parity tests read them, so a new or renamed Java test needs the same test in Python, .NET and TypeScript |
| The Python, .NET or TypeScript client | `./gradlew checkPython`, `checkDotnet` or `checkTypescript` |
| What a package contains or depends on (`pyproject.toml`, a `.csproj`, `package.json`, the publishing plugin, a build hook) | `./gradlew packageTest` ([package-tests](package-tests/README.md)) |
| `scripts/`, `.githooks/`, a shell script, `docker/pa-setup`'s setup scripts | `./gradlew checkScripts` |
| `api-site/` | `./gradlew checkApiSite` |
| How a client talks to TM1 | `./gradlew liveTest --continue`, with a TM1 ([`scripts/live-tests.sh`](scripts/live-tests.sh) says how to give it one). CI has no TM1 and can't run these for you |
| Not sure | `./gradlew checkAll packageTest` |

`checkAll` is the Java build, the three other clients, the scripts and hooks, the API docs site and
the license lines. The clients' own commands (`pytest`, `dotnet test`, `npm test`) still work; the
tasks only set them up and run them.

**The four clients change together.** They are one API in four languages, and Java is the
reference. A change to how a client behaves lands in all four in the same pull request, each with
a test: write the Java test first, then the same test under the same name in Python, .NET and
TypeScript. A fix found in another client goes into Java as well. These gates read the Java sources
and fail a client's checks, or the docs build, when it lacks something Java has:

| What Java has | What holds the other clients to it |
|---|---|
| A public class or method | The `java-parity` tests |
| A service call and what it sends | The recorded calls in [`spec/conformance`](spec/conformance/README.md), which every client replays |
| A live test | The live-parity tests, and each client's `coverage/live-excluded.txt` |
| A unit test of the runtime (session, pool, batch, authenticators, OData, errors) | The unit-parity tests, and each client's `coverage/unit-excluded.txt` |
| A public type, and a public member of a documented type | The docs build ([`api-site/scripts/members.mjs`](api-site/scripts/members.mjs)), and each client's `coverage/members-excluded.txt` |
| A public class or method of `tm1-testcontainers` | Each language's Testcontainers `java-parity` tests |
| A test of `tm1-testcontainers` (unit or live, the example tests too) | Each language's Testcontainers test-parity tests, and its exclusions file: `python/tm1-testcontainers/coverage/unit-excluded.txt`, `dotnet/coverage/testcontainers-unit-excluded.txt`, `typescript/tm1-testcontainers/coverage/unit-excluded.txt` |

A test with no meaning in a client gets a line in its exclusions file saying why; a line that has
gone stale fails the gate. "Not ported yet" is not a reason. The gates can't see a behaviour change
that comes with no new Java test, which is why the test comes first.

**A test that needs TM1 is skipped without one, never left out.** Mark it with the language's
`RequiresTm1` (`@RequiresTm1`, `@pytest.mark.requires_tm1`, `[RequiresTm1Fact]`, `requiresTm1`), so
it's collected everywhere and runs wherever a TM1 is configured. Each CI job's summary shows how
many tests ran and how many were skipped (`scripts/test-summary.py` prints the same table locally).

**New code comes with tests that CI runs.** That goes for scripts, hooks and workflows too: they're
what decides whether a change merges. A new kind of check is a Gradle task first, then a CI job
that runs it.

## What gates a merge

The `master` ruleset requires three checks:

- `ci-ok` passes only when every job of [`ci.yml`](.github/workflows/ci.yml) did: each client's
  checks, the scripts, the package tests, the API docs site, the paw and setup scripts.
- `docker-ok` passes only when every image built and passed its smoke test.
- `conventions` checks the PR's title and description.

So:

- **A branch needn't be up to date with `master` to merge.** Requiring it made every merge send
  every other open PR back for a rebase and another run of CI, and there's no merge queue to do
  that for us: GitHub doesn't offer one on a repository a user owns. So two PRs that each pass
  can, rarely, break `master` together. CI runs on every commit of `master` and shows it within
  minutes, and a release checks the tagged commit again, so it can't ship. If `master` is red,
  mending it comes before anything else merges.
- **When a branch conflicts with `master`, rebase it** (`git rebase origin/master`, then
  `git push --force-with-lease`) rather than merging `master` in.
- **A new CI job goes in `ci-ok`'s `needs`,** or it gates nothing. A new image goes in
  `docker.yml`'s matrix, which `docker-ok` covers.
- **A workflow that gates has no path filter.** A required check can't wait for a workflow that
  didn't start, so a workflow of its own is called from `ci.yml` instead (as `paw.yml`,
  `setup-scripts.yml` and `api-docs.yml` are).
- **Don't work around a red check:** no `--admin` merge, no `--no-verify`, no test deleted or
  skipped to get through. Fix the cause, or say why it can't be fixed in the PR.

Releases have a gate of their own: see [Releases](README.md#releases).

## Commit and PR messages

Enable the commit message hook once per clone:

```sh
git config core.hooksPath .githooks
```

Commit messages follow this structure (checked by `.githooks/check_message.py`):

```
Add cube listing to CubeService

What changed, why, and the decisions behind it that the code can't show.

Refs: #12
```

- Title: imperative, 72 characters or fewer, capitalized, no trailing period, no issue numbers.
- Description: required.
- `Refs: #N` trailer: required; it links the issue.

PRs are squash-merged, so the PR title and description become the commit on `master`. CI checks that the title follows the same rules and that the description links the issue with `Closes #N` or `Part of #N`. A PR a bot opened (Dependabot) isn't checked: it writes its own title and carries its own context.

## Code style

**Layout is Spring Java Format, and the build checks it.** `./gradlew build` fails on a
file the formatter would change; `./gradlew format` fixes them all. The pre-commit hook formats
what you stage, so you rarely need to (skip it once with `SKIP_FORMAT_HOOK=1`). The same check
fails on unused imports, imports out of order (static, `java`/`javax`, `org`, then the rest) and
fully qualified type names in code; `format` fixes all but the last. In a Javadoc code example,
write `@` as `&#64;` inside `<pre class="code">`, as Spring does: the formatter takes an `@` at
the start of a line for a block tag and breaks the comment, which the check also reports.

**The other clients have the same guard rails.** Each has a formatter that decides the layout, at
160 columns, and its ordinary build or test command fails on a file the formatter would change:

| | Formatter | Fix | Checked by | Lint |
|---|---|---|---|---|
| Java | Spring Java Format | `./gradlew format` | `./gradlew build` | Error Prone, NullAway |
| Python | Ruff | `ruff format python && ruff check --fix python` | `pytest` (`tests/test_format.py`) | Ruff |
| TypeScript | Prettier | `npm run format` | `npm test` | ESLint, strict `tsc` |
| .NET | CSharpier | `dotnet csharpier format dotnet` | `dotnet build` | .NET analyzers, warnings as errors |

The pre-commit hook formats what you stage in all four, and refuses the commit if a formatter
can't run or a file still isn't formatted. Install the tools once: `pip install -e
'./python/tm1-client[test]'`, `npm ci` in `typescript`, and `dotnet tool restore` in `dotnet`.
The pre-push hook runs the same checks on every commit that touches a client, and CI runs them
again. Generated code is never formatted, and neither are the recorded fixtures in
`spec/conformance`. Rules a client doesn't follow are listed, each with its reason, in
`python/ruff.toml`, `typescript/eslint.config.mjs` and `dotnet/Directory.Build.props`.

**Every source file names its license.** Code, scripts, stylesheets and Dockerfiles start with
`SPDX-License-Identifier: Apache-2.0`, in a comment. The pre-commit hook adds the line to what you
stage; `scripts/license-headers.py --fix` adds it everywhere, and says which files get one. Each
client's build fails on a file without it (`./gradlew build`, `pytest`, `npm test`, `dotnet build`),
the pre-push hook checks every commit, and CI checks the whole repository. Generated code gets the
line from its generator.

**Public API needs Javadoc.** In the published libraries, every public class, method, constructor
and record component is documented, with `@param` and `@return`. `./gradlew build` runs Javadoc
and fails on any gap. For a Lombok `@Getter`, put the `@return` line in the field's Javadoc;
Lombok copies it onto the accessor.

**Show how to use the main types.** `Tm1Client`, `Tm1Session`, every service and the
testcontainers and Spring Boot entry points have usage examples in their Javadoc. The examples are
real code in the library's `src/examples/java`, in a package named just `examples` (which keeps
the `{@snippet}` tags short enough for the formatter to leave on one line), one
`<Type>Examples` class per documented type. The build compiles them against the library, so an
API change that breaks one fails the build. The Javadoc quotes a marked region of one:

```java
// In src/examples/java/examples/CubeServiceExamples.java
Flux<String> list(Tm1Client tm1) {
	// @start region = list
	return tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)).map(Cube::name);
	// @end
}

// In CubeService's Javadoc
 * {@snippet class = examples.CubeServiceExamples region = list}
```

- Show the common tasks of the type, a few per class, each introduced by a sentence. Not every
  method needs one.
- Write them as an application would: methods that take a `Tm1Client` and return the `Mono` or
  `Flux`, without `block()`, except where a standalone `main` is the point.
- Use what users can use: the public API only, so the examples sit in their own package.
- Respect TM1's quirks: an example mustn't do what the Javadoc says TM1 refuses or ignores. The
  integration tests (`*Tm1Test`) show what works.
- The examples are compiled but not run, since they need a TM1 server. The walkthroughs'
  examples (`<Task>Walkthrough`, shown on the site's walkthrough pages) are the exception: they
  block, since a walkthrough runs its steps in order from a `main`, and the live tests run them
  and record what they print, which the pages show under the code. Change one, run the live tests,
  and commit the recording in `api-site/example-output` with it: the site's build fails on a
  stale one ([api-site/README.md](api-site/README.md#walkthroughs-and-what-the-examples-print)).
  The quick start's `HelloWorld` is run the same way: its `main` connects to localhost:8010 and
  calls `hello`, which the live test (`HelloWorldTm1Test`) calls with a server of its own.
- Spring Java Format writes `region = list` with spaces, in the comment and the `{@snippet}`
  tag. Javadoc accepts both forms.
- **Never write code into the site's Markdown.** Every code block on a landing page or
  walkthrough comes from a directive: `::example` (a compiled example region, in every
  language), `::file` (a file a test loads) or `::install` (generated from the build files).
  A fenced code block in a content file fails the site's build, so code on the site is always
  compiled and tested.
- **Never type code into a doc comment either.** A Javadoc quotes a region with `{@snippet}`, or
  a file a test reads with `{@snippet file = examples/application.yml}`. The other languages'
  pages show the same region in their language, so their member comments need no copy. The site's
  build fails on a `<pre>` or inline `{@snippet}` in a Javadoc, a `<code>` block in a .NET member's
  comment (use `<c>` for a name), a fenced block or `@example` in a TypeScript member's, and a `::`
  block or doctest in a Python member's. The other languages' type comments are left alone: the
  site shows Java's overview, so they never reach a page.

**Document TM1's surprises where they bite.** When TM1 behaves differently from its
`$metadata`, or from what a reader would expect, say so in the Javadoc of the method or type it
affects, as "TM1 (11.8) …". That covers a property a PATCH ignores, an append where a replace is
natural, a 204 instead of an entity, or an operation it refuses. Pin it with a test against TM1,
and add it to the plan's §16.5 table. Agents and people reading the API learn it there, at the
call they're about to make.

**Error Prone and NullAway check every compile.** Their findings are errors. Library packages
are `@NullMarked` (JSpecify): nothing is null unless declared `@Nullable`, and NullAway checks
that at compile time, so a nullable field, parameter or return gets `@Nullable` where it's
declared. Lombok copies it onto the accessors it generates. Tests aren't null-checked.

**Architecture rules are tests.** `ArchitectureTest` in `tm1-client` checks the compiled library:
it never blocks or calls a bare `subscribe()`, builds request URIs instead of using URI templates,
logs only through SLF4J, and keeps the `odata` package free of TM1. Each rule's `because` says
why. If one fails, fix the code; change a rule only with a reason in the commit.

**Value types are records.** Immutable data (requests, results, model types) is a Java
record. Records already remove the boilerplate of constructors, accessors, `equals`,
`hashCode` and `toString`.

**Lombok covers what records don't.** It's set up for every module, compile-time only, so
published libraries don't depend on it. Settings are in the root `lombok.config`, and
accessors are fluent (`status()`, not `getStatus()`) to match records.

| Use | For |
|---|---|
| `@RequiredArgsConstructor` | Constructor injection of services and components |
| `@Getter` on fields | Accessors on classes that can't be records, such as exceptions and container subclasses. Put the Javadoc on the field; delombok copies it to the accessor. |
| `@Builder` | Options and configuration with many optional settings, on types that aren't public API. A public builder is written by hand, since the Javadoc rule needs every public method documented and Lombok's generated builder has nowhere to carry it (see `Tm1Timeouts`). |
| `@With` | Copy-on-change of immutable values, including on records |
| `@Slf4j` | Logging (see below) |

Not used, and refused by `lombok.config`: `@Data`, `@Setter`, `@SneakyThrows`, `val`, `var`
and every logger annotation except `@Slf4j`. Experimental features need a good reason.

**Logging goes through SLF4J,** with Lombok's `@Slf4j`. The libraries depend only on
`slf4j-api`, and the application chooses the backend (Spring Boot's is Logback).

- `debug` for each request and response, `info` for session lifecycle (login, re-login,
  close), `warn` for something the caller should know about but that didn't fail, such as a
  close that timed out. Failures reach the caller as errors, so don't also log them as `error`.
- Use placeholders (`log.debug("{} {}", method, url)`), not string concatenation.
- **Never log credentials:** no `Authorization` headers, cookies, CAM passports, passwords
  or pre-signed URLs, at any level.

**Metrics and traces go through Micrometer's Observation API,** which Spring Boot turns into
metrics and traces. The client takes an `ObservationRegistry` (a no-op one by default).

- Every HTTP request is already observed by WebClient (`http.client.requests`), so don't
  wrap single requests again.
- Add an observation for work above one request: logging in, closing a session, and later
  session pool events and long-running operations. Name them under `tm1.`, e.g.
  `tm1.session.login`.
- Tags (low-cardinality key values) must come from a small, fixed set: an outcome, a reason,
  a kind. Object names, URLs and user names go in high-cardinality values, or nowhere.
- The same rule as logging: never put credentials in tags or events.
- Test them with `TestObservationRegistry` from `micrometer-observation-test`.

**Compiler warnings fail the build** (`-Werror`). Fix the code; suppress a warning only with a
comment saying why, as `Tm1ServerContainer` does for `this-escape`.
