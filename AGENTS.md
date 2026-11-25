# Working in this repository

For AI agents, and anyone else who wants the short version. [CONTRIBUTING.md](CONTRIBUTING.md) has
the detail and the reasons; this is what's expected.

## Before you start

- Every change has a GitHub issue first (what, why, how, context), a branch named after it, and a
  pull request that links it. Commits and the PR follow the message rules in CONTRIBUTING.
- Enable the hooks once per clone or worktree: `git config core.hooksPath .githooks`. Don't bypass
  them (`--no-verify`); fix what they refuse.

## Checking your work

Run the Gradle task for what you changed before you push. They're what CI runs.

| You changed | Run |
|---|---|
| Java only, and no test or behaviour of the Java client | `./gradlew build` |
| The Java client's API, services, behaviour or tests, or `spec/` | `./gradlew checkAll` (the other clients' parity tests read them) |
| The Python, .NET or TypeScript client | `./gradlew checkPython`, `checkDotnet`, `checkTypescript` |
| What a package contains or depends on | `./gradlew packageTest` |
| `scripts/`, `.githooks/`, shell scripts | `./gradlew checkScripts` |
| `.github/workflows/` | Nothing local: CI lints them with actionlint. Read the workflow's header comment first, and say in the PR what you couldn't run |
| `api-site/` | `./gradlew checkApiSite` |
| `java/tm1-codegen/`, `spec/tm1-overlay.json`, or the probe file | `./gradlew :tm1-codegen:exportSpec` (rewrites `spec/tm1-api.json`), then `./gradlew :tm1-client:test -PrecordConformance` (rewrites `spec/conformance/builders.json` and the recorded calls), then `./gradlew checkAll`. See [the conformance README](spec/conformance/README.md) |
| `java/tools/tm1-probe/` | `./gradlew :tools:tm1-probe:build`; for a new TM1 version, `./gradlew probeTm1` with a TM1 source, and commit the file it writes ([its README](java/tools/tm1-probe/README.md)) |
| How a client talks to TM1 | `./gradlew liveTest --continue`, if a TM1 is configured: every client's live tests, then a comparison of what each sent TM1 (`scripts/compare-live-traffic.py`), which must be equal. CI can't run these: say in the PR whether you ran them |
| Not sure | `./gradlew checkAll packageTest` |

- **The four clients change together.** A change to how a client behaves (a fix, a new option, a
  different error) lands in Java, Python, .NET and TypeScript in the same pull request, each with a
  test. Java goes first: write the Java test, then the same test under the same name in the other
  three. A fix found in another client goes into Java too, with its test.
- The parity gates hold you to it. They read the Java sources, and a client's checks fail when Java
  has a public method, a service call, a live test or a runtime unit test it lacks. A test with no
  meaning in a client gets a line saying why in its `coverage/unit-excluded.txt` (or
  `live-excluded.txt`); "not ported yet" is not a reason.
- The gates can't see a behaviour change that comes with no new Java test, so don't make one.
- Each client has its own generator, reading `spec/tm1-api.json`. A write type's rules (its
  setters, what `builder(read)` copies and binds) are made once, in `tm1-codegen`'s `Builders`, and
  exported as the spec's `builders`: a generator takes every decision from that plan, and only
  names, types and documents the code itself. What the generated write types offer is held to
  `spec/conformance/builders.json`, and what `builder(read)` writes to the cases in
  `spec/conformance/writes.json`: a change to what `builder(read)` copies or binds comes with a
  case there, edge cases included (an unknown subtype, a null, an empty collection).
- A write the probe finds TM1 applies needs a step in a live scenario (`spec/conformance/live`):
  `ProbedWritesCoverageTest` fails until it has one, and `ProbeCoverageTest` fails when the
  builders and the probe disagree. Every builder and setter is written by some live scenario, or
  listed in `spec/conformance/live-builders-pending.txt` (what's still to write) or
  `live-builders-excluded.txt` with why: `LiveBuildersCoverageTest` holds it.
- Every commit builds on its own: the pre-push hook runs `./gradlew build` on each (and the lint of
  a client the commit touches, not its tests). Java can go in a commit before the other clients,
  but a commit that leaves the Java build failing has to be squashed before pushing.
- New code comes with tests that CI runs, scripts and hooks included.
- A test that needs TM1 is marked with the language's `RequiresTm1`, so it's skipped without a TM1
  rather than left out. Never delete, disable or loosen a test to get a check through.
- Report what you ran and what you couldn't. "Not run: no TM1 here" is a fine thing to say; a
  check that didn't run isn't one that passed.

## Merging

- `master` requires `ci-ok`, `docker-ok` and `conventions`. A branch needn't be up to date with
  `master`; when it conflicts, rebase it (`git push --force-with-lease` afterwards) rather than
  merging `master` in.
- After you merge, look at CI on `master`. Two PRs that each passed can break it together; if
  it's red, mending it comes before anything else.
- Squash-merge, with the PR's title and description as the commit: write the description as the
  permanent record.
- Don't merge past a red check (`--admin`), and don't change the ruleset, the required checks or a
  workflow's gate to get a change in. If a check is wrong, fix the check in its own PR.
- A new CI job goes in `ci-ok`'s `needs` in `.github/workflows/ci.yml`; a workflow that gates has no
  path filter.

## Releasing

Only when asked. A tag `vX.Y.Z` on a commit of `master` publishes to Maven Central, PyPI, NuGet and
npm, permanently, after the release workflow's own gate. Run the live tests and a dry run
(`gh workflow run release.yml`) first: see [Releases](README.md#releases).
