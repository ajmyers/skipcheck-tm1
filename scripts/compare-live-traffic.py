#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""Compares what each client sent to a real TM1 for the same live conformance scenario.

Each client's live run of a scenario (spec/conformance/live/<name>.json) writes its traffic to
build/conformance-live/<language>/<name>.jsonl: a first line naming the scenario and the names
it made up for the run ({"scenario", "language", "names": {"cube": "tm1conf_1a2b_cube"}}), then
one line per request it sent, in order ({"step", "request": {method, target, headers, json |
text | bytes}, "status"}). The session's own requests (login, close, $metadata, the version) are
left out by the runners.

This script normalizes every log the same way: the run's names back to their placeholders
(${cube}), entity tags left out, the ID TM1 made up for a cellset as <id>, and each run of requests a scenario's step marks "unordered" (a
regular expression on "METHOD target", for requests a client has in flight at once, such as a
cellset stream's pages) put in one order, and each run of equal requests a step marks "repeats" (the
same, for a poll that sends as many as TM1 takes) kept once, as long as no run is longer than the
step's "maxRepeats" (30 if it gives none); then requires every language's log of a scenario to be
equal, request for request, and prints the first difference. With fewer than two logs of a scenario,
there's nothing to compare: it says so and passes.

    scripts/compare-live-traffic.py [build/conformance-live]

Exits 1 if any two languages sent something different. Standard library only.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

ETAG_HEADERS = ("if-match", "if-none-match")

# How many equal requests a step that "repeats" may send in a row: past it, a client polling far more
# than another would still compare equal, as each run is kept once
REPEAT_LIMIT = 30

# A cellset's ID is TM1's, new for every run, and a scenario can't name it. One a scenario does
# name (a missing cellset) is a placeholder by now, and stays.
CELLSET_ID = re.compile(r"Cellsets\('(?!\$\{)[^']*'\)")

SCENARIOS = Path(__file__).resolve().parents[1] / "spec" / "conformance" / "live"


def load(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """A log: its header line and its exchanges."""
    lines = [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not lines or "names" not in lines[0]:
        raise ValueError(f"{path}: the first line must name the scenario and its names")
    return lines[0], lines[1:]


def _placeholders(names: dict[str, str]) -> list[tuple[str, str]]:
    # Longest first, so a name that contains another is replaced whole
    return sorted(
        ((actual, "${" + placeholder + "}") for placeholder, actual in names.items()),
        key=lambda p: -len(p[0]),
    )


def _replace(value: Any, names: list[tuple[str, str]]) -> Any:
    if isinstance(value, str):
        for actual, placeholder in names:
            value = value.replace(actual, placeholder)
        return value
    if isinstance(value, list):
        return [_replace(v, names) for v in value]
    if isinstance(value, dict):
        return {
            _replace(k, names): _replace(v, names)
            for k, v in value.items()
            if k != "@odata.etag"
        }
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def normalize(
    header: dict[str, Any], exchanges: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """A log as it compares: the run's names as placeholders, without entity tags."""
    names = _placeholders(header["names"])
    out = []
    for exchange in exchanges:
        request = dict(exchange["request"])
        headers = {
            k: ("<etag>" if k in ETAG_HEADERS else v)
            for k, v in request.get("headers", {}).items()
        }
        request["headers"] = headers
        request = _replace(request, names)
        request["target"] = CELLSET_ID.sub("Cellsets('<id>')", request["target"])
        out.append(
            {
                "step": exchange.get("step"),
                "request": request,
                "status": exchange.get("status"),
            }
        )
    return out


def unordered(scenario: dict[str, Any]) -> dict[str, re.Pattern[str]]:
    """The steps whose matching requests may go out in any order: step label to pattern."""
    return {
        f"steps[{i}]": re.compile(step["unordered"])
        for i, step in enumerate(scenario.get("steps", []))
        if "unordered" in step
    }


def reorder(
    exchanges: list[dict[str, Any]], patterns: dict[str, re.Pattern[str]]
) -> list[dict[str, Any]]:
    """A normalized log with each run of a step's consecutive requests that match its pattern
    sorted, so runs sent in different orders compare equal. Everything else keeps its order."""

    def free(x: dict[str, Any]) -> bool:
        pattern = patterns.get(x.get("step") or "")
        return bool(
            pattern
            and pattern.search(f"{x['request']['method']} {x['request']['target']}")
        )

    out: list[dict[str, Any]] = []
    i = 0
    while i < len(exchanges):
        j = i
        while (
            j < len(exchanges)
            and free(exchanges[j])
            and exchanges[j].get("step") == exchanges[i].get("step")
        ):
            j += 1
        if j > i:
            out.extend(
                sorted(exchanges[i:j], key=lambda x: json.dumps(x, sort_keys=True))
            )
            i = j
        else:
            out.append(exchanges[i])
            i += 1
    return out


def repeating(scenario: dict[str, Any]) -> dict[str, re.Pattern[str]]:
    """The steps, finally ones too, whose matching requests may repeat: step label to pattern. A call
    that polls TM1 until it's done, such as finishing a debug run, sends as many as TM1 takes."""
    return {
        f"{part}[{i}]": re.compile(step["repeats"])
        for part in ("steps", "finally")
        for i, step in enumerate(scenario.get(part, []))
        if "repeats" in step
    }


def repeat_limits(scenario: dict[str, Any]) -> dict[str, int]:
    """Per step that repeats, how many equal requests it may send in a row: its "maxRepeats", else
    REPEAT_LIMIT."""
    return {
        f"{part}[{i}]": int(step.get("maxRepeats", REPEAT_LIMIT))
        for part in ("steps", "finally")
        for i, step in enumerate(scenario.get(part, []))
        if "repeats" in step
    }


def overlong(
    exchanges: list[dict[str, Any]],
    patterns: dict[str, re.Pattern[str]],
    limits: dict[str, int],
) -> list[str]:
    """Each run of a step's equal consecutive requests matching its pattern that's longer than the
    step's limit, which collapsing them would hide."""
    problems: list[str] = []
    runs: list[tuple[dict[str, Any], int]] = []
    for x in exchanges:
        pattern = patterns.get(x.get("step") or "")
        if (
            pattern
            and runs
            and _ordered(runs[-1][0]) == _ordered(x)
            and pattern.search(f"{x['request']['method']} {x['request']['target']}")
        ):
            runs[-1] = (runs[-1][0], runs[-1][1] + 1)
        else:
            runs.append((x, 1))
    for x, count in runs:
        limit = limits.get(x.get("step") or "", REPEAT_LIMIT)
        if x.get("step") in patterns and count > limit:
            problems.append(
                f"{x['step']} sent {x['request']['method']} {x['request']['target']} {count} times in a row, more than its {limit}"
            )
    return problems


def collapse(
    exchanges: list[dict[str, Any]], patterns: dict[str, re.Pattern[str]]
) -> list[dict[str, Any]]:
    """A normalized log with each run of a step's equal consecutive requests that match its pattern
    kept once, so polls that took TM1 more or fewer answers compare equal. Everything else stays."""
    out: list[dict[str, Any]] = []
    for x in exchanges:
        pattern = patterns.get(x.get("step") or "")
        if (
            pattern
            and out
            and _ordered(out[-1]) == _ordered(x)
            and pattern.search(f"{x['request']['method']} {x['request']['target']}")
        ):
            continue
        out.append(x)
    return out


def _ordered(value: Any) -> Any:
    """For comparing: JSON with its members' order kept."""
    if isinstance(value, dict):
        return [(k, _ordered(v)) for k, v in value.items()]
    if isinstance(value, list):
        return [_ordered(v) for v in value]
    return value


def compare(logs: dict[str, list[dict[str, Any]]]) -> list[str]:
    """The differences between normalized logs of one scenario, by language; empty if none."""
    problems: list[str] = []
    languages = sorted(logs)
    if len(languages) < 2:
        return problems
    reference = languages[0]
    for other in languages[1:]:
        a, b = logs[reference], logs[other]
        for i, (x, y) in enumerate(zip(a, b)):
            if _ordered(x) != _ordered(y):
                problems.append(
                    f"request {i + 1} differs:\n  {reference}: {json.dumps(x)}\n  {other}: {json.dumps(y)}"
                )
                break
        else:
            if len(a) != len(b):
                longer, extra = (
                    (reference, a[len(b)]) if len(a) > len(b) else (other, b[len(a)])
                )
                problems.append(
                    f"{reference} sent {len(a)} requests, {other} {len(b)}: {longer} also sent {json.dumps(extra)}"
                )
    return problems


def main(argv: list[str]) -> int:
    root = (
        Path(argv[1])
        if len(argv) > 1
        else Path(__file__).resolve().parents[1] / "build" / "conformance-live"
    )
    if not root.is_dir():
        print(f"No live conformance traffic in {root}: nothing to compare")
        return 0
    scenarios: dict[str, dict[str, list[dict[str, Any]]]] = {}
    failed = False
    for log in sorted(root.glob("*/*.jsonl")):
        header, exchanges = load(log)
        spec = SCENARIOS / f"{log.stem}.json"
        definition = (
            json.loads(spec.read_text(encoding="utf-8")) if spec.is_file() else {}
        )
        patterns = repeating(definition)
        for problem in overlong(
            normalize(header, exchanges), patterns, repeat_limits(definition)
        ):
            failed = True
            print(f"{log.stem}: {log.parent.name} {problem}")
        normalized = collapse(normalize(header, exchanges), patterns)
        scenarios.setdefault(log.stem, {})[log.parent.name] = reorder(
            normalized, unordered(definition)
        )
    for scenario, logs in sorted(scenarios.items()):
        problems = compare(logs)
        if len(logs) < 2:
            print(f"{scenario}: only {', '.join(logs)} ran it, nothing to compare")
        elif problems:
            failed = True
            print(f"{scenario}: the clients sent different requests")
            for problem in problems:
                print("  " + problem.replace("\n", "\n  "))
        else:
            print(
                f"{scenario}: {', '.join(sorted(logs))} sent the same {len(next(iter(logs.values())))} requests"
            )
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
