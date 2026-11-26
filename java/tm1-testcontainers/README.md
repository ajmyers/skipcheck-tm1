# tm1-testcontainers

Run tests against a real IBM Planning Analytics (TM1) server in Docker, with
[Testcontainers](https://testcontainers.com). Built on the [`tm1-server`](../../docker/tm1-server)
image.

> **No IBM software is included.** You supply your own licensed TM1 Server for Linux, and
> you're responsible for following your IBM license terms. Without it, tests that need TM1
> are skipped.

```kotlin
testImplementation("io.skipcheck:tm1-testcontainers:<version>")
```

The client's live tests run with TM1 Server 11.8.03500.6 (Planning Analytics 2.1.19). The
`tm1-server` image also starts Planning Analytics 2.1.2 to 2.1.25 ([which ones](../../docker/tm1-server/README.md#getting-tm1-into-the-container)).
If you run it with another version, please open an issue saying how it went.

To run the same parts by hand, outside tests (to develop against, or to look at in a browser), use
[`pa-setup`](../../docker/pa-setup/README.md). It starts the same images from one file, `pa-environment.yaml`.

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

Each of these is a real test in [`src/test/java/.../examples`](src/test/java/io/skipcheck/tm1/testcontainers/examples),
run on every build where TM1 is configured, so they always work as written.

| You want to... | Example |
|---|---|
| Share one server across all your tests: injected into a test method, a constructor, or a static `@BeforeAll` that sets up a client once per class | [`SharedServerExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/SharedServerExampleTest.java) |
| Give one test class its own server, with IBM's SData sample to read | [`SampleDatabaseExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/SampleDatabaseExampleTest.java) |
| Run your own database (a fixture model in `src/test/resources`) | [`OwnDatabaseExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/OwnDatabaseExampleTest.java) |
| Require one TM1 version, or skip tests on versions without a feature | [`PinnedVersionExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/PinnedVersionExampleTest.java) |
| Run two servers at once, e.g. to test a migration or comparison | [`TwoServersExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/TwoServersExampleTest.java) |
| Start a whole environment in one object: several servers, TM1 Web, Cognos with LDAP and OIDC | [`EnvironmentExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/EnvironmentExampleTest.java) |
| Register several servers with one Admin Server, and list them all in TM1 Web | [`SharedAdminServerExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/SharedAdminServerExampleTest.java) |
| Choose where TM1 comes from in code: install folder, bundle, download link, Docker volume or private image | [`ExplicitSourceExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/ExplicitSourceExampleTest.java) |
| Skip the extension: plain assumptions and a throwaway server, e.g. for a test that damages its server | [`WithoutTheExtensionExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/WithoutTheExtensionExampleTest.java) |
| Run TM1 Web against a TM1 server of its own | [`Tm1WebExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/Tm1WebExampleTest.java) |
| Test Cognos (CAM) security: three users, three TM1 security profiles | [`CamSecurityExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/CamSecurityExampleTest.java) |
| Log on to TM1 through OIDC: a password, or an ID token exchanged for a CAM passport | [`OidcExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/OidcExampleTest.java) |

Spring Boot tests will connect with `@ServiceConnection` once #15 lands.

## Telling it where TM1 is

Set **one** of these, as an environment variable or a system property with the same name.
The names match the `tm1-server` compose settings, so a `.env` that works for compose works
here too. If several are set, the first in this list wins.

| Setting | TM1 comes from | Start time |
|---|---|---|
| `TM1_PRIVATE_IMAGE` | An image with TM1 built in, from a **private** registry ([how to build one](../../docker/tm1-server/examples/private-image/Dockerfile)) | ~3 s |
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
guide](../../docker/tm1-server/GETTING-STARTED.md#step-1-install-docker)).

## A whole environment

`PlanningAnalyticsEnvironment` starts and wires any mix of the containers below on one Docker
network, every part optional:

```java
try (var env = PlanningAnalyticsEnvironment.builder()
        .tm1("planning", tm1 -> tm1.withSeed(Tm1Seed.SDATA))
        .tm1("reporting")
        .tm1Web()
        .cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers"))
        .keycloak()                               // adds Cognos's OIDC namespace
        .camSecurity(Namespace.OIDC, "admin")     // default: LDAP, the first test user as admin
        .build()) {
    env.start();
    env.tm1("planning").getRestUrl();
    env.tm1Web().getUrl();
    env.cognos().camAuthorization("viewer", Namespace.OIDC);
}
```

- **Every builder method takes a customizer** that receives the ordinary container
  (`Tm1ServerContainer`, `Tm1WebContainer`, `PawContainer`, `CognosContainer`, ...), so all
  their settings are available. Customizers run after the environment's wiring, so they can
  change it. Cognos's runs first, because the TM1 servers are wired from its test users.
- **Servers** are named (`TM1_SERVER_NAME`) and reachable by name on the network. With more
  than one, or with `adminServer()`, they share a `Tm1AdminServerContainer`, and TM1 Web and
  PAW list them all.
- **With `cognos(...)`, every server runs in CAM security**
  ([below](#cognos-cam-security)), and PAW logs users on through Cognos too.
- **Sources** are discovered from the environment, as for each container, unless set with
  `tm1Source(...)`, `cognosSource(...)` and so on. `start()` starts parts in dependency
  order, in parallel where it can; `close()` stops everything and removes the network.

### From pa-environment.yaml

`fromConfig` reads the [`pa-environment.yaml`](../../docker/pa-setup/README.md#pa-environmentyaml)
that `pa-setup` runs, so tests run the environment developers run, from one file:

```java
try (var env = PlanningAnalyticsEnvironment.fromConfig(Path.of("pa-environment.yaml")).build()) {
    env.start();
    env.tm1("planning").getRestUrl();
}
```

It returns the builder, configured, so you can still change it before `build()`. The file is
checked as `pa-setup` checks it, with the same defaults and messages.

- **Each part** maps onto the builder: `tm1.servers` (with `seed` and `cfg`), `tm1.adminServer`,
  `tm1Web` (with `heap`), `cognos` (`users`, `oidc` as `keycloak()`, `cam` as `camSecurity`,
  `memory`) and `paw` (`acceptLicense`, `zip`).
- **Sources:** a part's source set on the builder wins; then `ibm`, the folder of IBM downloads;
  then the settings above (pa-setup's `.env` sets the same `*_URL` names); then the part's
  `images` entry, as a private image with IBM's software built in. With another source, an
  `images` entry is the image the part runs.
- **Ignored:** `name`, every port, and `files`. Testcontainers gives the environment its own
  network, random host ports and a new database each time.
- **Refused** with a message naming the setting: `cognos` without `tm1.servers`, and
  `seed: 24retail`, which the environment can't run yet.

## Several servers, one Admin Server

By default each `Tm1ServerContainer` runs its own Admin Server. For a deployment-like
setup, `Tm1AdminServerContainer` runs one on its own (the `tm1-server` image in `admin`
mode, from the same `Tm1Source`), and servers register with it:

```java
try (var admin = new Tm1AdminServerContainer(source);
     var planning = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "planning").withAdminServer(admin);
     var reporting = new Tm1ServerContainer(source).withEnv("TM1_SERVER_NAME", "reporting").withAdminServer(admin);
     var web = new Tm1WebContainer(webSource).withAdminServer(admin)) {
    planning.start();
    reporting.start();
    web.start();   // lists planning and reporting
}
```

`withAdminServer` puts the containers on the Admin Server's Docker network (or on the one
they already share) and starts it first. `Tm1WebContainer` and `PawContainer` take
`withAdminServer` too, and list every registered server from it. PAW still logs users on
through its `withTm1Server`.

## Cognos (CAM) security

`CognosContainer` runs IBM Cognos Analytics on the [`cognos-server`](../../docker/cognos-server)
image, with test users you list. `Tm1ServerContainer.withCamSecurity` runs TM1 in CAM
security against it, so each test user logs on to TM1 with their own identity and groups:

```java
@RequiresCognos
class SecurityTest {

    @Test
    void viewersCanOnlyRead(CognosSource cognosSource) {
        try (var cognos = new CognosContainer(cognosSource)
                 .withUser("admin", "adminpw", "Admins")
                 .withUser("analyst", "analystpw", "Analysts", "Readers")
                 .withUser("viewer", "viewerpw", "Readers");
             var tm1 = new Tm1ServerContainer(Tm1Source.discover().orElseThrow())
                 .withCamSecurity(cognos, "admin")) {
            tm1.start();   // starts Cognos first
            String auth = cognos.camAuthorization("viewer");   // the Authorization header
            ...
        }
    }
}
```

- **Users and groups:** `withUser(name, password, groups...)`. Cognos gets an LDAP namespace
  (`LDAP`) with them, and anonymous access is off.
- **`withCamSecurity(cognos, admins...)`** gives the two containers a shared network and
  starts Cognos first. It makes the named users TM1 administrators, and the container's own
  login (`getUsername()`, `getAuthorization()`) is the first of them.
- **Groups:** every group of the test users is added to TM1, named by its CAMID, e.g.
  `CAMID("LDAP:g:cn=analysts,ou=groups")`. TM1 puts users into their groups at logon, so give
  each group the rights the test needs.
- **Logging on:** `cognos.camAuthorization(user)` is the `Authorization: CAMNamespace …`
  header for that user. Native logons are refused.

### OIDC

`withKeycloak(new KeycloakContainer())` gives Cognos an OIDC namespace too, through Keycloak:
Cognos builds a realm there from its test users. `withCamSecurity(cognos, Namespace.OIDC,
"admin")` points TM1 at that namespace instead of LDAP:

```java
try (var keycloak = new KeycloakContainer();
     var cognos = new CognosContainer(cognosSource).withUser("viewer", "viewerpw", "Readers")
         .withUser("admin", "adminpw", "Admins").withKeycloak(keycloak);
     var tm1 = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, Namespace.OIDC, "admin")) {
    tm1.start();
    // A password, which Cognos checks with Keycloak:
    String byPassword = cognos.camAuthorization("viewer", Namespace.OIDC);
    // A token an app already holds, exchanged at Cognos for a CAM passport:
    String byToken = "CAMPassport " + cognos.passport(keycloak.idToken("viewer", "viewerpw"));
}
```

`keycloak.idToken(user, password)` gets a user's ID token from the realm's `tm1-app` client,
whose tokens Cognos accepts (Cognos requires its own client in a token's audience). Keycloak's
issuer is its in-network address, `http://keycloak:8080`, so the interactive browser logon
isn't wrapped: a browser would need to reach that address. `KEYCLOAK_IMAGE` swaps the image.

`@RequiresCognos` includes `@RequiresTm1` and also skips without a Cognos source. It injects
the `CognosSource` found. There's no shared Cognos: it takes about two minutes to start, and
each test wants its own users. Cognos needs about 8 GB of memory, so give Docker at least 12 GB.

Cognos is found from these settings, first match wins:

| Setting | Cognos comes from | Start time |
|---|---|---|
| `COGNOS_PRIVATE_IMAGE` | An image with Cognos built in, kept **private** or local ([how to build one](../../docker/cognos-server/examples/private-image/Dockerfile)). Recommended. | ~2 min |
| `COGNOS_INSTALL_VOLUME` | A Docker volume holding an install. Cognos writes into it, so one container at a time. | ~2 min |
| `COGNOS_ZIP_DIR` (+ `COGNOS_ZIP`) | A folder holding IBM's `casrv-*-linuxi38664h.zip` | ~3 min, installing every time |
| `COGNOS_ZIP_URL` (+ `COGNOS_ZIP_SHA256`) | A download link for the zip | download + ~3 min |
| `TM1_BUNDLE_DIR` | The TM1 downloads folder, if it also holds the Cognos zip | as `COGNOS_ZIP_DIR` |

Cognos writes into its install while it runs, so unlike TM1 the zip sources install into
every new container (about a minute and 10 GB). A private image avoids that: Docker gives
each container its own copy-on-write copy. `COGNOS_SERVER_IMAGE` swaps the
`cognos-server` image itself, e.g. `cognos-server:local`. Start times are from Apple Silicon.

## TM1 Web

`Tm1WebContainer` runs TM1 Web (Planning Analytics Spreadsheet Services) on the
[`tm1-web`](../../docker/tm1-web) image, pointed at a `Tm1ServerContainer`'s Admin Server.
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
first (see [`Tm1WebExampleTest`](src/test/java/io/skipcheck/tm1/testcontainers/examples/Tm1WebExampleTest.java)).

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

## Planning Analytics Workspace

`PawContainer` runs IBM Planning Analytics Workspace (PAW) Local against a TM1 server.
IBM ships PAW Local as Docker images in `ipa_workspace_local_<release>.<build>.zip`.
`PawContainer` uses them as they are, through [`docker/paw`](../../docker/paw/README.md)'s
`paw-local.sh`, run inside a small Linux container. So it works the same on Windows, macOS and
Linux, and the host needs only Docker. Users log on to PAW as users of the TM1 server.

`@RequiresPaw` works like `@RequiresTm1` (it includes it). It skips tests unless the zip is
configured **and** IBM's license is accepted, and injects one shared PAW wired to the shared
TM1 server:

```java
@RequiresPaw
class WorkspaceTest {

    @Test
    void opensPaw(PawContainer paw, Tm1ServerContainer tm1) {
        String url = paw.getUrl();   // http://localhost:54321/
    }
}
```

| Setting | What |
|---|---|
| `PAW_ZIP` | IBM's `ipa_workspace_local_*.zip` |
| `PAW_BUNDLE_DIR` | A folder holding it; the newest one is used |
| `PAW_ACCEPT_LICENSE=true` | You accept IBM's license for PAW (`licenses/LA_en` in the zip). Or call `acceptLicense()`. |

Each is read as a system property, then an environment variable.

Without the extension:

```java
try (var tm1 = new Tm1ServerContainer(tm1Source);
     var paw = new PawContainer(new PawSource(zip)).withTm1Server(tm1).acceptLicense()) {
    paw.start();   // starts tm1 first
}
```

Things to know:

- **Heavy.** PAW is about 30 containers and needs about 15 GB of memory; give Docker Desktop
  well over that. The first start loads about 8 GB of images, which takes a few minutes. After
  that, a start takes about a minute, or seconds if PAW is still running.
- **One PAW per Docker daemon.** IBM gives every container a fixed name. The shared PAW is never
  stopped, so the next test run reuses it. `stop()` removes the PAW containers and keeps PAW's
  data. IBM's containers carry no Testcontainers labels, so Ryuk can't remove them; stop them
  with `./paw-local.sh down` in `docker/paw`.
- **Ready means TM1 works through PAW.** `start()` waits until a logon and a TM1 REST call
  through PAW succeed, not just until the logon page answers.
- PAW's files (the unpacked zip and its secrets) live in the Docker volume `tm1-paw-home`.
  Don't run `paw-local.sh` by hand with a different `PAW_HOME` against the same PAW data:
  the secrets wouldn't match. `paw-local.sh` stops and says so.

## Faster local runs

The shared container is marked reusable. If you turn on Testcontainers reuse, it keeps
running between test runs, so tests start instantly:

```properties
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

Objects tests leave behind then survive between runs too. Stop the container (`docker ps`,
`docker rm -f`) to start fresh.

### Reusing a whole environment

Cognos takes minutes to start and PAW longer. A `PlanningAnalyticsEnvironment` keeps its
containers between runs when you ask for it with `reuse(true)` and have turned on reuse as
above. Without the switch, `reuse(true)` has no effect.

```java
var env = PlanningAnalyticsEnvironment.builder()
	.tm1("planning")
	.cognos(cognos -> cognos.withUser("admin", "adminpw", "Admins"))
	.reuse(true)
	.build();
env.start(); // seconds, once a run with this configuration has started it
```

- **Each configuration has its own containers.** The environment's identity is a hash of its
  configuration: every part's image, source, environment variables, command, network aliases,
  labels, exposed ports and copied files, the Cognos users and the CAM settings. Its containers
  carry it as the label `io.skipcheck.tm1.environment`, and its network is named
  `tm1-environment-<identity>`, so the reused containers still reach each other by name. A
  different configuration gets new containers and never picks up another's.
- **`stop()` and `close()` leave everything running**, PAW too.
- **What tests leave behind stays:** objects in TM1, sessions, Cognos's content store. Give
  test objects unique names and delete them.
- **Old configurations keep running.** Changing the configuration starts new containers; the
  old ones stay until you remove them, and Cognos alone holds about 8 GB. To start clean:

  ```sh
  docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)
  docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)
  ```

- **Not seen by the identity:** a change made with `withCreateContainerCmdModifier`. Remove the
  containers after changing one.
- **PAW is one per Docker daemon** whatever the identity (see above). Each start runs IBM's
  compose files again, which takes about a minute.
- **With your own `network(...)`,** reuse works only if it's the same network each run:
  Testcontainers matches a container by everything it was created with, its network's ID too.

## Not yet

- Running test classes once per TM1 version, and pinning tests to a version range.
- Spring Boot `@ServiceConnection` (#15).
