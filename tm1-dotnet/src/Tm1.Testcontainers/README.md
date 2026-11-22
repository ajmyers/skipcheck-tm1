# AjMyers.Tm1.Testcontainers

The .NET port of the Java [`tm1-testcontainers`](../../../tm1-testcontainers) module, built on [Testcontainers for .NET](https://dotnet.testcontainers.org/). It runs TM1 and the rest of Planning Analytics in Docker for tests. It reads the same settings in the same order and uses the same images, mounts and cache volume as the Java module, so a bundle unpacked for the Java tests is reused here.

The images contain no IBM software. A source supplies it: `TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` (with `TM1_BUNDLE`) or `TM1_BUNDLE_URL` (with `TM1_BUNDLE_SHA256`). You can set each one as an environment variable or as an `AppContext` switch, which is .NET's counterpart of a Java system property.

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
| `@RequiresTm1`, `@RequiresCognos`, `@RequiresTm1Web`, `@RequiresPaw` | `[RequiresTm1Fact]`, `[RequiresCognosFact]`, `[RequiresTm1WebFact]`, `[RequiresPawFact]` |
| A `Tm1ServerContainer` / `Tm1WebContainer` / `PawContainer` / `CognosSource` test parameter | `Tm1ServerFixture` / `Tm1WebFixture` / `PawFixture` / `CognosFixture` as an `IClassFixture` |

### How the containers are built

Testcontainers for .NET configures containers through immutable builders. Each container class here keeps its configuration (environment, network, aliases, dependencies) in a form you can read and change before the container starts, as Java's `GenericContainer` does. The builder only runs when the container starts. This is what lets `PlanningAnalyticsEnvironment` wire containers together, and lets the tests check that wiring without Docker.

### Reuse

Reuse follows the same switch as Java: set `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, or `TESTCONTAINERS_REUSE_ENABLE=true`. Without it, Testcontainers' Ryuk removes the containers once the run ends.

### Image tag

The image tag is the library's version. For a development build it's `edge`: Java's `-SNAPSHOT`, .NET's `-dev`.

### PAW

`paw-local.sh` comes from `docker/paw` and is embedded in the assembly, as the Java build embeds it in its jar.

## Tests

- **`JavaParityTests`** reads the Java sources and checks that every public type, nested record, constant, constructor and method has a .NET counterpart. It also checks that the `[…Fact]` skip messages match Java's word for word.
- **Configuration tests** need no Docker. They port Java's `*SourceTest`, `AdminServerWiringTest`, `CognosContainerTest` and `PlanningAnalyticsEnvironmentTest`.
- **Container tests** are skipped without Docker or a source. They port `Tm1ServerContainerTest`, `Tm1WebContainerTest` (with its `Tm1WebSession`) and `PawContainerTest`.
