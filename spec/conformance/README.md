# Conformance: one behaviour, every client

The Java client is the definition of how a call behaves. Its unit tests record each service call they make, with its arguments, every request the call sent, the answers it got, and what it returned or how it failed. The Python (both flavours), .NET and TypeScript clients replay every recording and must match it exactly. Against a real TM1, the same scenarios run in every client and their traffic is compared.

| File | What it is | Written by |
|---|---|---|
| `odata-encoding.json` | Paths and literals every client must encode identically | Hand |
| `writes.json` | Writes made from what was read (`builder(read)` on the create and patch types) that every client must produce identically | Hand |
| `builders.json` | What every generated create and patch builder offers: each `XPatch` and `XCreate`, its `@odata.type`, the JSON names its setters write, what its `build()` requires, and whether it takes undeclared properties (`property`). Every client's `BuilderManifestTest` drives its own builders to find these, and must match | `./gradlew :tm1-client:test -PrecordConformance` (the Java builders) |
| `calls/<JavaTestClass>.json` | The service calls one Java unit-test class records | `./gradlew :tm1-client:test -PrecordConformance` |
| `live/<scenario>.json` | Steps every client runs against a real TM1 | Hand |

## The builder manifest (`builders.json`)

Every client generates its create and patch types from the write plan in `spec/tm1-api.json` (`builders`), each with its own generator, so the rules (what a PATCH offers, what a create requires, what `builder(read)` copies) are made once, but written out four times. The manifest holds what the Java builders offer, and each client's `BuilderManifestTest` (`test_builder_manifest.py`, `BuilderManifestTests.cs`, `builder-manifest.test.ts`) finds what its own offer by driving them, not by reading its generator. A setter called on a builder of its own gives the JSON name it writes. A property is required when a builder with every other setter called refuses to build. The test checks the list of builders too, so a client that generates one more or one fewer fails.

When a change to the spec, the overlay, the probe file or the Java generator changes what a builder offers, the Java test fails until the manifest is re-recorded (`./gradlew :tm1-client:test -PrecordConformance`). The other clients then fail until their generators agree.

The manifest doesn't hold the types a setter takes (Python's setters take `Any`, and TypeScript's types are gone at runtime), nor what `builder(read)` copies: `writes.json` covers that.

## Recorded calls (`calls/`)

### Format

```jsonc
{
  "specVersion": 1,                                   // runners refuse a version they don't know
  "source": "io.skipcheck.tm1.client.service.cube.CubeServiceTest",
  "session": { "productVersion": "11.8.03500.6" },    // what every runner's fake reports
  "cases": [ {
    "id": "CubeServiceTest#getsACubeWithItsEtag#1",   // test class, test method, the call's number in it
    "service": "cubes",                               // the Tm1Client property
    "method": "get",                                  // the Java method
    "params": ["name", "query"],                      // Java's parameter names: runners bind arguments by them
    "args": ["Sales", { "$query": { "select": ["Name", "Rules"] } }],
    "exchanges": [ {
      "request":  { "method": "GET", "target": "Cubes('Sales')?$select=Name,Rules",
                    "headers": { "accept": "application/json", "odata-maxversion": "4.0", "odata-version": "4.0" } },
      "response": { "status": 200, "headers": { "content-type": "application/json" }, "json": { "Name": "Sales" } }
    } ],
    "result": { "$model": "Cube", "json": { "Name": "Sales" } }   // or "error": { … }
  } ]
}
```

- An entry of `exchanges` may instead be a group whose order is free, `{"unordered": [exchange, …]}`: for requests a call has in flight at once, such as a cellset stream's pages. Its requests may come in any order, each answered with the answer recorded for the equal request, and all of them before the next entry. Everything outside a group is in order.
- A request's `target` is its path and query relative to the service root, exactly as sent (encoded).
- Its `headers` are the ones that are the call's, not the session's: `accept`, `content-type`, `if-match`, `if-none-match`, `odata-version`, `odata-maxversion`, `prefer`, and any `tm1-*` except `tm1-sessioncontext`. Cookies, credentials and the user agent aren't compared.
- A body is `json`, `text` or `bytes` (base64), by its content type, or absent.
- The session's own requests (login, `Server?$select=ProductVersion`, `$metadata`, close) aren't in the fixture. Every runner's fake answers them the same way: the same product version and the same `$metadata` snapshot.
- `result` is what the call returned: `null` for nothing (`Mono<Void>`, an empty `Mono`), a list for a `Flux`, a model as `{"$model": <type>, "json": <TM1's JSON>}`, or a plain value. A polymorphic model is named by its own type (`Member` for `Member.Plain`, `TupleMember`), and its JSON leaves out the `@odata.type` Jackson writes for one. A stream of bytes (`Flux<DataBuffer>`, such as a document's content) is `{"$bytes": …}`, however it was split; a `Publisher<DataBuffer>` argument is recorded the same way. Durations and date-times are as Java writes what it read: `PT26H3.5S`, and the instant in UTC (`2026-01-01T21:34:00.12Z`).
- `take`, when there, is how many items of a `Flux` that doesn't end by itself (a log tail) the call took before it was cancelled: runners stop iterating after as many, and `result` lists them.
- `error` is how it failed: `category` (`api`, `protocol`, `transport`, `unsupported`, `argument` or `state`), and for TM1's own errors (`api`) its `kind` (Java's `Tm1ErrorKind` constant), `status` and `code`. `exception` is Java's class name, for reading; each language has its own, and it isn't compared.

### Arguments

A JSON value stands for itself. Anything else is an object with one `$` tag:

| Tag | Java value | Example |
|---|---|---|
| `$query` | `ODataQuery`, as its parts | `{"$query": {"select": ["Name"], "expand": {"Hierarchies": {"select": ["Name"]}}, "filter": "startswith(Name,'S')", "orderBy": ["Name desc"], "top": 5, "skip": 1, "count": true, "options": {"!sandbox": "s"}, "aliases": {"@p": "{}"}}}` |
| `$patch` | a generated patch | `{"$patch": "CubePatch", "json": {"Rules": null}}` |
| `$model` | a generated model | `{"$model": "Cube", "json": {...}}` |
| `$enum` | an enum | `{"$enum": "CheckFeedersSelection", "name": "UNFED", "json": "Unfed"}` |
| `$map` | a `Map` | `{"$map": {"Region": "North"}}` |
| `$path` | an `ODataPath` | `{"$path": "Dimensions('Region')"}` |
| `$double` | a `double` (to tell it from an `int`) | `{"$double": 12.5}` |
| `$duration`, `$dateTime`, `$date`, `$time` | `java.time` values | `{"$duration": "PT1H"}` |
| `$bytes` | `byte[]` | `{"$bytes": "AAEC"}` |
| `$type` | a service package's helper type, by its static factory; `new` for its constructor; or an instance method called on the first argument | `{"$type": "AttributeOwner", "factory": "view", "args": ["Sales", {"$enum": "ViewScope", ...}, "v"]}`, `{"$type": "GitCommitDetails", "factory": "new", "args": ["m", "a", "a@example.com"]}`, `{"$type": "ContentPath", "factory": "child", "args": [{"$type": "ContentPath", "factory": "applications", "args": []}, "Planning", {"$enum": "ContentScope", ...}]}` |
| `$handler` | a handler every runner knows by name (`ConformanceHandler`) | `{"$handler": "cells"}`: a cellset stream's handler that gives back its cells, `(cellset, cells) -> cells` |

Each runner rebuilds a query with its own query builder (`select`, `expand`, `Filter.raw`, `orderBy` or `orderByDescending`, and so on), and a patch from its JSON. A value the recorder can't encode, or a tag a runner doesn't know, is an error, never a skip. A helper type needs an encoder registered with `ConformanceCodec.register(type, value -> ConformanceCodec.factory("AttributeOwner", "view", ...))`. A call whose argument is a function (a change set's work, a cellset handler other than the named ones) can't be recorded: test it with plain Java unit tests, and port those by hand.

### What each runner checks

For every case, in each client (`python/tm1-client/tests/conformance/test_calls.py` in both flavours, `dotnet/tests/.../Conformance/ConformanceCallsTests.cs`, `typescript/tm1-client/test/conformance/calls.test.ts`):

1. It decodes the arguments and calls the service's method by its own name for Java's (`model_cubes`, `ModelCubesAsync`, `modelCubes`), binding them by Java's parameter names (TypeScript, positionally in Java's order, which its ports keep).
2. It answers each request with the recorded answers, in order; within an `unordered` group, with the answer of the group's equal request not yet answered.
3. Each request must equal the recorded one it was answered for: method, target, the headers above, and the body. JSON bodies are compared with their members in order.
4. It must send exactly as many requests (a group's count as its members).
5. The result must equal the recorded one (models by their JSON, with null members left out, as clients differ on writing them), or the error must match its category, kind, status and code. A model's JSON leaves out `@odata.type` (its `$model` is its type), and an enum's fallback (a value the client doesn't know) is `null`, as Java writes its fallback constant. Everything else, durations and date-times included, must be exactly as Java wrote it.

A case a language can't express goes in that client's `conformance/unsupported.txt`, with the reason. The runner fails if the list names a case no fixture has.

### Adding cases

1. Write the Java unit test in `java/tm1-client/src/test/java/…/<package>/<Name>ServiceTest.java`:

   ```java
   @RegisterExtension
   static final Conformance conformance = new Conformance();

   @Test
   void getsACubeWithItsEtag() {
       conformance.tm1().json("{\"@odata.etag\":\"W/\\\"1\\\"\",\"Name\":\"Sales\"}");   // the answer
       Cube cube = conformance.call("cubes", "get", "Sales", ODataQuery.none().select("Name"));
       assertThat(cube.etag()).isEqualTo("W/\"1\"");
       assertThat(conformance.tm1().last().key()).isEqualTo("GET Cubes('Sales')?$select=Name");
   }
   ```

   `conformance.call` calls the method by name, so what's recorded is what ran; `conformance.fail` does the same for a call that must fail, and returns the error; `conformance.take(n, …)` takes the first `n` items of a `Flux` that doesn't end by itself, then cancels it. Script one answer per request (`json`, `text`, `bytes`, `status`, `error`): a call that leaves answers unused fails. Requests that may come in any order are scripted as a group, each answer for its `METHOD target`, and recorded in the order scripted:

   ```java
   conformance.tm1().unordered(pages -> pages
       .json("GET Cellsets('c1')/tm1.GetPartition(Begin=3,End=5)?$select=Ordinal,Value", "{\"value\":[…]}")
       .json("GET Cellsets('c1')/tm1.GetPartition(Begin=6,End=8)?$select=Ordinal,Value", "{\"value\":[…]}"));
   ```

   Keep a group to what's really in flight at once: everything else stays in order. Assert what Java does as you would anyway; the fixture holds the rest.
2. Record: `./gradlew :tm1-client:test --tests '*<Name>ServiceTest' -PrecordConformance`. It writes `spec/conformance/calls/<Name>ServiceTest.json`. Only a whole class records, and only if all its tests pass.
3. Run each runner: `pytest tests/conformance` (python/tm1-client), `dotnet test --filter FullyQualifiedName~Conformance` (dotnet), `npx vitest run test/conformance` (typescript/tm1-client).
4. Where a client differs, fix the client, not the fixture.

Without `-PrecordConformance`, the Java build fails if a class's recording differs from its fixture. It also fails if a fixture belongs to no test class (`ConformanceFixturesTest`). A change of Java's behaviour therefore shows up first as a failing Java build, then, once re-recorded, as a fixture diff every other client has to match.

## Live scenarios (`live/`)

A scenario is steps every client runs against a real TM1, with names made fresh for each run:

```jsonc
{
  "specVersion": 1,
  "name": "cubes",
  "names": ["region", "cube", "missing"],          // each becomes tm1conf_<run>_<name>
  "steps": [
    { "service": "cubes", "method": "get", "params": ["name", "query"],
      "args": ["${cube}", { "$query": { "select": ["Name"] } }],
      "expect": { "fields": { "Name": "${cube}" } } }
  ],
  "finally": [ { "service": "cubes", "method": "delete", "params": ["name"], "args": ["${cube}"] } ]
}
```

- Arguments use the same encoding as the fixtures. `${name}` placeholders are replaced in every string.
- A step can save fields of its result for the steps after it, `"save": {"note": "ID"}`: the result's `ID` (a field of its JSON, a value, not null) becomes the name `note`, for an ID TM1 makes up, such as an annotation's or a debug context's. A step whose result lacks one fails. Saved names join the run's names, so the traffic has them as placeholders too; give each a fallback in `names`, so a `finally` step that uses it still runs when its step didn't.
- A step whose requests may go out in any order (a cellset stream's pages) names them with `"unordered"`, a regular expression matching `METHOD target` (after names become placeholders): each run of the step's consecutive requests that match it is compared as a set. The step's other requests, and every other step's, are compared in order.
- A step that polls TM1 until it's done (finishing a debug run resumes it until it's complete) names the requests it repeats with `"repeats"`, a regular expression on `METHOD target`: each run of equal consecutive ones compares as one, since how many TM1 takes varies. A `finally` step can too.
- Each step has one expectation:
  - `{"none": true}`: the call returns nothing.
  - `{"count": n}`: a list of `n` items.
  - `{"fields": {...}}`: these fields of the result. An `@odata.type` field is the result's type (its `$model`, as `#ibm.tm1.api.v1.<model>`), since a result's JSON leaves the type out.
  - `{"items": {"Name": [...]}}`: a field of each item, in order.
  - `{"error": {"category": "api", "kind": "NOT_FOUND"}}`: the call fails this way.
- The run stops at the first step that misses its expectation. The `finally` steps always run, ignoring failures.

All 23 scenarios pass in every client against TM1 11.8.03500.6 (Planning Analytics 2.1.19), and the clients' traffic compares equal. Two leftovers are known: the views scenario leaves a cellset until its session ends, and the git scenario assumes Git isn't initialised on the server.

The runners are Java's `ConformanceScenarioTm1Test`, Python's `tests/live/test_conformance_scenario.py` (sync and async), .NET's `Live/ConformanceScenarioLiveTests.cs` and TypeScript's `test/live/ConformanceScenario.live.test.ts`. Each runs with its client's live harness (testcontainers, or `TM1_URL`) and records what it sends to `build/conformance-live/<language>/<scenario>.jsonl`. The session's own requests are left out of the record.

[`scripts/compare-live-traffic.py`](../../scripts/compare-live-traffic.py) normalizes the logs: names go back to placeholders, entity tags are dropped, and a cellset's ID, which TM1 makes up for each run, becomes `<id>`. It then requires every language to have sent the same requests, with the same statuses, in the same order, but for a step's `unordered` runs. [`scripts/live-tests.sh`](../../scripts/live-tests.sh) runs it after the suites. Each runner also has offline tests: the cubes scenario against a fake TM1; the data scenarios (cellsets, views, sandboxes) against a fake that answers as TM1 would; and other scenarios (subsets, members, edges, member-sets, content, encryption, git and more) against a fake answering each step's one request with what the step expects (and what it saves, as `saved_<name>`, if it doesn't expect it). A byte stream's result is checked as `{"fields": {"$bytes": …}}`.

### Adding a scenario

Write `live/<name>.json` with the steps a service's live test covers, using only names from `names`, then run `./gradlew liveTest --continue` (or `scripts/live-tests.sh`).
