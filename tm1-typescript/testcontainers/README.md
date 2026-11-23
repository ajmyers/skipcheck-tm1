# @skipcheck/tm1-testcontainers

The TypeScript port of the Java [`tm1-testcontainers`](../../java/tm1-testcontainers) module, built on [Testcontainers for Node](https://node.testcontainers.org/) (`testcontainers` on npm). It runs TM1 and the rest of Planning Analytics in Docker for tests. It reads the same settings in the same order and uses the same images, mounts and cache volume as the Java module (and the [Python](../../python/tm1-testcontainers) and [.NET](../../dotnet/src/Tm1.Testcontainers) ports), so a bundle unpacked for the Java tests is reused here.

The images contain no IBM software. A source supplies it, from an environment variable: `TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` (with `TM1_BUNDLE`) or `TM1_BUNDLE_URL` (with `TM1_BUNDLE_SHA256`). If several are set, the first in this list wins. A bundle URL is never logged with its query string or credentials.

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
| `@RequiresTm1`, `@RequiresCognos`, `@RequiresTm1Web`, `@RequiresPaw` | `requiresTm1`, `requiresCognos`, `requiresTm1Web`, `requiresPaw` from `./vitest`; their messages and `skipReason()` on `RequiresTm1` and the others |
| A `Tm1ServerContainer` / `Tm1WebContainer` / `PawContainer` / `CognosSource` test parameter | the `tm1` / `tm1Web` / `paw` / `cognosSource` fixture |
| `logger().info(…)` | Testcontainers for Node's log: `DEBUG=testcontainers*` |

### How the containers are built

Testcontainers for Node configures a container on its `GenericContainer`, whose settings can't be read back, and takes only a network that's already created. So each container here keeps its configuration (environment, binds, network, aliases, dependencies) where it can be read and changed before it starts, as Java's `GenericContainer` does, and builds the Testcontainers container when it starts, after what it depends on. That's what lets `PlanningAnalyticsEnvironment` wire containers together, and lets the tests check the wiring without Docker. `getContainer()` is the started Testcontainers container, for anything not wrapped here. The .NET port does the same, for the same reason.

### Sharing

`shared()` gives one server per module graph: per test file, by default, since vitest isolates files. To share one across files, run them without isolation (`isolate: false`), or turn on reuse. Java's is one per JVM.

### Reuse

Reuse follows the Java switch: set `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or `TESTCONTAINERS_REUSE_ENABLE=true`. Testcontainers for Node reuses any container that asks for it. This library only asks when the switch is on, so the shared container is otherwise removed (by Ryuk) once the run ends, as in Java.

### Image tag

The image tag is the library's version. For a development build it's `edge`: Java's `-SNAPSHOT`, npm's `-dev` (this package is `0.1.0-dev`). `TM1_SERVER_IMAGE`, `TM1_WEB_IMAGE`, `COGNOS_SERVER_IMAGE` and `KEYCLOAK_IMAGE` swap the images.

### PAW

`paw-local.sh` comes from [`docker/paw`](../../docker/paw). `npm run build` copies it into the package, as the Java build puts it in its jar and the .NET build embeds it in its assembly. In this repository, `PawContainer` reads `docker/paw`'s copy when there's no built one.

## Tests

```sh
npm test                 # from tm1-typescript: the client's tests, then these
npm test -w testcontainers
TM1_PRIVATE_IMAGE=tm1-server-private:2.1.19 npm test -w testcontainers
```

- **`test/java-parity.test.ts`** reads the Java sources and checks that every public type, nested record and enum, constant, constructor and method has its TypeScript counterpart, with Java's parameter names in order, and that what does I/O returns a `Promise`. It also checks that the skip messages match Java's word for word.
- **Configuration tests** need no Docker. `sources.test.ts` ports Java's `*SourceTest`; `wiring.test.ts` ports `AdminServerWiringTest`, `CognosContainerTest` and `PlanningAnalyticsEnvironmentTest`; `vitest-helpers.test.ts` checks the skip helpers.
- **Container tests** are skipped without Docker or a source. `containers.test.ts` ports `Tm1ServerContainerTest`, `Tm1WebContainerTest` (with its `Tm1WebSession`) and `PawContainerTest`. `examples.test.ts` ports the Java examples that need only the shared server's source: `SharedServerExampleTest`, `WithoutTheExtensionExampleTest` and `PinnedVersionExampleTest`. The others need IBM's SData sample, Cognos or TM1 Web of their own, as in the .NET port, which doesn't port them either.

The client's live tests (`../test/live`) run against the shared server when `TM1_URL` isn't set: see `Live.containerTarget`.
