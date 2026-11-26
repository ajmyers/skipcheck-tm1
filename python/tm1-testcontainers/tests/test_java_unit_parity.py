# SPDX-License-Identifier: Apache-2.0

"""This package's tests are the Java module's, test for test, read from the Java sources
(java/tm1-testcontainers/src/test/java), as the client's test_java_unit_parity.py does for the
client. It's what keeps a fix made in one package from being left out of another: a behaviour change
starts with a Java test, and this fails until that test is here too.

* Every test (``@Test``, ``@ParameterizedTest``...) of a Java test class has a test of the same name
  somewhere in tests/, in snake case after ``test_`` (``usesTheZipSetDirectly`` is
  ``test_uses_the_zip_set_directly``);
* or coverage/unit-excluded.txt says why not: ``ClassName#methodName: reason``, or ``ClassName:
  reason`` for a whole class. A line naming a class or a test Java doesn't have, a test this
  package has after all, or a class whose every test is here, fails too, so the list can't rot.

"Needs TM1" (or TM1 Web, Cognos or PAW) is no reason to exclude: those tests are ported with their
``requires_…`` marker, and skip at run time.

A test may be in any file, though each Java class has its own here or shares one with its
neighbours. Names are matched whatever their file, and counted: where several Java classes have a
test of the same name (``findsNothingWhenNothingIsSet``), this package has as many tests of that
name, in as many files (a module holds one function of a name). The gate can't tell which class's is
missing, so it names them all. A test that never runs (an unconditional ``@pytest.mark.skip``) isn't
counted; one that skips on a condition (``skipif``, the ``requires_…`` markers) is. This reads sources
only, so it runs without Docker or TM1.
"""

from __future__ import annotations

import ast
import re
from collections import Counter
from collections.abc import Iterable
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "java" / "tm1-testcontainers" / "src" / "test" / "java"
TESTS = Path(__file__).resolve().parent
EXCLUDED = Path(__file__).resolve().parents[1] / "coverage" / "unit-excluded.txt"

# A test annotation, then (lazily) the first method after it, whatever comes between: the
# annotation's arguments, other annotations, modifiers, line breaks. JUnit's tests return nothing
_TEST = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestTemplate)\b.*?\bvoid\s+(\w+)\s*\(", re.S)

# Java's literals and comments, the literals first: a /* or // inside one opens no comment
_LITERAL_OR_COMMENT = re.compile(
    r"""
      (?P<literal> \"\"\" .*? \"\"\"          # a text block
                 | " (?: \\. | [^"\\\n] )* "  # a string
                 | ' (?: \\. | [^'\\\n] )* '  # a char
      )
    | (?P<comment> /\* .*? \*/ | //[^\n]* )
    """,
    re.S | re.X,
)


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def named(java_method: str) -> str:
    """The test of a Java test method."""
    return "test_" + snake(java_method)


def java_tests(source: str) -> list[str]:
    """The test methods of a Java test class, from its source."""
    return [m.group(1) for m in _TEST.finditer(without_comments(source))]


def without_comments(source: str) -> str:
    """Java source without its comments, and with its string and char literals emptied: neither
    holds an annotation or a method, and an @Test or a void inside one mustn't read as one."""

    def emptied(match: re.Match[str]) -> str:
        if match.group("comment") is not None:
            return " "  # so what a comment separated stays separate
        return '""' if match.group().startswith('"') else "' '"

    return _LITERAL_OR_COMMENT.sub(emptied, source)


def java_unit_tests() -> dict[str, list[str]]:
    """Each Java test class of the module, with its test methods."""
    out: dict[str, list[str]] = {}
    for path in sorted(JAVA.rglob("*Test.java")):
        assert path.stem not in out, f"two Java classes named {path.stem}"
        tests = java_tests(path.read_text(encoding="utf-8"))
        if tests:
            out[path.stem] = tests
    return out


def _never_runs(decorator: ast.expr) -> bool:
    """Whether a decorator is an unconditional skip: pytest.mark.skip, with or without a reason."""
    mark = decorator.func if isinstance(decorator, ast.Call) else decorator
    return (isinstance(mark, ast.Attribute) and mark.attr == "skip") or (isinstance(mark, ast.Name) and mark.id == "skip")


def _marks(value: ast.expr) -> list[ast.expr]:
    return list(value.elts) if isinstance(value, (ast.List, ast.Tuple)) else [value]


def python_tests(source: str) -> set[str]:
    """The test functions of a test file that can run: not those skipped unconditionally, by their
    own decorator or the module's pytestmark."""
    tree = ast.parse(source)
    for node in tree.body:
        targets = node.targets if isinstance(node, ast.Assign) else [node.target] if isinstance(node, ast.AnnAssign) else []
        if any(isinstance(target, ast.Name) and target.id == "pytestmark" for target in targets):
            if node.value is not None and any(_never_runs(mark) for mark in _marks(node.value)):
                return set()
    return {
        node.name
        for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_") and not any(_never_runs(d) for d in node.decorator_list)
    }


def unit_tests_here() -> Counter[str]:
    """Every test function of tests/, with the number of files that have one of that name."""
    out: Counter[str] = Counter()
    for path in TESTS.rglob("test_*.py"):
        out.update(python_tests(path.read_text(encoding="utf-8")))
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


def problems(java: dict[str, list[str]], here: Iterable[str], excluded: dict[tuple[str, str | None], str]) -> list[str]:
    """What keeps the tests from being Java's: ``here`` is every test of this package, a name as many
    times as there are tests of it (a Counter, or the names one by one)."""
    have = Counter(here)
    # The Java classes that want a test of each name: those that have one and don't exclude it
    wanting: dict[str, list[str]] = {}
    for java_class, methods in java.items():
        for method in methods:
            if (java_class, None) not in excluded and (java_class, method) not in excluded:
                wanting.setdefault(method, []).append(java_class)

    def spare(method: str) -> bool:
        """Whether there's a test of the name beyond those the classes that want one account for:
        the excluded class's own."""
        return have[named(method)] > len(wanting.get(method, []))

    out = []
    for (java_class, method), _reason in excluded.items():
        said = java_class if method is None else f"{java_class}#{method}"
        if java_class not in java:
            out.append(f"{EXCLUDED.name} excludes {said}, but Java has no test class {java_class}")
        elif method is None and all(spare(each) for each in java[java_class]):
            out.append(f"{EXCLUDED.name} excludes {said}, but every test of {java_class} is here: take the line off")
        elif method is not None and method not in java[java_class]:
            out.append(f"{EXCLUDED.name} excludes {said}, but {java_class} has no test {method}")
        elif method is not None and (java_class, None) in excluded:
            out.append(f"{EXCLUDED.name} excludes {said} and the whole of {java_class}")
        elif method is not None and spare(method):
            out.append(f"{EXCLUDED.name} excludes {said}, but {named(method)} is here: take the line off")
    for method, classes in wanting.items():
        count = have[named(method)]
        if count >= len(classes):
            continue
        if len(classes) == 1:
            out.append(f"{classes[0]}#{method} has no {named(method)} here: port the test (and what it tests), or say why not in {EXCLUDED.name}")
        else:
            out.append(
                f"{', '.join(classes)} each have a test {method}, so {len(classes)} tests named {named(method)} belong here, in different files, "
                f"and {count} {'is' if count == 1 else 'are'} here: port the missing ones (and what they test), or say why not in {EXCLUDED.name}"
            )
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


def _in_a_class(body: str) -> list[str]:
    return java_tests("class SomeTest {\n" + body + "\n}\n")


def test_reads_a_test_with_a_comment_after_its_annotation() -> None:
    assert _in_a_class("@Test // needs Docker\nvoid startsAServer() {\n}") == ["startsAServer"]
    assert _in_a_class("@Test /* needs Docker */ void startsAServer() {\n}") == ["startsAServer"]


def test_reads_a_test_with_a_comment_line_between_its_annotation_and_the_method() -> None:
    assert _in_a_class("@Test\n// void notThis() {}\n\t// nor @Test void this() {}\nvoid startsAServer() {\n}") == ["startsAServer"]


def test_reads_a_parameterized_test_with_parentheses_in_its_name() -> None:
    body = '@ParameterizedTest(name = "{0} (legacy)")\n@ValueSource(strings = { "a (b)", "c" })\nvoid startsOn(String kind) {\n}'
    assert _in_a_class(body) == ["startsOn"]


def test_reads_a_test_whatever_its_modifiers() -> None:
    assert _in_a_class("@Test\nprotected void startsAServer() {\n}") == ["startsAServer"]
    assert _in_a_class("@Test\npublic final void startsAServer() throws Exception {\n}") == ["startsAServer"]
    assert _in_a_class("@Test\nstatic <T extends Comparable<T>> void\n\tstartsAServer() {\n}") == ["startsAServer"]


def test_reads_a_test_with_nested_annotations() -> None:
    assert _in_a_class('@Test\n@Tags({ @Tag("slow"), @Tag("docker") })\nvoid startsAServer() {\n}') == ["startsAServer"]
    assert _in_a_class('@Tags({ @Tag("slow") })\n@Test\nvoid startsAServer() {\n}') == ["startsAServer"]


def test_reads_a_repeated_test_and_a_test_template() -> None:
    assert _in_a_class("@RepeatedTest(3)\nvoid startsAServer() {\n}\n\n@TestTemplate\nvoid stopsIt() {\n}") == ["startsAServer", "stopsIt"]


def test_doesnt_take_a_method_for_a_test_because_its_javadoc_mentions_one() -> None:
    body = "/** Runs before each {@code @Test} of the class. */\n@BeforeEach\nvoid setUp() {\n}\n\n@Test\nvoid startsAServer() {\n}"
    assert _in_a_class(body) == ["startsAServer"]
    assert _in_a_class("/** Runs before each {@code @Test} of the class. */\n@BeforeEach\nvoid setUp() {\n}") == []
    assert _in_a_class("// Before every @Test\n@BeforeEach\nvoid setUp() {\n}") == []


def test_doesnt_read_a_commented_out_test() -> None:
    assert _in_a_class("// @Test void x() {}") == []
    assert _in_a_class("\t// @Test\n\t// void x() {\n\t// }\n") == []
    assert _in_a_class("/* @Test void x() {} */") == []
    assert _in_a_class("int port = 1; // @Test void x() {}") == []


def test_doesnt_take_a_string_for_a_comment() -> None:
    body = 'static final String GLOB = "samples/*";\nstatic final String URL = "http://localhost";\n\n@Test\nvoid startsAServer() {\n}\n\nstatic final String END = "*/";'
    assert _in_a_class(body) == ["startsAServer"]
    quoted = 'static final char QUOTE = \'"\';\nstatic final String ESCAPED = "a \\" /* b";\n\n@Test\nvoid startsAServer() {\n\tString s = "*/";\n}'
    assert _in_a_class(quoted) == ["startsAServer"]
    block = 'static final String TEXT = """\n\t// not a comment, "/*" neither\n\t""";\n\n@Test\nvoid startsAServer() {\n}'
    assert _in_a_class(block) == ["startsAServer"]


def test_doesnt_read_a_test_out_of_a_string() -> None:
    assert _in_a_class('static final String SOURCE = "@Test void x() {}";') == []
    assert _in_a_class('static final String SOURCE = "@Test";\n\nvoid helper() {\n}') == []


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


def test_doesnt_count_a_test_that_never_runs() -> None:
    source = (
        "import pytest\n\n"
        "@pytest.mark.skip\ndef test_skipped() -> None: ...\n\n"
        "@pytest.mark.skip(reason='not ported yet')\ndef test_skipped_with_a_reason() -> None: ...\n\n"
        "@requires_tm1\n@pytest.mark.skip('later')\nasync def test_skipped_among_other_markers() -> None: ...\n\n"
        "def test_runs() -> None: ...\n\n"
        "def helper() -> None: ...\n"
    )
    assert python_tests(source) == {"test_runs"}


def test_counts_a_test_that_skips_on_a_condition() -> None:
    source = (
        "import pytest\n\n"
        "@pytest.mark.skipif(not docker_available(), reason='needs Docker')\ndef test_needs_docker() -> None: ...\n\n"
        "@requires_tm1\ndef test_needs_tm1(tm1_server) -> None: ...\n\n"
        "@requires_paw()\n@pytest.mark.parametrize('kind', ['zip', 'folder'])\ndef test_needs_paw(kind) -> None: ...\n\n"
        "def test_skips_itself() -> None:\n    pytest.skip('no sample here')\n"
    )
    assert python_tests(source) == {"test_needs_docker", "test_needs_tm1", "test_needs_paw", "test_skips_itself"}


def test_doesnt_count_the_tests_of_a_module_that_never_runs() -> None:
    assert python_tests("import pytest\n\npytestmark = pytest.mark.skip(reason='later')\n\ndef test_a() -> None: ...\n") == set()
    assert python_tests("import pytest\n\npytestmark = [requires_tm1, pytest.mark.skip]\n\ndef test_a() -> None: ...\n") == set()
    assert python_tests("import pytest\n\npytestmark = [requires_tm1, pytest.mark.skipif(True, reason='x')]\n\ndef test_a() -> None: ...\n") == {"test_a"}


def _check(here: Iterable[str], excluded: str = "") -> list[str]:
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


def test_fails_on_a_whole_class_exclusion_when_every_test_of_the_class_is_here() -> None:
    (stale,) = _check(COMPLETE, "SharedServerExampleTest: tests JUnit's parameter injection\n")
    assert "excludes SharedServerExampleTest, but every test of SharedServerExampleTest is here: take the line off" in stale
    (stale,) = _check(COMPLETE, "PawSourceTest: this package has no PAW\n")
    assert "every test of PawSourceTest is here" in stale
    assert _check(COMPLETE - {"test_uses_the_zip_set_directly"}, "PawSourceTest: this package has no PAW\n") == [], (
        "one of its tests is missing: the line stands"
    )


SHARED = {
    "CognosSourceTest": ["findsNothingWhenNothingIsSet"],
    "PawSourceTest": ["findsNothingWhenNothingIsSet", "usesTheZipSetDirectly"],
    "Tm1SourceTest": ["findsNothingWhenNothingIsSet"],
}


def _check_shared(here: list[str], excluded: str = "") -> list[str]:
    return problems(SHARED, here, exclusions(excluded))


def test_wants_a_test_for_each_java_class_that_has_one_of_the_name() -> None:
    nothing = "test_finds_nothing_when_nothing_is_set"
    assert _check_shared([nothing, nothing, nothing, "test_uses_the_zip_set_directly"]) == []
    (missing,) = _check_shared([nothing, nothing, "test_uses_the_zip_set_directly"])
    assert "CognosSourceTest, PawSourceTest, Tm1SourceTest each have a test findsNothingWhenNothingIsSet" in missing
    assert f"3 tests named {nothing} belong here, in different files, and 2 are here" in missing
    (missing,) = _check_shared([nothing, "test_uses_the_zip_set_directly"])
    assert "and 1 is here" in missing
    (missing,) = _check_shared(["test_uses_the_zip_set_directly"])
    assert "and 0 are here" in missing


def test_counts_an_excluded_class_out_of_a_shared_name() -> None:
    nothing = "test_finds_nothing_when_nothing_is_set"
    one_test = "CognosSourceTest#findsNothingWhenNothingIsSet: this package has no Cognos\n"
    assert _check_shared([nothing, nothing, "test_uses_the_zip_set_directly"], one_test) == [], "two classes want it, and two are here"
    (missing,) = _check_shared([nothing, "test_uses_the_zip_set_directly"], one_test)
    assert "PawSourceTest, Tm1SourceTest each have a test findsNothingWhenNothingIsSet" in missing and "and 1 is here" in missing
    (stale,) = _check_shared([nothing, nothing, nothing, "test_uses_the_zip_set_directly"], one_test)
    assert f"excludes CognosSourceTest#findsNothingWhenNothingIsSet, but {nothing} is here" in stale, "a third is the excluded class's own"

    whole_class = "CognosSourceTest: this package has no Cognos\n"
    assert _check_shared([nothing, nothing, "test_uses_the_zip_set_directly"], whole_class) == []
    (stale,) = _check_shared([nothing, nothing, nothing, "test_uses_the_zip_set_directly"], whole_class)
    assert "every test of CognosSourceTest is here" in stale


def test_counts_a_name_once_for_each_file_of_this_package_that_has_it() -> None:
    here = unit_tests_here()
    assert here["test_finds_nothing_when_nothing_is_set"] == 5, "the TM1, TM1 v12, TM1 Web, Cognos and PAW sources' tests"
    assert here["test_uses_the_zip_set_directly"] == 1
    assert here["test_no_such_test"] == 0
