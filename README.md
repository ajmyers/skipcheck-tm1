# TM1 / Planning Analytics Developer Tools

Java libraries and tools for the **IBM Planning Analytics (TM1) REST API**.

| Module | What it is |
|---|---|
| [`tm1-client`](tm1-client) | Plain Java client. No Spring container required. |
| [`tm1-spring-boot-starter`](tm1-spring-boot-starter) | Spring Boot auto-configuration for the client. |
| [`tm1-bom`](tm1-bom) | Keeps the module versions aligned. |
| [`tools/`](tools) | Applications built on the libraries (not published). |

Requires Java 17+.

## Building

```sh
./gradlew build                          # compile + test everything
./gradlew :tools:example:bootRun
./gradlew publishToMavenLocal            # try the libraries from another project
```

Builds use JDK 27 via Gradle toolchains; published libraries target Java 17 bytecode.

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
