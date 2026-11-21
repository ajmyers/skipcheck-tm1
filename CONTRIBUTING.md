# Contributing

Issues and pull requests are welcome.

Because this project is dual-licensed (AGPL-3.0 and commercial), contributors must agree to a
Contributor License Agreement before a pull request can be merged. The agreement lets your
contribution be distributed under both licenses, and you keep the copyright to your work.

## Workflow

1. Every change starts with a GitHub issue that records the what, the why, the how, and any context.
2. Work on a branch named after the issue, e.g. `gh issue develop 12 --checkout`.
3. Before opening a PR, run `./gradlew build`. Every commit must build on its own: the pre-push
   hook builds each commit you push that the remote doesn't have yet, in a temporary worktree, and
   refuses the push if one fails. It skips TM1 tests (as CI does), so run those by hand.

## Commit and PR messages

Enable the commit message hook once per clone:

```sh
git config core.hooksPath .githooks
```

Commit messages follow this structure (checked by `.githooks/check_message.py`):

```
Add cube listing to CubeService

What changed, why, and the decisions behind it that the code can't show.

Refs: #12
```

- Title: imperative, 72 characters or fewer, capitalized, no trailing period, no issue numbers.
- Description: required.
- `Refs: #N` trailer: required; it links the issue.

PRs are squash-merged, so the PR title and description become the commit on `master`. CI checks that the title follows the same rules and that the description links the issue with `Closes #N` or `Part of #N`.

## Code style

**Layout is Spring Java Format, and the build checks it.** `./gradlew build` fails on a
file the formatter would change; `./gradlew format` fixes them all. The pre-commit hook formats
what you stage, so you rarely need to (skip it once with `SKIP_FORMAT_HOOK=1`). The same check
fails on unused imports, imports out of order (static, `java`/`javax`, `org`, then the rest) and
fully qualified type names in code; `format` fixes all but the last. In a Javadoc code example,
write `@` as `&#64;` inside `<pre class="code">`, as Spring does: the formatter takes an `@` at
the start of a line for a block tag and breaks the comment, which the check also reports.

**Public API needs Javadoc.** In the published libraries, every public class, method, constructor
and record component is documented, with `@param` and `@return`. `./gradlew build` runs Javadoc
and fails on any gap. For a Lombok `@Getter`, put the `@return` line in the field's Javadoc;
Lombok copies it onto the accessor.

**Error Prone and NullAway check every compile.** Their findings are errors. Library packages
are `@NullMarked` (JSpecify): nothing is null unless declared `@Nullable`, and NullAway checks
that at compile time, so a nullable field, parameter or return gets `@Nullable` where it's
declared. Lombok copies it onto the accessors it generates. Tests aren't null-checked.

**Architecture rules are tests.** `ArchitectureTest` in `tm1-client` checks the compiled library:
it never blocks or calls a bare `subscribe()`, builds request URIs instead of using URI templates,
logs only through SLF4J, and keeps the `odata` package free of TM1. Each rule's `because` says
why. If one fails, fix the code; change a rule only with a reason in the commit.

**Value types are records.** Immutable data (requests, results, model types) is a Java
record. Records already remove the boilerplate of constructors, accessors, `equals`,
`hashCode` and `toString`.

**Lombok covers what records don't.** It's set up for every module, compile-time only, so
published libraries don't depend on it. Settings are in the root `lombok.config`, and
accessors are fluent (`status()`, not `getStatus()`) to match records.

| Use | For |
|---|---|
| `@RequiredArgsConstructor` | Constructor injection of services and components |
| `@Getter` on fields | Accessors on classes that can't be records, such as exceptions and container subclasses. Put the Javadoc on the field; delombok copies it to the accessor. |
| `@Builder` | Options and configuration with many optional settings, including on records |
| `@With` | Copy-on-change of immutable values, including on records |
| `@Slf4j` | Logging (see below) |

Not used, and refused by `lombok.config`: `@Data`, `@Setter`, `@SneakyThrows`, `val`, `var`
and every logger annotation except `@Slf4j`. Experimental features need a good reason.

**Logging goes through SLF4J,** with Lombok's `@Slf4j`. The libraries depend only on
`slf4j-api`, and the application chooses the backend (Spring Boot's is Logback).

- `debug` for each request and response, `info` for session lifecycle (login, re-login,
  close), `warn` for something the caller should know about but that didn't fail, such as a
  close that timed out. Failures reach the caller as errors, so don't also log them as `error`.
- Use placeholders (`log.debug("{} {}", method, url)`), not string concatenation.
- **Never log credentials:** no `Authorization` headers, cookies, CAM passports, passwords
  or pre-signed URLs, at any level.

**Metrics and traces go through Micrometer's Observation API,** which Spring Boot turns into
metrics and traces. The client takes an `ObservationRegistry` (a no-op one by default).

- Every HTTP request is already observed by WebClient (`http.client.requests`), so don't
  wrap single requests again.
- Add an observation for work above one request: logging in, closing a session, and later
  session pool events and long-running operations. Name them under `tm1.`, e.g.
  `tm1.session.login`.
- Tags (low-cardinality key values) must come from a small, fixed set: an outcome, a reason,
  a kind. Object names, URLs and user names go in high-cardinality values, or nowhere.
- The same rule as logging: never put credentials in tags or events.
- Test them with `TestObservationRegistry` from `micrometer-observation-test`.

**Compiler warnings fail the build** (`-Werror`). Fix the code; suppress a warning only with a
comment saying why, as `Tm1ServerContainer` does for `this-escape`.
