# SPDX-License-Identifier: Apache-2.0

"""The comparison of what each client sent to a real TM1 (scripts/compare-live-traffic.py), which
ends a run of the live tests."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pytest
from conftest import load

traffic = load("scripts/compare-live-traffic.py")

HEADER = {
    "scenario": "cubes",
    "language": "java",
    "names": {"cube": "tm1conf_1a2b_cube", "cube2": "tm1conf_1a2b_cube2"},
}


def exchange(
    method: str, target: str, step: str = "steps[0]", **request: Any
) -> dict[str, Any]:
    return {
        "step": step,
        "request": {"method": method, "target": target, "headers": {}, **request},
        "status": 200,
    }


def test_puts_the_runs_names_back_as_placeholders_longest_first() -> None:
    sent = exchange(
        "POST",
        "Cubes('tm1conf_1a2b_cube2')",
        json={"Name": "tm1conf_1a2b_cube", "tm1conf_1a2b_cube2": ["tm1conf_1a2b_cube"]},
    )
    request = traffic.normalize(HEADER, [sent])[0]["request"]
    assert request["target"] == "Cubes('${cube2}')"
    assert request["json"] == {"Name": "${cube}", "${cube2}": ["${cube}"]}


def test_leaves_out_what_differs_from_run_to_run() -> None:
    sent = exchange(
        "PATCH",
        "Cellsets('abc123')/Cells",
        headers={"if-match": 'W/"5"', "accept": "application/json"},
        json={"@odata.etag": 'W/"5"', "Value": 2.0, "Ratio": 0.5},
    )
    request = traffic.normalize(HEADER, [sent])[0]["request"]
    assert request["target"] == "Cellsets('<id>')/Cells"
    assert request["headers"] == {"if-match": "<etag>", "accept": "application/json"}
    assert request["json"] == {"Value": 2, "Ratio": 0.5}
    # A cellset a scenario names is a placeholder by now, and stays
    named = traffic.normalize(
        {"names": {"missing": "tm1conf_x"}}, [exchange("GET", "Cellsets('tm1conf_x')")]
    )[0]["request"]
    assert named["target"] == "Cellsets('${missing}')"


def test_sorts_only_the_requests_a_step_calls_unordered() -> None:
    patterns = traffic.unordered({"steps": [{}, {"unordered": "^GET Cellsets"}]})
    assert list(patterns) == ["steps[1]"]
    log = [
        exchange("POST", "ExecuteMDX", "steps[1]"),
        exchange("GET", "Cellsets('<id>')?$skip=2", "steps[1]"),
        exchange("GET", "Cellsets('<id>')?$skip=1", "steps[1]"),
        exchange("DELETE", "Cellsets('<id>')", "steps[1]"),
        exchange("GET", "Cubes('b')", "steps[0]"),
        exchange("GET", "Cubes('a')", "steps[0]"),
    ]
    targets = [x["request"]["target"] for x in traffic.reorder(log, patterns)]
    assert targets == [
        "ExecuteMDX",
        "Cellsets('<id>')?$skip=1",
        "Cellsets('<id>')?$skip=2",
        "Cellsets('<id>')",
        "Cubes('b')",
        "Cubes('a')",
    ]
    assert traffic.reorder(log, {}) == log


def test_keeps_one_of_a_run_of_the_requests_a_step_repeats() -> None:
    patterns = traffic.repeating(
        {
            "steps": [{}, {"repeats": "tm1\\.Continue$"}],
            "finally": [{"repeats": "tm1\\.Continue$"}],
        }
    )
    assert list(patterns) == ["steps[1]", "finally[0]"]
    log = [
        exchange("GET", "Cubes('a')", "steps[0]"),
        exchange("GET", "Cubes('a')", "steps[0]"),
        exchange("POST", "C/tm1.Continue", "steps[1]"),
        exchange("POST", "C/tm1.Continue", "steps[1]"),
        exchange("POST", "C/tm1.Continue", "finally[0]"),
        exchange("POST", "C/tm1.Continue", "finally[0]"),
        exchange("POST", "C/tm1.Continue", "finally[0]"),
    ]
    steps = [x["step"] for x in traffic.collapse(log, patterns)]
    assert steps == ["steps[0]", "steps[0]", "steps[1]", "finally[0]"]
    assert traffic.collapse(log, {}) == log


def test_refuses_a_run_of_repeats_longer_than_its_limit() -> None:
    scenario = {
        "steps": [
            {"repeats": "tm1\\.Continue$"},
            {"repeats": "tm1\\.Continue$", "maxRepeats": 2},
        ]
    }
    patterns, limits = traffic.repeating(scenario), traffic.repeat_limits(scenario)
    assert limits == {"steps[0]": traffic.REPEAT_LIMIT, "steps[1]": 2}
    polls = [exchange("POST", "C/tm1.Continue", "steps[1]") for _ in range(3)]
    assert traffic.overlong(polls, patterns, limits) == [
        "steps[1] sent POST C/tm1.Continue 3 times in a row, more than its 2"
    ]
    assert traffic.overlong(polls[:2], patterns, limits) == []
    many = [
        exchange("POST", "C/tm1.Continue", "steps[0]")
        for _ in range(traffic.REPEAT_LIMIT + 1)
    ]
    assert len(traffic.overlong(many, patterns, limits)) == 1
    # Only a step that repeats is held to it: other equal requests aren't a run
    assert (
        traffic.overlong(
            [exchange("GET", "Cubes('a')", "steps[2]")] * 40, patterns, limits
        )
        == []
    )


def test_fails_a_client_that_repeats_past_the_limit(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    # main reads each log's steps from its scenario's definition
    definitions = tmp_path / "live"
    definitions.mkdir()
    (definitions / "poll.json").write_text(
        json.dumps({"steps": [{"repeats": "tm1\\.Continue$"}]}), encoding="utf-8"
    )
    monkeypatch.setattr(traffic, "SCENARIOS", definitions)
    log = [exchange("POST", "C/tm1.Continue") for _ in range(traffic.REPEAT_LIMIT + 1)]
    write_log(tmp_path / "traffic", "java", "poll", log, {})
    write_log(tmp_path / "traffic", "python", "poll", log[:1], {})
    assert traffic.main(["compare", str(tmp_path / "traffic")]) == 1
    assert (
        "java steps[0] sent POST C/tm1.Continue 31 times in a row"
        in capsys.readouterr().out
    )
    write_log(tmp_path / "traffic", "java", "poll", log[:-1], {})
    assert traffic.main(["compare", str(tmp_path / "traffic")]) == 0


def test_logs_that_agree_have_no_differences() -> None:
    log = [exchange("GET", "Cubes"), exchange("DELETE", "Cubes('a')")]
    assert (
        traffic.compare({"java": log, "python": list(log), "dotnet": list(log)}) == []
    )
    assert traffic.compare({"java": log}) == []
    assert traffic.compare({}) == []


def test_names_the_first_request_that_differs() -> None:
    java = [
        exchange("GET", "Cubes"),
        exchange("POST", "Cubes", json={"Name": "a", "Rules": ""}),
    ]
    python = [
        exchange("GET", "Cubes"),
        exchange("POST", "Cubes", json={"Rules": "", "Name": "a"}),
    ]
    problems = traffic.compare({"java": java, "python": python})
    assert (
        len(problems) == 1
        and problems[0].startswith("request 2 differs")
        and "java:" in problems[0]
        and "python:" in problems[0]
    )


def test_names_the_request_one_client_sent_and_another_didnt() -> None:
    java = [exchange("GET", "Cubes")]
    python = [exchange("GET", "Cubes"), exchange("GET", "Dimensions")]
    problems = traffic.compare({"java": java, "python": python})
    assert (
        len(problems) == 1
        and "java sent 1 requests, python 2" in problems[0]
        and "Dimensions" in problems[0]
    )


def write_log(
    root: Path,
    language: str,
    scenario: str,
    exchanges: list[dict[str, Any]],
    names: dict[str, str],
) -> None:
    folder = root / language
    folder.mkdir(parents=True, exist_ok=True)
    lines = [{"scenario": scenario, "language": language, "names": names}, *exchanges]
    (folder / f"{scenario}.jsonl").write_text(
        "\n".join(json.dumps(line) for line in lines) + "\n", encoding="utf-8"
    )


def test_passes_when_every_client_sent_the_same(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    write_log(
        tmp_path,
        "java",
        "made-up",
        [exchange("GET", "Cubes('tm1conf_aa_cube')")],
        {"cube": "tm1conf_aa_cube"},
    )
    write_log(
        tmp_path,
        "python",
        "made-up",
        [exchange("GET", "Cubes('tm1conf_bb_cube')")],
        {"cube": "tm1conf_bb_cube"},
    )
    write_log(tmp_path, "java", "alone", [exchange("GET", "Cubes")], {})
    assert traffic.main(["compare", str(tmp_path)]) == 0
    printed = capsys.readouterr().out
    assert "made-up: java, python sent the same 1 requests" in printed
    assert "alone: only java ran it, nothing to compare" in printed


def test_fails_when_two_clients_differ(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    write_log(tmp_path, "java", "made-up", [exchange("GET", "Cubes")], {})
    write_log(tmp_path, "dotnet", "made-up", [exchange("GET", "Cubes?$top=1")], {})
    assert traffic.main(["compare", str(tmp_path)]) == 1
    assert "made-up: the clients sent different requests" in capsys.readouterr().out


def test_passes_with_nothing_to_compare(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert traffic.main(["compare", str(tmp_path / "none")]) == 0
    assert "nothing to compare" in capsys.readouterr().out


def test_refuses_a_log_without_its_header(tmp_path: Path) -> None:
    (tmp_path / "java").mkdir()
    (tmp_path / "java" / "made-up.jsonl").write_text(
        json.dumps(exchange("GET", "Cubes")) + "\n", encoding="utf-8"
    )
    with pytest.raises(
        ValueError, match=re.escape("the first line must name the scenario")
    ):
        traffic.main(["compare", str(tmp_path)])
