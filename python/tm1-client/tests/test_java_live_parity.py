# SPDX-License-Identifier: Apache-2.0

"""The live tests are the Java client's, class for class and test for test, read from the Java
sources themselves (java/tm1-client/src/test/java/.../*Tm1Test.java and *BehaviorTest.java):

* each Java live test class has its file in tests/live: ``CubeServiceTm1Test`` is
  ``test_cube_service.py``, ``Tm1SessionBehaviorTest`` is ``test_tm1_session_behavior.py``;
* each of its tests (``@Test``, ``@ParameterizedTest``...) has a test there under the same name, in
  snake case after ``test_`` (``reordersTheStorageOrder`` is ``test_reorders_the_storage_order``);
* or coverage/live-excluded.txt says why not: ``ClassName#methodName: reason``, or ``ClassName:
  reason`` for a whole class. A line naming a class or a test Java doesn't have, or one this
  client has after all, fails too, so the list can't rot;
* and a file of tests/live that isn't a Java class's holds what only this client tests, and is
  named ``test_python_only…``; a Java class's file has no test Java doesn't.

"Needs Cognos" (or TM1 Web, or PAW) is no reason to exclude: those tests are ported, and skip at
run time. This reads sources only, so it runs without TM1.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "java" / "tm1-client" / "src" / "test" / "java"
LIVE = Path(__file__).resolve().parent / "live"
EXCLUDED = Path(__file__).resolve().parents[1] / "coverage" / "live-excluded.txt"

_SUFFIXES = {"Tm1Test": "", "BehaviorTest": "_behavior"}
# A test annotation, then the first method after it: JUnit's tests return nothing
_TEST = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestTemplate)\b.*?\bvoid\s+(\w+)\s*\(", re.S)
_PYTHON_ONLY = "test_python_only"


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def file_for(java_class: str) -> str:
    """The live test file of a Java live test class."""
    for suffix, ending in _SUFFIXES.items():
        if java_class.endswith(suffix):
            return f"test_{snake(java_class[: -len(suffix)])}{ending}.py"
    raise ValueError(f"{java_class} isn't a live test class")


def named(java_method: str) -> str:
    """The test of a Java test method."""
    return "test_" + snake(java_method)


def java_tests(source: str) -> list[str]:
    """The test methods of a Java test class, from its source."""
    without_comments = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    without_comments = re.sub(r"^\s*//.*$", "", without_comments, flags=re.M)
    return [m.group(1) for m in _TEST.finditer(without_comments)]


def java_live_tests() -> dict[str, list[str]]:
    """Each Java live test class, with its test methods."""
    out: dict[str, list[str]] = {}
    for suffix in _SUFFIXES:
        for path in sorted(JAVA.rglob(f"*{suffix}.java")):
            assert path.stem not in out, f"two Java classes named {path.stem}"
            out[path.stem] = java_tests(path.read_text(encoding="utf-8"))
    return out


def python_tests(path: Path) -> set[str]:
    """The test functions of a test file."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    return {node.name for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_")}


def exclusions(text: str) -> dict[tuple[str, str | None], str]:
    """The exclusions by (class, method), the method None for a whole class, with their reasons."""
    out: dict[tuple[str, str | None], str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        what, colon, reason = line.partition(":")
        assert colon and reason.strip(), f"{line!r} needs a reason: ClassName#methodName: reason"
        java_class, _, method = what.strip().partition("#")
        key = (java_class.strip(), method.strip() or None)
        assert key not in out, f"duplicate exclusion {line!r}"
        out[key] = reason.strip()
    return out


def problems(java: dict[str, list[str]], files: dict[str, set[str]], excluded: dict[tuple[str, str | None], str]) -> list[str]:
    """What keeps the live tests from being Java's: ``files`` is each test file of tests/live with
    its tests."""
    out = []
    ported = {file_for(java_class): java_class for java_class in java}
    for (java_class, method), _reason in excluded.items():
        said = java_class if method is None else f"{java_class}#{method}"
        if java_class not in java:
            out.append(f"{EXCLUDED.name} excludes {said}, but Java has no live test class {java_class}")
        elif method is None and file_for(java_class) in files:
            out.append(f"{EXCLUDED.name} excludes {java_class}, but {file_for(java_class)} is here")
        elif method is not None and method not in java[java_class]:
            out.append(f"{EXCLUDED.name} excludes {said}, but {java_class} has no test {method}")
        elif method is not None and named(method) in files.get(file_for(java_class), set()):
            out.append(f"{EXCLUDED.name} excludes {said}, but {file_for(java_class)} has {named(method)}")
        elif method is not None and (java_class, None) in excluded:
            out.append(f"{EXCLUDED.name} excludes {said} and the whole of {java_class}")
    for java_class, methods in java.items():
        if (java_class, None) in excluded:
            continue
        file = file_for(java_class)
        if file not in files:
            out.append(f"{java_class} has no tests/live/{file}")
            continue
        for method in methods:
            if named(method) not in files[file] and (java_class, method) not in excluded:
                out.append(f"{java_class}#{method} has no {named(method)} in tests/live/{file}")
        expected = {named(method) for method in methods}
        for extra in sorted(files[file] - expected):
            out.append(f"tests/live/{file} has {extra}, which isn't a test of {java_class}: rename it, or move it to a {_PYTHON_ONLY} file")
    for file in sorted(files):
        if file not in ported and not file.startswith(_PYTHON_ONLY):
            out.append(f"tests/live/{file} isn't a Java live test class's file: name it after one, or {_PYTHON_ONLY}….py if only this client has it")
    return out


def test_every_java_live_test_is_here_or_excluded_with_a_reason() -> None:
    java = java_live_tests()
    assert len(java) > 40 and sum(len(methods) for methods in java.values()) > 140, "the Java live tests weren't found"
    files = {path.name: python_tests(path) for path in LIVE.glob("test_*.py")}
    found = problems(java, files, exclusions(EXCLUDED.read_text(encoding="utf-8")))
    assert not found, "\n".join(found)


# The gate itself


JAVA_CLASS = """
class CubeServiceTm1Test {
	/** Not a test: @Test void inAComment() {} */
	@BeforeEach
	void setUp() {
	}

	@Test
	void reordersTheStorageOrder() {
	}

	// @Test void commentedOut() {}
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "cube", "view" })
	void opensASessionOnEvery401(String kind) throws Exception {
	}

	private void helper() {
	}
}
"""


def test_reads_a_java_classs_tests() -> None:
    assert java_tests(JAVA_CLASS) == ["reordersTheStorageOrder", "opensASessionOnEvery401"]


def test_names_files_and_tests_after_javas() -> None:
    assert file_for("CubeServiceTm1Test") == "test_cube_service.py"
    assert file_for("Tm1SessionBehaviorTest") == "test_tm1_session_behavior.py"
    assert file_for("OidcKeyAndSqlDataSourceTm1Test") == "test_oidc_key_and_sql_data_source.py"
    assert named("opensASessionOnEvery401") == "test_opens_a_session_on_every401"


def _check(files: dict[str, set[str]], excluded: str = "") -> list[str]:
    java = {"CubeServiceTm1Test": ["createsACube", "reordersTheStorageOrder"], "Tm1SessionBehaviorTest": ["expiresTheCookie"]}
    return problems(java, files, exclusions(excluded))


COMPLETE = {"test_cube_service.py": {"test_creates_a_cube", "test_reorders_the_storage_order"}, "test_tm1_session_behavior.py": {"test_expires_the_cookie"}}


def test_passes_when_every_class_and_test_is_here() -> None:
    assert _check(COMPLETE) == []
    assert _check({**COMPLETE, "test_python_only.py": {"test_the_generated_api"}}) == []


def test_fails_on_a_missing_file_or_test() -> None:
    (missing,) = _check({"test_cube_service.py": COMPLETE["test_cube_service.py"]})
    assert "Tm1SessionBehaviorTest has no tests/live/test_tm1_session_behavior.py" in missing
    (missing,) = _check({**COMPLETE, "test_cube_service.py": {"test_creates_a_cube"}})
    assert "CubeServiceTm1Test#reordersTheStorageOrder has no test_reorders_the_storage_order" in missing


def test_takes_an_exclusion_with_a_reason_for_a_test_or_a_class() -> None:
    files = {"test_cube_service.py": {"test_creates_a_cube"}}
    excluded = "# why\nCubeServiceTm1Test#reordersTheStorageOrder: this client has no storage order\nTm1SessionBehaviorTest: tests Java's connector\n"
    assert _check(files, excluded) == []
    try:
        exclusions("CubeServiceTm1Test#reordersTheStorageOrder\n")
    except AssertionError as e:
        assert "needs a reason" in str(e)
    else:
        raise AssertionError("an exclusion without a reason was accepted")


def test_fails_on_an_exclusion_java_doesnt_have_or_this_client_does() -> None:
    (stale,) = _check(COMPLETE, "GoneTm1Test: was removed\n")
    assert "Java has no live test class GoneTm1Test" in stale
    (stale,) = _check(COMPLETE, "CubeServiceTm1Test#renamedSince: no counterpart\n")
    assert "CubeServiceTm1Test has no test renamedSince" in stale
    (stale,) = _check(COMPLETE, "CubeServiceTm1Test#createsACube: no counterpart\n")
    assert "test_cube_service.py has test_creates_a_cube" in stale
    (stale,) = _check(COMPLETE, "Tm1SessionBehaviorTest: tests Java's connector\n")
    assert "test_tm1_session_behavior.py is here" in stale


def test_fails_on_a_test_or_a_file_java_doesnt_have() -> None:
    (extra,) = _check({**COMPLETE, "test_cube_service.py": {*COMPLETE["test_cube_service.py"], "test_only_here"}})
    assert "test_only_here, which isn't a test of CubeServiceTm1Test" in extra
    (extra,) = _check({**COMPLETE, "test_cube_live.py": {"test_creates_a_cube"}})
    assert "tests/live/test_cube_live.py isn't a Java live test class's file" in extra
