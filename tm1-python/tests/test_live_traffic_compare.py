"""scripts/compare-live-traffic.py: the normalizer and comparer the live conformance runs share."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "compare-live-traffic.py"


@pytest.fixture(scope="module")
def tool() -> ModuleType:
    spec = importlib.util.spec_from_file_location("compare_live_traffic", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _log(language: str, names: dict[str, str], *requests: dict[str, Any], status: int = 200) -> list[dict[str, Any]]:
    head = {"scenario": "cubes", "language": language, "names": names}
    return [head, *({"step": f"steps[{i}]", "request": r, "status": status} for i, r in enumerate(requests))]


def _write(root: Path, language: str, lines: list[dict[str, Any]]) -> None:
    (root / language).mkdir(parents=True, exist_ok=True)
    (root / language / "cubes.jsonl").write_text("\n".join(json.dumps(x) for x in lines) + "\n", encoding="utf-8")


def _get(name: str, etag: str | None = None) -> dict[str, Any]:
    headers = {"accept": "application/json"}
    if etag:
        headers["if-match"] = etag
    return {"method": "GET", "target": f"Cubes('{name}')", "headers": headers}


def test_names_become_placeholders_longest_first(tool: ModuleType) -> None:
    head, *rest = _log(
        "java",
        {"cube": "tm1conf_ab_cube", "cube2": "tm1conf_ab_cube2"},
        {"method": "POST", "target": "Cubes", "headers": {}, "json": {"Name": "tm1conf_ab_cube2", "x": ["tm1conf_ab_cube"]}},
    )
    normal = tool.normalize(head, rest)
    assert normal[0]["request"]["json"] == {"Name": "${cube2}", "x": ["${cube}"]}


def test_entity_tags_are_left_out(tool: ModuleType) -> None:
    head, *rest = _log("java", {}, {**_get("c", 'W/"7"'), "json": {"@odata.etag": 'W/"7"', "Name": "c"}})
    normal = tool.normalize(head, rest)
    assert normal[0]["request"]["headers"]["if-match"] == "<etag>"
    assert normal[0]["request"]["json"] == {"Name": "c"}


def test_the_same_traffic_under_different_names_compares_equal(tool: ModuleType) -> None:
    a = _log("java", {"cube": "tm1conf_11_cube"}, _get("tm1conf_11_cube", 'W/"1"'))
    b = _log("typescript", {"cube": "tm1conf_22_cube"}, _get("tm1conf_22_cube", 'W/"2"'))
    logs = {"java": tool.normalize(a[0], a[1:]), "typescript": tool.normalize(b[0], b[1:])}
    assert tool.compare(logs) == []


def test_a_different_request_body_order_status_or_count_is_reported(tool: ModuleType) -> None:
    base = _log("java", {}, {"method": "POST", "target": "Cubes", "headers": {}, "json": {"Name": "c", "Rules": "x"}})
    reordered = _log("dotnet", {}, {"method": "POST", "target": "Cubes", "headers": {}, "json": {"Rules": "x", "Name": "c"}})
    status = _log("python-sync", {}, base[1]["request"], status=201)
    longer = _log("python-async", {}, base[1]["request"], _get("c"))
    for other in (reordered, status, longer):
        logs = {"java": tool.normalize(base[0], base[1:]), other[0]["language"]: tool.normalize(other[0], other[1:])}
        assert len(tool.compare(logs)) == 1, other[0]["language"]


def test_main_passes_alone_and_fails_on_a_difference(tool: ModuleType, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert tool.main(["x", str(tmp_path / "none")]) == 0
    _write(tmp_path, "java", _log("java", {"cube": "tm1conf_1_cube"}, _get("tm1conf_1_cube")))
    assert tool.main(["x", str(tmp_path)]) == 0
    assert "nothing to compare" in capsys.readouterr().out
    _write(tmp_path, "dotnet", _log("dotnet", {"cube": "tm1conf_2_cube"}, _get("tm1conf_2_cube")))
    assert tool.main(["x", str(tmp_path)]) == 0
    _write(tmp_path, "typescript", _log("typescript", {"cube": "tm1conf_3_cube"}, _get("tm1conf_3_cube") | {"target": "Cubes"}))
    assert tool.main(["x", str(tmp_path)]) == 1
    assert "request 1 differs" in capsys.readouterr().out


def test_a_steps_unordered_requests_compare_in_any_order_but_only_those(tool: ModuleType) -> None:
    def page(n: int) -> dict[str, Any]:
        return {"method": "GET", "target": f"Cellsets('c')/tm1.GetPartition(Begin={n},End={n})", "headers": {}}

    post = {"method": "POST", "target": "ExecuteMDX", "headers": {}}
    delete = {"method": "DELETE", "target": "Cellsets('c')", "headers": {}}
    pages = tool.unordered({"steps": [{"unordered": r"^GET Cellsets\('[^']*'\)/tm1\.GetPartition\("}]})

    def log(patterns: Any, *requests: dict[str, Any]) -> list[dict[str, Any]]:
        head = {"scenario": "cellsets", "language": "x", "names": {}}
        return tool.reorder(tool.normalize(head, [{"step": "steps[0]", "request": r, "status": 200} for r in requests]), patterns)

    assert tool.compare({"java": log(pages, post, page(1), page(2), delete), "dotnet": log(pages, post, page(2), page(1), delete)}) == []
    assert len(tool.compare({"java": log(pages, post, page(1), delete), "dotnet": log(pages, page(1), post, delete)})) == 1, "the rest keeps its order"
    assert len(tool.compare({"java": log({}, page(1), page(2)), "dotnet": log({}, page(2), page(1))})) == 1, "only a marked step's"
    assert tool.unordered(json.loads((Path(tool.__file__).parents[1] / "spec/conformance/live/cellsets.json").read_text()))
