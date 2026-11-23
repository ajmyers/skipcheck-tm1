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
| Java only | `./gradlew build` |
| The Java client's API or services, or `spec/` | `./gradlew checkAll` (the other clients' parity tests read them) |
| The Python, .NET or TypeScript client | `./gradlew checkPython`, `checkDotnet`, `checkTypescript` |
| What a package contains or depends on | `./gradlew packageTest` |
| `scripts/`, `.githooks/`, shell scripts | `./gradlew checkScripts` |
| `.github/workflows/` | Nothing local: CI lints them with actionlint. Read the workflow's header comment first, and say in the PR what you couldn't run |
| `api-site/` | `./gradlew checkApiSite` |
| How a client talks to TM1 | `./gradlew liveTest --continue`, if a TM1 is configured. CI can't run these: say in the PR whether you ran them |
| Not sure | `./gradlew checkAll packageTest` |

- A change to one client usually belongs in all four: the parity gates fail when the Java client
  has a class, method or test the others lack.
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
