# SPDX-License-Identifier: Apache-2.0

"""Ported from the Java client's error/Tm1ErrorsTest, odata/ODataQueryTest, FilterTest and
ODataPathTest, version/Tm1VersionTest and Tm1DialectTest, session/Tm1TimeoutsTest,
auth/Tm1AuthenticatorTest, Tm1RequestTest and Tm1OperationTest, and metadata/OperationCatalogTest."""

from __future__ import annotations

import base64
import math
import random
import re
from decimal import Decimal
from urllib.parse import unquote

import pytest

from skipcheck_tm1 import (
    Filter,
    ODataPath,
    ODataProperty,
    ODataQuery,
    Tm1Capability,
    Tm1Dialect,
    Tm1Operation,
    Tm1Request,
    Tm1Timeouts,
    Tm1Version,
    auth,
)
from skipcheck_tm1.errors import (
    ODataError,
    Rule,
    Tm1ApiError,
    Tm1AuthenticationError,
    Tm1BadRequestError,
    Tm1ConflictError,
    Tm1ErrorClassifier,
    Tm1ErrorKind,
    Tm1ForbiddenError,
    Tm1NotFoundError,
    Tm1NotImplementedError,
    Tm1PreconditionFailedError,
    Tm1ProtocolError,
    Tm1ServerError,
    Tm1TransportError,
)
from skipcheck_tm1.model import Tm1Operations
from skipcheck_tm1.odata import Alias, ODataLiterals, Raw
from skipcheck_tm1.version import Tm1Capabilities

from ..conftest import SNAPSHOTS

# Tm1ErrorsTest


def error(doc: object) -> ODataError | None:
    return ODataError.from_json(doc)


def msg(message: str) -> dict[str, object]:
    return {"error": {"code": "278", "message": message}}


def api(status: int, body: object = None) -> Tm1ApiError:
    return Tm1ApiError.of(status, None if body is None else error(body), "GET", "Cubes('NoSuchCube')", Tm1ErrorClassifier.defaults())


def test_reads_the_odata_error_body() -> None:
    e = error({"error": {"code": "278", "message": "Boom", "target": "Name", "details": [{"code": "1", "message": "inner"}]}})
    assert e is not None
    assert (e.code, e.message, e.target) == ("278", "Boom", "Name")
    assert [d.message for d in e.details] == ["inner"]
    assert error({"value": []}) is None
    assert error([]) is None


def test_reads_tm112_auth_error_bodies() -> None:
    v12 = error({"error": "invalid_token", "error_details": "Unable to validate authorization header"})
    assert v12 is not None
    assert (v12.code, v12.message) == ("invalid_token", "Unable to validate authorization header")

    oauth = error({"error": "invalid_client", "error_description": "Bad credentials"})
    assert oauth is not None
    assert (oauth.code, oauth.message) == ("invalid_client", "Bad credentials")

    empty_details = error({"error": "invalid_token", "error_details": "", "error_description": "Token expired"})
    assert empty_details is not None
    assert empty_details.message == "Token expired"

    object_details = error({"error": "invalid_token", "error_details": {"reason": "expired"}})
    assert object_details is not None
    assert (object_details.code, object_details.message) == ("invalid_token", '{"reason":"expired"}')

    code_only = error({"error": "invalid_token"})
    assert code_only is not None
    assert (code_only.code, code_only.message) == ("invalid_token", "")

    assert error({"error": ""}) is None

    auth_err = api(401, {"error": "invalid_token", "error_details": "Bad token"})
    assert isinstance(auth_err, Tm1AuthenticationError)
    assert "Bad token" in str(auth_err) and "[code invalid_token]" in str(auth_err)


def test_reads_and_classifies_plain_text_errors() -> None:
    unknown = ODataError.from_text("Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n")
    assert unknown is not None
    assert unknown.message == "The user specified in the impersonation request does not exist."
    assert unknown.code == ""
    classifier = Tm1ErrorClassifier.defaults()
    assert classifier.classify(400, unknown) is Tm1ErrorKind.UNKNOWN_USER
    assert (
        classifier.classify(403, ODataError.from_text("Error: Forbidden\r\nYou have insufficient rights to impersonate the user specified!\r\n"))
        is Tm1ErrorKind.IMPERSONATION_REFUSED
    )
    assert (
        classifier.classify(
            403,
            ODataError.from_text("Error: Forbidden\r\nYou need to be a (data-)administrator to be able to impersonate another user.\r\n"),
        )
        is Tm1ErrorKind.IMPERSONATION_REFUSED
    )
    assert ODataError.from_text("Error: Unauthorized\r\n") is None
    assert len(ODataError.from_text("x" * 5000).message) == 1001  # type: ignore[union-attr]


def test_classifies_captured_responses() -> None:
    nope = api(404, msg("'NoSuchCube' can not be found in collection of type 'Cube'."))
    assert isinstance(nope, Tm1NotFoundError) and nope.kind is Tm1ErrorKind.NOT_FOUND
    assert str(nope) == ("TM1 returned 404: 'NoSuchCube' can not be found in collection of type 'Cube'. [code 278] (GET Cubes('NoSuchCube'))")
    assert isinstance(api(501, msg("$search not implemented.")), Tm1NotImplementedError)
    assert isinstance(api(412, msg("")), Tm1PreconditionFailedError)
    multipart = api(400, msg("The content type specified is not supported. Please use JSON (application/json) as the content type."))
    assert isinstance(multipart, Tm1BadRequestError) and multipart.kind is Tm1ErrorKind.OTHER


def test_classifies_known_messages() -> None:
    assert api(403, msg("You have insufficient rights to impersonate the user specified!")).kind is Tm1ErrorKind.IMPERSONATION_REFUSED
    assert api(400, msg("The user specified in the impersonation request does not exist")).kind is Tm1ErrorKind.UNKNOWN_USER
    assert api(400, msg("TM1UserException: Cancel")).kind is Tm1ErrorKind.CANCELLED
    assert api(400, msg("DataSpreadFailed")).kind is Tm1ErrorKind.DATA_SPREAD_FAILED
    assert (
        api(400, {"error": {"code": "248", "message": "Invalid element unique name specified in dimensional context"}}).kind is Tm1ErrorKind.INVALID_UNIQUE_NAME
    )
    assert isinstance(api(400, msg("Dimension 'Region' already exists")), Tm1ConflictError)
    assert isinstance(api(400, msg("ObjectNotFound")), Tm1NotFoundError)
    assert api(400, msg("Object is locked by another user")).kind is Tm1ErrorKind.LOCKED
    assert api(400, msg("Subset is used by a view and cannot be deleted")).kind is Tm1ErrorKind.IN_USE
    # "Blocked" isn't "locked"
    assert api(400, msg("Blocked")).kind is Tm1ErrorKind.OTHER


def test_falls_back_on_the_status() -> None:
    assert isinstance(api(401), Tm1AuthenticationError)
    assert isinstance(api(403), Tm1ForbiddenError)
    assert isinstance(api(409), Tm1ConflictError)
    assert isinstance(api(428), Tm1PreconditionFailedError)
    assert isinstance(api(503), Tm1ServerError)
    assert isinstance(api(418), Tm1BadRequestError)
    assert type(Tm1ApiError.of(302, None, "GET", "x", Tm1ErrorClassifier.defaults())) is Tm1ApiError


def test_lets_callers_add_rules() -> None:
    custom = Tm1ErrorClassifier.defaults().with_rule(Rule.of_message("frozen", Tm1ErrorKind.LOCKED))
    assert custom.classify(400, error(msg("Cube is frozen"))) is Tm1ErrorKind.LOCKED
    assert Tm1ErrorClassifier.defaults().classify(400, error(msg("Cube is frozen"))) is Tm1ErrorKind.OTHER
    with pytest.raises(ValueError):
        Rule(None, None, None, Tm1ErrorKind.OTHER)


def test_says_whether_a_retry_could_help_and_whether_the_request_may_have_applied() -> None:
    assert api(503).is_retryable() and api(503).may_have_applied()
    assert not api(400).is_retryable() and not api(400).may_have_applied()
    assert not api(501).may_have_applied()
    connect = Tm1TransportError(Tm1TransportError.Phase.CONNECT, False, "POST", "x")
    assert connect.is_retryable() and not connect.may_have_applied()
    post_timed_out = Tm1TransportError(Tm1TransportError.Phase.RESPONSE, False, "POST", "x")
    assert not post_timed_out.is_retryable() and post_timed_out.may_have_applied()
    assert Tm1TransportError(Tm1TransportError.Phase.RESPONSE, True, "GET", "x").is_retryable()
    protocol = Tm1ProtocolError("bad", None, "GET", "x")
    assert not protocol.is_retryable() and protocol.may_have_applied()


# ODataQueryTest


def parse(query: str) -> dict[str, str]:
    out = {}
    for part in query.split("&"):
        name, _, value = part.partition("=")
        out[unquote(name)] = unquote(value)
    return out


def test_is_empty_by_default() -> None:
    assert ODataQuery.none().to_query_string() == ""
    assert ODataQuery.none().is_empty()


def test_writes_options_in_a_fixed_order() -> None:
    query = ODataQuery.none().top(10).with_count().order_by("Name").skip(5).filter(Filter.raw("Level eq 0")).expand("Hierarchies").select("Name", "Type")
    assert str(query) == "$select=Name,Type&$expand=Hierarchies&$filter=Level eq 0&$orderby=Name&$top=10&$skip=5&$count=true"


def test_nests_expand_options_with_semicolons() -> None:
    query = (
        ODataQuery.none().select("Name").expand("Hierarchies", lambda h: h.select("Name").expand("Elements", lambda e: e.select("Name").top(1))).expand("Cube")
    )
    assert str(query) == "$select=Name&$expand=Hierarchies($select=Name;$expand=Elements($select=Name;$top=1)),Cube"
    # '=' inside a nested option is encoded; TM1 accepts it
    assert query.to_query_string() == "$select=Name&$expand=Hierarchies($select%3DName;$expand%3DElements($select%3DName;$top%3D1)),Cube"


def test_lifts_an_expands_custom_options_and_aliases_to_the_top() -> None:
    # They mean nothing inside $expand: a nested one goes to the top, after the outer's, and the
    # outer's wins a name both set
    query = (
        ODataQuery.none()
        .option("!sandbox", "Outer")
        .expand(
            "CurrentBreakpoint",
            lambda b: b.option("!sandbox", "Inner").option("!x", "1").alias(Alias("@p"), "'a'").expand("Nested", lambda n: n.option("!y", "2")),
        )
    )
    assert str(query) == "$expand=CurrentBreakpoint($expand=Nested)&!sandbox=Outer&!x=1&!y=2&@p='a'"
    assert query.option("!x") == "1"
    assert ODataQuery.none().expand("B", lambda b: b.option("!sandbox", "S")).option("!sandbox") == "S"


def test_applies_nested_options_in_any_order() -> None:
    query = ODataQuery.none().expand("Hierarchies", lambda h: h.top(1).select("Name").order_by("Name"))
    assert str(query) == "$expand=Hierarchies($select=Name;$orderby=Name;$top=1)"


def test_merges_repeated_expands_of_one_navigation() -> None:
    query = ODataQuery.none().expand("Hierarchies", lambda h: h.select("Name")).expand("Hierarchies", lambda h: h.top(2))
    assert str(query) == "$expand=Hierarchies($select=Name;$top=2)"


def test_orders_descending() -> None:
    assert str(ODataQuery.none().order_by_descending("TimeStamp").order_by("ID")) == "$orderby=TimeStamp desc,ID"


def test_encodes_each_value_once() -> None:
    query = ODataQuery.none().filter(ODataProperty.of("Name").eq("a&b=c+d #e%f")).option("!sandbox", "What if & more")
    encoded = query.to_query_string()
    assert " " not in encoded and "+" not in encoded
    for escaped in ("%26", "%3D", "%2B", "%23", "%25"):
        assert escaped in encoded
    back = parse(encoded)
    assert back["$filter"] == "(Name eq 'a&b=c+d #e%f')"
    assert back["!sandbox"] == "What if & more"


def test_survives_fuzzed_filter_values() -> None:
    rnd = random.Random(42)
    alphabet = list("aZ0 '\"/%{}&=+#?!$@:;,()*É日😀")
    for _ in range(3000):
        v = "".join(rnd.choice(alphabet) for _ in range(1 + rnd.randrange(10)))
        encoded = ODataQuery.none().filter(ODataProperty.of("Name").eq(v)).option("!sandbox", v).to_query_string()
        assert " " not in encoded
        back = parse(encoded)
        assert back["$filter"] == "(Name eq " + ODataLiterals.string(v) + ")", v
        assert back["!sandbox"] == v


def test_defines_parameter_aliases() -> None:
    query = ODataQuery.none().alias(Alias("@tuple"), '["a","b"]')
    assert str(query) == '@tuple=["a","b"]'
    assert parse(query.to_query_string())["@tuple"] == '["a","b"]'


def test_refuses_bad_options() -> None:
    with pytest.raises(ValueError):
        ODataQuery.none().option("$top", "1")
    with pytest.raises(ValueError):
        ODataQuery.none().option("@p", "1")
    with pytest.raises(ValueError):
        ODataQuery.none().top(-1)
    with pytest.raises(ValueError):
        Alias("p")


def test_is_immutable() -> None:
    base = ODataQuery.none().select("Name")
    more = base.top(1)
    assert str(base) == "$select=Name"
    assert str(more) == "$select=Name&$top=1"
    assert base.option("!sandbox") is None


def test_takes_properties_for_expand_and_order_by() -> None:
    dimensions, name = ODataProperty.of("Dimensions"), ODataProperty.of("Name")
    assert ODataQuery.none().expand(dimensions, lambda d: d.select(name)).expand(ODataProperty.of("Views")).order_by(name).order_by_descending(
        ODataProperty.of("LastDataUpdate")
    ) == ODataQuery.none().expand("Dimensions", lambda d: d.select("Name")).expand("Views").order_by("Name").order_by_descending("LastDataUpdate")


def test_keyword_options_make_the_same_query() -> None:
    assert ODataQuery.of(None, select=["Name"], top=5) == ODataQuery.none().select("Name").top(5)
    assert ODataQuery.of(ODataQuery.none().select("Name"), expand="Views") == ODataQuery.none().select("Name").expand("Views")
    with pytest.raises(TypeError):
        ODataQuery.of(None, bananas=1)


# FilterTest

NAME = ODataProperty.of("Name")
LEVEL = ODataProperty.of("Level")


def test_compares_with_literals() -> None:
    assert str(NAME.eq("O'Neil")) == "(Name eq 'O''Neil')"
    assert str(LEVEL.ge(0)) == "(Level ge 0)"
    assert str(ODataProperty.of("Weight").lt(Decimal("1.50"))) == "(Weight lt 1.50)"
    assert str(ODataProperty.of("Value").gt(2.5)) == "(Value gt 2.5)"
    assert str(ODataProperty.of("Active").eq(True)) == "(Active eq true)"
    assert str(ODataProperty.of("Parent").is_null()) == "(Parent eq null)"
    assert str(ODataProperty.of("Type").eq(Raw(ODataLiterals.enum_member("ibm.tm1.api.v1.ElementType", "Numeric")))) == (
        "(Type eq ibm.tm1.api.v1.ElementType'Numeric')"
    )


def test_parenthesizes_every_combination() -> None:
    f = Filter.or_(Filter.and_(NAME.eq("a"), LEVEL.eq(0)), Filter.not_(Filter.starts_with(NAME, "}")))
    assert str(f) == "(((Name eq 'a') and (Level eq 0)) or not(startswith(Name,'}')))"
    assert Filter.and_(NAME.eq("a")) == NAME.eq("a")
    assert (NAME.eq("a") & LEVEL.eq(0)) | ~NAME.eq("b") == Filter.or_(Filter.and_(NAME.eq("a"), LEVEL.eq(0)), Filter.not_(NAME.eq("b")))


def test_keeps_a_raw_expressions_meaning_when_combined() -> None:
    either = Filter.raw("A eq 1 or B eq 2")
    assert str(either) == "A eq 1 or B eq 2"
    assert str(Filter.and_(either, NAME.eq("x"))) == "((A eq 1 or B eq 2) and (Name eq 'x'))"
    assert str(NAME.eq("x") | Filter.raw("A eq 1 and B eq 2")) == "((Name eq 'x') or (A eq 1 and B eq 2))"
    assert str(Filter.and_(either)) == "A eq 1 or B eq 2"
    assert str(~either) == "not(A eq 1 or B eq 2)"


def test_uses_canonical_functions() -> None:
    assert str(Filter.contains(NAME, "Q1")) == "contains(Name,'Q1')"
    assert str(Filter.ends_with(NAME, "'s")) == "endswith(Name,'''s')"
    assert str(Filter.in_(NAME, "a", "b")) == "(Name in ('a','b'))"


def test_writes_lambdas() -> None:
    assert str(Filter.any("Hierarchies", "h", lambda h: h.nav("Name").eq("}Clients"))) == "Hierarchies/any(h:(h/Name eq '}Clients'))"
    assert str(Filter.all("Tags", "t", lambda t: t.self_().ne("x"))) == "Tags/all(t:(t ne 'x'))"


def test_refuses_values_without_a_clear_literal() -> None:
    with pytest.raises(TypeError):
        NAME.eq(object())
    with pytest.raises(ValueError):
        Filter.in_(NAME)
    with pytest.raises(ValueError):
        Filter.and_()
    with pytest.raises(ValueError):
        Filter.any("X", "1v", lambda v: v.self_().eq(1))


def test_writes_special_numbers() -> None:
    assert ODataLiterals.of(math.nan) == "NaN"
    assert ODataLiterals.of(-math.inf) == "-INF"
    assert ODataLiterals.of(1e-7) == "0.0000001"
    assert ODataLiterals.of(12) == "12"


def test_writes_a_value_that_knows_its_own_literal() -> None:
    class Numeric:
        def odata_literal(self) -> str:
            return "ibm.tm1.api.v1.ElementType'Numeric'"

    assert str(ODataProperty.of("Type").eq(Numeric())) == "(Type eq ibm.tm1.api.v1.ElementType'Numeric')"


# ODataPathTest (what spec/conformance/odata-encoding.json doesn't already pin)


def test_addresses_the_metadata_document() -> None:
    assert str(ODataPath.metadata()) == "$metadata"
    assert str(ODataPath.batch()) == "$batch"
    with pytest.raises(ValueError):
        ODataPath.metadata().key("x")


@pytest.mark.parametrize("name", ["a'b", "''", "%2F", "?", "\\", "a\tb", "}Clients", "日本"])
def test_keeps_awkward_names_intact(name: str) -> None:
    segment = str(ODataPath.of("Dimensions").key(name).nav("Hierarchies")).split("/")[0]
    assert unquote(segment[len("Dimensions('") : -2]).replace("''", "'") == name


@pytest.mark.parametrize("name", ["", "a b", "Cubes('x')", "1abc", "a/b"])
def test_refuses_names_that_arent_identifiers(name: str) -> None:
    with pytest.raises(ValueError):
        ODataPath.of(name)
    with pytest.raises(ValueError):
        ODataPath.of("Cubes").nav(name)


def test_refuses_two_keys_on_one_segment() -> None:
    with pytest.raises(ValueError):
        ODataPath.of("Cubes").key("a").key("b")
    with pytest.raises(ValueError):
        ODataPath.of("Cubes").count().key("a")


def test_is_a_value_type() -> None:
    assert ODataPath.of("Cubes").key("a") == ODataPath.of("Cubes").key("a")
    assert hash(ODataPath.of("Cubes").key("a")) == hash(ODataPath.of("Cubes").key("a"))


def test_builds_nested_paths() -> None:
    assert (
        str(ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements")) == "Dimensions('Region')/Hierarchies('Region')/Elements"
    )
    assert str(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve")) == "Cubes('Sales')/tm1.Reserve"
    assert str(ODataPath.of("Cubes").key("Sales").nav("Views").cast("tm1.NativeView")) == "Cubes('Sales')/Views/tm1.NativeView"
    assert str(ODataPath.of("Configuration").nav("ProductVersion").value()) == "Configuration/ProductVersion/$value"
    assert str(ODataPath.of("Dimensions").count()) == "Dimensions/$count"
    assert str(ODataPath.of("Cubes").key("Sales").nav("Dimensions").ref()) == "Cubes('Sales')/Dimensions/$ref"
    assert str(ODataPath.of("Threads").key(42)) == "Threads(42)"


def test_writes_compound_keys_in_order() -> None:
    key = {"ParentName": "Total", "ComponentName": "Q1 '26"}
    assert str(ODataPath.of("Edges").key(key)) == "Edges(ParentName='Total',ComponentName='Q1%20''26')"


def test_calls_functions_with_literals_or_aliases() -> None:
    assert str(ODataPath.of("Cellsets").key("abc").function("tm1.GetPartition", {"Begin": 0, "End": 99})) == "Cellsets('abc')/tm1.GetPartition(Begin=0,End=99)"
    assert str(ODataPath.of("Hierarchies").function("tm1.Fn", {"p": Alias("@p")})) == "Hierarchies/tm1.Fn(p=@p)"


def test_encodes_slashes_so_they_cant_split_the_path() -> None:
    assert str(ODataPath.of("Dimensions").key("a/b")) == "Dimensions('a%2Fb')"
    assert str(ODataPath.of("Dimensions").key("O'Neil")) == "Dimensions('O''Neil')"
    assert str(ODataPath.of("Dimensions").key("}Clients")) == "Dimensions('%7DClients')"
    assert str(ODataPath.of("Dimensions").key("a b")) == "Dimensions('a%20b')"


# What RFC 3986 allows in a path: Java's URI.create refuses anything else
_URI_PATH = re.compile(r"(?:[A-Za-z0-9\-._~!$&'()*+,;=:@/]|%[0-9A-Fa-f]{2})*")


def _key_of(path: ODataPath) -> str:
    """The name in the first segment's key literal, decoded as a server would."""
    segment = unquote(str(path).split("/")[0])
    literal = segment[segment.index("(") + 1 : segment.rindex(")")]
    assert literal.startswith("'") and literal.endswith("'")
    return literal[1:-1].replace("''", "'")


def test_survives_fuzzed_names() -> None:
    rng = random.Random(20261121)
    alphabet = "aZ09 '\"/\\%{}[]()&=+#?!$@:;,.*~_-^|<>`\tÉé日😀"
    for _ in range(5000):
        name = "".join(rng.choice(alphabet) for _ in range(1 + rng.randrange(12)))
        path = ODataPath.of("Cubes").key(name).nav("Views").key(name)
        assert _URI_PATH.fullmatch(str(path)), name
        assert _key_of(ODataPath.of("Cubes").key(name)) == name


def test_calls_a_function_import_from_the_service_root() -> None:
    assert str(ODataPath.function_import("ModelCubes", {})) == "ModelCubes()"
    assert str(ODataPath.function_import("TailMessageLog", {"Top": 10})) == "TailMessageLog(Top=10)"


# Tm1VersionTest and Tm1DialectTest


def test_keeps_the_text_as_reported() -> None:
    assert str(Tm1Version.parse(" 11.8.03500.6\n")) == "11.8.03500.6"


def test_compares_parts_numerically() -> None:
    assert Tm1Version.parse("11.8.03500.6") == Tm1Version.parse("11.8.3500.6")
    assert Tm1Version.parse("11.8.03500.6") > Tm1Version.parse("11.8.02800.9")
    assert Tm1Version.parse("11.10") > Tm1Version.parse("11.9.99999")
    assert Tm1Version.parse("12.0.0") > Tm1Version.parse("11.8.03500.6")


def test_treats_missing_parts_as_zero() -> None:
    assert Tm1Version.parse("11.8") == Tm1Version.parse("11.8.0.0")
    assert hash(Tm1Version.parse("11.8")) == hash(Tm1Version.parse("11.8.0.0"))
    assert Tm1Version.parse("11.8.1") > Tm1Version.parse("11.8")


def test_answers_range_questions() -> None:
    v = Tm1Version.parse("11.8.03500.6")
    assert v.is_at_least(Tm1Version.parse("11.8.03500.6"))
    assert v.is_at_least(Tm1Version.parse("11.8.02300"))
    assert not v.is_at_least(Tm1Version.parse("11.8.03600"))
    assert v.is_before(Tm1Version.parse("12"))
    assert not v.is_before(v)


@pytest.mark.parametrize("text", ["", "11.", ".8", "11..8", "v11.8", "11.8-beta", "99999999999"])
def test_rejects_non_versions(text: str) -> None:
    with pytest.raises(ValueError, match="Not a TM1 version"):
        Tm1Version.parse(text)


def test_finds_the_tm111_service_root_from_an_address() -> None:
    assert Tm1Dialect.V11.service_root("http://tm1:8010") == "http://tm1:8010/api/v1/"
    assert Tm1Dialect.V11.service_root("https://tm1.example.com:12354/") == "https://tm1.example.com:12354/api/v1/"
    assert Tm1Dialect.V11.service_root("http://tm1:8010/api/v1") == "http://tm1:8010/api/v1/", "already a root"
    assert Tm1Dialect.V11.service_root("http://proxy/tm1/sales/") == "http://proxy/tm1/sales/api/v1/", "behind a proxy path"
    assert Tm1Dialect.V11.name == "TM1 11"


@pytest.mark.parametrize("address", ["tm1:8010/x", "/api/v1/", "http://tm1:8010?x=1"])
def test_refuses_what_isnt_an_address(address: str) -> None:
    with pytest.raises(ValueError):
        Tm1Dialect.V11.service_root(address)


def test_finds_the_tm112_service_root_and_close_url() -> None:
    sales = Tm1Dialect.v12("sales")
    assert sales.name == "TM1 12"
    assert sales == Tm1Dialect.v12("tm1", "sales")
    assert hash(sales) == hash(Tm1Dialect.v12("tm1", "sales"))
    assert repr(sales) == "Tm1Dialect[TM1 12, tm1/sales]"
    assert sales.service_root("http://engine:4444") == "http://engine:4444/tm1/api/v1/Databases('sales')/"
    assert sales.service_root("https://engine.example.com:4444/") == "https://engine.example.com:4444/tm1/api/v1/Databases('sales')/"
    assert sales.service_root("http://engine:4444/tm1/api/v1") == "http://engine:4444/tm1/api/v1/Databases('sales')/", "already an instance root"
    assert sales.service_root("http://engine:4444/tm1/api/v1/Databases('sales')/") == "http://engine:4444/tm1/api/v1/Databases('sales')/", "already a db root"
    assert Tm1Dialect.v12("prod", "O'Neil Sales").service_root("https://proxy/pa/") == "https://proxy/pa/prod/api/v1/Databases('O''Neil%20Sales')/"
    assert (
        Tm1Dialect.v12("café").service_root("http://engine:4444/tm1/api/v1/Databases('caf%c3%a9')/") == "http://engine:4444/tm1/api/v1/Databases('caf%C3%A9')/"
    )

    assert Tm1Dialect.V11.close_url("http://tm1:8010/api/v1/") == "http://tm1:8010/api/v1/ActiveSession/tm1.Close"
    assert sales.close_url("http://engine:4444/tm1/api/v1/Databases('sales')/") == "http://engine:4444/tm1/api/v1/ActiveSession/tm1s.Close"
    assert sales.close_url("https://proxy/pa/tm1/api/v1/Databases('O''Neil%20Sales')") == "https://proxy/pa/tm1/api/v1/ActiveSession/tm1s.Close"


def test_refuses_invalid_tm112_dialect_inputs() -> None:
    for bad in (
        lambda: Tm1Dialect.v12("  "),
        lambda: Tm1Dialect.v12("", "sales"),
        lambda: Tm1Dialect.v12("prod", None),  # type: ignore[arg-type]
        lambda: Tm1Dialect.v12("a/b", "sales"),
        lambda: Tm1Dialect.v12("..", "sales"),
        lambda: Tm1Dialect.v12("a[b", "sales"),
        lambda: Tm1Dialect.v12("tm1", " "),
        lambda: Tm1Dialect.v12("sales").service_root("engine:4444"),
        lambda: Tm1Dialect.v12("sales").service_root("ftp://engine:4444"),
        lambda: Tm1Dialect.v12("sales").service_root("http://engine:4444?"),
        lambda: Tm1Dialect.v12("sales").service_root("http://engine:4444#"),
        lambda: Tm1Dialect.v12("sales").service_root("http:///tm1/api/v1"),
        lambda: Tm1Dialect.v12("sales").service_root("http://engine:4444/tm1/api/v1/Databases('other')/"),
        lambda: Tm1Dialect.v12("sales").close_url("http://engine:4444/"),
        lambda: Tm1Dialect.v12("sales").close_url("http://engine:4444/tm1/api/v1/"),
        lambda: Tm1Dialect.v12("sales").close_url("http://engine:4444/api/v1/"),
        lambda: Tm1Dialect.v12("sales").close_url("http://engine:4444/api/v1/Databases('sales')/"),
        lambda: Tm1Dialect.v12("sales").close_url("http://engine:4444//api/v1/Databases('sales')/"),
        lambda: Tm1Dialect.V11.close_url("not a url"),
    ):
        with pytest.raises(ValueError):
            bad()


def test_finds_the_tm112_auth_session_url() -> None:
    sales = Tm1Dialect.v12("sales")
    assert Tm1Dialect.V11.auth_session_url("http://tm1:8010/api/v1/") is None
    with pytest.raises(ValueError):
        Tm1Dialect.V11.auth_session_url("not a url")
    assert sales.auth_session_url("http://engine:4444/tm1/api/v1/Databases('sales')/") == "http://engine:4444/tm1/auth/v1/session"
    assert (
        Tm1Dialect.v12("prod", "O'Neil Sales").auth_session_url("https://proxy/pa/prod/api/v1/Databases('O''Neil%20Sales')")
        == "https://proxy/pa/prod/auth/v1/session"
    )
    with pytest.raises(ValueError):
        sales.auth_session_url("http://engine:4444/api/v1/Databases('sales')/")
    with pytest.raises(ValueError):
        sales.auth_session_url("http://engine:4444//api/v1/Databases('sales')/")
    with pytest.raises(ValueError):
        sales.auth_session_url("http://engine:4444/tm1/api/v1/")


# Tm1TimeoutsTest


def test_fills_in_defaults() -> None:
    assert Tm1Timeouts.DEFAULTS == Tm1Timeouts(10, 30, 600, 5, 30)
    long_requests = Tm1Timeouts.builder().request(3600).build()
    assert long_requests.request == 3600 and long_requests.login == 30
    assert long_requests.to_builder().close(1).build().close == 1


def test_refuses_timeouts_that_arent_positive() -> None:
    with pytest.raises(ValueError):
        Tm1Timeouts.builder().login(0).build()
    with pytest.raises(ValueError):
        Tm1Timeouts.builder().connect(-1).build()


# Tm1AuthenticatorTest


def b64(text: str) -> str:
    return base64.b64encode(text.encode()).decode()


def test_writes_basic_credentials() -> None:
    basic = auth.basic("admin", "apple")
    assert basic.authorization() == "Basic " + b64("admin:apple")
    assert auth.basic("admin", None).authorization() == "Basic " + b64("admin:")
    assert basic.describe() == "Basic admin"


def test_writes_cam_namespace_credentials() -> None:
    cam = auth.cam_namespace("alice", "secret", "LDAP")
    assert cam.authorization() == "CAMNamespace " + b64("alice:secret:LDAP")
    assert cam.describe() == "CAMNamespace LDAP/alice"


def test_asks_for_a_fresh_passport_on_every_login() -> None:
    issued = iter(range(1, 10))
    cam = auth.cam_passport(lambda: f"p{next(issued)}")
    assert cam.authorization() == "CAMPassport p1"
    assert cam.authorization() == "CAMPassport p2"
    assert cam.describe() == "CAMPassport"


def test_writes_a_passport_it_was_given() -> None:
    cam = auth.cam_passport("p1")
    assert cam.authorization() == "CAMPassport p1"
    assert cam.authorization() == "CAMPassport p1", "the same one at each login: it isn't renewed"
    assert cam.describe() == "CAMPassport"
    assert "p1" not in repr(cam)


def test_authenticators_that_hold_their_credentials_are_equal_when_the_credentials_are() -> None:
    assert auth.basic("admin", "apple") == auth.basic("admin", "apple")
    assert hash(auth.basic("admin", "apple")) == hash(auth.basic("admin", "apple"))
    assert auth.basic("admin", "apple") != auth.basic("admin", "pear")
    assert auth.cam_namespace("alice", "secret", "LDAP") == auth.cam_namespace("alice", "secret", "LDAP")
    assert auth.cam_namespace("alice", "secret", "LDAP") != auth.cam_namespace("alice", "other", "LDAP")
    assert auth.cam_passport("p1") == auth.cam_passport("p1") and hash(auth.cam_passport("p1")) == hash(auth.cam_passport("p1"))
    assert auth.cam_passport("p1") != auth.cam_passport("p2")
    supplier = auth.cam_passport(lambda: "p1")
    assert supplier == supplier and supplier != auth.cam_passport(lambda: "p1"), "one that asks a supplier is equal only to itself"
    assert auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "d") == auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "d")
    assert hash(auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "d")) == hash(auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "d"))
    assert auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "d") != auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "other")
    assert auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "d") != auth.cognos_logon("http://other:9300", "LDAP", "dave", "d")

    def token() -> str:
        return "t"

    assert auth.cognos_id_token("http://cognos:9300", "LDAP", token) == auth.cognos_id_token("http://cognos:9300", "LDAP", token)
    assert auth.cognos_id_token("http://cognos:9300", "LDAP", token) != auth.cognos_id_token("http://cognos:9300", "LDAP", lambda: "t")
    assert "secret" not in repr(auth.cognos_logon("http://cognos:9300", "LDAP", "dave", "secret"))


def test_writes_bearer_credentials() -> None:
    bearer = auth.bearer("eyJ.token.sig")
    assert bearer.authorization() == "Bearer eyJ.token.sig"
    assert bearer.describe() == "Bearer"
    assert bearer == auth.bearer("eyJ.token.sig") and hash(bearer) == hash(auth.bearer("eyJ.token.sig"))
    assert bearer != auth.bearer("other-token")
    with pytest.raises(ValueError):
        auth.bearer("  ")
    with pytest.raises(ValueError):
        auth.bearer("bad\r\ntoken")
    with pytest.raises(ValueError):
        auth.bearer("Bearer eyJ.token")


def test_asks_for_a_fresh_bearer_token_on_every_login() -> None:
    import asyncio

    issued = iter(range(1, 10))
    bearer = auth.bearer(lambda: f"t{next(issued)}")
    assert bearer.authorization() == "Bearer t1"
    assert bearer.authorization() == "Bearer t2"
    assert bearer.describe() == "Bearer"
    assert bearer != auth.bearer(lambda: "t1")
    with pytest.raises(ValueError):
        auth.bearer(lambda: "  ").authorization()
    with pytest.raises(ValueError):
        auth.bearer(lambda: "bad\ntoken").authorization()

    async def async_token() -> str:
        return "async-jwt"

    async def blank_async_token() -> str:
        return "  "

    assert asyncio.run(auth.bearer(async_token).authorization()) == "Bearer async-jwt"
    with pytest.raises(ValueError):
        asyncio.run(auth.bearer(blank_async_token).authorization())


def test_writes_application_credentials() -> None:
    app = auth.application("svc-client", "s3cr3t", "analyst")
    assert app.authorization() == "Basic " + b64("svc-client:s3cr3t")
    assert app.describe() == "Application svc-client as analyst"
    assert app == auth.application("svc-client", "s3cr3t", "analyst")
    assert hash(app) == hash(auth.application("svc-client", "s3cr3t", "analyst"))
    assert app != auth.application("svc-client", "s3cr3t", "other")
    assert app != auth.application("svc-client", "other", "analyst")
    assert app != auth.basic("svc-client", "s3cr3t")
    for bad in (
        lambda: auth.application(" ", "s3cr3t", "analyst"),
        lambda: auth.application("bad:id", "s3cr3t", "analyst"),
        lambda: auth.application("bad\nid", "s3cr3t", "analyst"),
        lambda: auth.application("svc-client", " ", "analyst"),
        lambda: auth.application("svc-client", "s3cr3t", " "),
        lambda: auth.application("svc-client", "s3cr3t", "bad\nuser"),
    ):
        with pytest.raises(ValueError):
            bad()


def test_never_shows_credentials_in_to_string() -> None:
    # Java's toString is repr here
    assert "apple" not in repr(auth.basic("admin", "apple")) and b64("admin:apple") not in repr(auth.basic("admin", "apple"))
    assert "secret" not in repr(auth.cam_namespace("alice", "secret", "LDAP"))
    assert "passport-value" not in repr(auth.cam_passport(lambda: "passport-value"))
    assert "secret-jwt" not in repr(auth.bearer("secret-jwt"))
    assert "secret-jwt" not in repr(auth.bearer(lambda: "secret-jwt"))
    app_repr = repr(auth.application("svc-client", "s3cr3t", "analyst"))
    assert "s3cr3t" not in app_repr and b64("svc-client:s3cr3t") not in app_repr


# Tm1RequestTest


def test_describes_a_get() -> None:
    get = Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none().select("Name"), dict)
    assert get.method == "GET"
    assert get.relative_uri() == "Cubes('Sales')?$select=Name"
    assert get.response is Tm1Request.Response.SINGLE
    assert get.is_idempotent()
    assert str(get) == "GET Cubes('Sales')?$select=Name"


def test_gives_actions_a_json_body_even_without_parameters() -> None:
    reserve = Tm1Request.post(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve"), None)
    assert reserve.body == {}
    assert reserve.response is Tm1Request.Response.NONE
    assert not reserve.is_idempotent()


def test_keeps_a_body_on_a_write() -> None:
    reserve = ODataPath.of("Cubes").key("Sales").action("tm1.Reserve")
    with pytest.raises(ValueError):
        Tm1Request.post(reserve, None).with_body(None)
    with pytest.raises(ValueError):
        Tm1Request.patch(ODataPath.of("Cubes").key("x"), {}).with_body(None)
    assert Tm1Request.delete(ODataPath.of("Cubes").key("x")).with_body(None).body is None
    assert Tm1Request.post(reserve, None).with_body({"A": 1}).body == {"A": 1}


def test_describes_other_kinds() -> None:
    assert Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), dict).response is Tm1Request.Response.COLLECTION
    assert Tm1Request.text(ODataPath.of("Dimensions").count()).response is Tm1Request.Response.TEXT
    assert not Tm1Request.patch(ODataPath.of("Cubes").key("x"), {"Rules": ""}).is_idempotent()
    assert Tm1Request.delete(ODataPath.of("Cubes").key("x")).is_idempotent()


def test_a_request_is_immutable() -> None:
    delete = Tm1Request.delete(ODataPath.of("Cubes").key("x"))
    guarded = delete.if_match('W/"1"').with_query(ODataQuery.none().option("!sandbox", "s"))
    assert dict(delete.headers) == {}
    assert guarded.headers["If-Match"] == 'W/"1"'
    assert guarded.relative_uri() == "Cubes('x')?!sandbox=s"


def test_carries_its_own_timeout_through_copies() -> None:
    run = Tm1Request.post(ODataPath.of("Processes").key("p").action("tm1.ExecuteWithReturn"), None)
    assert run.timeout is None
    slow = run.with_timeout(7200).with_header("Prefer", "respond-async")
    assert slow.timeout == 7200 and run.timeout is None
    with pytest.raises(ValueError):
        run.with_timeout(0)


def test_posts_and_streams_a_collection() -> None:
    request = Tm1Request.post_list(ODataPath.of("Cubes").key("c").action("tm1.CheckRules"), {}, str)
    assert request.method == "POST"
    assert request.response is Tm1Request.Response.COLLECTION
    assert not request.is_idempotent()


def test_describes_raw_bytes_both_ways() -> None:
    download = Tm1Request.bytes(ODataPath.of("Contents").key("Blobs").nav("Contents").key("a.txt"))
    assert download.response is Tm1Request.Response.BYTES and download.method == "GET"
    upload = Tm1Request.put(ODataPath.of("Contents").key("Blobs"), b"abc")
    assert upload.method == "PUT" and upload.is_idempotent()
    assert upload.body == b"abc"
    assert upload.response is Tm1Request.Response.NONE


# Tm1OperationTest


def test_a_bound_operation_needs_its_binding_and_operation() -> None:
    activate = Tm1Operation(Tm1Operation.Kind.ACTION, "tm1.Activate", "tm1.Chore", None, (), None, "10.2.2.1", "11.8.3")
    assert activate.bound() and activate.deprecated()
    assert activate.endpoint() == "Action Chore/Activate"
    assert activate.capability() == Tm1Capability.bound_operation("tm1.Chore", "tm1.Activate").since("10.2.2.1")


def test_an_unbound_operation_needs_its_import() -> None:
    mdx = Tm1Operation(Tm1Operation.Kind.ACTION, "tm1.ExecuteMDX", None, "ExecuteMDX", (Tm1Operation.Parameter("MDX", "Edm.String", False),), "tm1.Cellset")
    assert not mdx.bound()
    assert mdx.endpoint() == "ActionImport ExecuteMDX"
    assert mdx.capability() == Tm1Capability.unbound_operation("ExecuteMDX")


def test_needs_exactly_one_of_a_binding_and_an_import() -> None:
    with pytest.raises(ValueError):
        Tm1Operation(Tm1Operation.Kind.ACTION, "tm1.A", "tm1.Cube", "A")
    with pytest.raises(ValueError):
        Tm1Operation(Tm1Operation.Kind.FUNCTION, "tm1.F", None, None)


# OperationCatalogTest


def test_has_one_descriptor_per_operation_endpoint_of_the_coverage_gate(spec: dict[str, object]) -> None:
    operations = {e["id"] for e in spec["endpoints"] if e["kind"] in ("Action", "Function", "ActionImport", "FunctionImport")}  # type: ignore[index]
    assert sorted(op.endpoint() for op in Tm1Operations.ALL) == sorted(operations)
    assert len({op.endpoint() for op in Tm1Operations.ALL}) == len(Tm1Operations.ALL) == 112


def test_every_capability_is_one_the_snapshot_has() -> None:
    snapshots = [Tm1Capabilities.parse(path.read_bytes()) for path in SNAPSHOTS]
    assert [op.endpoint() for op in Tm1Operations.ALL if not any(s.supports(op.capability()) for s in snapshots)] == []


def test_carries_revisions_parameters_and_return_types() -> None:
    assert Tm1Operations.CHORE_ACTIVATE.deprecated_in == "11.8.3"
    mdx = Tm1Operations.EXECUTE_MDX
    assert mdx.kind is Tm1Operation.Kind.ACTION
    assert mdx.import_name == "ExecuteMDX"
    assert mdx.parameters == (
        Tm1Operation.Parameter("MDX", "Edm.String", False),
        Tm1Operation.Parameter("Context", "Edm.Untyped", True),
    )
    assert mdx.return_type == "tm1.Cellset"
    assert mdx.added_in == "10.2.2.1"
    assert any(op.deprecated() for op in Tm1Operations.ALL)
