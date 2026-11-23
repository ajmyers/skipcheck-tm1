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
| `@RequiresTm1`, `@RequiresCognos`, `@RequiresTm1Web`, `@RequiresPaw` | `[RequiresTm1Fact]`, `[RequiresCognosFact]`, `[RequiresTm1WebFact]`, `[RequiresPawFact]` |
| A `Tm1ServerContainer` / `Tm1WebContainer` / `PawContainer` / `CognosSource` test parameter | `Tm1ServerFixture` / `Tm1WebFixture` / `PawFixture` / `CognosFixture` as an `IClassFixture` |

### How the containers are built

Testcontainers for .NET configures containers through immutable builders. Each container class here keeps its configuration (environment, network, aliases, dependencies) in a form you can read and change before the container starts, as Java's `GenericContainer` does. The builder only runs when the container starts. This is what lets `PlanningAnalyticsEnvironment` wire containers together, and lets the tests check that wiring without Docker.

### Reuse

Reuse follows the same switch as Java: set `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or `TESTCONTAINERS_REUSE_ENABLE=true`. Without it, Testcontainers' Ryuk removes the containers once the run ends.

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
- **Refused** with a message naming the setting: `cognos` without `tm1.servers`, `paw` with `cognos`, and `seed: 24retail`, which the environment can't run yet.
- **YAML** is read with [YamlDotNet](https://github.com/aaubry/YamlDotNet), as YAML 1.1, the way pa-setup's PyYAML reads it: `yes`, `no`, `on` and `off` are booleans too.

### PAW

`paw-local.sh` comes from `docker/paw` and is embedded in the assembly, as the Java build embeds it in its jar.

## Tests

- **`JavaParityTests`** reads the Java sources and checks that every public type, nested record, constant, constructor and method has a .NET counterpart. It also checks that the `[…Fact]` skip messages match Java's word for word.
- **Configuration tests** need no Docker. They port Java's `*SourceTest`, `AdminServerWiringTest`, `CognosContainerTest` and `PlanningAnalyticsEnvironmentTest`.
- **Container tests** are skipped without Docker or a source. They port `Tm1ServerContainerTest`, `Tm1WebContainerTest` (with its `Tm1WebSession`) and `PawContainerTest`.
