"""The session and the resources' safety rules, against a fake TM1."""

from __future__ import annotations

import base64
import json

import httpx
import pytest

from tm1_client import (
    ODataQuery,
    Tm1Client,
    Tm1ErrorKind,
    Tm1NotFoundError,
    Tm1PreconditionFailedError,
    Tm1Session,
    Tm1UnsupportedOperationError,
    auth,
)
from tm1_client._generated import model as m

from .conftest import BASE, FakeTm1, target


def test_logs_in_once_then_sends_only_the_cookie(client: Tm1Client, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": []}
    client.cubes.list()
    client.dimensions.list()
    assert fake.logins == 1
    for request in fake.requests:
        assert "authorization" not in request.headers
        assert request.headers["cookie"] == "TM1SessionId=s1"
        assert request.headers["odata-version"] == "4.0"
        assert request.headers["tm1-sessioncontext"] == "tm1-client (Python)"


def test_logs_in_again_when_the_session_expired(client: Tm1Client, fake: FakeTm1) -> None:
    answers = iter([httpx.Response(401), {"value": [{"Name": "c"}]}])
    fake.respond = lambda r: next(answers)
    assert [c.name for c in client.cubes.list()] == ["c"]
    assert fake.logins == 2
    assert fake.requests[-1].headers["cookie"] == "TM1SessionId=s2"


def test_closes_the_session_on_the_server(session: Tm1Session, fake: FakeTm1) -> None:
    with Tm1Client(session) as client:
        fake.respond = lambda r: {}
        client.server.get()
    assert target(fake.last) == "POST ActiveSession/tm1.Close"
    assert not session.logged_in


def test_login_headers() -> None:
    def decode(header: str) -> str:
        return base64.b64decode(header.split(" ", 1)[1]).decode()

    assert decode(auth.basic("admin", "apple").authorization()) == "admin:apple"
    assert auth.cam_namespace("u", "p", "LDAP").authorization().startswith("CAMNamespace ")
    assert decode(auth.cam_namespace("u", "p", "LDAP").authorization()) == "u:p:LDAP"
    assert auth.cam_passport(lambda: "pp").authorization() == "CAMPassport pp"
    assert "apple" not in repr(auth.basic("admin", "apple"))


def test_follows_next_links_across_pages(client: Tm1Client, fake: FakeTm1) -> None:
    pages = {
        "Cubes?$select=Name": {"value": [{"Name": "a"}], "@odata.nextLink": "Cubes?$select=Name&$skiptoken=2"},
        "Cubes?$select=Name&$skiptoken=2": {"value": [{"Name": "b"}]},
    }
    fake.respond = lambda r: pages[target(r).split(" ", 1)[1]]
    assert [c.name for c in client.cubes.list(select="Name")] == ["a", "b"]


def test_sends_query_options_encoded(client: Tm1Client, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": []}
    client.cubes.list(ODataQuery(select=["Name", "Rules"], filter="startswith(Name,'Sales X')", top=5))
    assert target(fake.last) == "GET Cubes?$select=Name,Rules&$filter=startswith(Name,'Sales%20X')&$top=5"


def test_runs_in_a_sandbox(fake: FakeTm1) -> None:
    http = httpx.Client(transport=httpx.MockTransport(fake.handler))
    client = Tm1Client(Tm1Session(BASE, auth.basic("a"), sandbox="What if", client=http))
    fake.respond = lambda r: {}
    client.cubes["c"].get()
    assert target(fake.last) == "GET Cubes('c')?!sandbox=What%20if"


def test_updates_with_if_match_and_classifies_refusals(client: Tm1Client, fake: FakeTm1) -> None:
    fake.respond = lambda r: httpx.Response(412, json={"error": {"code": "278", "message": "stale"}})
    with pytest.raises(Tm1PreconditionFailedError) as refused:
        client.cubes["c"].update(m.Cube(rules="SKIPCHECK;"), if_match='W/"1"')
    assert fake.last.headers["if-match"] == 'W/"1"'
    assert json.loads(fake.last.content) == {"Rules": "SKIPCHECK;"}
    assert refused.value.kind is Tm1ErrorKind.PRECONDITION_FAILED and refused.value.error.code == "278"


def test_reads_a_400_that_means_not_found_as_not_found(client: Tm1Client, fake: FakeTm1) -> None:
    # TM1 (11.8) answers a missing annotation with 400
    fake.respond = lambda r: httpx.Response(400, json={"error": {"message": "Annotation 'x' can not be found"}})
    with pytest.raises(Tm1NotFoundError) as refused:
        client.annotations["x"].get()
    assert refused.value.status == 400 and refused.value.kind is Tm1ErrorKind.NOT_FOUND


def test_creates_with_binds(client: Tm1Client, fake: FakeTm1) -> None:
    from tm1_client import Bind

    fake.respond = lambda r: {"Name": "c"}
    created = client.cubes.create(m.Cube(name="c", dimensions=[Bind("Dimensions('a')"), Bind("Dimensions('b')")]))
    assert created.name == "c"
    assert target(fake.last) == "POST Cubes"
    assert json.loads(fake.last.content) == {"Name": "c", "Dimensions@odata.bind": ["Dimensions('a')", "Dimensions('b')"]}


def test_refuses_to_delete_through_a_reference(client: Tm1Client, fake: FakeTm1) -> None:
    # DELETE Users('u')/Groups('g') deletes the group itself on TM1
    group = client.users["u"].groups["g"]
    with pytest.raises(Tm1UnsupportedOperationError, match="deletes the group itself"):
        group.delete()
    with pytest.raises(Tm1UnsupportedOperationError):
        client.users["u"].groups.create(m.Group(name="g"))
    # Any reference, by OData's rules: change dimensions through Dimensions
    with pytest.raises(Tm1UnsupportedOperationError, match="kept elsewhere"):
        client.cubes["c"].dimensions["d"].delete()
    assert fake.requests == []
    # Reading through it is fine
    fake.respond = lambda r: {"Name": "g"}
    assert group.get().name == "g"
    assert target(fake.last) == "GET Users('u')/Groups('g')"


def test_deletes_through_a_containment(client: Tm1Client, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    client.cubes["c"].views["v"].delete()
    assert target(fake.last) == "DELETE Cubes('c')/Views('v')"


def test_a_compound_key(client: Tm1Client, fake: FakeTm1) -> None:
    fake.respond = lambda r: {}
    client.dimensions["d"].hierarchies["h"].edges["Total", "Q1"].get()
    assert target(fake.last) == "GET Dimensions('d')/Hierarchies('h')/Edges(ParentName='Total',ComponentName='Q1')"
    with pytest.raises(ValueError):
        client.dimensions["d"].hierarchies["h"].edges["Total"]


def test_reads_mdx_values_and_deletes_the_cellset(client: Tm1Client, fake: FakeTm1) -> None:
    def respond(request: httpx.Request) -> object:
        if request.method == "POST":
            return {"ID": "cs1", "Cells": [{"Ordinal": 1, "Value": 2.5}, {"Ordinal": 0, "Value": "a"}]}
        return None

    fake.respond = respond
    assert client.mdx_values("SELECT {} ON 0 FROM [c]") == ["a", 2.5]
    assert target(fake.requests[0]) == "POST ExecuteMDX?$select=ID&$expand=Cells($select%3DOrdinal,Value)"
    assert json.loads(fake.requests[0].content) == {"MDX": "SELECT {} ON 0 FROM [c]"}
    assert target(fake.last) == "DELETE Cellsets('cs1')"
