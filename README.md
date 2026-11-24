# TM1 / Planning Analytics Developer Tools

Libraries and tools for the **IBM Planning Analytics (TM1) REST API**: a Java client, and Python, .NET and TypeScript clients with the same API, all generated from one spec.

The same client in four languages, each with a testcontainers library beside it:

| | Client | Testcontainers | Installed as |
|---|---|---|---|
| [`java`](java) | [`tm1-client`](java/tm1-client) | [`tm1-testcontainers`](java/tm1-testcontainers) | `io.skipcheck:tm1-client`, `io.skipcheck:tm1-testcontainers` |
| [`python`](python) | [`tm1-client`](python/tm1-client) (sync and asyncio) | [`tm1-testcontainers`](python/tm1-testcontainers) | `skipcheck-tm1`, `skipcheck-tm1-testcontainers` |
| [`dotnet`](dotnet) | [`Tm1.Client`](dotnet/src/Tm1.Client) | [`Tm1.Testcontainers`](dotnet/src/Tm1.Testcontainers) | `Skipcheck.Tm1.Client`, `Skipcheck.Tm1.Testcontainers` |
| [`typescript`](typescript) | [`tm1-client`](typescript/tm1-client) (Node and browsers) | [`tm1-testcontainers`](typescript/tm1-testcontainers) | `@skipcheck/tm1-client`, `@skipcheck/tm1-testcontainers` |

Java is the reference: the other clients have its API, and their tests replay what its tests record ([`spec/conformance`](spec/conformance)). The testcontainers libraries run real TM1 servers in Docker for your tests, with [Testcontainers](https://testcontainers.com); you bring your own licensed TM1.

| Also here | What it is |
|---|---|
| [`java/tm1-spring-boot-starter`](java/tm1-spring-boot-starter) | Spring Boot auto-configuration for the Java client. |
| [`java/tm1-bom`](java/tm1-bom) | Keeps the Java module versions aligned. |
| [`java/tools`](java/tools) | Applications built on the libraries (not published). |
| [`spec`](spec) | The language-neutral API spec every client is generated from and counts its coverage against, exported from TM1's `$metadata` by `tm1-codegen`. |
| [`docker/tm1-server`](docker/tm1-server) | Docker image for TM1 Server, for local development and tests. You supply your own licensed TM1; no IBM software is included. **[Getting started](docker/tm1-server/GETTING-STARTED.md)** |
| [`docker/tm1-web`](docker/tm1-web) | Docker image for TM1 Web (Planning Analytics Spreadsheet Services), to run alongside `tm1-server`. You supply your own licensed TM1 Web; no IBM software is included. |
| [`docker/paw`](docker/paw) | Runs IBM's Planning Analytics Workspace Local images from your own PAW zip, next to `tm1-server`. No IBM software is included. |
| [`docker/cognos-server`](docker/cognos-server) | Docker image for Cognos Analytics, for testing TM1's Cognos (CAM) security with test users through LDAP and OIDC. You supply your own licensed Cognos; no IBM software is included. |
| [`docker/pa-setup`](docker/pa-setup) | One command for any mix of the above: TM1 servers, TM1 Web, PAW and Cognos, described in one file, `pa-environment.yaml`. |
| [`agent-plugin`](agent-plugin) | Agent skills that set these up for you: `tm1-setup` for one TM1 server, `pa-setup` for the rest. |

The Java libraries require Java 17+, the Python client Python 3.11+, the .NET client .NET 10, the TypeScript client Node 22+ or a modern browser (its testcontainers, Node 22.22+).

## Stability

These libraries are pre-1.0: their API may still change. Until 1.0.0, the version says what to expect:

- A minor release (0.1.0 → 0.2.0) may make breaking changes, in any client.
- A patch release (0.1.0 → 0.1.1) won't.

So pin an exact version, or a range that stays within one minor release: `^0.1.0` in npm, `~=0.1.0` in Python. All four clients share one version and change together. From 1.0.0 on, breaking changes come only with a new major version.

## Building

```sh
./gradlew build                          # compile + test everything
./gradlew :tools:example:bootRun
./gradlew publishToMavenLocal            # try the libraries from another project
./gradlew :tm1-codegen:exportSpec        # refresh spec/tm1-api.json after changing the snapshot or overlay
./gradlew checkAll                       # the above, and the Python, .NET and TypeScript clients' checks
./gradlew checkPython                    # one client: checkPython, checkDotnet, checkTypescript
./gradlew checkScripts checkApiSite      # the scripts and git hooks, and the API docs site (both part of checkAll)
./gradlew packageTest                    # build every package as a release does, install each somewhere clean, run it
./gradlew liveTest --continue            # every client's tests against a real TM1 (bring your own: see scripts/live-tests.sh)
```

The `check` and `liveTest` tasks run each client's own tool, the same on macOS, Linux and Windows, and are what CI runs: `pytest python` in a virtual environment Gradle makes (`build/python-venv`), `dotnet test dotnet/Tm1.slnx`, and `npm ci`, `npm test` and `npm run build` in `typescript`. Those commands work on their own too. They need Python 3.11+, the .NET 10 SDK and Node 22+ installed; `./gradlew build` needs only a JDK. [CONTRIBUTING](CONTRIBUTING.md#checks) says which to run for what, and what a pull request has to pass.

Builds use JDK 27 via Gradle toolchains; published libraries target Java 17 bytecode.

## Releases

Pushing a tag like `v0.1.0` to a commit of `master` publishes everything at that version ([`release.yml`](.github/workflows/release.yml)):

- The Java libraries go to Maven Central as `io.skipcheck:<module>:0.1.0`.
- The Python packages go to PyPI (`skipcheck-tm1`, `skipcheck-tm1-testcontainers`), the .NET packages to NuGet (`Skipcheck.Tm1.Client`, `Skipcheck.Tm1.Testcontainers`), and the TypeScript packages to npm (`@skipcheck/tm1-client`, `@skipcheck/tm1-testcontainers`).
- Docker images go to `ghcr.io/ajmyers/<image>:0.1.0` and `:latest` ([`docker.yml`](.github/workflows/docker.yml)), and the API docs are deployed. Every commit to `master` also publishes `:edge`. Pull requests build and smoke-test the images but don't push them.

Nothing is published until everything has passed on the tagged commit: all of CI, and each ecosystem's packages built with the release's version, installed somewhere clean and run ([package-tests](package-tests/README.md)). A tag that isn't on `master` is refused. PyPI, NuGet and npm get the very files that were tested. A published version can't be taken back, so if a registry fails after the gate, the others stay published: run the workflow again by hand for what's missing.

Before tagging:

1. Run the live tests, which CI can't: `./gradlew liveTest --continue`, with a TM1.
2. Do a dry run: `gh workflow run release.yml`. It runs the whole gate, uploads `0.0.0-dryrun.<n>` to the Central Portal for validation without releasing it (drop it there afterwards), and publishes nothing else. `-f only=python` (or `dotnet`, `typescript`, `maven`) limits what it uploads to.

A manual run can also publish a pre-release to one registry: `gh workflow run release.yml -f only=python -f python_version=0.1.0.dev1` (`dotnet_version` and `npm_version` take `0.1.0-dev.1`). A version can never be reused on a registry, even after it's deleted.

Each image has its own on/off switch, a repository variable named in `docker.yml` (e.g. `PUBLISH_DOCKER_TM1_SERVER`). Until it's set to `true`, that image is built and tested but never pushed:

```sh
gh variable set PUBLISH_DOCKER_TM1_SERVER --body true
```

To add an image, add the sub-project to the matrix in `docker.yml`, with its own `publish-var`.

## Project layout

- `build-logic/` holds convention plugins: `tm1.java-conventions`, `tm1.java-library` (published), `tm1.spring-boot-app` (tools), and `tm1.publishing`.
- `gradle/libs.versions.toml` is the single place for dependency versions.
- To add a tool, create `java/tools/<name>/build.gradle.kts` with `plugins { id("tm1.spring-boot-app") }`, then `include("tools:<name>")` in `settings.gradle.kts`.

## License

[Apache License 2.0](LICENSE): free to use, modify and distribute, in open source and in proprietary or commercial products alike. Keep the license and the [`NOTICE`](NOTICE) file with what you distribute.

---

*Not affiliated with or endorsed by IBM. IBM, TM1, and Planning Analytics are trademarks of International Business Machines Corporation.*
