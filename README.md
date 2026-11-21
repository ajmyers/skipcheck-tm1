# TM1 / Planning Analytics Developer Tools

Java libraries and tools for the **IBM Planning Analytics (TM1) REST API**.

| Module | What it is |
|---|---|
| [`tm1-client`](tm1-client) | Plain Java client. No Spring container required. |
| [`tm1-spring-boot-starter`](tm1-spring-boot-starter) | Spring Boot auto-configuration for the client. |
| [`tm1-testcontainers`](tm1-testcontainers) | Real TM1 servers in Docker for your tests, with [Testcontainers](https://testcontainers.com). Bring your own licensed TM1. |
| [`tm1-bom`](tm1-bom) | Keeps the module versions aligned. |
| [`tools/`](tools) | Applications built on the libraries (not published). |
| [`docker/tm1-server`](docker/tm1-server) | Docker image for TM1 Server, for local development and tests. You supply your own licensed TM1; no IBM software is included. **[Getting started](docker/tm1-server/GETTING-STARTED.md)** |
| [`docker/tm1-web`](docker/tm1-web) | Docker image for TM1 Web (Planning Analytics Spreadsheet Services), to run alongside `tm1-server`. You supply your own licensed TM1 Web; no IBM software is included. |

Requires Java 17+.

## Building

```sh
./gradlew build                          # compile + test everything
./gradlew :tools:example:bootRun
./gradlew publishToMavenLocal            # try the libraries from another project
```

Builds use JDK 27 via Gradle toolchains; published libraries target Java 17 bytecode.

## Releases

Pushing a tag like `v0.1.0` publishes everything at that version:

- Libraries go to Maven Central as `net.ajmyers.tm1:<module>:0.1.0` ([`release.yml`](.github/workflows/release.yml)).
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
