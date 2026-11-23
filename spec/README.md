# The API spec: one source for every client

Every client in this repository (Java, Python, .NET, TypeScript, and any added later) is generated from the same files, and has its coverage counted against the same list of endpoints.

```
java/tm1-client/src/model/metadata/tm1-schema-<v>.xml   TM1's $metadata, as captured (the source of truth)
spec/tm1-overlay.json                              what TM1 does differently from its $metadata (hand-written)
        │  tm1-codegen  (./gradlew :tm1-codegen:exportSpec)
        ▼
spec/tm1-api.json                                  the language-neutral spec (generated, committed)
        ├── Java:   tm1-codegen's ModelGenerator  → tm1-client's model (at build time)
        ├── Python: python/tm1-client/codegen (tm1_pygen) → skipcheck_tm1._generated (at build time)
        ├── .NET:   dotnet/src/Tm1.Codegen    → Skipcheck.Tm1.Client.Model (at build time)
        └── TypeScript: typescript/tm1-client/codegen    → src/generated (before build and test)
spec/conformance/*.json                            cases every client's tests must pass
```

## Files

| File | What it is | Edited by |
|---|---|---|
| `tm1-overlay.json` | TM1's departures from `$metadata`: properties sent in another type (`departures`), dates TM1 sometimes garbles (`lenientDates`), and notes about endpoints, with generic requests a client mustn't offer (`forbid`). Notes are Markdown. | Hand |
| `tm1-api.json` | The snapshot as plain JSON, with the overlay merged in: `enums`, `types`, `operations`, `container`, and `endpoints`, the coverage inventory. Sorted and versioned (`specVersion`), so a TM1 upgrade shows up as a readable diff. | `./gradlew :tm1-codegen:exportSpec` |
| `conformance/odata-encoding.json` | Paths and literals every client must build identically. | Hand |
| `conformance/calls/*.json` | Service calls the Java unit tests record, with every request and answer, which every other client replays exactly ([format](conformance/README.md)). | `./gradlew :tm1-client:test -PrecordConformance` |
| `conformance/live/*.json` | Scenarios every client runs against a real TM1; `scripts/compare-live-traffic.py` compares what each sent ([format](conformance/README.md#live-scenarios-live)). | Hand |

`tm1-api.json` is committed so clients in other languages build without a JVM. The `build` job fails when it's out of date (`ApiSpecTest`).

## Endpoints and coverage

`endpoints` lists every endpoint as an ID, such as `EntitySet Cubes`, `Singleton Server`, `ActionImport ExecuteMDX`, `Action Cube/Reserve` or `Navigation Cube/Views`. It's the inventory the Java client's `CoverageGateTest` counts (242 for schema 11.8.35), and `ApiSpecInventoryTest` checks the two agree.

Every client has a coverage gate over this list. An endpoint is implemented, excluded with a reason, or pending, and the pending list only shrinks:

| Client | Gate | Lists | Implemented |
|---|---|---|---|
| Java | `tm1-client` `CoverageGateTest` | `java/tm1-client/src/test/resources/metadata/coverage-*.txt` | 240 (2 excluded as duplicates) |
| Python (sync and asyncio, counted separately) | `python/tm1-client/tests/test_coverage_gate.py` | `python/tm1-client/coverage/*.txt` | 242 each (the generated `tm1.api`; the services match Java's 240) |
| .NET | `dotnet/tests/.../CoverageGateTests.cs` | `dotnet/coverage/*.txt` | as Java's: 240, the same 2 excluded |
| TypeScript | `typescript/tm1-client/test/coverage-gate.test.ts` | `typescript/tm1-client/coverage/*.txt` | as Java's: 240, the same 2 excluded |

Beyond counting endpoints, each client has a parity gate that reads the Java sources: every public service class and method, with the same endpoints and parameters (`python/tm1-client/tests/test_java_parity.py`, `dotnet/tests/.../JavaParityTests.cs`, `typescript/tm1-client/test/java-parity.test.ts`), and the runtime's public API (`test_java_parity_runtime.py`, `JavaParityRuntimeTests.cs`, `java-parity-runtime.test.ts`). Each also pins the exact headers Java's `Connection` sends.

The gates compare names and signatures; behaviour is compared by [conformance](conformance/README.md). The Java unit tests record each service call they make, with every request and answer. Every other client replays the recording and must send the same requests and return the same result. Against a real TM1, every client runs the same scenarios, and their traffic is compared.

Python also runs `test_contract.py`, which works out a route from the root to every endpoint from the spec alone, calls it through both generated APIs against a fake server, and checks the request each sends. And it reads the `$metadata` snapshot itself, as a server's capabilities, into exactly the spec's 242 IDs.

## Adding a language

1. Write a generator that reads `tm1-api.json`. `python/tm1-client/codegen/tm1_pygen/spec.py` has the lookups any generator needs and no Python-specific code, so it ports easily. Refuse a `specVersion` you don't know.
2. Generate the model: an enum per `enums` entry (with a fallback member for values a newer server adds), and a type per `types` entry. Every property is optional, a hierarchy reads by `@odata.type`, open types keep undeclared properties, writes send only what was set, and properties with a `departure` or `lenientDate` read leniently.
3. Generate a member per endpoint, and honour `forbid`. Through a navigation that doesn't contain its targets (`containsTarget: false`), offer reads only: on TM1, deleting through one deletes the target itself.
4. Hand-write the runtime: session (login, `TM1SessionId` cookie, re-login on 401, `ActiveSession/tm1.Close`), errors (the Java `Tm1ErrorClassifier` rules), and OData encoding.
5. Add the gate and `conformance/` tests to CI.
