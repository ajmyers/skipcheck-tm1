# SPDX-License-Identifier: Apache-2.0

"""This package's tests are the Java module's, test for test, read from the Java sources
(java/tm1-testcontainers/src/test/java), as the client's test_java_unit_parity.py does for the
client. It's what keeps a fix made in one package from being left out of another: a behaviour change
starts with a Java test, and this fails until that test is here too.

* Every test (``@Test``, ``@ParameterizedTest``...) of a Java test class has a test of the same name
  somewhere in tests/, in snake case after ``test_`` (``usesTheZipSetDirectly`` is
  ``test_uses_the_zip_set_directly``);
* or coverage/unit-excluded.txt says why not: ``ClassName#methodName: reason``, or ``ClassName:
  reason`` for a whole class. A line naming a class or a test Java doesn't have, or a test this
  package has after all, fails too, so the list can't rot.

"Needs TM1" (or TM1 Web, Cognos or PAW) is no reason to exclude: those tests are ported with their
``requires_…`` marker, and skip at run time.

A test may be in any file, though each Java class has its own here or shares one with its
neighbours. Names are matched whatever their file, so where Java classes have a test of the same
name (``findsNothingWhenNothingIsSet``), one test here answers for all of them: the gate can't tell
which class's is missing. This reads sources only, so it runs without Docker or TM1.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "java" / "tm1-testcontainers" / "src" / "test" / "java"
TESTS = Path(__file__).resolve().parent
EXCLUDED = Path(__file__).resolve().parents[1] / "coverage" / "unit-excluded.txt"

# A test annotation, then the first method after it: JUnit's tests return nothing
_TEST = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestTemplate)\b.*?\bvoid\s+(\w+)\s*\(", re.S)


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def named(java_method: str) -> str:
    """The test of a Java test method."""
    return "test_" + snake(java_method)


def java_tests(source: str) -> list[str]:
    """The test methods of a Java test class, from its source."""
    without_comments = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    without_comments = re.sub(r"^\s*//.*$", "", without_comments, flags=re.M)
    return [m.group(1) for m in _TEST.finditer(without_comments)]


def java_unit_tests() -> dict[str, list[str]]:
    """Each Java test class of the module, with its test methods."""
    out: dict[str, list[str]] = {}
    for path in sorted(JAVA.rglob("*Test.java")):
        assert path.stem not in out, f"two Java classes named {path.stem}"
        tests = java_tests(path.read_text(encoding="utf-8"))
        if tests:
            out[path.stem] = tests
    return out


def python_tests(path: Path) -> set[str]:
    """The test functions of a test file."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    return {node.name for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_")}


def unit_tests_here() -> set[str]:
    """Every test function of tests/."""
    out: set[str] = set()
    for path in TESTS.rglob("test_*.py"):
        out |= python_tests(path)
    return out


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


def problems(java: dict[str, list[str]], here: set[str], excluded: dict[tuple[str, str | None], str]) -> list[str]:
    """What keeps the tests from being Java's: ``here`` is every test of this package."""
    out = []
    for (java_class, method), _reason in excluded.items():
        said = java_class if method is None else f"{java_class}#{method}"
        if java_class not in java:
            out.append(f"{EXCLUDED.name} excludes {said}, but Java has no test class {java_class}")
        elif method is not None and method not in java[java_class]:
            out.append(f"{EXCLUDED.name} excludes {said}, but {java_class} has no test {method}")
        elif method is not None and named(method) in here:
            out.append(f"{EXCLUDED.name} excludes {said}, but {named(method)} is here: take the line off")
        elif method is not None and (java_class, None) in excluded:
            out.append(f"{EXCLUDED.name} excludes {said} and the whole of {java_class}")
    for java_class, methods in java.items():
        if (java_class, None) in excluded:
            continue
        for method in methods:
            if named(method) not in here and (java_class, method) not in excluded:
                out.append(f"{java_class}#{method} has no {named(method)} here: port the test (and what it tests), or say why not in {EXCLUDED.name}")
    return out


def test_every_java_unit_test_is_here_or_excluded_with_a_reason() -> None:
    java = java_unit_tests()
    assert len(java) > 15 and sum(len(methods) for methods in java.values()) > 60, "the Java tests weren't found"
    found = problems(java, unit_tests_here(), exclusions(EXCLUDED.read_text(encoding="utf-8")))
    assert not found, "\n".join(found)


# The gate itself


JAVA_CLASS = """
class PawSourceTest {
	/** Not a test: @Test void inAComment() {} */
	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
	}

	@Test
	void usesTheZipSetDirectly() throws Exception {
	}

	// @Test void commentedOut() {}
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "zip", "folder" })
	void startsOnPort8010(String kind) throws Exception {
	}

	@Test
	@EnabledIfEnvironmentVariable(named = Tm1Source.HOME_DIR, matches = ".+")
	void fromAnInstalledFolder() {
	}

	private void helper() {
	}
}
"""


def test_reads_a_java_classs_tests() -> None:
    assert java_tests(JAVA_CLASS) == ["usesTheZipSetDirectly", "startsOnPort8010", "fromAnInstalledFolder"]


def test_names_tests_after_javas() -> None:
    assert named("usesTheZipSetDirectly") == "test_uses_the_zip_set_directly"
    assert named("tm1WebListsServersFromTheAdminServer") == "test_tm1_web_lists_servers_from_the_admin_server"
    assert named("fromIbmsBundleFile") == "test_from_ibms_bundle_file"
    assert named("startsOnPort8010") == "test_starts_on_port8010"


def test_reads_the_test_classes_and_the_example_tests_of_the_java_module() -> None:
    java = java_unit_tests()
    assert "usesTheZipSetDirectly" in java["PawSourceTest"]
    assert "logsOnThroughCognosAndCallsTm1" in java["PawCamSecurityTest"], "a test that needs Cognos and PAW is read too"
    assert "startsAThrowawayServer" in java["WithoutTheExtensionExampleTest"], "and those of the examples folder"
    assert "Tm1WebSession" not in java and "Tm1Rest" not in java, "helpers aren't test classes"


def test_reads_the_tests_of_this_package_whatever_their_file() -> None:
    here = unit_tests_here()
    assert "test_uses_the_zip_set_directly" in here
    assert test_reads_the_tests_of_this_package_whatever_their_file.__name__ in here


def _check(here: set[str], excluded: str = "") -> list[str]:
    java = {"PawSourceTest": ["usesTheZipSetDirectly", "refusesToStartWithoutTheLicense"], "SharedServerExampleTest": ["getsTheServerAsAParameter"]}
    return problems(java, here, exclusions(excluded))


COMPLETE = {"test_uses_the_zip_set_directly", "test_refuses_to_start_without_the_license", "test_gets_the_server_as_a_parameter"}


def test_passes_when_every_test_is_here_whatever_its_file() -> None:
    assert _check(COMPLETE) == []
    assert _check({*COMPLETE, "test_only_python_has_this"}) == []


def test_fails_on_a_java_test_that_isnt_here() -> None:
    (missing,) = _check(COMPLETE - {"test_uses_the_zip_set_directly"})
    assert "PawSourceTest#usesTheZipSetDirectly has no test_uses_the_zip_set_directly here" in missing


def test_takes_an_exclusion_with_a_reason_for_a_test_or_a_class() -> None:
    here = {"test_refuses_to_start_without_the_license"}
    excluded = "# why\nPawSourceTest#usesTheZipSetDirectly: this package takes no zip\nSharedServerExampleTest: tests JUnit's parameter injection\n"
    assert _check(here, excluded) == []


def test_refuses_an_exclusion_without_a_reason_or_given_twice() -> None:
    for text, why in [
        ("PawSourceTest#usesTheZipSetDirectly\n", "needs a reason"),
        ("PawSourceTest#usesTheZipSetDirectly:  \n", "needs a reason"),
        ("SharedServerExampleTest: one reason\nSharedServerExampleTest: another\n", "duplicate exclusion"),
    ]:
        try:
            exclusions(text)
        except AssertionError as e:
            assert why in str(e)
        else:
            raise AssertionError(f"{text!r} was accepted")


def test_fails_on_an_exclusion_java_doesnt_have_or_this_package_does() -> None:
    (stale,) = _check(COMPLETE, "GoneTest: was removed\n")
    assert "Java has no test class GoneTest" in stale
    (stale,) = _check(COMPLETE, "PawSourceTest#renamedSince: no counterpart\n")
    assert "PawSourceTest has no test renamedSince" in stale
    (stale,) = _check(COMPLETE, "PawSourceTest#usesTheZipSetDirectly: no counterpart\n")
    assert "test_uses_the_zip_set_directly is here" in stale
    (both,) = _check(
        COMPLETE - {"test_gets_the_server_as_a_parameter"},
        "SharedServerExampleTest: tests JUnit's parameter injection\nSharedServerExampleTest#getsTheServerAsAParameter: and again\n",
    )
    assert "and the whole of SharedServerExampleTest" in both
