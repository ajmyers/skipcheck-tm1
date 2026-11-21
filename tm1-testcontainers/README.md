# tm1-testcontainers

Run tests against a real IBM Planning Analytics (TM1) server in Docker, with
[Testcontainers](https://testcontainers.com). Built on the [`tm1-server`](../docker/tm1-server)
image.

> **No IBM software is included.** You supply your own licensed TM1 Server for Linux, and
> you're responsible for following your IBM license terms. Without it, tests that need TM1
> are skipped.

```kotlin
testImplementation("net.ajmyers.tm1:tm1-testcontainers:<version>")
```

Tested with TM1 Server 11.8.03500.6 (Planning Analytics 2.1.19), the only version we've had
to test with. If you run it with another version, please open an issue saying how it went.

## Writing a test

```java
@RequiresTm1
class CubeTest {

    @Test
    void listsCubes(Tm1ServerContainer tm1) {
        String url = tm1.getRestUrl();      // http://localhost:54321/api/v1/
        String user = tm1.getUsername();    // admin, with no password
        ...
    }
}
```

`@RequiresTm1`:

- **Skips** the test when Docker isn't running or no TM1 source is configured, so builds
  without IBM software (such as GitHub CI) stay green. A source that's configured but wrong
  fails instead of skipping.
- **Injects** a `Tm1ServerContainer` parameter: one server per test JVM, started on first
  use and shared by every test class.

Because tests share a server, each test should create its own uniquely named objects and
delete them afterwards.

For a container of your own (another version, a sample database, your own data):

```java
try (var tm1 = new Tm1ServerContainer(new Tm1Source.InstallDir(Path.of("/opt/ibm/tm1_64")))
        .withSeed(Tm1Seed.SDATA)                          // IBM's SData sample, admin / apple
        .withExpectedVersion(Tm1Version.parse("11.8.03500.6"))) {
    tm1.start();
    ...
}
```

`withData(dir)` copies a database folder in, and `withCredentials(...)` sets its login. Once
started, the container reads the server's version (`getTm1Version()`). With
`withExpectedVersion`, it fails to start on a mismatch, before any test runs against the wrong
TM1.

## Examples

Each of these is a real test in [`src/test/java/.../examples`](src/test/java/net/ajmyers/tm1/testcontainers/examples),
run on every build where TM1 is configured, so they always work as written.

| You want to... | Example |
|---|---|
| Share one server across all your tests: injected into a test method, a constructor, or a static `@BeforeAll` that sets up a client once per class | [`SharedServerExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/SharedServerExampleTest.java) |
| Give one test class its own server, with IBM's SData sample to read | [`SampleDatabaseExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/SampleDatabaseExampleTest.java) |
| Run your own database (a fixture model in `src/test/resources`) | [`OwnDatabaseExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/OwnDatabaseExampleTest.java) |
| Require one TM1 version, or skip tests on versions without a feature | [`PinnedVersionExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/PinnedVersionExampleTest.java) |
| Run two servers at once, e.g. to test a migration or comparison | [`TwoServersExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/TwoServersExampleTest.java) |
| Choose where TM1 comes from in code: install folder, bundle, download link, Docker volume or private image | [`ExplicitSourceExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/ExplicitSourceExampleTest.java) |
| Skip the extension: plain assumptions and a throwaway server, e.g. for a test that damages its server | [`WithoutTheExtensionExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/WithoutTheExtensionExampleTest.java) |
| Run TM1 Web against a TM1 server of its own | [`Tm1WebExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/Tm1WebExampleTest.java) |

Spring Boot tests will connect with `@ServiceConnection` once #15 lands.

## Telling it where TM1 is

Set **one** of these, as an environment variable or a system property with the same name.
The names match the `tm1-server` compose settings, so a `.env` that works for compose works
here too. If several are set, the first in this list wins.

| Setting | TM1 comes from | Start time |
|---|---|---|
| `TM1_PRIVATE_IMAGE` | An image with TM1 built in, from a **private** registry ([how to build one](../docker/tm1-server/examples/private-image/Dockerfile)) | ~3 s |
| `TM1_HOME_DIR` | An installed `tm1_64` folder (the one with `bin64/tm1s.exe`), mounted read-only | ~3 s |
| `TM1_INSTALL_VOLUME` | A Docker volume holding an install, e.g. compose's `tm1_tm1-install` | ~3 s |
| `TM1_BUNDLE_DIR` (+ `TM1_BUNDLE`) | A folder holding IBM's `tm1server-*-linuxi38664h_bundle.tar.gz` (and the file to use, if there are several) | ~15 s the first time, then ~5 s |
| `TM1_BUNDLE_URL` (+ `TM1_BUNDLE_SHA256`) | A download link for the bundle, such as a pre-signed S3 URL. Never logged with its query string. | download + ~15 s, then ~5 s |

Bundles are installed into the Docker volume `tm1-testcontainers-cache`, shared by every
test container on the machine, so only the first run installs. Remove the volume to clear it.

For example, to run this repository's TM1 tests:

```sh
TM1_HOME_DIR=~/ibm/tm1_64 ./gradlew test
TM1_BUNDLE_DIR=~/Downloads ./gradlew test
```

`TM1_SERVER_IMAGE` swaps the `tm1-server` image itself, e.g. `tm1-server:local` for a local
build. By default the image's tag matches this library's version, and `edge` for snapshots.

**Apple Silicon:** TM1 runs under Rosetta, as with compose. Turn on *Use Rosetta for
x86_64/amd64 emulation* in Docker Desktop (see the [getting-started
guide](../docker/tm1-server/GETTING-STARTED.md#step-1-install-docker)).

## TM1 Web

`Tm1WebContainer` runs TM1 Web (Planning Analytics Spreadsheet Services) on the
[`tm1-web`](../docker/tm1-web) image, pointed at a `Tm1ServerContainer`'s Admin Server.
`@RequiresTm1Web` works like `@RequiresTm1` (it includes it), skips tests unless a TM1 Web
source is configured too, and injects one shared TM1 Web wired to the shared TM1 server:

```java
@RequiresTm1Web
class WebsheetTest {

    @Test
    void opensAWebsheet(Tm1WebContainer web, Tm1ServerContainer tm1) {
        String url = web.getUrl();             // http://localhost:54321/tm1web/
        String adminHost = web.getAdminHost(); // the AdminHost to log on with
        ...
    }
}
```

For TM1 Web against a server of its own, `new Tm1WebContainer(source).withTm1Server(tm1)`
gives the two containers a shared Docker network, and starting TM1 Web starts the server
first (see [`Tm1WebExampleTest`](src/test/java/net/ajmyers/tm1/testcontainers/examples/Tm1WebExampleTest.java)).

TM1 Web is found the same way as TM1, with `TM1_WEB_` settings: `TM1_WEB_PRIVATE_IMAGE`,
`TM1_WEB_HOME_DIR` (an installed `tm1web` folder), `TM1_WEB_INSTALL_VOLUME`,
`TM1_WEB_BUNDLE_DIR` (+ `TM1_WEB_BUNDLE`) or `TM1_WEB_BUNDLE_URL` (+ `TM1_WEB_BUNDLE_SHA256`).
Simplest of all, keep IBM's `tm1web-*_bundle.tar.gz` next to the TM1 bundle: if
`TM1_BUNDLE_DIR` holds one, it's used. `TM1_WEB_IMAGE` swaps the image, e.g.
`tm1-web:local`. The first start installs TM1 Web into the shared cache volume (about 45 s
on Apple Silicon), later starts take 15–20 s.

TM1 Web's browser API is internal to IBM (DWR), so this module doesn't wrap it: drive TM1
Web with a browser tool such as Playwright or Selenium, or its URL API. This module's own
tests use a small DWR client to prove TM1 Web can list and log on to the server.

## Faster local runs

The shared container is marked reusable. If you turn on Testcontainers reuse, it keeps
running between test runs, so tests start instantly:

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

Objects tests leave behind then survive between runs too. Stop the container (`docker ps`,
`docker rm -f`) to start fresh.

## Not yet

- Running test classes once per TM1 version, and pinning tests to a version range.
- Several TM1 servers with a shared Admin Server (`Tm1Stack`), and PAW (#16).
- Spring Boot `@ServiceConnection` (#15).
