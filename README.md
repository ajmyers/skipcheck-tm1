# TM1 / Planning Analytics Developer Tools

Libraries and tools for the **IBM Planning Analytics (TM1) REST API**: a Java client, and Python, .NET and TypeScript clients with the same API, all generated from one spec.

| Module | What it is |
|---|---|
| [`tm1-client`](tm1-client) | Plain Java client. No Spring container required. |
| [`tm1-spring-boot-starter`](tm1-spring-boot-starter) | Spring Boot auto-configuration for the client. |
| [`tm1-testcontainers`](tm1-testcontainers) | Real TM1 servers in Docker for your tests, with [Testcontainers](https://testcontainers.com). Bring your own licensed TM1. Ported to Python ([`tm1-python/testcontainers`](tm1-python/testcontainers)), .NET ([`tm1-dotnet/src/Tm1.Testcontainers`](tm1-dotnet/src/Tm1.Testcontainers)) and TypeScript ([`tm1-typescript/testcontainers`](tm1-typescript/testcontainers)). |
| [`tm1-bom`](tm1-bom) | Keeps the module versions aligned. |
| [`spec`](spec) | The language-neutral API spec every client is generated from and counts its coverage against, exported from TM1's `$metadata` by `tm1-codegen`. |
| [`tm1-python`](tm1-python) | Python client (sync and asyncio), with the Java client's API. |
| [`tm1-dotnet`](tm1-dotnet) | .NET client, with the Java client's API. See its [README](tm1-dotnet/README.md). |
| [`tm1-typescript`](tm1-typescript) | TypeScript client for Node and browsers, with the Java client's API. See its [README](tm1-typescript/README.md). |
| [`tools/`](tools) | Applications built on the libraries (not published). |
| [`docker/tm1-server`](docker/tm1-server) | Docker image for TM1 Server, for local development and tests. You supply your own licensed TM1; no IBM software is included. **[Getting started](docker/tm1-server/GETTING-STARTED.md)** |
| [`docker/tm1-web`](docker/tm1-web) | Docker image for TM1 Web (Planning Analytics Spreadsheet Services), to run alongside `tm1-server`. You supply your own licensed TM1 Web; no IBM software is included. |
| [`docker/paw`](docker/paw) | Runs IBM's Planning Analytics Workspace Local images from your own PAW zip, next to `tm1-server`. No IBM software is included. |

The Java libraries require Java 17+, the Python client Python 3.10+, the .NET client .NET 10, the TypeScript client Node 20+ or a modern browser.

## Building

```sh
./gradlew build                          # compile + test everything
./gradlew :tools:example:bootRun
./gradlew publishToMavenLocal            # try the libraries from another project
./gradlew :tm1-codegen:exportSpec        # refresh spec/tm1-api.json after changing the snapshot or overlay
pip install -e './tm1-python[test]' && pytest tm1-python
dotnet test tm1-dotnet/Tm1.slnx
(cd tm1-typescript && npm ci && npm test)
scripts/live-tests.sh                    # every client's tests against a real TM1 (bring your own: see the script)
```

Builds use JDK 27 via Gradle toolchains; published libraries target Java 17 bytecode.

## Releases

Pushing a tag like `v0.1.0` publishes everything at that version:

- Libraries go to Maven Central as `io.skipcheck:<module>:0.1.0` ([`release.yml`](.github/workflows/release.yml)).
- Docker images go to `ghcr.io/ajmyers/<image>:0.1.0` and `:latest` ([`docker.yml`](.github/workflows/docker.yml)). Every commit to `master` also publishes `:edge`. Pull requests build and smoke-test the images but don't push them.

Each image has its own on/off switch, a repository variable named in `docker.yml` (e.g. `PUBLISH_DOCKER_TM1_SERVER`). Until it's set to `true`, that image is built and tested but never pushed:

```sh
gh variable set PUBLISH_DOCKER_TM1_SERVER --body true
```

To add an image, add the sub-project to the matrix in `docker.yml`, with its own `publish-var`.

## Project layout

- `build-logic/` holds convention plugins: `tm1.java-conventions`, `tm1.java-library` (published), `tm1.spring-boot-app` (tools), and `tm1.publishing`.
- `gradle/libs.versions.toml` is the single place for dependency versions.
- To add a tool, create `tools/<name>/build.gradle.kts` with `plugins { id("tm1.spring-boot-app") }`, then `include("tools:<name>")` in `settings.gradle.kts`.

## License

Dual-licensed:

- **[AGPL-3.0](LICENSE)**: free to use, modify, and run, including inside companies. If you distribute it, or offer it over a network as part of your own software, that software must also be released under the AGPL.
- **[Commercial license](COMMERCIAL-LICENSE.md)**: for embedding in proprietary or commercial products without the AGPL's obligations.

---

*Not affiliated with or endorsed by IBM. IBM, TM1, and Planning Analytics are trademarks of International Business Machines Corporation.*
