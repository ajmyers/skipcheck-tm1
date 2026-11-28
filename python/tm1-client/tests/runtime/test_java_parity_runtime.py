# SPDX-License-Identifier: Apache-2.0

"""The runtime offers what the Java client's does, read from the Java sources: every public
method of the session, version, error, auth, OData and request classes has a Python
counterpart in snake case (a property for a no-argument accessor, a field for a record's
component). What's renamed, or has no meaning in Python, is listed below with why.

As tests/test_java_parity.py does for the services.
"""

from __future__ import annotations

import inspect
import re
from pathlib import Path
from typing import Any

import pytest

import skipcheck_tm1
import skipcheck_tm1.aio as aio
from skipcheck_tm1 import auth, errors, odata, version

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / "java" / "tm1-client" / "src" / "main" / "java" / "io" / "skipcheck" / "tm1" / "client"

SKIP_ALWAYS = {"toString", "equals", "hashCode", "compareTo"}

# Java member → Python name, where Python's keywords or naming called for another
RENAMED = {
    "Tm1Session.async": "async_every",  # `async` is a keyword
    "Filter.and": "and_",
    "Filter.or": "or_",
    "Filter.not": "not_",
    "Filter.in": "in_",
    "Filter.Variable.self": "self_",
    "ODataError.from": "from_json",  # `from` is a keyword
    "Tm1ErrorClassifier.Rule.message": "of_message",  # the record's field of that name holds the pattern
    "Tm1ErrorClassifier.Rule.code": "of_code",
    "Tm1SessionPool.Builder.scheduler": "clock",  # a clock to move in tests, rather than a Reactor scheduler
    "Tm1Session.Builder.webClient": "http_client",  # an httpx2 client, rather than Spring's
    "Tm1Session.Builder.connector": "http_client",
    "Tm1Client.Builder.webClient": "http_client",
    "Tm1Client.Builder.connector": "http_client",
    "Tm1Version.isAtLeast": "is_at_least",
}

# Java members with no Python counterpart, and why
NOT_IN_PYTHON = {
    "Tm1Session.Builder.jsonMapper": "the model is pydantic; there's no mapper to configure",
    "Tm1Client.Builder.jsonMapper": "the model is pydantic; there's no mapper to configure",
    "Tm1Session.Builder.observationRegistry": "Micrometer observations; the Python client logs to `skipcheck_tm1` instead",
    "Tm1Client.Builder.observationRegistry": "Micrometer observations; the Python client logs to `skipcheck_tm1` instead",
    "Tm1Capabilities.metadata": "the Python capabilities keep what checks need, not a CSDL document model",
    "Tm1Capabilities.of": "made by Tm1Capabilities.parse from $metadata, rather than from a parsed CSDL document",
    "Tm1Request.Bytes": "it wraps a reactive publisher of buffers; a body of bytes is `bytes` itself in Python",
}

# (Java file, nested class or None, Python class or module)
CLASSES: list[tuple[str, str | None, Any]] = [
    ("session/Tm1Session.java", None, aio.AsyncTm1Session),
    ("session/Tm1Session.java", "Builder", aio.AsyncTm1SessionBuilder),
    ("session/Tm1SessionBatch.java", None, aio.AsyncTm1SessionBatch),
    ("session/Tm1SessionPool.java", None, aio.AsyncTm1SessionPool),
    ("session/Tm1SessionPool.java", "Builder", aio.AsyncTm1SessionPoolBuilder),
    ("session/Tm1Timeouts.java", None, skipcheck_tm1.Tm1Timeouts),
    ("session/Tm1Timeouts.java", "Builder", skipcheck_tm1.Tm1Timeouts.Builder),
    ("session/Tm1Identity.java", None, skipcheck_tm1.Tm1Identity),
    ("Tm1Request.java", None, skipcheck_tm1.Tm1Request),
    ("Tm1Operation.java", None, skipcheck_tm1.Tm1Operation),
    ("Tm1Client.java", None, aio.AsyncTm1Client),
    ("Tm1Client.java", "Builder", aio.AsyncTm1ClientBuilder),
    ("Tm1Batch.java", None, aio.AsyncTm1Batch),
    ("odata/ODataQuery.java", None, odata.ODataQuery),
    ("odata/Filter.java", None, odata.Filter),
    ("odata/Filter.java", "Variable", odata.Filter.Variable),
    ("odata/ODataPath.java", None, odata.ODataPath),
    ("odata/ODataLiterals.java", None, odata.ODataLiterals),
    ("odata/ODataProperty.java", None, odata.ODataProperty),
    ("odata/ODataEncoding.java", None, odata.ODataEncoding),
    ("odata/ODataJson.java", None, odata.ODataJson),
    ("version/Tm1Version.java", None, version.Tm1Version),
    ("version/Tm1Capability.java", None, version.Tm1Capability),
    ("version/Tm1Capabilities.java", None, version.Tm1Capabilities),
    ("version/Tm1Dialect.java", None, version.Tm1Dialect),
    ("error/Tm1ErrorClassifier.java", None, errors.Tm1ErrorClassifier),
    ("error/Tm1ErrorClassifier.java", "Rule", errors.Rule),
    ("error/ODataError.java", None, errors.ODataError),
    ("error/Tm1ApiException.java", None, errors.Tm1ApiError),
    ("error/Tm1TransportException.java", None, errors.Tm1TransportError),
]


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    return re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s).lower()


def java_members(path: Path, nested: str | None) -> set[str]:
    """Public methods (and a record's components) of a class or one of its nested classes."""
    source = path.read_text(encoding="utf-8").replace("@Nullable ", "")
    outer = path.stem
    if nested is None:
        indent, body, owner = "\t", source, outer
    else:
        start = re.search(rf"(class|record|interface)\s+{nested}\b", source)
        assert start, f"{nested} in {path.name}"
        indent, body, owner = "\t\t", source[start.start() :], nested
    names = set(re.findall(rf"^{indent}public\s+(?:static\s+)?(?:default\s+)?(?:<[^>]+>\s+)?[\w.<>\[\]?, ]+?\s+(\w+)\s*\(", body, re.M))
    if nested is None:
        # an interface's methods have no `public`
        if re.search(rf"public\s+(?:sealed\s+)?interface\s+{outer}\b", source):
            names |= set(re.findall(rf"^{indent}(?:static\s+|default\s+)?[\w.<>\[\]?, ]+?\s+(\w+)\s*\([^)]*\)\s*[;{{]", body, re.M))
        record = re.search(rf"public\s+record\s+{outer}\s*\(([^)]*)\)", source, re.S)
    else:
        record = re.search(rf"record\s+{nested}\s*\(([^)]*)\)", body, re.S)
    if record:
        for component in record.group(1).split(","):
            parts = component.replace("@Nullable", "").split()
            if parts:
                names.add(parts[-1])
    return {n for n in names if n != owner and n not in ("if", "for", "while", "switch", "return", "new")}


def has(cls: Any, name: str) -> bool:
    if inspect.getattr_static(cls, name, None) is not None:
        return True
    fields = getattr(cls, "__dataclass_fields__", {})
    if name in fields:
        return True
    # A plain attribute set in __init__ (Tm1ApiError.status, Tm1TransportError.phase)
    init = getattr(cls, "__init__", None)
    try:
        return inspect.isfunction(init) and f"self.{name} =" in inspect.getsource(init)
    except OSError:  # a dataclass's generated __init__ has no source
        return False


@pytest.mark.parametrize("java,nested,python", CLASSES, ids=[f"{j.split('/')[-1][:-5]}{'.' + n if n else ''}" for j, n, _ in CLASSES])
def test_every_public_member_has_a_python_counterpart(java: str, nested: str | None, python: Any) -> None:
    owner = Path(java).stem + ("." + nested if nested else "")
    missing = []
    for name in sorted(java_members(JAVA / java, nested)):
        key = f"{owner}.{name}"
        if name in SKIP_ALWAYS or key in NOT_IN_PYTHON:
            continue
        # A nested type (ODataLiterals.Raw, Tm1ErrorClassifier.Rule) keeps its name
        py = RENAMED.get(key, name if name[0].isupper() else snake(name))
        if not has(python, py):
            missing.append(f"{name} → {py}")
    assert not missing, f"{owner} lacks: {missing}"


def test_the_authenticators_are_javas() -> None:
    """Every static factory of Java's ``Tm1Authenticator`` is a function of ``auth``. Java's
    overloads of one name are one function here, which tells them apart by what it's given:
    ``camPassport(String)`` and ``camPassport(Supplier)`` are both ``cam_passport``."""
    source = (JAVA / "auth" / "Tm1Authenticator.java").read_text(encoding="utf-8")
    factories = re.findall(r"^\tstatic Tm1Authenticator (\w+)\(([^)]*)\)", source, re.M)
    assert {name for name, _ in factories} >= {"basic", "bearer", "application", "camNamespace", "camPassport", "cognosLogon", "cognosIdToken"}, (
        "the factories weren't found"
    )
    for name, _ in factories:
        assert callable(getattr(auth, snake(name), None)), f"{name} → {snake(name)}"
    given = sorted(parameters.split()[0] for name, parameters in factories if name == "camPassport")
    assert given == ["String", "Supplier<Mono<String>>"], "Java's camPassport overloads changed: cam_passport takes each of them"
    bearer_given = sorted(parameters.split()[0] for name, parameters in factories if name == "bearer")
    assert bearer_given == ["String", "Supplier<Mono<String>>"], "Java's bearer overloads changed: bearer takes each of them"
    assert auth.cam_passport("p").authorization() == "CAMPassport p"
    assert auth.cam_passport(lambda: "p").authorization() == "CAMPassport p"
    assert auth.bearer("t").authorization() == "Bearer t"
    assert auth.bearer(lambda: "t").authorization() == "Bearer t"
    basic = auth.basic("a", "b")
    assert callable(basic.authorization) and callable(basic.describe)


def test_the_sync_flavor_has_the_same_runtime() -> None:
    pairs = [
        (aio.AsyncTm1Session, skipcheck_tm1.Tm1Session),
        (aio.AsyncTm1SessionBuilder, skipcheck_tm1.Tm1SessionBuilder),
        (aio.AsyncTm1SessionBatch, skipcheck_tm1.Tm1SessionBatch),
        (aio.AsyncTm1SessionPool, skipcheck_tm1.Tm1SessionPool),
        (aio.AsyncTm1SessionPoolBuilder, skipcheck_tm1.Tm1SessionPoolBuilder),
        (aio.AsyncTm1Client, skipcheck_tm1.Tm1Client),
        (aio.AsyncTm1Batch, skipcheck_tm1.Tm1Batch),
    ]
    for a, s in pairs:
        public = lambda c: {n for n in dir(c) if not n.startswith("_")}  # noqa: E731
        assert public(a) == public(s), a.__name__


def test_listed_exceptions_are_still_in_java() -> None:
    """A rename or an exclusion for a member Java no longer has is stale."""
    by_owner: dict[str, set[str]] = {}
    for java, nested, _ in CLASSES:
        owner = Path(java).stem + ("." + nested if nested else "")
        by_owner[owner] = java_members(JAVA / java, nested)
    for key in list(RENAMED) + list(NOT_IN_PYTHON):
        owner, _, name = key.rpartition(".")
        assert name in by_owner.get(owner, set()), f"{key} isn't in the Java sources"
