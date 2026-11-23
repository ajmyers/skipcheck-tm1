# SPDX-License-Identifier: Apache-2.0

"""The runtime's unit tests are the Java client's, test for test, read from the Java sources
(java/tm1-client/src/test/java). It's what keeps a fix made in one client from being left out of
another: a behaviour change starts with a Java test, and this fails until that test is here too.

* Every unit test of a Java test class has a test of the same name somewhere in tests/ outside
  tests/live, in snake case after ``test_`` (``followsANextLinkInAResult`` is
  ``test_follows_a_next_link_in_a_result``);
* or coverage/unit-excluded.txt says why not: ``ClassName#methodName: reason``, or ``ClassName:
  reason`` for a whole class. A line naming a class or a test Java doesn't have, or a test this
  client has after all, fails too, so the list can't rot.

Two kinds of Java test class are someone else's to check, and aren't read here: the live tests
(``*Tm1Test``, ``*BehaviorTest``: tests/test_java_live_parity.py), and the service tests that record
their calls (``new Conformance()``: every client replays the recordings in spec/conformance).

Unlike the live gate, a test may be in any file: the unit tests here are grouped by what they test,
not by Java's classes. This reads sources only.
"""

from __future__ import annotations

from pathlib import Path

from .test_java_live_parity import exclusions, java_tests, named, python_tests

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "java" / "tm1-client" / "src" / "test" / "java"
TESTS = Path(__file__).resolve().parent
EXCLUDED = Path(__file__).resolve().parents[1] / "coverage" / "unit-excluded.txt"

_LIVE = ("Tm1Test", "BehaviorTest")
_RECORDS = "new Conformance("


def java_unit_tests() -> dict[str, list[str]]:
    """Each Java unit test class that is neither live nor recorded, with its test methods."""
    out: dict[str, list[str]] = {}
    for path in sorted(JAVA.rglob("*Test.java")):
        source = path.read_text(encoding="utf-8")
        if path.stem.endswith(_LIVE) or _RECORDS in source:
            continue
        assert path.stem not in out, f"two Java classes named {path.stem}"
        tests = java_tests(source)
        if tests:
            out[path.stem] = tests
    return out


def unit_tests_here() -> set[str]:
    """Every test function of tests/, outside tests/live."""
    out: set[str] = set()
    for path in TESTS.rglob("test_*.py"):
        if "live" not in path.relative_to(TESTS).parts:
            out |= python_tests(path)
    return out


def problems(java: dict[str, list[str]], here: set[str], excluded: dict[tuple[str, str | None], str]) -> list[str]:
    """What keeps the unit tests from being Java's: ``here`` is every unit test of this client."""
    out = []
    for (java_class, method), _reason in excluded.items():
        said = java_class if method is None else f"{java_class}#{method}"
        if java_class not in java:
            out.append(f"{EXCLUDED.name} excludes {said}, but Java has no unit test class {java_class}")
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
    assert len(java) > 20 and sum(len(methods) for methods in java.values()) > 150, "the Java unit tests weren't found"
    found = problems(java, unit_tests_here(), exclusions(EXCLUDED.read_text(encoding="utf-8")))
    assert not found, "\n".join(found)


# The gate itself


def test_leaves_out_the_live_tests_and_those_that_record_conformance_calls() -> None:
    java = java_unit_tests()
    assert [c for c in java if c.endswith(_LIVE)] == []
    assert "CubeServiceTest" not in java, "it records conformance calls"
    assert "Tm1SessionTest" in java


def _check(here: set[str], excluded: str = "") -> list[str]:
    java = {"Tm1SessionBatchTest": ["splitsABigQueue", "refusesCallsAfterItRan"], "MetadataSnapshotTest": ["readsTheSnapshot"]}
    return problems(java, here, exclusions(excluded))


COMPLETE = {"test_splits_a_big_queue", "test_refuses_calls_after_it_ran", "test_reads_the_snapshot"}


def test_passes_when_every_test_is_here_whatever_its_file() -> None:
    assert _check(COMPLETE) == []
    assert _check({*COMPLETE, "test_only_python_has_this"}) == []


def test_fails_on_a_java_test_that_isnt_here() -> None:
    (missing,) = _check(COMPLETE - {"test_splits_a_big_queue"})
    assert "Tm1SessionBatchTest#splitsABigQueue has no test_splits_a_big_queue here" in missing


def test_takes_an_exclusion_with_a_reason_for_a_test_or_a_class() -> None:
    here = {"test_refuses_calls_after_it_ran"}
    excluded = "Tm1SessionBatchTest#splitsABigQueue: this client sends a batch whole\nMetadataSnapshotTest: tests Java's snapshot\n"
    assert _check(here, excluded) == []


def test_fails_on_an_exclusion_java_doesnt_have_or_this_client_does() -> None:
    (stale,) = _check(COMPLETE, "GoneTest: was removed\n")
    assert "Java has no unit test class GoneTest" in stale
    (stale,) = _check(COMPLETE, "Tm1SessionBatchTest#renamedSince: no counterpart\n")
    assert "Tm1SessionBatchTest has no test renamedSince" in stale
    (stale,) = _check(COMPLETE, "Tm1SessionBatchTest#splitsABigQueue: no counterpart\n")
    assert "test_splits_a_big_queue is here" in stale
    (both,) = _check(COMPLETE - {"test_reads_the_snapshot"}, "MetadataSnapshotTest: tests Java's snapshot\nMetadataSnapshotTest#readsTheSnapshot: and again\n")
    assert "and the whole of MetadataSnapshotTest" in both
