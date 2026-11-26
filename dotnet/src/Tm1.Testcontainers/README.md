# Skipcheck.Tm1.Testcontainers

The .NET port of the Java [`tm1-testcontainers`](../../../java/tm1-testcontainers) module, built on [Testcontainers for .NET](https://dotnet.testcontainers.org/). It runs TM1 and the rest of Planning Analytics in Docker for tests. It reads the same settings in the same order and uses the same images, mounts and cache volume as the Java module, so a bundle unpacked for the Java tests is reused here.

The images contain no IBM software. A source supplies it: `TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` (with `TM1_BUNDLE`) or `TM1_BUNDLE_URL` (with `TM1_BUNDLE_SHA256`). You can set each one as an environment variable or as an `AppContext` switch, which is .NET's counterpart of a Java system property.

To run the same parts by hand, outside tests (to develop against, or to look at in a browser), use [`pa-setup`](../../../docker/pa-setup/README.md). It starts the same images from one file, `pa-environment.yaml`.

```csharp
public class CubeTests(Tm1ServerFixture tm1) : IClassFixture<Tm1ServerFixture>
{
    [RequiresTm1Fact]                       // Java's @RequiresTm1
    public async Task ListsCubes()
    {
        await using var client = tm1.Client();   // the shared server, started once per run
        ...
    }
}
```

## From the Java module

| Java | .NET |
|---|---|
| `new Tm1ServerContainer(source).withSeed(Tm1Seed.SDATA)` | `new Tm1ServerContainer(source).WithSeed(Tm1Seed.Sdata)` |
| `start()`, `stop()`, `shared()`, `idToken(…)`, `passport(…)` | `StartAsync()`, `StopAsync()`, `SharedAsync()`, `IdTokenAsync(…)`, `PassportAsync(…)`; `await using` stops a container |
| `getRestUrl()`, `getTm1Version()`, `isRunning()` | `RestUrl`, `Tm1Version`, `IsRunning` |
| `Optional<Tm1Source> discover()` | `Tm1Source? Discover()` |
| `Tm1Source.PRIVATE_IMAGE` and similar constants | `Tm1Source.PrivateImageSetting` (the `PrivateImage` record has the name); see `Renamed` in the parity test |
| `Network.newNetwork()` | `Network.NewNetwork()` (`Network.Of(…)` wraps a Testcontainers for .NET network) |
| `PlanningAnalyticsEnvironment.builder()….build()` | `PlanningAnalyticsEnvironment.Builder()….Build()` |
| `PlanningAnalyticsEnvironment.fromConfig(Path.of("pa-environment.yaml"))` | `PlanningAnalyticsEnvironment.FromConfig("pa-environment.yaml")` |
| `@RequiresTm1`, `@RequiresCognos`, `@RequiresTm1Web`, `@RequiresPaw`, `@RequiresTm1Engine` | `[RequiresTm1Fact]`, `[RequiresCognosFact]`, `[RequiresTm1WebFact]`, `[RequiresPawFact]`, `[RequiresTm1EngineFact]` |
| A `Tm1ServerContainer` / `Tm1WebContainer` / `PawContainer` / `CognosSource` test parameter | `Tm1ServerFixture` / `Tm1WebFixture` / `PawFixture` / `CognosFixture` as an `IClassFixture` |
| A `Tm1Database` test parameter with `@RequiresTm1Engine` | `Tm1DatabaseFixture<TheTestClass>` as an `IClassFixture`; with `shareDatabase = true`, `Tm1EngineFixture` |
| `engine.databases()`, `createDatabase(…)`, `dropDatabase(…)` | `DatabasesAsync()`, `CreateDatabaseAsync(…)`, `DropDatabaseAsync(…)` |
| `login.cookie()`, `token()`, `session(…)`, `authorize(HttpRequest.Builder)` | `CookieAsync()`, `TokenAsync()`, `SessionAsync(…)`, `AuthorizeAsync(HttpRequestMessage)` |

### TM1 v12 (the TM1 engine)

`Tm1EngineContainer` runs TM1 v12 (Planning Analytics 3.x) from the [`tm1-engine`](../../../docker/tm1-engine/README.md) image, with the MongoDB it keeps its metadata in (`mongo:8.0`, `engine.Datastore`) on a network of their own. A source supplies TM1: `TM1_ENGINE_PRIVATE_IMAGE`, `TM1_ENGINE_HOME_DIR`, `TM1_ENGINE_INSTALL_VOLUME`, `TM1_ENGINE_BUNDLE_DIR` (with `TM1_ENGINE_BUNDLE`) or `TM1_ENGINE_BUNDLE_URL` (with `TM1_ENGINE_BUNDLE_SHA256`), or `TM1_BUNDLE_DIR` when that folder holds a `tm1-v*-standalone-linux.tgz` too, so one downloads folder serves both versions. `TM1_ENGINE_IMAGE` names another image, such as `tm1-engine:local` for a local build.

One engine holds instances, each holding databases. One engine serves the whole test run (`Tm1EngineContainer.SharedAsync()`), and **each xUnit test class gets a database of its own**:

```csharp
public class CubeTests(Tm1DatabaseFixture<CubeTests> tm1) : IClassFixture<Tm1DatabaseFixture<CubeTests>>
{
    [RequiresTm1EngineFact]                 // Java's @RequiresTm1Engine
    public async Task ListsCubes()
    {
        var db = tm1.Database;              // this class's own, dropped when the class is done
        using var http = new HttpClient(new HttpClientHandler { UseCookies = false });
        using var request = new HttpRequestMessage(HttpMethod.Get, db.RestUrl + "Cubes?$select=Name");
        using var response = await http.SendAsync(await db.Login().AuthorizeAsync(request));
        ...
    }
}
```

- **A database per test class.** Java's "test class" is the unit a test framework shares set-up over, which in xUnit is a class: `Tm1DatabaseFixture<TTestClass>` creates the database before the class's first test and drops it after its last (about 10 seconds and 7). A class fixture isn't told whose it is, so the class is the fixture's type argument, and the database is named after it: its letters and digits in lower case, up to 24 of them, `_`, and four random characters (`cubetests_k3f9`).
- **`Tm1EngineFixture`** is Java's `shareDatabase = true`: the engine's one database, `tm1`, at no cost. Its tests need uniquely named objects, and to delete them afterwards.
- **No `minVersion`.** xUnit 2 can't skip a test that has started, and checking the version starts the engine. A test for a newer TM1 checks `db.Version` and returns, as a `[RequiresTm1Fact]` test does.
- **Logins.** TM1 v12 has no passwords of its own: `db.Login()` (`admin`) or `db.Login("analyst")` logs in through the instance's application, which the container creates. `CookieAsync()` gives the session cookie, `TokenAsync()` a bearer token, and `ClientId`, `ClientSecret` and `User` are for a client that logs in itself.
- **An engine of your own** takes about 40 seconds to start: `new Tm1EngineContainer(source).WithDatabase("planning").WithUser("analyst", "Analysts")`. `engine.Database("planning")` is checked against the databases the engine had when it started or was last asked for them (`DatabasesAsync()`, `CreateDatabaseAsync`, `DropDatabaseAsync`), where Java asks the engine each time.
- **The shared engine isn't reused** between runs, whatever the switch below says: an engine and its datastore belong together, and a kept engine would keep every database tests left in it.
- **Its own cache volume,** `tm1-engine-testcontainers-cache`, shared with the other languages' tests.

### How the containers are built

Testcontainers for .NET configures containers through immutable builders. Each container class here keeps its configuration (environment, network, aliases, dependencies) in a form you can read and change before the container starts, as Java's `GenericContainer` does. The builder only runs when the container starts. This is what lets `PlanningAnalyticsEnvironment` wire containers together, and lets the tests check that wiring without Docker.

### Reuse

Reuse follows the same switch as Java: set `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or `TESTCONTAINERS_REUSE_ENABLE=true`. Without it, Testcontainers' Ryuk removes the containers once the run ends.

### Reusing a whole environment

Cognos takes minutes to start and PAW longer. A `PlanningAnalyticsEnvironment` keeps its containers between runs when you ask for it with `Reuse(true)` and have turned on reuse as above. Without the switch, `Reuse(true)` has no effect.

```csharp
await using var env = PlanningAnalyticsEnvironment
    .Builder()
    .Tm1("planning")
    .Cognos(cognos => cognos.WithUser("admin", "adminpw", "Admins"))
    .Reuse(true)
    .Build();
await env.StartAsync(); // seconds, once a run with this configuration has started it
```

- **Each configuration has its own containers.** The environment's identity is a hash of its configuration: every part's image, source, environment variables, command, network aliases, labels, exposed ports, mounts and copied files, the Cognos users and the CAM settings. Its containers carry it as the label `io.skipcheck.tm1.environment`, and its network is named `tm1-environment-<identity>`, so the reused containers still reach each other by name. A different configuration gets new containers and never picks up another's. The label matters more here than in Java: Testcontainers for .NET's own reuse hash leaves out copied files and mounts, and the label puts them back in.
- **`StopAsync()` and disposing leave everything running**, PAW too.
- **What tests leave behind stays:** objects in TM1, sessions, Cognos's content store. Give test objects unique names and delete them.
- **Old configurations keep running.** Changing the configuration starts new containers; the old ones stay until you remove them, and Cognos alone holds about 8 GB. To start clean:

  ```sh
  docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)
  docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)
  ```

- **Not seen by the identity:** what a container subclass sets that the classes here don't show, such as its host name. Remove the containers after changing one.
- **PAW is one per Docker daemon** whatever the identity: IBM's compose files give its containers fixed names. Each start runs IBM's compose files again, which takes about a minute.
- **With your own `Network(...)`,** reuse works only if it's the same network each run. Testcontainers for .NET's reuse hash leaves the network out, so a container it finds is still on the network it was created on.

### Image tag

The image tag is the library's version. For a development build it's `edge`: Java's `-SNAPSHOT`, .NET's `-dev` and a published `-dev.1`.

### From pa-environment.yaml

`FromConfig` reads the [`pa-environment.yaml`](../../../docker/pa-setup/README.md#pa-environmentyaml) that `pa-setup` runs, so tests run the environment developers run, from one file:

```csharp
await using var env = PlanningAnalyticsEnvironment.FromConfig("pa-environment.yaml").Build();
await env.StartAsync();
var url = env.Tm1("planning").RestUrl;
```

It returns the builder, configured, so you can still change it before `Build()`. The file is checked as `pa-setup` checks it, with the same defaults and messages. A file it refuses throws an `ArgumentException`.

- **Each part** maps onto the builder: `tm1.servers` (with `seed` and `cfg`), `tm1.adminServer`, `tm1Web` (with `heap`), `cognos` (`users`, `oidc` as `Keycloak()`, `cam` as `CamSecurity`, `memory`) and `paw` (`acceptLicense`, `zip`).
- **Sources:** a part's source set on the builder wins; then `ibm`, the folder of IBM downloads; then the settings above (pa-setup's `.env` sets the same `*_URL` names); then the part's `images` entry, as a private image with IBM's software built in. With another source, an `images` entry is the image the part runs.
- **Ignored:** `name`, every port, and `files`. Testcontainers gives the environment its own network, random host ports and a new database each time.
- **Refused** with a message naming the setting: `cognos` without `tm1.servers`, and `seed: 24retail`, which the environment can't run yet.
- **YAML** is read with [YamlDotNet](https://github.com/aaubry/YamlDotNet), as YAML 1.1, the way pa-setup's PyYAML reads it: `yes`, `no`, `on` and `off` are booleans too.

### PAW

`paw-local.sh` comes from `docker/paw` and is embedded in the assembly, as the Java build embeds it in its jar.

## Tests

- **`JavaParityTests`** reads the Java sources and checks that every public type, nested record, constant, constructor and method has a .NET counterpart. It also checks that the `[…Fact]` skip messages match Java's word for word.
- **Configuration tests** need no Docker. They port Java's `*SourceTest`, `AdminServerWiringTest`, `CognosContainerTest`, `PlanningAnalyticsEnvironmentTest` and `Tm1EngineWiringTest`.
- **Container tests** are skipped without Docker or a source. They port `Tm1ServerContainerTest`, `Tm1WebContainerTest` (with its `Tm1WebSession`), `PawContainerTest`, `Tm1EngineContainerTest` (skipped without a TM1 v12 source), and `EnvironmentExampleTest`'s reuse test, which is also skipped with reuse off.
- **Example tests** are in `ExampleTests.cs`, and TM1 v12's in `Tm1EngineExampleTests.cs`. Every Java `*ExampleTest` is ported there.
- **`JavaTestParityTests`** reads the Java tests and holds these to them. Every Java test has a test of the same name here, or a line in `dotnet/coverage/testcontainers-unit-excluded.txt` saying why not.
