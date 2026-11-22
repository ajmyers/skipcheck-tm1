#!/usr/bin/env python3
"""Compares what each client sent to a real TM1 for the same live conformance scenario.

Each client's live run of a scenario (spec/conformance/live/<name>.json) writes its traffic to
build/conformance-live/<language>/<name>.jsonl: a first line naming the scenario and the names
it made up for the run ({"scenario", "language", "names": {"cube": "tm1conf_1a2b_cube"}}), then
one line per request it sent, in order ({"step", "request": {method, target, headers, json |
text | bytes}, "status"}). The session's own requests (login, close, $metadata, the version) are
left out by the runners.

This script normalizes every log the same way: the run's names back to their placeholders
(${cube}), entity tags left out; then requires every language's log of a scenario to be equal,
request for request, and prints the first difference. With fewer than two logs of a scenario,
there's nothing to compare: it says so and passes.

    scripts/compare-live-traffic.py [build/conformance-live]

Exits 1 if any two languages sent something different. Standard library only.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

ETAG_HEADERS = ("if-match", "if-none-match")


def load(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """A log: its header line and its exchanges."""
    lines = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not lines or "names" not in lines[0]:
        raise ValueError(f"{path}: the first line must name the scenario and its names")
    return lines[0], lines[1:]


def _placeholders(names: dict[str, str]) -> list[tuple[str, str]]:
    # Longest first, so a name that contains another is replaced whole
    return sorted(((actual, "${" + placeholder + "}") for placeholder, actual in names.items()), key=lambda p: -len(p[0]))


def _replace(value: Any, names: list[tuple[str, str]]) -> Any:
    if isinstance(value, str):
        for actual, placeholder in names:
            value = value.replace(actual, placeholder)
        return value
    if isinstance(value, list):
        return [_replace(v, names) for v in value]
    if isinstance(value, dict):
        return {_replace(k, names): _replace(v, names) for k, v in value.items() if k != "@odata.etag"}
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def normalize(header: dict[str, Any], exchanges: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """A log as it compares: the run's names as placeholders, without entity tags."""
    names = _placeholders(header["names"])
    out = []
    for exchange in exchanges:
        request = dict(exchange["request"])
        headers = {k: ("<etag>" if k in ETAG_HEADERS else v) for k, v in request.get("headers", {}).items()}
        request["headers"] = headers
        out.append({"step": exchange.get("step"), "request": _replace(request, names), "status": exchange.get("status")})
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
                problems.append(f"request {i + 1} differs:\n  {reference}: {json.dumps(x)}\n  {other}: {json.dumps(y)}")
                break
        else:
            if len(a) != len(b):
                longer, extra = (reference, a[len(b)]) if len(a) > len(b) else (other, b[len(a)])
                problems.append(f"{reference} sent {len(a)} requests, {other} {len(b)}: {longer} also sent {json.dumps(extra)}")
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[1] / "build" / "conformance-live"
    if not root.is_dir():
        print(f"No live conformance traffic in {root}: nothing to compare")
        return 0
    scenarios: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for log in sorted(root.glob("*/*.jsonl")):
        header, exchanges = load(log)
        scenarios.setdefault(log.stem, {})[log.parent.name] = normalize(header, exchanges)
    failed = False
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
            print(f"{scenario}: {', '.join(sorted(logs))} sent the same {len(next(iter(logs.values())))} requests")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
