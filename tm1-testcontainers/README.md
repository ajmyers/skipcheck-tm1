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
- Several TM1 servers with a shared Admin Server (`Tm1Stack`), TM1 Web and PAW (#16).
- Spring Boot `@ServiceConnection` (#15).
