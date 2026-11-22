# Handoff: finish the end-to-end tests against a real TM1

Paste everything below the line into a Claude Code session on your own computer, in your clone of `ajmyers/tm1-pa-developer-tools`.

---

You're picking up work from a cloud session, on branch `claude/sweet-meitner-cmh3ze` of this repository. Don't merge it and don't open a PR; keep committing and pushing to that branch. The goal of this session is to run every implementation's end-to-end (live) tests against a real TM1 and get them green. The Skipcheck rename (`io.skipcheck`) is deliberately left for later. Don't start it.

## What's on the branch

One TM1 REST client in four languages, all generated from `spec/tm1-api.json`:
- **Java:** `tm1-client`, the source of truth.
- **Python:** `tm1-python`, sync and asyncio flavours.
- **.NET:** `tm1-dotnet`.
- **TypeScript:** `tm1-typescript`.

Each port has full parity with Java's services and runtime, and Java parity gates that read the Java sources. Each also has a port of `tm1-testcontainers`:
- `tm1-python/testcontainers`
- `tm1-dotnet/src/Tm1.Testcontainers`
- `tm1-typescript/testcontainers`

Conformance (read `spec/conformance/README.md`):
- **Unit level:** Java's unit tests record fixtures in `spec/conformance/calls/*.json`, one per Java test class, 32 in all. Every other client replays them, and must send the same requests and return the same results and errors. This all passes offline.
- **Live level:** `spec/conformance/live/*.json` holds 22 shared scenarios. Each implementation's live runner executes them and writes normalised traffic to `build/conformance-live/<language>/`. Then `scripts/compare-live-traffic.py` fails if any two languages sent different requests.

**None of the live tests or live scenarios have ever run against a real TM1.** Neither have the container-starting code paths in the Python and TypeScript testcontainers ports; .NET's ran once against a stand-in image. Expect failures. Finding and fixing them is the job.

## Setup

1. `git fetch origin claude/sweet-meitner-cmh3ze && git checkout claude/sweet-meitner-cmh3ze`.
2. Toolchains:
   - JDK 27 via Gradle toolchains (the build expects it; javadoc fails on older JDKs).
   - Python 3.10+: `cd tm1-python && pip install -e '.[test]' -e './testcontainers[test]'`. This build generates the sync flavour and the model; reinstall after pulling changes to `tm1_client/aio`.
   - .NET 10 SDK.
   - Node 20+: `cd tm1-typescript && npm ci`.
   - Docker running.
3. Confirm the offline suites are green first:
   - `./gradlew build`
   - `cd tm1-python && pytest tests testcontainers`
   - `dotnet test tm1-dotnet/Tm1.slnx`
   - `cd tm1-typescript && npm test && npm run typecheck && npm run build`

   If anything is red before you've touched a TM1, fix that first. The cloud session's last run was all green, apart from javadoc, which needed JDK 27.
4. TM1 itself: the same sources the Java tests take. Set one of:
   - `TM1_PRIVATE_IMAGE`
   - `TM1_HOME_DIR`
   - `TM1_INSTALL_VOLUME`
   - `TM1_BUNDLE_DIR` (optionally with `TM1_BUNDLE`)
   - `TM1_BUNDLE_URL` (optionally with `TM1_BUNDLE_SHA256`)

   The containers use `ghcr.io/ajmyers/tm1-server:edge` unless `TM1_SERVER_IMAGE` overrides it. If `:edge` is stale or missing, build `docker/tm1-server` locally and point `TM1_SERVER_IMAGE` at the result. The cache volume `tm1-testcontainers-cache` is shared by all languages.

   Alternatively, `TM1_URL` (with `TM1_USER`, `TM1_PASSWORD`, `TM1_NAMESPACE`, `TM1_VERIFY=false`) points Python, .NET and TypeScript at a running server. Java's live tests only use testcontainers.

## The run

`scripts/live-tests.sh` runs all four languages' live suites, then the cross-language traffic comparison. You can name suites to run some: `scripts/live-tests.sh python dotnet`.

Work in this order:
1. **Java first, alone:** `scripts/live-tests.sh java`. Java is the source of truth and its `*Tm1Test`s existed before this branch. Its new parts are:
   - `MemberServiceTm1Test`, `EdgeServiceTm1Test` and `MemberSetServiceTm1Test`
   - `ConformanceScenarioTm1Test`, which runs the shared scenarios

   When a scenario fails in Java, decide which is wrong: the scenario's expectation, or the client. A scenario expectation is a guess unless a Java `*Tm1Test` already proves it. Fix the scenario JSON in `spec/conformance/live/` when TM1 behaves differently from what was guessed. Fix Java when Java is wrong; then re-record unit fixtures (`./gradlew :tm1-client:test -PrecordConformance`) and make the other clients' replays pass again.
2. **Then Python, .NET and TypeScript,** one at a time. A failure here that Java passes is drift in that client: fix the client, not the test.
3. **Then the full run:** `scripts/live-tests.sh` with all four suites, so `compare-live-traffic.py` compares traffic across every language. A difference there is drift too.
4. **Also run the testcontainers packages' own container tests,** which the script only partly covers:
   - Java: `./gradlew :tm1-testcontainers:test`
   - Python: `cd tm1-python && pytest testcontainers`
   - .NET: `dotnet test tm1-dotnet/tests/Tm1.Testcontainers.Tests`
   - TypeScript: `cd tm1-typescript && npm test -w testcontainers`

   PAW, TM1 Web, Cognos and Keycloak tests need their own sources and are fine to leave skipped unless you have them.

## Known guesses and weak spots to check first

- **Scenario expectations no test has confirmed:**
  - 404 after deleting a hierarchy
  - level 0 members being `["World"]`, and the element-type filter (`dimensions`/`levels` scenarios)
  - `Sessions(2147483646)` and `Threads(2147483646)` returning 404
  - the `}Cubes` facet existing right after an annotation is made
  - `"PT24H"` accepted as a chore frequency
  - 404 for a missing logger
  - no SQL data sources on the test server
  - bad MDX and a missing drillthrough process expecting only `category: "api"`
- **Known leftovers:**
  - `annotations.json` can't delete the annotation it creates (scenarios can't carry an ID between steps).
  - `views.json` leaves an executeView cellset until the session ends.
  - `git.json` assumes Git isn't initialised on the shared TM1.
- **Git live tests:** in Python and TypeScript they now start the same git-daemon container as Java's test; that code has never run. `TM1_GIT_URL` overrides it.
- **Shared containers:** TypeScript's shared container is one per test file (vitest isolates files), so it may start several TMs. Enable reuse (`TESTCONTAINERS_REUSE_ENABLE=true`) if that's slow.
- **`TM1_VERIFY=false` in TypeScript** sets `NODE_TLS_REJECT_UNAUTHORIZED=0` for the whole process.
- **Cognos passports in TypeScript** work in Node only.

## Rules

- **Java is the source of truth.** Never weaken a test or hand-edit a recorded fixture to make it pass. Re-record from Java (`-PrecordConformance`) only when Java's behaviour legitimately changed.
- **Exactness:**
  - Result comparison is strict, apart from the documented exceptions in `spec/conformance/README.md`: unknown enums as null, no `@odata.type` in result JSON, null members dropped, numbers compared by value.
  - Request comparison is exact.
  - Don't add leniency to get green. Fix the client.
- **Unsupported cases:** something a language genuinely can't express goes in that client's `conformance/unsupported.txt` with a reason. There's one entry today (.NET, a null subset scope).
- **Commits:** commit in small steps. Each message says what failed against TM1 and why. Push to `claude/sweet-meitner-cmh3ze`.
- **Before each push,** rerun the offline suites from Setup step 3. A live fix mustn't break a replay.

## Done means

- `scripts/live-tests.sh` passes with all four suites and the traffic comparison.
- The testcontainers container tests pass for the TM1 server in every language.
- Every scenario guess above is confirmed or corrected.
- Then update the READMEs: each says the live tests "haven't yet run against a real TM1". Change that to state what was run and on which TM1 version.
- Report the drift found per language, any scenario corrected, and anything still skipped and why.
