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
