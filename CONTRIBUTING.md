# Contributing

Issues and pull requests are welcome.

Because this project is dual-licensed (AGPL-3.0 and commercial), contributors must agree to a
Contributor License Agreement before a pull request can be merged. The agreement lets your
contribution be distributed under both licenses, and you keep the copyright to your work.

## Workflow

1. Every change starts with a GitHub issue that records the what, the why, the how, and any context.
2. Work on a branch named after the issue, e.g. `gh issue develop 12 --checkout`.
3. Before opening a PR, run `./gradlew build`.

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
| `@CommonsLog` | Logging through Spring's commons-logging bridge, with no extra dependency |

Not used, and refused by `lombok.config`: `@Data`, `@Setter`, `@SneakyThrows`, `val` and
`var`. Experimental features need a good reason.

**Compiler warnings fail the build** (`-Werror`). Fix the code; suppress a warning only with a
comment saying why, as `Tm1ServerContainer` does for `this-escape`.
