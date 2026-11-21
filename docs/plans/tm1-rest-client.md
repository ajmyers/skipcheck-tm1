# Plan: the reactive TM1 REST client

**Status:** agreed direction; open questions in §14 · **Issue:** #21 · **Last updated:** 2026-11-21

This is the plan for `tm1-client`, a low-level, reactive Java client for the IBM Planning
Analytics (TM1) REST API. It works standalone or as injectable Spring Boot beans. It records
the requirements, what we learned from the live server and the OData spec, the proposed
architecture, the open questions, and the phases. Once it's agreed, each phase becomes its
own issue.

---

## 1. Requirements

| # | Requirement | Where it's addressed |
|---|---|---|
| R1 | Works standalone **and** as independently injectable Spring Boot components | §6, §11 |
| R2 | Covers **every** endpoint in the server's `$metadata`, with integration tests against our `tm1-server` | §8, §12, Appendix A |
| R3 | Designed up front for capabilities and behavior that differ by TM1 version. The first build targets our version, and version-specific code is added only when something breaks | §9 |
| R4 | One shared TM1 session passed to every service. Each service can be injected alone, and an aggregate holds them all | §5, §6 |
| R5 | WebClient and Reactor throughout: reactive first | §7 |
| R6 | Low level: maps the REST calls 1:1. Higher-level helpers come later, as a separate layer | §4, §6 |
| R7 | Follows OData v4 as the spec intends | §3, §4 |

**Non-goals for the first release:** higher-level helpers (e.g. "load a DataFrame-like grid",
"rebuild a dimension from a list"; these come later as a second layer, Q10), a blocking facade
(not planned at all, Q9), TM1 v12 / PA Cloud support (designed
for, not built), and TM1 Web or PAW APIs.

---

## 2. What the server gives us (measured, 2026-11-21)

Local `tm1-server` (`ghcr.io/ajmyers/tm1-server:edge`), **TM1 11.8.03500.6**, `$metadata`
`Core.SchemaVersion` **11.8.35**, `Core.ODataVersions` **4.0**, `Capabilities.ConformanceLevel`
**Intermediate**.

**API surface** (namespace `ibm.tm1.api.v1`, alias `tm1`):

| Item | Count |
|---|---|
| Entity types | 67 (24 in inheritance hierarchies: `Entry`, `View`, `Drillthrough`, `GitPlan`, `ProcessDebugContextBreakpoint`, `Member`) |
| Complex types | 62 (mostly `ServerSettings` sections) |
| Enum types | 31 |
| Actions | 91 (30 unbound action imports, 61 bound) |
| Functions | 14 (11 unbound function imports, 3 bound) |
| Navigation properties | 90 |
| Entity sets / singletons | 27 / 6 |
| `Core.Revisions` records | 281: 261 *Added* and 20 *Deprecated*, from 10.2.2.0 to 11.8.35 |

Full inventory in [Appendix A](#appendix-a-endpoint-inventory-tm1-11803500--schema-11835).

**Protocol behavior, probed with curl:**

| Behavior | Result | Consequence |
|---|---|---|
| `GET /api/v1/$metadata.json` | **400**, "Resource invalid" | Only XML CSDL is served (`SupportedMetadataFormats: application/xml`). We parse XML. |
| Basic auth with no cookie | Every request creates a **new TM1 session** (`Set-Cookie: TM1SessionId=…; Path=/api/`). A dozen probes left a dozen sessions open. | The client must hold onto the session cookie. Re-sending Basic credentials on every request would leak sessions. |
| `POST ActiveSession/tm1.Close` | 204 with `Content-Type: application/json`. **400 without it**, even with no body. | Always send a JSON content type (and `{}`) when invoking actions. |
| Error body | `{"error":{"code":"278","message":"…"}}`. The code was `278` for a 404, a 400, a 412 and a 501. | The TM1 `code` isn't a discriminator. Map exceptions from HTTP status, and keep code and message for the reader. |
| `$select`, `$expand` (nested, `;`-separated), `$filter` (`startswith`, `contains`, `any()` lambda), `$orderby`, `$top`, `$skip`, `$count=true`, `/$count` | All work | The query builder can offer the full syntax. |
| `$search` | **501** "not implemented" | Leave it out of the builder, or gate it on a capability. |
| `Prefer: odata.maxpagesize=2` | Ignored: the full collection came back, with no `nextLink` | No server-driven paging. Large collections arrive in a single body, so streaming decode matters (§7). We still follow `nextLink` if one ever appears (spec MUST). |
| Type-cast segment `Cubes('x')/Views/tm1.NativeView` | Works | Derived types can be addressed in paths. |
| `@odata.etag` on entities, `PATCH` with a stale `If-Match` | ETags returned; **412** on mismatch | Optimistic concurrency is real. Expose ETags and `If-Match`. |
| `Prefer: respond-async` | **202**, `Location: _async('…')`, `Preference-Applied: respond-async` | OData async works. We can use it for long-running TI processes and chores. |
| `Prefer: odata.track-changes` on `TransactionLogEntries` | Applied; `@odata.deltaLink: TransactionLogEntries/!delta('…')` | Delta and change tracking work on the 7 sets annotated `Capabilities.ChangeTracking`: Annotations, ApplicationContextFacets, AuditLogEntries, MessageLog, MessageLogEntries, TransactionLog, TransactionLogEntries. A natural fit for `Flux` "tail" streams. |
| `$batch`, multipart | **400**: "use JSON" | OData 4.01 **JSON batch works**, even though the server says 4.0. `BatchSupport` says there are no cross-request references or `continue-on-error`. |
| `$ref` on a navigation, `odata.metadata=full` | Work (`@odata.id`, `navigationLink`, `associationLink`) | Standard OData. |

**Session behavior, probed in step 3 (#30)** and pinned by `Tm1SessionBehaviorTest`:

| Behavior | Consequence |
|---|---|
| A cookie-only request on a killed session gets **401 and doesn't run** | The re-login replay is safe for POSTs (§5) |
| **Every 401 opens a session** with no user, and so does an anonymous `GET Server` (allowed, 200) | Never send a request outside the session |
| Sending the 401's cookie with credentials logs **that** session in | Re-login reuses it and leaves nothing behind |
| A closed session is refused at once but stays listed in `Sessions` for 5–9 s | Leak checks wait for the count to settle |
| Requests on one session run **in parallel** | One shared session doesn't serialize traffic |
| `TM1-SessionContext` labels the whole session; the last request to send one wins | The label is a session setting, not a view (§5) |
| A create returns 201 with `Location` and `ETag`; `return=minimal` gives 204 with `Location` and `OData-EntityId` | Creates can skip the body |
| `odata.metadata=none` drops `@odata.etag` but keeps `@odata.context` | Use it only where ETags aren't needed |

---

## 3. OData v4: what "as intended" means here

Sources read: OData 4.01 Part 1 (Protocol), Part 2 (URL Conventions), JSON Format, CSDL XML,
and the Core vocabulary (links in [Appendix B](#appendix-b-references)). TM1 says it's
**4.0 Intermediate**, so we follow 4.0 rules on the wire and use 4.01 features only where the
server has shown it supports them (JSON batch).

### 3.1 Rules the client follows

- **Versions.** Send `OData-MaxVersion: 4.0` and `OData-Version: 4.0` on every request
  (Protocol 8.1.5, 8.2.7; interoperable clients MUST, 13.3). Read the response's `OData-Version`.
- **4.0 spellings.** Lowercase, `$`-prefixed system query options. `odata.`-prefixed preferences
  (`odata.maxpagesize`, `odata.track-changes`). `@odata.`-prefixed control information.
  `Prop@odata.bind` for references, not the 4.01 `{"@id": …}` form (JSON 8.5).
- **URL building.** The service root ends in `/`. Use canonical key form `Cubes('Sales')`.
  Double single quotes in string literals (`'O''Neil'`). Percent-encode `/` as `%2F` and
  never encode a space as `+`. Encode each segment and query value **once** (URL 2.1, 2.2).
  TM1 names often contain `}` (control objects), spaces, `'` and `/`, so this must be correct
  and fuzz-tested.
- **Updates use PATCH** (Protocol 11.4.3; client MUST, 13.3). PATCH changes only the properties
  sent, and an explicit `null` sets null. So update payloads must tell *absent* apart from
  *null* (§8.3).
- **ETags.** Keep `@odata.etag` and send it as `If-Match` when asked. Map 412 and 428 to a
  concurrency exception.
- **Opaque links.** Follow `@odata.nextLink` / `@odata.deltaLink` as given. Never append query
  options to them (Protocol 11.2.6.7, 11.3).
- **Tolerance.** Ignore unknown properties, navigation properties, annotations and control
  information (JSON 4.5; client MUST, 13.3). This is also what makes newer TM1 versions safe
  for older clients.
- **Operations.** Actions are `POST …/tm1.Name` with a JSON object of parameters. Functions are
  `GET …/tm1.Name(p=…)`, with complex or collection arguments passed as parameter aliases
  (`?@p=<json>`). Bound operations are namespace-qualified (Protocol 11.5).
- **Polymorphism.** Use `@odata.type` to choose the subtype (`#ibm.tm1.api.v1.MDXView`). Send
  `@odata.type` when creating a derived type (JSON 4.5.3).
- **Numbers.** Request `IEEE754Compatible=true` so Int64 and Decimal arrive as strings, and map
  them to `long`/`BigDecimal` without precision loss (JSON 3.2). This needs checking against
  TM1: cell values are `Edm.Double`-like, but counts and IDs are Int64.
- **Errors.** Parse `{"error":{code,message,target?,details?,innererror?}}` (JSON 21.1). A
  response can fail **after** a 200 status (in-stream error, Protocol 9.5), so a decode failure
  part-way through a 200 body is an error, not a truncated success.
- **Feature detection from metadata.** The spec says clients SHOULD use the metadata's
  Capabilities annotations to detect features, and MUST handle 501/400 when they try
  unadvertised syntax (13.3). This is the basis for version handling (§9).

### 3.2 How OData shapes the code

OData is a **generic protocol** with a TM1 **model** on top. We split the code the same way, so
that OData correctness lives in one tested place, not in every service:

- `…client.odata`: TM1-agnostic. Resource paths and key literals, the typed query builder and
  `$filter` DSL, JSON control information (`@odata.context/etag/type/nextLink/deltaLink/count/bind`),
  the error model, `Prefer` / `Preference-Applied`, collection decoding with `nextLink`, delta,
  async monitors, JSON batch, and a CSDL parser.
- `…client.tm1…`: the TM1 session, auth, headers, sandbox, version and capabilities, the generated
  model, and the services.

**Rejected: an existing OData library.** Apache Olingo's client is blocking, ships its own HTTP
stack and model, and isn't really maintained. The SAP Cloud SDK is SAP-specific and heavy.
Neither is reactive. The part of OData we need is small and well specified, and owning it keeps
the dependency footprint at Spring WebFlux plus Jackson.

---

## 4. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Tm1Client (aggregate: one session, every service)                │  R4
├──────────────────────────────────────────────────────────────────┤
│  Services: CubeService, DimensionService, HierarchyService,       │  R6: 1:1 with
│  ElementService, SubsetService, ViewService, CellsetService,      │  REST resources
│  ProcessService, ChoreService, SecurityService, ServerService …   │  and operations
├──────────────────────────────────────────────────────────────────┤
│  Generated model: records, sealed hierarchies, enums, patch       │  from CSDL
│  builders, field constants for $select/$filter, Since/Deprecated  │
├──────────────────────────────────────────────────────────────────┤
│  Tm1Session: WebClient, base URL, auth, cookie, version,          │  the shared
│  capabilities, request context (sandbox, session context label)   │  "cursor"
├──────────────────────────────────────────────────────────────────┤
│  odata: paths, literals, query builder, codecs, errors, paging,   │  R7
│  delta, async, batch, CSDL parser                                 │
├──────────────────────────────────────────────────────────────────┤
│  Spring WebFlux WebClient + ClientHttpConnector (Reactor Netty or │  R5
│  JDK HttpClient) + Jackson 3                                      │
└──────────────────────────────────────────────────────────────────┘
          ▲
          │ tm1-spring-boot-starter: properties, Tm1ConnectionDetails (#15),
          │ beans for the session, each service and Tm1Client               R1
```

**Packages** (`net.ajmyers.tm1.client`, proposed):
`odata` (+ `odata.csdl`, `odata.query`), `session`, `auth`, `version`, `model` (generated, split
by area), `service` (one subpackage per area), `error`.

---

## 5. The session: our "cursor"

`Tm1Session` is the one object every service receives. It's immutable and thread-safe, shared
by every service and every concurrent subscriber.

- **Holds:** the configured `WebClient`, the service root (`http://host:8010/api/v1/`), the
  authenticator, and the **TM1 session cookie**, plus the server's `Tm1Version` and
  `Tm1Capabilities` once connected.
- **Lazy, single login.** `connect()` returns `Mono<Tm1Session>`. The first request triggers
  login (`GET ActiveSession?$select=ID`, which also gives the session ID; the deprecated
  `Configuration` singleton is avoided), and concurrent first requests share one login (a cached
  `Mono`). Only the login carries credentials. On a **401** with an established session (an
  expired or killed session), it logs in again once, sending the cookie TM1 set with the 401 so
  that session is the one logged in, and replays the request once. Login failures are not
  replayed.
  - **This replay covers POSTs too, deliberately.** It's an exception to "never retry a
    non-idempotent request" (§16.2). A 401 means TM1 refused the request before running it,
    because the session wasn't valid, so replaying it after logging in again can't run it twice.
    **Verified in step 3 (#30):** a cookie-only POST on a killed session doesn't run, and through
    the client the replayed POST creates its object exactly once. Every other failure after a
    request was sent is never replayed for non-idempotent requests.
- **Explicit close.** `close()` returns `Mono<Void>` and calls `POST ActiveSession/tm1.Close`
  with a JSON body. Spring calls it on context shutdown. Without it, sessions pile up on the
  server until they time out (seen while probing).
  - **A login in progress is waited for, then closed.** Otherwise a `close()` that races a
    first request would do nothing, and the login would finish afterwards and leave a session
    open. Only a session that never started a login is skipped.
  - After `close()`, the session refuses new requests rather than logging in again.
- **Request context, derived not mutated.** `session.withSandbox("What-if")` returns a **new
  view sharing the same connection and cookie** that adds TM1's `!sandbox=` query option to each
  request. Services take whichever view they're given:
  `new CellsetService(session.withSandbox("x"))`. This avoids the classic shared-cursor bug,
  where one caller's sandbox leaks into another's request. Rejected alternative: carrying the
  sandbox in Reactor `Context`. It's implicit and easy to lose across operator boundaries.
  - **The session label is not a view.** The `withContext` view first planned here was dropped
    in step 3: TM1 keeps one `TM1-SessionContext` per session and the last request wins, so
    views sharing a session would keep relabeling it. The label is a builder setting
    (`sessionContext("my-app")`) sent at login; a different label needs another session.
- **Per-user sessions.** A single session suits service accounts. Apps acting **on behalf of
  users** need one session per identity. The identity-keyed `Tm1SessionPool` (step 4, #31;
  §16.1) provides them, with bounded lifetimes and close-on-evict. It takes the place of the
  `Tm1SessionFactory` this plan first described. A `Tm1Identity` is the service account,
  impersonating a user (`TM1-Impersonate`, sent at login only), or a user's own credentials.
  Credentials come from the `Tm1Authenticator` SPI (Basic, `CAMNamespace`, `CAMPassport`),
  asked on every login so passports can be refreshed.
- **Raw escape hatch.** `session.execute(Tm1Request)` and `session.stream(Tm1Request)` send
  any request (path, query, body), returning `JsonNode` or a chosen type. They're for anything
  not yet modeled, and for endpoints that only exist on newer servers.

---

## 6. Services and composition (R1, R4, R6)

- **One service per REST resource family**, constructed from a session and nothing else:
  `new DimensionService(session)`. Services hold no state, so constructing them is free and
  each can be a Spring bean of its own.
- **Methods map 1:1 to REST calls**, with names derived from the resource and operation.
  Examples:
  - `DimensionService.list(Query)` is `GET Dimensions`.
  - `get(name, Query)` is `GET Dimensions('n')`.
  - `create(Dimension)` is `POST Dimensions`.
  - `update(name, DimensionPatch, IfMatch)` is `PATCH`.
  - `delete(name)` is `DELETE`.
  - `saveAs(name, newName, …)` is `POST Dimensions('n')/tm1.SaveAs`.
- **Every read takes a `Query`** (`$select`/`$expand`/`$filter`/…), so callers control payload
  size, as they do with raw REST. No method silently expands.
- **Nested resources get their own services, addressed by parent keys.** For example
  `ElementService.list(dim, hier, Query)` is `Dimensions('d')/Hierarchies('h')/Elements`. This
  keeps services small and injectable. The alternative, fluent navigation
  (`tm1.dimension("d").hierarchy("h").elements().list()`), is an open question (Q3).
- **Aggregate:** `Tm1Client` holds a session and creates each service once:
  `tm1.dimensions().list(…)`, `tm1.session()`, `tm1.withSandbox("x")` (returns an aggregate over
  the derived session). It's convenient for scripts and standalone use, and it's a bean too.
- **Standalone:**

  ```java
  Tm1Client tm1 = Tm1Client.builder()
      .baseUrl("http://localhost:8010/api/v1/")
      .basicAuth("admin", "")
      .build();
  tm1.cubes().list(Query.select(Cube.NAME))
      .map(Cube::name)
      .doOnNext(System.out::println)
      .then(tm1.close())       // close after the list completes, not alongside it
      .block();                // standalone main() only; services never block
  ```

---

## 7. Reactive conventions (R5)

**Streaming is a first-class feature (decided 2026-11-21).** Assume that anything that *can*
be large *is* large: collections, cellsets and cells, axis tuples, drillthrough rowsets,
logs, and file or document content. The rules:

- Those methods return a `Flux` that decodes incrementally, and the client never buffers a whole
  response internally.
- Writes of bulk data take a `Publisher` and stream the request body.
- A consumer that wants everything in memory collects it themselves (`collectList()`, `reduce`),
  and that choice stays visible in their code.
- For cellsets, the low-level calls stay separate: create (`ExecuteMDX` returning only the
  ID), read the axes, read the cells, delete. A first-class `stream` method composes them:
  - axes first, then cells streamed in ordinal order, optionally in `$top`/`$skip` pages for
    bounded server memory per request;
  - the cellset is always deleted at the end.
  - Paging uses TM1's own mechanism (§16.3). `ExecuteMDX` takes `Cells($top=N;$select=…)`
    inline, so axes and the first page arrive in one round trip. Later pages use
    `Cellsets('id')/tm1.GetPartition(Begin=…,End=…)`, which is inclusive and must keep the same
    cell `$select`. A small, bounded number of pages are fetched in parallel, in order
    (`flatMapSequential`).

- `Mono<T>` for single results, `Mono<Void>` for no content, `Flux<T>` for collections.
  Nothing blocks, and no method returns a blocking type. Blocking callers use `.block()` themselves.
- **Collections stream.** TM1 doesn't page (§2), so `GET Hierarchies(…)/Elements` can be one
  very large body. Spring's `bodyToFlux` only streams a *top-level* JSON array. We need a
  decoder that streams the items of the `value` array of `{"@odata.context":…, "value":[…]}`
  with Jackson's non-blocking parser, and follows `@odata.nextLink` if present. This is a core
  piece of the `odata` layer, and each collection method returns a real `Flux`.
- **Resources with a lifecycle use `Flux.usingWhen`.** `ExecuteMDX` creates a server-side
  `Cellset` that must be deleted afterwards. The cellset method creates it, streams it, and
  issues `DELETE Cellsets('id')` on complete, error **or cancel**. The same applies to change
  sets (`BeginChangeSet` / `EndChangeSet` / `UndoChangeSet`) and debug contexts.
- **Cancellation.** Cancelling a subscription aborts the HTTP exchange but **doesn't stop
  TM1**: a running TI process keeps running. Long operations can opt into OData async
  (`Prefer: respond-async`, §2). The client then polls the `_async('id')` monitor, honoring
  `Retry-After`, and on cancel can `DELETE` the monitor or call `Threads('id')/tm1.CancelOperation`.
- **Backpressure.** Decode is demand-driven. HTTP concurrency is bounded by the connector's
  pool, which is configurable. We don't parallelize on the caller's behalf.
- **Delta streams.** `tail…()` methods on the change-tracked sets return a `Flux` that polls the
  delta link at an interval (an operator on top of the plain single-call method).
- **Tests use `reactor-test`'s `StepVerifier`.**

---

## 8. Model and code generation (R2)

### 8.1 Proposed: generate the model from CSDL, write the services by hand

With 67 entity types, 62 complex types and 31 enums, hand-written models would drift. We
generate them from a **checked-in metadata snapshot**, using a small generator built on our own
CSDL parser:

- **Entities and complex types become Java records.** Every property is nullable, since
  `$select` makes any property optional. Navigation properties are nullable fields, populated
  when expanded. `@odata.etag` becomes an `etag()` component.
- **Inheritance becomes a sealed interface plus records** (`View` → `NativeView` | `MDXView`),
  decoded by `@odata.type`. Java 17 allows sealed types, and #8 decided on a Java 17 baseline.
- **Enums become Java enums,** with an `UNKNOWN` fallback so a newer server's new member doesn't
  break decoding.
- **Field constants** for type-safe `$select`/`$expand`/`$filter`/`$orderby`: `Element.NAME`,
  `Element.TYPE`, and so on.
- **A `Patch` builder per entity** (§8.3).
- **Each type, property and operation carries its revision history** from `Core.Revisions`,
  e.g. `@Since("11.8.34")` and `@Deprecated(since = "11.8.3")`, and Javadoc from IBM's
  `Core.Description` text (Q2).
- **An operation catalog:** one descriptor per action and function, holding its name, binding,
  parameters and return type. Hand-written service methods refer to these descriptors, which
  feeds the coverage gate (§12.3).

**Services stay hand-written.** Their names, parameter order and grouping are API design that
a generator would do badly. Generating them is still possible later.

Generation runs at **build time** from the snapshot, so a metadata upgrade shows up as a reviewable
diff of the snapshot, not of thousands of generated lines. This is open question Q1.

### 8.2 Jackson

Jackson 3 (`tools.jackson`, already in the catalog), through Spring 7's Jackson 3 WebFlux
codecs. One shared, configured `JsonMapper`:
- ignore unknown properties;
- keep `@odata.*` in dedicated components;
- read `BigDecimal`/`long` from strings when `IEEE754Compatible`;
- never use default typing; polymorphism only through `@odata.type`.

### 8.3 Writes: absent vs. null

A record can't tell "not set" from "set to null", and PATCH depends on that difference (§3.1).
So writes use generated builders backed by an ordered map: `DimensionPatch.builder().name("x").build()`.
They serialize only the properties that were set, explicit nulls included, plus `@odata.bind`
references (`Dimensions@odata.bind: ["Dimensions('A')", …]` when creating a cube). Create
payloads use the same builders, with required properties checked.

---

## 9. Versions and capabilities (R3)

Three mechanisms, from most to least preferred:

1. **Capability from the server's own `$metadata`, the OData-intended way.** On first use, the
   session fetches and parses the connected server's `$metadata` once, and caches it per server
   version. `Tm1Capabilities` answers "does this server define action `tm1.Cube/Reserve`,
   property `Element.X`, or entity set `GitPlans`?" Before a request, a service method checks
   the capability of the operation it calls. If it's missing, the method fails fast with
   `Tm1UnsupportedOperationException` ("`Cube.Reserve` isn't defined by this server: it was added
   in schema 11.8.34, and the server at … reports schema 11.8.28"), without calling the server.
   Both versions are schema versions: one from the `Core.Revisions` annotation, the other from
   the server's own `Core.SchemaVersion`. So the message needs no mapping to server build
   numbers. This needs no version tables, and works for any TM1 we've never seen.
2. **A version range, when behavior changes but the metadata doesn't.** `Tm1Version` is shared
   with `tm1-testcontainers` (#14). It's parsed from `Server.ProductVersion` (`11.8.03500.6`).
   It deliberately isn't mapped to the schema version (`11.8.35`) or the PA release (`2.1.x`):
   one data point suggests the schema version is the build number divided by 100, but we won't
   rely on that until more versions confirm it. Code branches only
   where a known difference exists, e.g. `if (version.atLeast(V11_8_23)) … else …`. Per R3,
   **we add none of these up front**. We add them when a test on another version fails.
3. **Dialects, for structural differences.** `Tm1Dialect` covers how to reach and authenticate
   against a deployment:
   - v11 on-prem: `host:port/api/v1/`, Basic or CAM.
   - v12 / PA Engine: different root paths, bearer or API-key auth, endpoints removed.
   - PA Cloud: a tenant-scoped URL.

   The first build implements **only `V11`**. The interface exists so v12 doesn't force a
   redesign.

`Core.Revisions` supports all three. It feeds `@Since`/`@Deprecated` in the generated model, the
error messages above, and version pinning in tests. Its `Kind` values are `Added`, `Modified` and
`Deprecated`. Unknown kinds are tolerated.

**Built in step 5 (#32).** `Tm1Capabilities` (mechanism 1) answers `supports(Tm1Capability)` from
the connected server's `$metadata`. The session fetches it once per server and product version,
and shares it with every session built from the same settings (a pool fetches it once).
`Tm1Request.requires(capability)` makes the session check before sending and fail with
`Tm1UnsupportedOperationException`, whose message compares the schema version that added the
element (`Tm1Capability.since`, from `Core.Revisions`, which the generated model will fill in)
with the server's `Core.SchemaVersion` and product version. `Tm1Session.version()` reads
`Server.ProductVersion` inside the session, once per login. `Tm1Dialect.V11` (mechanism 3) maps
an address to its `/api/v1/` root. Version ranges (mechanism 2) have no branches yet, as intended.

**Deprecations.** Deprecated operations (e.g. `Process.Execute`, deprecated in 11.3.0 in favor
of `ExecuteWithReturn`; `Chore.Activate`) are still covered, because servers still expose
them. They get Java `@Deprecated` with a pointer to the replacement.

---

## 10. Errors

A sealed hierarchy under `Tm1Exception` (unchecked):

- **`Tm1ApiException`** holds the HTTP status, the OData error (`code`, `message`, `details`,
  `innererror`), the method and URL (with credentials and secrets redacted) and the TM1 session
  ID. Subtypes:
  - `Tm1NotFoundException` (404)
  - `Tm1ConflictException` (409)
  - `Tm1PreconditionFailedException` (412 and 428)
  - `Tm1AuthenticationException` (401 after re-login, or a login failure)
  - `Tm1ForbiddenException` (403)
  - `Tm1NotImplementedException` (501)
  - `Tm1ServerException` (5xx)
- **`Tm1UnsupportedOperationException`** is raised client-side by capability checks (§9).
- **`Tm1ProtocolException`** covers malformed or in-stream-failed responses (§3.1).

Exceptions are only ever raised through `Mono.error`/`Flux.error`, never thrown at assembly
time. The TM1 `code` is kept but not relied on, because it's `278` for nearly everything (§2).
TI process failures are **not** exceptions: `ExecuteWithReturn` returns a
`ProcessExecuteResult` with a status code and error log, and we map that as data.

---

## 11. Spring Boot starter (R1)

- `Tm1Properties` (`tm1.base-url`, `tm1.user`, `tm1.password`, `tm1.namespace`, `tm1.ssl.*`,
  `tm1.session-context`, timeouts).
- **`Tm1ConnectionDetails`** is the source of truth for connections, per #15, so
  `@ServiceConnection` to a `Tm1ServerContainer` works.
- **Auto-configured beans**, each `@ConditionalOnMissingBean`:
  - `Tm1Session`, with `destroyMethod` closing it;
  - **every service, individually**;
  - `Tm1Client`;
  - `Tm1SessionPool` (step 4), closed on shutdown.
- **Builds on Boot's `WebClient.Builder`,** so the app's customizers, codecs and Micrometer
  observations apply.
- **Works in servlet (MVC) apps too.** WebClient doesn't need a reactive web server, only
  `spring-webflux` plus a connector on the classpath.
- **Named multi-server support** (`tm1.servers.<name>.*`) comes later, with #16.

**Dependency change:** `tm1-client` today declares `api(libs.spring.web)`, commented for
`RestClient`. That becomes `api(spring-webflux)`, Reactor and `reactor-netty-http` (Q4).

---

## 12. Testing (R2)

### 12.1 Unit

The `odata` layer gets exhaustive tests with no server:
- literal and URL encoding, fuzzed with TM1-style names: `}Clients`, `Year 2024`, `O'Neil`,
  `a/b`, `%`, Unicode;
- query rendering;
- the streaming decoder;
- error parsing;
- the CSDL parser against the snapshot.

Services are tested against a mock HTTP server, which checks the exact method, path, headers
and body for each operation.

### 12.2 Integration, against a real TM1

- **Infrastructure:** `tm1-testcontainers` (#14) provides `Tm1ServerContainer`, `@RequiresTm1`
  (skip, don't fail, when there's no IBM install) and version pinning. **This plan depends on
  #14**, which is built in phase 0 so that end-to-end tests run on Testcontainers from the first
  service onwards (decided 2026-11-21; no interim harness).
- **Plain JUnit, no Spring**, for `tm1-client` (decided in #14). The starter has its own Spring
  tests.
- **Isolation:** each test creates objects named `it_<class>_<random>` and deletes them in
  `@AfterEach`. A sweeper deletes `it_*` leftovers at start-up. Tests never touch IBM's control
  objects except to read them.
- **Fixture model:** a small cube and dimension set built through the API by a fixture class.
  It doubles as a test of the create paths. The SData sample is optional, for read-heavy tests.
- **Some endpoints can't be tested in our container yet,** so they're tagged and skipped with a
  reason, never silently missing:
  - Git (needs a remote; a local bare repo may work);
  - encryption at rest (needs a key provider);
  - OIDC keys and CAM (need an identity provider);
  - relational drillthrough and `SQLDataSources` (need ODBC);
  - anything that shuts down or reconfigures the shared server.

### 12.3 Coverage gate, which makes "all endpoints" enforceable

A unit test loads the metadata snapshot and lists every entity set, singleton, navigation
path, bound and unbound action and function. Each must be mapped by a service method (through
the generated operation catalog or an `@Tm1Endpoint("…")` annotation) or appear in a reviewed
`coverage-exclusions` file with a reason. **The build fails on any unmapped endpoint.** A second
check, run only when a server is present, diffs the live server's `$metadata` against the
snapshot and reports drift. That's how we notice a new TM1 version's additions.


### 12.4 Testing version differences with one TM1 version

Only TM1 11.8.03500.6 (PA 2.1.19) is available for now (decided 2026-11-21: do the best we
can with it). So version handling is built in full, but proven as follows:

- **Approximate older schemas.** `Core.Revisions` records when each element was added or
  deprecated. A test fixture removes everything added after version *X* from the snapshot,
  giving an approximate `$metadata` for *X*. Capability checks (§9, mechanism 1) are tested
  against these, e.g. `Cube.Reserve` fails fast on an approximate 11.8.20 and works on 11.8.35.
  This captures added and deprecated elements, not changes in behavior.
- **Version branches** (§9, mechanism 2) take a `Tm1Version`, so they're unit-tested with any
  version and no server.
- **The quirk suite** (§16.7) pins how 11.8.03500.6 actually behaves. On another version, its
  failures are the list of differences.
- **Metadata drift report:** when TM1 tests run, the live `$metadata` is diffed against the
  snapshot and the differences are reported, so any other version shows its API changes
  automatically.
- **Version-range pinning** (`@Tm1Version(atLeast…, below…)`) comes with the first
  version-specific test, since its skip logic can be tested on one server. Running every test
  once per version (the matrix, #45) waits for a second version.
- **The README states the tested version.** Users with other versions can run the TM1 tests
  and report what fails.

---

## 13. Your question 7: what else is missing

These are concerns the requirements don't name, but that need deciding early:

1. **The OData layer as a first-class module** (§3.2): correctness in one place, not repeated in
   every service.
2. **Session hygiene** (§5): stateless Basic auth leaks TM1 sessions, and that hurts production
   servers with license-counted or capped sessions. Reuse the cookie, close sessions, and keep
   per-user sessions explicit.
3. **Absent vs. null in updates** (§8.3), and **ETag concurrency** (§3.1).
4. **Streaming large payloads** (§7): cellsets and element lists can be hundreds of MB, with no
   server paging.
5. **Server-side resource lifecycles:** cellsets, change sets, debug contexts and session
   subsets must be cleaned up even on cancel (§7).
6. **Long-running work:** TI processes and chores can run for hours. This needs per-request
   timeouts, OData async, and real cancellation (§7).
7. **Authentication modes:** Basic (security modes 1 and 2) first. The `Tm1Authenticator` SPI
   must also fit CAM (`Authorization: CAMNamespace <base64 user:pass:ns>`), CAM passports and
   SSO, and v12/Cloud bearer or API keys.
8. **TLS:** TM1 ships with self-signed certificates. Offer explicit trust-store configuration
   and an opt-in "trust all" for dev only, never a default.
9. **Observability:**
   - Micrometer observations, through WebClient;
   - the `TM1-SessionContext` label;
   - structured debug logging of requests with `Authorization`, cookies, CAM passports and
     pre-signed URLs redacted;
   - TM1 session and thread IDs in exceptions.
10. **Sandboxes** as request context, not global state (§5).
11. **Batch (public API, Q8):**
    - **What TM1 supports:** the JSON format only; multipart is rejected. `BatchSupport`
      advertises no cross-request or ETag references, no `dependsOn` conditions and no
      `continue-on-error`. So a batch is a list of independent requests.
    - **Results per item:** the outer response is 200 even when every inner request failed
      (§16.3). Each item gets its own result or error, never one exception for the batch.
    - **Shape:** every service gets a `request()` view that builds the same `Tm1Request<T>` as
      its direct method, without sending it. Direct methods are those requests run on their
      own, so batching duplicates nothing and the coverage gate covers both.
      `tm1.batch().add(request)` returns a `Mono<T>` that completes with that item's result when
      the batch runs. `executeBatched(Flux<Tm1Request<T>>)` chunks a stream of requests into
      several batches and streams `BatchResult<T>` outcomes in order.
    - **To verify against TM1 first:** whether it stops at the first failure or carries on (the
      spec and the production evidence point different ways), whether `atomicityGroup` works,
      and the largest batch it accepts.
    - **Not the same as TM1's change sets** (`BeginChangeSet`/`EndChangeSet`/`UndoChangeSet`),
      which group cell changes for undo. Those are separate operations with a `usingWhen`
      lifecycle (§7).
12. **Thread-safety and concurrency limits on one TM1 session**, which still need testing (§2).
13. **Nullability annotations:** JSpecify, which Spring 7 uses, so Kotlin and IDE users get null
    safety.
14. **Public API stability:** this library will be on Maven Central. Mark `odata` internals and
    generated code `@Internal`/`@Experimental` where appropriate, and decide what 0.x promises.
    Javadoc is part of the deliverable.
15. **Licensing of IBM metadata text** (Q2).

---

## 14. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Generate the model from CSDL, or write it by hand? | **Agreed 2026-11-21.** Generate records, enums, patch builders, field constants and the operation catalog. Write services by hand (§8.1). |
| Q2 | Can we commit IBM's `$metadata` to the repo? Its `Core.Description` text is IBM-authored. | **Decided 2026-11-21 (revised): commit it as served, IBM's text included.** First agreed as a stripped snapshot; the user then decided keeping IBM's text is fine. Keeping the exact response also makes the drift check a byte-for-byte comparison, and the descriptions can become generated Javadoc. |
| Q3 | Flat services with parent-key parameters, or fluent navigation (`tm1.dimension("d").hierarchy("h").elements()`)? | **Decided 2026-11-21: both.** Flat 1:1 services are the core: injectable, and what the coverage gate checks. Fluent handles holding context and defaults come later as a thin layer over them (with the Q10 helpers). Each fluent call runs the flat call it stands for, so the flat layer is always the escape hatch. |
| Q4 | Default HTTP connector: Reactor Netty, or the JDK `HttpClient` connector? | **Decided 2026-11-21: Reactor Netty is the default and a required dependency.** Any other `ClientHttpConnector` is accepted. Why Netty: pool control (separate named pools per workload, bounded waits for a connection, max idle and max life), which the connection-pinning and 100 s+ pause lessons in §16.2 depend on; per-request response timeouts; gzip; Spring Boot's own default and metrics. The JDK client lacks pool control, doesn't decompress gzip, and defaults to HTTP/2 (TM1 speaks 1.1). Session cookies are always managed by our session code, never a connector's cookie support. |
| Q5 | Build per-user sessions in phase 0, or design only? | **Superseded by Q11:** the identity-keyed `Tm1SessionPool` is built in phase 0 (step 4, #31), replacing the `Tm1SessionFactory` first described. |
| Q6 | Interim integration-test harness, or build #14 first? | **Decided 2026-11-21: Testcontainers works from the start.** #14 is part of phase 0, and there's no interim harness. |
| Q7 | When does v12 / PA Cloud become in scope? | **Decided 2026-11-21: later, not scheduled.** Only `Tm1Dialect` and `Tm1Authenticator` are shaped so it fits without a redesign. |
| Q8 | Is JSON `$batch` public API? | **Decided 2026-11-21: public.** See §13 item 11 for the shape. Batched cellset deletes (§16.3) use the same API. |
| Q9 | Ship a blocking facade? Tooling built on a reactive TM1 client ends up full of `.block()` calls (§16.8). | **Decided 2026-11-21: no.** The API is reactive only, and there is no blocking facade. |
| Q10 | Where do the data-access helpers in §16.8 live (MDX builder, keyed point reads, cellset-to-rows, attribute batch reader)? | **Agreed 2026-11-21.** In a **second layer** over the 1:1 services, in the fluent handles, after the endpoint surface is complete. The MDX member quoting helper comes earlier, because handles need it. |
| Q11 | Session pool keyed by identity: phase 0 or later? | **Agreed 2026-11-21: good session handling is a priority.** Phase 0 for the design and the SPI. Impersonation is common enough in real use that the session model must not assume one identity per client (§16.1). |

---

## 15. Phases (each becomes one or more issues)

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Foundation** (#27; steps #28–#32 and #15, as stacked PRs) | `tm1-testcontainers` (#14), before anything else. Metadata snapshot, CSDL parser and coverage gate. The `odata` layer: paths, literals, query and filter builder, streaming collection decoder, errors, `Tm1Request`. `Tm1Session`: Basic auth, cookie, single-flight re-login, close, request context, raw `odata()` access. The identity-keyed `Tm1SessionPool` and `Tm1Authenticator` SPI (Q11). `Tm1Capabilities`, `Tm1Version`, the `V11` dialect. Starter auto-configuration with `Tm1ConnectionDetails` and `@ServiceConnection` (#15). | On a Testcontainers TM1: connect, read `Server`, close, with no session left behind. Coverage report generated, all endpoints pending. |
| **1. Codegen** | Generator: records, sealed hierarchies, enums, patch builders, field constants, `@Since`/`@Deprecated`, operation catalog. | Generated model compiles; round-trip tests on captured payloads. |
| **2. Dimensions** | Dimensions, Hierarchies, Elements, Edges, Levels, Members, Subsets (public, private, session), element/hierarchy/subset attributes, locks and reservations | Integration tests green |
| **3. Cubes and views** | Cubes, Native and MDX views (public, private), rules, CheckRules/CheckFeeders/Trace\*, Load/Unload, ReorderDimensions, locks and reservations, cube attributes | Integration tests green |
| **4. Data** | ExecuteMDX\*, Cellsets (streamed, auto-deleted), Update/UpdateCells, Sandboxes, change sets, drillthrough, ModelCubes/ControlCubes | Integration tests green |
| **5. Processes and chores** | Processes (CRUD, Compile, ExecuteWithReturn with async, deprecated Execute), error logs, debugging (contexts, breakpoints, stepping), Chores and tasks | Integration tests green |
| **6. Security and runtime** | Users, Groups, ActiveUser, Sessions, ActiveSession, Threads (CancelOperation), Disconnect | Integration tests green |
| **7. Server, configuration and logs** | Server, Static/ActiveConfiguration, deprecated Configuration, Loggers, Message/Transaction/Audit logs with delta `tail` Flux, ErrorLogFiles | Integration tests green |
| **8. Contents and annotations** | Contents/Folders/Documents/Links/References (Copy/Move), Annotations, ApplicationContextFacets, DeleteAnnotationArtifacts | Integration tests green |
| **9. Infrastructure endpoints** | Git (Init/Status/Pull/Push/Deploy/Plans), encryption at rest, OIDC keys, SQLDataSources | Implemented, with tagged and skipped tests where the container can't host them |
| **10. Protocol extras** | Public async API. JSON `$batch` (public, Q8), whose `Tm1Request` / `request()` shape is set in phase 0. | Coverage gate shows zero pending endpoints |

Phases 2 to 9 can proceed in parallel once phases 0 and 1 land.

---

## 16. Lessons from an existing production TM1 client

We reviewed a large, private Spring application that has used TM1 11.8 heavily through its own
reactive REST client, then distilled the general lessons: TM1 behavior, and the failure modes
the authors hit in production. None of its code, names or architecture comes into this
project. Where a lesson changes the plan, the section it affects is noted. TM1 behavior noted
here was observed on 11.8. Our integration tests must re-confirm each item (§16.7), and none
of it is assumed for other versions.

### 16.1 Sessions and identity (§5)

- **Identity is the session.** Impersonation (the `TM1-Impersonate` header, typically
  `CAMID("…")`) pins a session to one identity. Changing the header on a live session made
  requests silently run as the wrong user. So identity belongs to the session, and a derived
  session with a different identity gets its **own cookie**.
- **The number of identities equals the number of sessions.** A production incident came from
  hundreds of held sessions, created by single-use identities. So we need a
  `Tm1SessionPool` keyed by (server, identity):
  - idle TTL, maximum age and maximum size;
  - close (`tm1.Close`) on eviction, after a **grace period**, so in-flight requests don't 401;
  - metrics for sessions held, evicted and closed.
- **Re-authentication must be single-flight and must not recurse.** One client looped into
  stack overflow when the reconnect itself got a 401. Another started several logins at once
  during a burst of 401s.
- **"Credential rejected" is not "session expired".** CAM passports expire on the Cognos side.
  After that every reconnect fails, so the client must raise a distinct
  `Tm1CredentialRejectedException` and must not keep logging in.
- **Impersonation failures are typed errors:** "insufficient rights to impersonate" (403) and
  "user does not exist" (400), recognized by message, since TM1 offers nothing better. The
  client **never falls back to another identity by itself**. Falling back to an admin identity
  is a privilege escalation, and that policy belongs to the caller.
- **What 11.8.03500.6 does, probed in step 4 (#31)** and pinned by `Tm1ImpersonationBehaviorTest`:
  - Impersonation is decided at login. The header on later requests is ignored, so on this
    version a live session can't switch users; one identity is one session either way.
  - Nobody can impersonate an administrator, not even another administrator (403, "insufficient
    rights"). Only administrators and data administrators can impersonate (403 otherwise).
  - An unknown user is a 400. A **disabled** user is a 401, the same as wrong credentials.
  - An **empty** header means no impersonation: the session runs as the logged-in admin. The
    client refuses blank names, and names that aren't printable ASCII, which TM1 can't match
    through the header.
  - The errors are plain text, not OData JSON. Every refused login leaves a session with no user
    behind, so the client remembers refusals (`Tm1Timeouts.refusedLogin`, 30 s) rather than
    retrying at once.
  - User names match ignoring case and spaces, so `Tm1Identity` compares them that way.
- **Never open a session just to close it.** If no login happened, `close()` does nothing.
- **Find the session cookie by name** (`TM1SessionId`) in the full `Set-Cookie` list, not by
  taking the first one. On a retry, **replace** the cookie header, never append to it.
- **Auth schemes** for the `Tm1Authenticator` SPI: `Basic`, `CAMNamespace base64(user:pass:ns)`,
  `CAMPassport <passport>`, and a passport obtained by exchanging an OIDC token with Cognos.
- **Login must be non-blocking.** Blocking logins on event loops, and identity held in
  thread-locals, caused deadlocks and wrong-user requests. Identity is an explicit value, never
  ambient state.
- TM1 tolerates parallel requests on one session (confirmed in production use, and in step 3:
  they run in parallel, not one at a time).

### 16.2 HTTP behavior (§7, §11)

- **Never retry or hedge non-idempotent calls at transport level.** A transport retry of
  `POST tm1.ExecuteWithReturn` or `UpdateCells` can run a process or a write twice. Retries are
  limited to idempotent methods and to failures *before* the request was sent.
- **Timeouts:** separate connect-phase failures ("nothing applied") from response-phase failures
  (**"may have applied"**), and carry that on the exception.
- **Timeouts per operation class:** short for login (a TM1 still loading accepts connections and
  never answers), long or unlimited for MDX and processes. TM1 only answers a long call when the
  work ends, and pauses of 100+ seconds have been observed. Cleanup calls get their own short
  timeout.
- **Long synchronous calls pin pooled connections,** so interactive requests queue behind batch
  work. Raising the pool size didn't fix it. The real fixes:
  - OData async for processes (implemented, not rejected; 202 is a feature);
  - optional separate pools or bulkheads per workload;
  - a **per-server concurrency limiter**, since TM1 serializes on locks anyway.
- **Headers:** `Accept-Encoding: gzip`; `Accept` that includes `text/plain` for `$value`;
  `odata.metadata=none` or `minimal` to cut payload.
- **Build the `URI` ourselves, already encoded.** Passing an encoded string through Spring's
  `uri(String)` treats `{…}` in TM1 names (common in control objects) as template variables.
  The `odata` path builder outputs a `URI`.
- **Stream properties** (document content, error log content) are read as a `Flux<DataBuffer>`.
  A "decode and take first" approach returned only the first chunk.

### 16.3 Cellsets and data (§7)

- **Paging (§7)** uses inline `Cells($top=N)` on `ExecuteMDX`, then `tm1.GetPartition`. Pages
  are whole rows (a multiple of the column count), with bounded parallelism (about 3). Row and column come
  from `Ordinal / cols` and `Ordinal % cols`. TM1 refuses a partition that starts past the end,
  so offsets are clamped.
- **Always delete cellsets.** They hold server memory for the life of the session.
  - There's no collection delete, so many deletes go in one **JSON `$batch`**. The outer
    response is 200 even when inner requests fail, so check each `responses[].status`, and treat
    404 as already deleted.
  - A sandboxed cellset must be deleted with `!sandbox=`.
  - **Don't requeue failed deletes:** requeueing turned a brief outage into a retry storm.
  - Cleanup must survive cancellation, and **one subscriber cancelling a shared fetch must not
    cancel it for the others.**
- **`Cell.Value` is a union** of number, string and null. Typing it as a string caused phantom
  "changes" on numeric cells. We model it as a sealed `CellValue`, plus `FormattedValue`.
  `Updateable` is a bitmask.
- **Sandboxes:**
  - `!sandbox=` must be on **every** request of an operation, including each `GetPartition`
    and the delete.
  - TM1 evaluates `NON EMPTY` and value filters in the *request's* sandbox. A `[Sandboxes]`
    member in the MDX changes values only, so rows that exist only in the sandbox vanish
    without the parameter.
  - `Cube.Dimensions` lists the virtual `Sandboxes` dimension first, and cellsets get a
    `Sandboxes` slicer axis. Handles exclude it by default, and it must not be sent back on create.
  - Model a `Sandbox` value type, with `Sandbox.BASE` accepting `null` or "Base".
  - Passing a sandbox to a process run does nothing unless the TI code opts in.
- **Point reads** use one MDX per cube shape with `TM1IGNORE_BADTUPLES`, which silently drops
  bad tuples. So answers must be **matched on the returned members, not on position.**
- **Write endpoints:**
  - `Cubes('c')/tm1.Update` and `tm1.UpdateCells` take `Tuple@odata.bind`. Default the hierarchy
    to the dimension, but allow alternate hierarchies.
  - `Cellsets('id')/tm1.Update` and `UpdateCells` take ordinals.
  - One refused cell fails the whole request, so streaming writes are **atomic per chunk** and
    report the failing chunk.
  - Bulk loads in practice often go through TI rather than REST, because REST writes stay
    modest.
  - Whether a read immediately after a write sees it needs an integration test. A production
    caller sleeps 50 ms after writing, for reasons nobody recorded.

### 16.4 Encoding (§3.1)

- **Keys:** double `'`, then percent-encode the UTF-8 bytes, keeping only unreserved characters
  and `'`. Names really contain `'#?/%{}&=+`. Every hand-rolled partial encoder in the reviewed
  code was wrong somewhere, including sandbox names sent unencoded. **One encoder, used for
  everything:** keys, `@odata.bind`, query values and custom options like `!sandbox`.
- **`=` and `&` inside nested query options** (`$expand=X($select=…)`) are encoded as `%3D`, and
  TM1 accepts it.
- **MDX:** double `]` inside `[…]`. `'` is literal inside brackets but doubled in string literals.
- **`UniqueName`:** parsing must handle `]]`, embedded newlines and `^`-joined ancestry paths
  (`[d].[h].[Total^2026^Q2^May]`). Parsing it was once about 28% of CPU on a large view.
  **Members come back already parsed** (dimension, hierarchy, element, leaf name), using a
  hand-written scanner rather than a regex.
- **Attribute names in `$expand`** reject spaces (error 278), although TM1 matches attribute
  names ignoring case and spaces. Strip spaces in the request and map the response back.
- **TM1 object names are case- and space-insensitive** for keyed access, but `$filter Name eq`
  is case-sensitive. So look up by key, never by filtering on name. A `resolveName` helper (keyed
  GET, with "did you mean" suggestions) belongs in the second layer.

### 16.5 TM1 departures from its own metadata (§8.3, §9)

The generated model and the services must encode these: strip or reject fields TM1 ignores,
and document the replace-vs-append semantics.

| Behavior (11.8) | Handling |
|---|---|
| Element PATCH silently ignores `Name` (no rename). Hierarchy PATCH ignores `Visible`. | Patch builders omit them. Javadoc. |
| Element and Process PATCH **reject** `Attributes` | Element attributes are written as cells of `}ElementAttributes_<dim>` |
| Hierarchy PATCH with `Elements`/`Edges` **replaces** the collection (deletes elements not listed). Subset and Chore PATCH **append**. | Separate, explicitly named methods: `replace…` and `add…` |
| `SetElement`/`SetComponent` with null `Before` put the element **last**; `$metadata` says first | Documented, and pinned by a test |
| `CreateSessionSubset` ignores the given name | Return the server's name |
| A Process GET with `$select=*` omits most `DataSource/*` properties, `UIData` and `VariablesUIData` | The default process projection lists them explicitly |
| Chore task `Step` is server-assigned. `Active` changes only through `Activate`/`Deactivate`. A chore task must list every process parameter. | Builders enforce this |
| Cube `Rules` and process code are saved even when they don't compile | `CheckRules` / `Compile` helpers, and an optional check before save |
| An annotation over about 48 KB returns 201 and stores nothing. Missing or sandbox-only annotations return **400**, not 404. | Typed not-found. Optional read-back verification. |
| The `tm1.NativeView` type-cast expand fails on MDX views | Read the base type, then re-read native views |
| Application entries are keyed by name plus a suffix (`.blob`, `.extr`, `.process`) and don't support PATCH (use `tm1.Move`) | Modeled in `Contents` keys |
| Read models carry server-computed fields | Never send a read model back as a write; that's why separate patch and create builders exist |
| A new cube grant isn't seen by a held session until it recycles (code 65) | Documented. A session-recycle hook is available. |
| `Thread.ElapsedTime` is ISO-8601 *or* a bare number. `ErrorLogs` are keyed by a quoted timestamp string. | Lenient decoders |

### 16.6 Errors (§10)

- **Message matching can't be fully avoided.** One central `Tm1ErrorClassifier` table covers
  error codes where they mean something (248 invalid unique name; 65 no read rights;
  `DataSpreadFailed` for text into a numeric cell, consolidated or rule cells, or missing
  rights) and message patterns where they don't. Its subtypes:
  - NotFound, AlreadyExists, Locked, InUse, Forbidden
  - MdxSyntax, RuleSyntax, TiCompile, DataSpreadFailed
  - Cancelled (`tm1userexception: cancel`)
  - ImpersonationRefused, UnknownUser, CredentialRejected

  The table is data, tested with captured responses, and can be extended by callers.
- **One unchecked exception root,** including transport and timeout errors, with `isRetryable()`
  and `mayHaveApplied()`. Checked exceptions and two parallel hierarchies forced unwrap loops at
  every call site.
- **Silent failures are worth helpers:** a stale member name gives zero columns and no error,
  `DESCENDANTS(x, 999, …)` gives an empty set, and empty sets render as invalid MDX. So
  builders refuse empty names and empty sets, and queries can opt in to asserting a non-empty
  axis.

### 16.7 Testing (§12)

- **Validated:** the reviewed project pins `$metadata` plus a coverage matrix with an
  exclusion reason for each endpoint, enforced by a test. That's our §12.3 gate, so the
  approach is proven.
- **Pain points we avoid:**
  - no CI against a real TM1 (we have Testcontainers, #14);
  - mocks coupled to public mutable fields (our services are interfaces or final classes with
    constructor injection);
  - only a few recorded payloads.
- **Add a quirk suite:** one integration test per §16.5 behavior, asserting TM1's *actual*
  behavior. When a new TM1 version fixes a quirk, the test fails, and that's exactly the signal
  R3 needs to add a version branch.
- **Add a recorded-exchange fake** (request, then canned response) for fast service tests.
- **Scratch-object convention:** a per-run prefix, reverse-order cleanup, never touch objects the
  test didn't create (as in §12.2).

### 16.8 What real use looks like (feeds Q3, Q9, Q10)

In a large application, TM1 traffic is dominated by these, in roughly this order:
1. attribute lookups (MDX over `}ElementAttributes_*`);
2. batched point reads of cells;
3. MDX set evaluation;
4. TI process runs;
5. cell writes;
6. "MDX to rows" in a long or pivoted shape.

Object CRUD is mostly developer tooling. Patterns callers kept re-implementing:
- **An MDX builder.** Hand-built MDX with unescaped `]` was the norm. Cover member, tuple and
  set builders plus `TM1SUBSETALL`, `TM1FILTERBYLEVEL`, `TM1DRILLDOWNMEMBER`, `TM1SUBSETTOSET`
  and `TM1IGNORE_BADTUPLES`.
- **Cube dimension order without `Sandboxes`,** and tuples built from a `Map<dimension, element>`.
- **Point reads keyed by coordinate,** with per-cell not-found.
- **Cellset-to-rows adapters,** streaming.
- **Micro-batched attribute and cell readers** (buffer by size and time, one MDX per group) and
  request coalescing with failure caching.
- **Process runs** with parameters typed from the process's declared parameters (numeric
  parameters must be numbers), fetching the error log when `ExecuteWithReturn` doesn't attach it.
- **Opt-in metadata caching** with TTL, negative caching (one caller's failure cache was added
  after a brief outage became about 750 failing requests a second) and explicit invalidation.
  Never cache cell data by default.
- **Fluent context** of server, identity and sandbox: `tm1.server("x").as(user).sandbox("y")`.
  That's how callers actually group work, and it supports flat services plus fluent handles (Q3).
- **Multi-server registries:** a templated base URL plus per-server overrides, and optional
  Admin Server discovery (`/api/v1/Servers`) (#16).

### 16.9 For future agent tooling (separate issue)

An MCP server over TM1 needs:
- risk tiers (read < run < write < admin), enforced by the tool, not by TM1 rights;
- one-shot confirmation tokens bound to the exact arguments;
- sandbox-first writes, and typed write tools (no raw TM1 JSON);
- local MDX, rule and TI validation before any network call;
- result paging and byte budgets, and async jobs for long calls;
- a compact cellset renderer that hides `Sandboxes`.

None of this belongs in `tm1-client`, but the client must make it easy.

---

## Appendix A: endpoint inventory (TM1 11.8.03500 / schema 11.8.35)

Generated from the live server's `$metadata` on 2026-11-21. `+x.y` means added in that schema
version, per `Core.Revisions`. The coverage gate (§12.3) makes this list executable.

### Entity sets and singletons

| Name | Kind | Type |
|---|---|---|
| `ActiveConfiguration` | Singleton | `ServerSettings` |
| `ActiveSession` | Singleton | `Session` |
| `ActiveUser` | Singleton | `User` |
| `Annotations` | EntitySet | `Annotation` |
| `ApplicationContextFacets` | EntitySet | `ApplicationContextFacet` |
| `AuditLogEntries` | EntitySet | `AuditLogEntry` |
| `Cellsets` | EntitySet | `Cellset` |
| `ChoreAttributes` | EntitySet | `AttributeDefinition` |
| `Chores` | EntitySet | `Chore` |
| `Configuration` | Singleton | `Configuration` |
| `Contents` | EntitySet | `Folder` |
| `CubeAttributes` | EntitySet | `AttributeDefinition` |
| `Cubes` | EntitySet | `Cube` |
| `DimensionAttributes` | EntitySet | `AttributeDefinition` |
| `Dimensions` | EntitySet | `Dimension` |
| `ErrorLogFiles` | EntitySet | `ErrorLogFile` |
| `GitPlans` | EntitySet | `GitPlan` |
| `Groups` | EntitySet | `Group` |
| `Loggers` | EntitySet | `Logger` |
| `MemberSets` | EntitySet | `CellsetAxis` |
| `MessageLogEntries` | EntitySet | `MessageLogEntry` |
| `ProcessAttributes` | EntitySet | `AttributeDefinition` |
| `ProcessDebugContexts` | EntitySet | `ProcessDebugContext` |
| `Processes` | EntitySet | `Process` |
| `Rowsets` | EntitySet | `Rowset` |
| `SQLDataSources` | EntitySet | `SQLDataSource` |
| `Sandboxes` | EntitySet | `Sandbox` |
| `Server` | Singleton | `Server` |
| `Sessions` | EntitySet | `Session` |
| `StaticConfiguration` | Singleton | `ServerSettings` |
| `Threads` | EntitySet | `Thread` |
| `TransactionLogEntries` | EntitySet | `TransactionLogEntry` |
| `Users` | EntitySet | `User` |

### Entity types: navigation properties and bound operations

| Entity type | Navigation properties | Bound actions/functions |
|---|---|---|
| `Annotation` | ApplicationContext[], DimensionalContext[], Attachments[] | – |
| `ApplicationContextFacet` | Values[] | – |
| `ApplicationContextFacetValue` | Facet | – |
| `Attachment` | – | – |
| `AttributeDefinition` | – | – |
| `AuditDetail` | – | – |
| `AuditLogEntry` | AuditDetails[] | – |
| `Cellset` | Cube, Axes[], Cells[] | fn GetPartition [+10.2.2.3]<br>SaveViewAs [+11.0.0]<br>Update<br>UpdateCells [+11.0.0] |
| `CellsetAxis` | Hierarchies[], Tuples[] | – |
| `CellsetAxisTuple` | Members[] | – |
| `CellsetCell` | DrillthroughScripts[], Members[], Annotations[] | – |
| `Chore` | LocalizedAttributes[], Tasks[] | Activate [+10.2.2.1, deprecated 11.8.3]<br>Deactivate [+10.2.2.1, deprecated 11.8.3]<br>Execute [+10.2.2.2]<br>SetServerLocalStartTime [+10.2.2.1, deprecated 11.8.3] |
| `ChoreReference` (: Entry) | Chore | – |
| `ChoreTask` | Process, Chore | – |
| `Configuration` | – | – |
| `Cube` | Dimensions[], Views[], ViewAttributes[], PrivateViews[], Annotations[], LocalizedAttributes[] | CheckFeeders [+11.1.0]<br>CheckRules [+11.1.0]<br>fn DimensionsStorageOrder [+11.4.0]<br>Load [+11.6.0]<br>Lock<br>Release [+11.8.34]<br>ReorderDimensions [+11.4.0]<br>Reserve [+11.8.34]<br>TraceCellCalculation [+11.1.0]<br>TraceFeeders [+11.1.0]<br>Unload [+11.6.0]<br>Unlock<br>Update [+10.2.2.1]<br>UpdateCells [+11.0.0] |
| `CubeDrillthrough` (: Drillthrough) | – | Execute [+10.2.2.6] |
| `CubeReference` (: Entry) | Cube | – |
| `Dimension` | Hierarchies[], DefaultHierarchy, HierarchyAttributes[], LocalizedAttributes[] | AddAllLeavesHierarchy [+11.0.0]<br>Lock<br>Release [+11.8.34]<br>Reserve [+11.8.34]<br>SaveAs [+11.0.0]<br>Unlock |
| `DimensionReference` (: Entry) | Dimension | – |
| `Document` (: Entry) | – | – |
| `DocumentReference` (: Entry) | Document | – |
| `Drillthrough` | – | – |
| `Edge` | Hierarchy, Parent, Component | – |
| `Element` | Hierarchy, Parents[], Components[], Edges[], LocalizedAttributes[] | Lock [+11.8.23]<br>Release [+11.8.34]<br>Reserve [+11.8.34]<br>SetComponent<br>Unlock [+11.8.23] |
| `Entry` | ContainedBy, LocalizedAttributes[] | Copy [+11.2.0]<br>Move [+11.2.0] |
| `ErrorLogFile` | – | – |
| `Folder` (: Entry) | Contents[], PrivateContents[] | – |
| `GitPlan` | – | – |
| `GitPullPlan` (: GitPlan) | – | Execute [+11.5.0] |
| `GitPushPlan` (: GitPlan) | – | Execute [+11.5.0] |
| `Group` | Users[] | – |
| `Hierarchy` | Dimension, Elements[], Edges[], Subsets[], PrivateSubsets[], SessionSubsets[], Members[], AllMember, DefaultMember, Levels[], LocalizedAttributes[], ElementAttributes[], SubsetAttributes[] | CreateSessionSubset<br>fn RootMembers [+11.4.7]<br>SaveAs [+11.0.0]<br>SetElement |
| `Level` | Hierarchy, Members[] | – |
| `Link` (: Entry) | – | – |
| `LocalizedAttributes` | – | – |
| `Logger` | – | – |
| `MDXView` (: View) | – | Execute |
| `Member` | Hierarchy, Level, Element, Parent, Children[], LocalizedAttributes[] | – |
| `MessageLogEntry` | – | – |
| `NativeView` (: View) | – | Execute |
| `Process` | LocalizedAttributes[], ErrorLogs[] | Compile [+11.0.0]<br>Debug<br>Execute [deprecated 11.3.0]<br>ExecuteWithReturn [+11.3.0] |
| `ProcessDebugContext` | CurrentBreakpoint, Process, CallStack[], Breakpoints[], Thread | Continue<br>StepIn<br>StepOut<br>StepOver |
| `ProcessDebugContextBreakpoint` | – | – |
| `ProcessDebugContextDataBreakpoint` (: ProcessDebugContextBreakpoint) | – | – |
| `ProcessDebugContextLineBreakpoint` (: ProcessDebugContextBreakpoint) | – | – |
| `ProcessDebugContextLockBreakpoint` (: ProcessDebugContextBreakpoint) | – | – |
| `ProcessDebugContextStackFrame` | Process, Variables[] | – |
| `ProcessDebugContextVariable` | – | – |
| `ProcessErrorLog` | – | – |
| `ProcessReference` (: Entry) | Process | – |
| `RelationalDrillthrough` (: Drillthrough) | – | Execute [+10.2.2.6]<br>ExecuteWithRowset [+11.6.0] |
| `Rowset` | Rows[] | – |
| `RowsetRow` | – | – |
| `SQLDataSource` | – | – |
| `Sandbox` | – | DiscardChanges<br>Load<br>Merge<br>Publish<br>Unload |
| `Server` | – | – |
| `ServerSettings` | – | – |
| `Session` | User, Threads[] | Close [+10.2.2.5] |
| `Subset` | Hierarchy, Elements[], LocalizedAttributes[] | SaveAs [+11.0.0]<br>SetElement [+10.2.2.6] |
| `SubsetReference` (: Entry) | Subset | – |
| `Thread` | Session | CancelOperation [+10.2.2.1] |
| `TransactionLogEntry` | – | – |
| `TupleMember` (: Member) | – | – |
| `User` | Groups[], Sessions[] | Disconnect [+10.2.2.6] |
| `View` | Cube, LocalizedAttributes[] | – |
| `ViewReference` (: Entry) | View | – |

### Unbound operations (container imports)

- `BeginChangeSet` (action)
- `CompileProcess` (action)
- `ConfigureEncryptionAtRest` (action) +11.8.35
- `ControlCubes` (function)
- `ControlDimensions` (function)
- `DecryptDataFile` (action) +11.3.0
- `DecryptDataModel` (action) +11.3.0, deprecated 11.8.35
- `DeleteAnnotationArtifacts` (action)
- `DisableEncryptionAtRest` (action) +11.8.35
- `EncryptDataFile` (action) +11.3.0
- `EncryptDataModel` (action) +11.3.0, deprecated 11.8.35
- `EndChangeSet` (action)
- `ExecuteChore` (action)
- `ExecuteCubeDrillthrough` (action)
- `ExecuteMDX` (action)
- `ExecuteMDXSetExpression` (action)
- `ExecuteMDXStatement` (action)
- `ExecuteProcess` (action)
- `ExecuteProcessWithReturn` (action) +11.3.0
- `ExecuteRelationalDrillthrough` (action)
- `ExecuteRelationalDrillthroughWithRowset` (action)
- `GetOIDCKeys` (function) +11.3.0
- `GitDeploy` (action) +11.5.0
- `GitInit` (action) +11.5.0
- `GitPull` (action) +11.5.0
- `GitPush` (action) +11.5.0
- `GitStatus` (action) +11.5.0
- `GitUninit` (action) +11.5.0
- `MessageLog` (function)
- `ModelCubes` (function)
- `ModelDimensions` (function)
- `RemoveOIDCKeyFromCache` (action) +11.3.0
- `RotateDataEncryptionKey` (action) +11.8.35
- `RotateDataModelKey` (action) +11.3.0, deprecated 11.8.35
- `TailAuditLog` (function) +11.6.0
- `TailMessageLog` (function) +11.3.0
- `TailTransactionLog` (function) +11.3.0
- `TransactionLog` (function)
- `UndoChangeSet` (action)
- `Update` (action)
- `ValidateEncryptionAtRestSettings` (function) +11.8.35

### Deprecated

- Action `Activate`: +10.2.2.1, deprecated 11.8.3
- NavigationProperty `Chore`: deprecated 11.4.0
- EntityType `Configuration`: deprecated 11.0.0
- Action `Deactivate`: +10.2.2.1, deprecated 11.8.3
- Action `DecryptDataModel`: +11.3.0, deprecated 11.8.35
- Action `EncryptDataModel`: +11.3.0, deprecated 11.8.35
- Property `ID`: deprecated 11.5.0
- Action `Execute`: deprecated 11.3.0
- Action `ExecuteProcess`: +11.0.0, deprecated 11.3.0
- Property `OracleErrorForceRowStatus`: deprecated 11.8.20
- Property `MTFeeders`: deprecated 11.8.2
- Property `MTFeedersAtStartup`: deprecated 11.8.2
- EnumType `OracleErrorForceRowStatus`: +11.0.0, deprecated 11.8.20
- EntityType `ProcessErrorLog`: +10.2.2.2, deprecated 11.3.0
- Action `SetServerLocalStartTime`: +10.2.2.1, deprecated 11.8.3
- Property `SkipLoadingAliases`: deprecated 11.8.10
- Singleton `Configuration`: deprecated 11.0.0
- ActionImport `DecryptDataModel`: +11.3.0, deprecated 11.8.35
- ActionImport `EncryptDataModel`: +11.3.0, deprecated 11.8.35
- ActionImport `RotateDataModelKey`: +11.3.0, deprecated 11.8.35

## Appendix B: references

- OData 4.01 Part 1, Protocol: <https://docs.oasis-open.org/odata/odata/v4.01/odata-v4.01-part1-protocol.html>
- OData 4.01 Part 2, URL Conventions: <https://docs.oasis-open.org/odata/odata/v4.01/odata-v4.01-part2-url-conventions.html>
- OData JSON Format 4.01: <https://docs.oasis-open.org/odata/odata-json-format/v4.01/odata-json-format-v4.01.html>
- OData CSDL XML 4.01: <https://docs.oasis-open.org/odata/odata-csdl-xml/v4.01/odata-csdl-xml-v4.01.html>
- OData Core vocabulary (`Core.Revisions`, `Core.SchemaVersion`): <https://github.com/oasis-tcs/odata-vocabularies/blob/main/vocabularies/Org.OData.Core.V1.md>
- OData Capabilities vocabulary: <https://github.com/oasis-tcs/odata-vocabularies/blob/main/vocabularies/Org.OData.Capabilities.V1.md> (not yet read in full; read before implementing §9 beyond presence checks)
- Related issues: #8 (Java 17 baseline), #14 (tm1-testcontainers), #15 (Spring service connections), #16 (more runtimes)

**Spec reading gaps** (read only in summary or not at all; read before implementing the matching code):
URL Conventions §5.1.1.17 operator precedence and the full ABNF (the query builder should
parenthesize everything until then), the `$expand` options grammar, JSON Format §8.6–21
details, CSDL §14.3+ (annotation expressions).
