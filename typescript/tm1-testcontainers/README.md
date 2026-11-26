# @skipcheck/tm1-testcontainers

> **Pre-1.0:** the API may change between minor versions (0.1 → 0.2) until 1.0.0. See [Stability](https://github.com/ajmyers/skipcheck-tm1#stability).

The TypeScript port of the Java [`tm1-testcontainers`](../../java/tm1-testcontainers) module, built on [Testcontainers for Node](https://node.testcontainers.org/) (`testcontainers` on npm). It runs TM1 and the rest of Planning Analytics in Docker for tests. It reads the same settings in the same order and uses the same images, mounts and cache volume as the Java module (and the [Python](../../python/tm1-testcontainers) and [.NET](../../dotnet/src/Tm1.Testcontainers) ports), so a bundle unpacked for the Java tests is reused here.

The images contain no IBM software. A source supplies it, from an environment variable: `TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` (with `TM1_BUNDLE`) or `TM1_BUNDLE_URL` (with `TM1_BUNDLE_SHA256`). If several are set, the first in this list wins. A bundle URL is never logged with its query string or credentials.

To run the same parts by hand, outside tests (to develop against, or to look at in a browser), use [`pa-setup`](../../docker/pa-setup/README.md). It starts the same images from one file, `pa-environment.yaml`.

```ts
import { requiresTm1 } from "@skipcheck/tm1-testcontainers/vitest";
import { Tm1Client } from "@skipcheck/tm1-client";

requiresTm1("lists cubes", async ({ tm1 }) => {     // Java's @RequiresTm1, and its injected Tm1ServerContainer
  await using client = Tm1Client.builder().baseUrl(tm1.getRestUrl()).basicAuth(tm1.getUsername(), tm1.getPassword()).build();
  ...
});
```

`requiresTm1` is vitest's `test`, extended. It skips the test with Java's message when Docker isn't running or no source is set, so builds without IBM software stay green. A source that's set but wrong fails the test instead. A test that names `tm1` gets the shared server, started the first time it's needed.

A container of your own:

```ts
await using tm1 = new Tm1ServerContainer(new Tm1Source.InstallDir("/opt/ibm/tm1_64"))
  .withSeed(Tm1Seed.SDATA)                    // IBM's SData sample, admin / apple
  .withExpectedVersion("11.8.03500.6");
await tm1.start();
```

## TM1 v12

TM1 v12, the TM1 engine of Planning Analytics 3.x, has classes of its own beside TM1 11's, on the [`tm1-engine`](../../docker/tm1-engine/README.md) image. One **engine** holds **instances**, each holding **databases**, and it has no passwords: a test logs in through the instance's application, by a user's name.

```ts
import { requiresTm1Engine } from "@skipcheck/tm1-testcontainers/vitest";

requiresTm1Engine("lists cubes", async ({ tm1Database: db }) => {     // Java's @RequiresTm1Engine, and its injected Tm1Database
  const response = await fetch(db.getRestUrl() + "Cubes?$select=Name", await db.login().authorize());
  expect(response.status).toBe(200);
});
```

- **The source** is a `Tm1EngineSource`, from `TM1_ENGINE_PRIVATE_IMAGE`, `TM1_ENGINE_HOME_DIR`, `TM1_ENGINE_INSTALL_VOLUME`, `TM1_ENGINE_BUNDLE_DIR` (with `TM1_ENGINE_BUNDLE`) or `TM1_ENGINE_BUNDLE_URL` (with `TM1_ENGINE_BUNDLE_SHA256`), the first set winning. After those comes `TM1_BUNDLE_DIR`, when that folder holds a `tm1-v*-standalone-linux.tgz` too, so one downloads folder serves both versions. Without a source the tests are skipped, with Java's message.
- **`requiresTm1Engine`** starts one engine (`Tm1EngineContainer.shared()`) and gives the test its fixtures: `tm1Database`, a `Tm1Database`, and `tm1Engine`, the engine.
- **A database per test file.** Java gives each test class a database of its own; the unit here is the vitest **file**. The file's database is created when one of its tests first names `tm1Database` (about 10 seconds), named after the file with a few random characters, and dropped when the file is done. So a file can create what it likes under any name and leave it there.
- **`requiresTm1Engine.extend({ shareDatabase: true })`** is a `test` whose tests get the engine's one shared database, `tm1`, instead (Java's `shareDatabase = true`). It costs nothing to get; its tests need uniquely named objects, and to delete them. **`extend({ minVersion: "12.6" })`** skips its tests on an older engine (Java's `minVersion`).
- **Logging in:** `db.login()` is `admin`, `db.login("analyst")` any other user. A `Tm1Login` gives the session cookie (`await login.cookie()`, or `await login.authorize(init)` for `fetch`), a bearer token (`token()`, then `session("Bearer " + token)`), or the application's client ID and secret for a client that logs in itself.

An engine of your own, with the databases and users you name:

```ts
await using engine = new Tm1EngineContainer(source).withDatabase("planning").withUser("analyst", "Analysts");
await engine.start();                         // about 40 seconds
const planning = engine.database("planning");
planning.getRestUrl();                        // http://localhost:54321/tm1/api/v1/Databases('planning')/
const scratch = await engine.createDatabase("scratch");
await engine.dropDatabase(scratch);
```

The engine keeps its metadata in a MongoDB (`mongo:8.0`), which the container starts first, on the engine's network, and stops with it: `getDatastore()`. The shared engine isn't reusable: an engine and its datastore belong together, and a kept one would keep every database tests left. Package installs are cached in the volume `tm1-engine-testcontainers-cache`, not TM1 11's. `TM1_ENGINE_IMAGE` swaps the image (`tm1-engine:local` for a local build).

Not here yet, as in the Java module: settings for OAuth 2.0 and pass-through logins, and TLS.

## From pa-environment.yaml

`PlanningAnalyticsEnvironment.fromConfig` reads the [`pa-environment.yaml`](../../docker/pa-setup/README.md#pa-environmentyaml) that `pa-setup` runs, so tests run the environment developers run, from one file:

```ts
await using env = PlanningAnalyticsEnvironment.fromConfig("pa-environment.yaml").build();
await env.start();
env.tm1("planning").getRestUrl();
```

It returns the builder, configured, so you can still change it before `build()`. The file is checked as `pa-setup` checks it, with the same defaults and messages.

- **Each part** maps onto the builder: `tm1.servers` (with `seed` and `cfg`), `tm1.adminServer`, `tm1Web` (with `heap`), `cognos` (`users`, `oidc` as `keycloak()`, `cam` as `camSecurity`, `memory`) and `paw` (`acceptLicense`, `zip`).
- **Sources:** a part's source set on the builder wins; then `ibm`, the folder of IBM downloads; then the settings above (pa-setup's `.env` sets the same `*_URL` names); then the part's `images` entry, as a private image with IBM's software built in. With another source, an `images` entry is the image the part runs.
- **Ignored:** `name`, every port, and `files`. Testcontainers gives the environment its own network, random host ports and a new database each time.
- **Refused** with an `IllegalArgumentException` naming the setting: `cognos` without `tm1.servers`, and `seed: 24retail`, which the environment can't run yet.

The file is read as YAML 1.1, with the [`yaml`](https://eemeli.org/yaml/) package, so `yes` and `off` are booleans, as in `pa-setup`'s PyYAML.

## From the Java module

| Java | TypeScript |
|---|---|
| `new Tm1ServerContainer(source).withSeed(Tm1Seed.SDATA)` | the same |
| `start()`, `stop()`, `shared()`, `idToken(…)`, `passport(…)` | the same names, returning a `Promise`; `await using` stops a container |
| `getRestUrl()`, `getTm1Version()`, `isRunning()`, `getEnvMap()` | the same: getters keep their names, as Testcontainers for Node has them (`getMappedPort()`) |
| `Optional<Tm1Source> discover()`, `discover(Function lookup)` | `Tm1Source.discover(lookup?)`, `Tm1Source \| undefined` |
| A system property, then an environment variable | an environment variable (`lookup` reads anything else) |
| `Tm1Source.PRIVATE_IMAGE` and the other constants | the same names: TypeScript has no clash between `PRIVATE_IMAGE` and the `PrivateImage` record, so nothing is renamed |
| `sealed interface Tm1Source` and its records (`Tm1Source.InstallDir(Path dir)`) | an abstract class and classes in a namespace merged with it (`new Tm1Source.InstallDir(dir)`), the components as `readonly` properties (`.dir`) |
| `Path`, `URI`, `Duration` | a path `string`, a `URL` (or a string), milliseconds |
| `enum Tm1Seed`, `CognosContainer.Namespace` | classes of static instances (`Tm1Seed.SDATA`, `Namespace.OIDC.id()`), so they keep Java's members |
| `DockerImageName image` | the image's name, a `string` |
| `IllegalArgumentException`, `IllegalStateException` | the exported `IllegalArgumentException` and `IllegalStateException` (both `Error`s) |
| `Network.newNetwork()` | `Network.newNetwork()` (`Network.of(…)` wraps a Testcontainers for Node `StartedNetwork`) |
| `PlanningAnalyticsEnvironment.builder()….build()` | the same; customizers are functions |
| `@RequiresTm1`, `@RequiresCognos`, `@RequiresTm1Web`, `@RequiresPaw`, `@RequiresTm1Engine` | `requiresTm1`, `requiresCognos`, `requiresTm1Web`, `requiresPaw`, `requiresTm1Engine` from `./vitest`; their messages and `skipReason()` on `RequiresTm1` and the others |
| A `Tm1ServerContainer` / `Tm1WebContainer` / `PawContainer` / `CognosSource` test parameter | the `tm1` / `tm1Web` / `paw` / `cognosSource` fixture |
| A `Tm1Database` / `Tm1EngineContainer` test parameter | the `tm1Database` / `tm1Engine` fixture |
| `@RequiresTm1Engine(shareDatabase = true)`, `(minVersion = "12.6")` | `requiresTm1Engine.extend({ shareDatabase: true })`, `.extend({ minVersion: "12.6" })`: a `test` with the setting |
| A database per test class | a database per test **file**: the unit vitest shares set-up over |
| `Tm1EngineContainer.databases()`, `createDatabase(…)`, `dropDatabase(…)`; `Tm1Login.cookie()`, `token()`, `session(…)` | the same names, returning a `Promise`: each asks the container or TM1 |
| `Tm1EngineContainer.database(…)`, `getRootClientId()`, `Tm1Database.login(…)`, read from the engine when called | answered at once, from what the engine reported when it started and when `databases()`, `createDatabase` or `dropDatabase` last ran |
| `Tm1Login.authorize(HttpRequest.Builder)` | `await login.authorize(init)`: `fetch`'s second argument, with the `Cookie` header added |
| `Tm1Database.equals(…)` | `equals(…)`: `===` compares the objects, and each call of `database(…)` makes one |
| `logger().info(…)` | Testcontainers for Node's log: `DEBUG=testcontainers*` |

### How the containers are built

Testcontainers for Node configures a container on its `GenericContainer`, whose settings can't be read back, and takes only a network that's already created. So each container here keeps its configuration (environment, binds, network, aliases, dependencies) where it can be read and changed before it starts, as Java's `GenericContainer` does, and builds the Testcontainers container when it starts, after what it depends on. That's what lets `PlanningAnalyticsEnvironment` wire containers together, and lets the tests check the wiring without Docker. `getContainer()` is the started Testcontainers container, for anything not wrapped here. The .NET port does the same, for the same reason.

### Sharing

`shared()` gives one server per module graph: per test file, by default, since vitest isolates files. Java's is one per JVM. To share one across files, start it once in a vitest `globalSetup` and `provide` its URL and login to the files, which is what the client's live tests do (`../tm1-client/test/live/global-setup.ts`); or run the files without isolation (`isolate: false`); or turn on reuse. A global setup runs in vitest's own process, so it imports from `@skipcheck/tm1-testcontainers`, not from `@skipcheck/tm1-testcontainers/vitest`.

### Reuse

Reuse follows the Java switch: set `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or `TESTCONTAINERS_REUSE_ENABLE=true`. Testcontainers for Node reuses any container that asks for it. This library only asks when the switch is on, so the shared container is otherwise removed (by Ryuk) once the run ends, as in Java.

### Reusing a whole environment

Cognos takes minutes to start and PAW longer. A `PlanningAnalyticsEnvironment` keeps its containers between runs when you ask for it with `reuse(true)` and have turned on reuse as above. Without the switch, `reuse(true)` has no effect.

```ts
await using env = PlanningAnalyticsEnvironment.builder()
  .tm1("planning")
  .cognos((cognos) => cognos.withUser("admin", "adminpw", "Admins"))
  .reuse(true)
  .build();
await env.start(); // seconds, once a run with this configuration has started it
```

- **Each configuration has its own containers.** The environment's identity is a hash of its configuration: every part's image, source, environment variables, command, network aliases, labels, exposed ports and copied files, the Cognos users and the CAM settings. Its containers carry it as the label `io.skipcheck.tm1.environment`, and its network is named `tm1-environment-<identity>`, so the reused containers still reach each other by name. A different configuration gets new containers and never picks up another's.
- **`stop()` and `await using` leave everything running**, PAW too.
- **What tests leave behind stays:** objects in TM1, sessions, Cognos's content store. Give test objects unique names and delete them.
- **Old configurations keep running.** Changing the configuration starts new containers; the old ones stay until you remove them, and Cognos alone holds about 8 GB. To start clean:

  ```sh
  docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)
  docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)
  ```

- **PAW is one per Docker daemon** whatever the identity. Each start runs IBM's compose files again, which takes about a minute.
- **With your own `network(...)`,** reuse works only if it's the same network each run. Testcontainers for Node matches a container by a hash of what it creates it with, and that hash leaves out the network, so the network's name is part of the identity here: a network with a new name each run (`Network.newNetwork()`) gets new containers each run.

### Image tag

The image tag is the library's version. For a development build it's `edge`: Java's `-SNAPSHOT`, npm's `-dev` and a published `-dev.1` (this package is `0.1.0-dev`). `TM1_SERVER_IMAGE`, `TM1_ENGINE_IMAGE`, `TM1_WEB_IMAGE`, `COGNOS_SERVER_IMAGE` and `KEYCLOAK_IMAGE` swap the images.

### PAW

`paw-local.sh` comes from [`docker/paw`](../../docker/paw). `npm run build` copies it into the package, as the Java build puts it in its jar and the .NET build embeds it in its assembly. In this repository, `PawContainer` reads `docker/paw`'s copy when there's no built one.

## Tests

```sh
npm test                 # from typescript/: the client's tests, then these
npm test -w testcontainers
TM1_PRIVATE_IMAGE=tm1-server-private:2.1.19 npm test -w testcontainers
```

- **`test/java-parity.test.ts`** reads the Java sources and checks that every public type, nested record and enum, constant, constructor and method has its TypeScript counterpart, with Java's parameter names in order, and that what does I/O returns a `Promise`. It also checks that the skip messages match Java's word for word.
- **Configuration tests** need no Docker. `sources.test.ts` ports Java's `*SourceTest`; `wiring.test.ts` ports `AdminServerWiringTest`, `CognosContainerTest`, `PlanningAnalyticsEnvironmentTest` and `Tm1EngineWiringTest`, and checks the wait for TM1 v12 to be set up; `vitest-helpers.test.ts` checks the skip helpers, `engine-fixtures.test.ts` `requiresTm1Engine`'s fixtures, with an engine that only records what it's asked for, and `engine-login.test.ts` `Tm1Login`, against a local server.
- **Container tests** are skipped without Docker or a source. `containers.test.ts` ports `Tm1ServerContainerTest`, `Tm1WebContainerTest` (with its `Tm1WebSession`) and `PawContainerTest`. `examples.test.ts` ports every one of Java's `*ExampleTest` classes for TM1 11, each test behind the helper for what it needs (`requiresTm1`, `requiresTm1Web`, `requiresCognos`). For TM1 v12, `engine-containers.test.ts` ports `Tm1EngineContainerTest` and `engine-examples.test.ts` the three examples (`HelloTm1EngineExampleTest`, `DatabasePerClassExampleTest`, `S2sUsersExampleTest`), behind `requiresTm1Engine`.
- **`test/java-test-parity.test.ts`** holds these tests to Java's: every test of `java/tm1-testcontainers/src/test/java` has a test of the same name here, or a line saying why not in `coverage/unit-excluded.txt`.

The client's live tests (`../tm1-client/test/live`) run against one server for the whole run when `TM1_URL` isn't set, one file after the other: see `global-setup.ts` there.
