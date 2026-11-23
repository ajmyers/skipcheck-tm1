# @skipcheck/tm1-client (TypeScript)

A TypeScript client for the **IBM Planning Analytics (TM1) REST API**, with the same API as the Java client (`tm1-client`): the same services, methods, parameters, queries, patches, session views and errors, named the TypeScript way. Its model is generated from the same language-neutral spec ([`spec/tm1-api.json`](../spec)). ESM, for Node 20+ and browsers, with no runtime dependencies: it sends with `fetch`.

```ts
import { Cube, CubePatch, Filter, ODataPath, ODataQuery, Tm1Client } from "@skipcheck/tm1-client";

await using tm1 = Tm1Client.builder().baseUrl("http://localhost:8010/api/v1/").basicAuth("admin", "apple").build();

for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name).filter(Filter.startsWith(Cube.Fields.Name, "Sales")))) {
  console.log(cube.Name);
}

const dimensions = ODataPath.of("Dimensions");
await tm1.cubes.create(CubePatch.builder().name("Plan").bindDimensions(dimensions.key("Region"), dimensions.key("Year")).build());

const rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];";
if ((await tm1.cubes.checkRules("Plan", rules).toArray()).length === 0) {
  await tm1.cubes.update("Plan", CubePatch.builder().rules(rules).build());
}
```

**Status:** at parity with the Java client: every service class and method (240 endpoints, the same 2 excluded), the runtime (session, views, batches, async operations, capabilities, errors, auth, pool, identities, Cognos passports) and tm1-testcontainers. The live tests pass against TM1 11.8.03500.6 (Planning Analytics 2.1.19), started in Docker by tm1-testcontainers from an installed `tm1_64` folder (`TM1_HOME_DIR`): 168 tests, with relational drillthrough skipped for want of an ODBC source. tm1-testcontainers' own tests pass for the TM1 server; the TM1 Web and Planning Analytics Workspace ones haven't run, for want of those products. `TM1_URL` hasn't been tried. Cognos passports work in Node only: a browser never lets a page read the `Set-Cookie` header they come back in.

## From the Java client

| Java | TypeScript |
|---|---|
| `tm1.cubes().modelCubes(query)` | `tm1.cubes.modelCubes(query)`: services are properties, methods have Java's names |
| `Mono<T>` | `Promise<T>` (`Promise<void>` for `Mono<Void>`) |
| `Flux<T>` | `Tm1Stream<T>`: an `AsyncIterable<T>`, fetched page by page as it's iterated, with `toArray()`, `first()`, `map()`, `filter()` |
| Cancelling a subscription | `{ signal }`, an `AbortSignal`, the last argument of every call |
| `ODataQuery query` parameter | `query?: ODataQuery \| null` |
| Overloads (`update(name, patch)`, `update(name, patch, etag)`) | Optional parameters (`update(name, patch, etag?)`), or overload signatures |
| `Map<String, String>` (a tuple) | `Map` or a plain object (`Tm1Map`); pass a `Map` if a key may look like an integer, which an object would reorder |
| `Duration` | milliseconds (`withAsync(2000)`, `Tm1Timeouts`); TM1's own durations are ISO 8601 strings, read and written in Java's form (`PT24H`, never `P1D`; `Tm1Duration` converts) |
| `cube.name()`, `Cube.NAME` | `cube.Name`, `Cube.Fields.Name`: the model keeps TM1's property names, so what's read is TM1's JSON, but for date-times and durations (below) |
| `OffsetDateTime`, `Duration` read from TM1 | strings, as the Java client reads the values: a date-time as its instant in UTC without trailing zeros (`2026-01-02T03:04:00.120+05:30` reads as `2026-01-01T21:34:00.12Z`), a duration as Java's `Duration.toString` (`P1DT02H00M03.5S`, or bare seconds, reads as `PT26H3.5S`). So every client holds the same value; TM1's exact text isn't kept |
| `CubePatch.builder().name("x").build()` | the same |
| `Tm1Operations.CUBE_RESERVE` | `Tm1Operations.CubeReserve` |
| An enum, `ElementType.NUMERIC` | a string union, `"Numeric"`, and its members, `ElementType.Numeric`; `ElementType.literal("Numeric")` for a filter |
| `View` read as `NativeView` | `readAs(View, …)` keeps `@odata.type`; `typeOf(View, v) === "NativeView"` |
| `Tm1Client.builder()…build()`, `withSandbox`, `withAsync`, `batch()` | the same; `close()`, and `await using` |
| `webClient(…)`, `connector(…)` | `transport(…)`: a `Tm1Transport`, `fetchTransport()` by default |
| `@Tm1Endpoint("EntitySet Cubes")` | the class's `endpoints` table: `CubeService.endpoints.get` |

A value read from TM1 is its JSON, with three changes: an enum value this client doesn't know becomes the enum's fallback (`"Unknown"`), a duration sent as bare seconds becomes ISO 8601, and a lenient date TM1 fills with `0000-00-00T00:00Z` becomes `null`.

In a browser, the browser keeps TM1's session cookie (`credentials: "include"`, which TM1's CORS settings must allow); in Node, the client sends it itself.

## How it's made

| Part | Where | How |
|---|---|---|
| Model: an interface and model constant per CSDL type, enums, patch builders, `Tm1Operations`, the reader's schema | `src/generated/` | Generated from the spec by [`codegen/`](codegen) (`npm run generate`, before every build and test); not committed |
| Services | `src/services/<package>/<Name>Service.ts` | Hand-written, as the Java packages |
| Session, views, batch, async operations, transport | `src/session/` | Hand-written |
| Queries, requests, errors, versions, auth, reading | `src/odata/`, `src/request.ts`, `src/errors/`, `src/version/`, `src/auth/`, `src/json.ts` | Hand-written |

## Tests

```sh
npm ci
npm test            # generates, then runs vitest
npm run typecheck
npm run build
```

- `test/java-parity.test.ts` reads the Java sources: every public service class and method, with the same `@Tm1Endpoint` IDs and parameters in order. `test/runtime/java-parity-runtime.test.ts` does the same for the runtime.
- `test/coverage-gate.test.ts` holds the client to the spec's 242 endpoints, by the Java gate's rules.
- `test/conformance.test.ts` shares OData encoding cases with Java, Python and .NET; `test/payload.test.ts` reads the payloads the Java client captured from a real TM1.
- `test/services/` ports the Java client's unit tests, against a fake TM1 (`test/support/fake-tm1.ts`).
- `test/runtime/` ports the Java client's unit tests of the session, pool, auth, error, OData, request and version classes, against a scripted TM1 (`test/support/scripted-tm1.ts`, the Java tests' `FakeTm1`).
- `test/live/` ports the Java live tests (`*Tm1Test`), against `TM1_URL` (with `TM1_USER`, `TM1_PASSWORD`, `TM1_NAMESPACE`, `TM1_VERIFY=false`), or else against TM1 in Docker, started from the Java tests' sources (`TM1_PRIVATE_IMAGE`, `TM1_HOME_DIR`, `TM1_INSTALL_VOLUME`, `TM1_BUNDLE_DIR`, `TM1_BUNDLE_URL`) by [`testcontainers/`](testcontainers) (`Live.containerTarget`). Without either, or without Docker, they're skipped. `Live.builder(user, password)` logs a second user in the same way, through the CAM namespace if there is one.

`testcontainers/` is `@skipcheck/tm1-testcontainers`, the port of the Java `tm1-testcontainers`, an npm workspace of this package: `npm ci` here installs both, and `npm test`, `npm run typecheck` and `npm run build` run the client's, then its. Each uses the other from its sources (`paths` in `tsconfig.json`, `alias` in `vitest.config.ts`), so neither needs building first; `npm run build` builds the client first, as the testcontainers build uses its declarations.

## Porting a service

Follow `src/services/cube/CubeService.ts` and its tests:

1. Replace the stub `src/services/<package>/<Name>.ts` (same package and name as the Java class). Each public Java method becomes a method of the same name, with Java's parameters in order (an `ODataQuery` is `query?: ODataQuery | null`), then `options?: Tm1CallOptions`. Return `Promise<T>` for a `Mono`, `Tm1Stream<T>` (from `this.session.stream`) for a `Flux`. Build requests with `Tm1Request` and the generated model constant (`Tm1Request.get(path, query, Cube)`), operations with `Tm1Operations.X` and `.requires(op.capability())`.
2. Fill the class's `static readonly endpoints = { method: ["…"] } as const satisfies Tm1Endpoints<Name>` with the Java method's `@Tm1Endpoint` IDs.
3. Port helper types in the Java package (an enum becomes a string union plus a const of Pascal-case members; a record, a class or interface) into the same package folder, and export everything from `src/services/index.ts`.
4. Port the Java unit tests to `test/services/<Name>.test.ts` against `FakeTm1`, and the live ones to `test/live/<Name>.live.test.ts` with `tm1Test` and `Live`.
5. Take the class off `coverage/pending-classes.txt` and its endpoints off `coverage/pending.txt`; the gates fail until both are right.
