# Skipcheck.Tm1.Client (.NET)

A .NET client for the **IBM Planning Analytics (TM1) REST API**, with the same API as the Java client (`tm1-client`): the same services, methods, parameters, queries, patches, session views and errors, named the .NET way. Its model is generated from the same language-neutral spec ([`spec/tm1-api.json`](../spec)). It requires .NET 10 and has no dependencies beyond the framework.

```csharp
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

await using var tm1 = Tm1Client.Builder().BaseUrl("http://localhost:8010/api/v1/").BasicAuth("admin", "apple").Build();

await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name).Filter(Filter.StartsWith(Cube.Fields.Name, "Sales"))))
{
    Console.WriteLine(cube.Name);
}

var dimensions = ODataPath.Of("Dimensions");
await tm1.Cubes.CreateAsync(CubePatch.Builder().Name("Plan").BindDimensions(dimensions.Key("Region"), dimensions.Key("Year")).Build());

var rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
if (await tm1.Cubes.CheckRulesAsync("Plan", rules).CountAsync() == 0)
{
    await tm1.Cubes.UpdateAsync("Plan", CubePatch.Builder().Rules(rules).Build());
}

// A long process, run asynchronously by TM1 and polled; cancelling the wait stops it on TM1
await tm1.WithAsync(TimeSpan.FromSeconds(2)).Processes.ExecuteWithReturnAsync("Rebuild Forecast");
```

## From the Java client

| Java | .NET |
|---|---|
| `tm1.cubes().modelCubes(query)` | `tm1.Cubes.ModelCubesAsync(query)`; services are properties, methods PascalCase with `Async` |
| `Mono<T>` | `Task<T>` (`Task` for `Mono<Void>`) |
| `Flux<T>` | `IAsyncEnumerable<T>`, streamed |
| Cancelling a subscription | A `CancellationToken`, the last parameter of every method |
| `ODataQuery query` parameter | `ODataQuery? query = null` |
| Overloads (`update(name, patch)`, `update(name, patch, etag)`) | Optional parameters where they're a tail, real overloads otherwise |
| `CubePatch.builder().name("x").build()` | `CubePatch.Builder().Name("x").Build()` |
| `Cube.NAME`, `Filter.and(…)` | `Cube.Fields.Name`, `Filter.And(…)` |
| `Tm1Client.builder()…build()`, `withSandbox`, `withAsync`, `batch()` | `Tm1Client.Builder()…Build()`, `WithSandbox`, `WithAsync`, `Batch()` |
| `Tm1SessionPool`, `Tm1Identity`, `Tm1Timeouts`, `Tm1ErrorClassifier` | the same names |
| `Tm1NotFoundException` and the others | the same names |
| `@Tm1Endpoint` | `[Tm1Endpoint]` |
| `close()` | `DisposeAsync()` (`await using`) |

## How it's made

| Part | Where | How |
|---|---|---|
| Model: an enum or class per CSDL type with its `Fields`, patch builders, `Tm1Operations` | `Skipcheck.Tm1.Client.Model` | Generated from the spec at build time, by [`Tm1.Codegen`](src/Tm1.Codegen), into `obj/generated/` |
| Services | `src/Tm1.Client/Services/` | Hand-written, one namespace (`Skipcheck.Tm1.Client.Services`) |
| Session, views, batch, async operations, pool, auth, Cognos | `src/Tm1.Client/Session/`, `Auth/` | Hand-written |
| Queries, requests, errors, versions, JSON | `OData/`, `Errors/`, `Version/`, `Json/` | Hand-written; JSON is System.Text.Json with converters for TM1's enums, durations, `@odata.type` and patches |

Nothing generated is committed. The `GenerateTm1Model` target in `Tm1.Client.csproj` runs the generator whenever the spec or the generator changes.

## Tests

```sh
dotnet test Tm1.slnx
```

- `JavaParityTests` and `Runtime/JavaParityRuntimeTests` read the Java sources. They require every public class and method, with the same `@Tm1Endpoint` IDs and parameters.
- `ExamplesParityTests` holds [`examples/`](examples) to the Java examples the guide quotes: a file for each Java examples class, with the same regions. The two examples projects build with the solution, under the same warnings as errors, so an example that stops compiling fails the build; they're never run or packed.
- `ArchitectureTests` holds the compiled libraries to the Java client's architecture rules (`ArchitectureTest`), each with its reason: asynchronous only, URLs from `ODataPath` only, no console, an OData layer that knows nothing of TM1, stateless services whose methods declare their endpoints.
- `CoverageGateTests` holds the client to the spec's 242 endpoints, by the Java gate's rules. It implements 240 and excludes the same 2 as Java ([`coverage/`](coverage)).
- `ConformanceTests` shares OData encoding cases with Java and Python. `PayloadTests` round-trips the payloads the Java client captured from a real TM1.
- `Services/` and `Runtime/` port the Java client's unit tests, against a fake TM1 (`FakeTm1`).
- `Live/` ports the Java live tests (`*Tm1Test` and `*BehaviorTest`), a file per Java class and a test per Java test, by the same name: `CubeServiceTm1Test` is `Live/CubeServiceLiveTests.cs`. `LiveTestParityTests` reads the Java sources and fails on a class or test with no port here and no line saying why in [`coverage/live-excluded.txt`](coverage/live-excluded.txt). Like the Java ones, they start TM1 in Docker themselves, with [`Skipcheck.Tm1.Testcontainers`](src/Tm1.Testcontainers) (the .NET port of Java's `tm1-testcontainers`). They read the same settings the Java tests do: `TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR` or `TM1_BUNDLE_URL`. To use a server that's already running instead, set `TM1_URL`, with `TM1_USER`, `TM1_PASSWORD`, `TM1_NAMESPACE` and `TM1_VERIFY=false` as needed. With neither, they're skipped.
- `tests/Tm1.Testcontainers.Tests` ports the Java module's tests, including a parity test against its sources.

`./gradlew liveTest --continue` (or [`scripts/live-tests.sh`](../scripts/live-tests.sh)) runs every client's live tests together.

The live tests pass against TM1 11.8.03500.6 (Planning Analytics 2.1.19), started in Docker by Tm1.Testcontainers from an installed `tm1_64` folder (`TM1_HOME_DIR`): 170 tests, with relational drillthrough skipped for want of an ODBC source. The five CAM tests (`Cam*AuthenticationLiveTests`) need a Cognos source and pass on the Cognos test container (12.1.1). They run one class at a time, since they share the server. Tm1.Testcontainers' own tests pass for the TM1 server; the TM1 Web and Planning Analytics Workspace ones haven't run, for want of those products. They also pass against a server that is already running, named with `TM1_URL`.
