"""Session views (sandbox, change set, async operations, batch) and capability checks, in both
flavors, against a fake TM1."""

from __future__ import annotations

import gzip
import json
from typing import Any

import httpx
import pytest

from tm1_client import Tm1Client, Tm1NotFoundError, Tm1Session, Tm1UnsupportedOperationError, auth
from tm1_client import _protocol as p
from tm1_client._generated import model as m

from .conftest import BASE, SNAPSHOT, Api, FakeTm1, target

# Views


def test_sandbox_and_change_set_views_add_tms_options(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {}
    view = api.client.with_sandbox("What if").with_change_set("3")
    api.run(view.cubes["c"].get())
    assert target(fake.last) == "GET Cubes('c')?!sandbox=What%20if&!ChangeSet=3"
    # A view is derived: the client it came from is unchanged
    api.run(api.client.cubes["c"].get())
    assert target(fake.last) == "GET Cubes('c')"
    # A request's own option wins
    from tm1_client import ODataQuery

    api.run(view.cubes["c"].get(ODataQuery(custom={"!sandbox": "Other"})))
    assert target(fake.last) == "GET Cubes('c')?!sandbox=Other&!ChangeSet=3"


def test_a_view_shares_the_login(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {}
    api.run(api.client.cubes["c"].get())
    api.run(api.client.with_sandbox("s").cubes["c"].get())
    assert fake.logins == 1


def test_change_set_ends_however_the_block_finishes(client: Tm1Client, fake: FakeTm1) -> None:
    def respond(request: httpx.Request) -> Any:
        if target(request) == "POST BeginChangeSet":
            return {"value": "3"}  # TM1 wraps the ID
        if target(request) == "POST EndChangeSet":
            return {"value": 2}
        return None

    fake.respond = respond
    with pytest.raises(RuntimeError):
        with client.change_set() as recorded:
            assert recorded.session.change_set == "3"
            recorded.cubes["c"].update(m.Cube(rules="x"))
            raise RuntimeError("the work failed")
    assert [target(r) for r in fake.requests] == [
        "POST BeginChangeSet",
        "PATCH Cubes('c')?!ChangeSet=3",
        "POST EndChangeSet",
    ]
    assert json.loads(fake.last.content) == {"ChangeSetID": "3"}


# Batches


def test_a_batch_sends_its_calls_together_each_with_its_own_result(api: Api, fake: FakeTm1) -> None:
    def respond(request: httpx.Request) -> Any:
        assert target(request) == "POST $batch"
        body = json.loads(request.content)
        assert [r["url"] for r in body["requests"]] == ["Cubes('a')?!sandbox=s", "Cubes('b')?!sandbox=s", "Cubes?!sandbox=s"]
        assert [r["method"] for r in body["requests"]] == ["GET", "PATCH", "GET"]
        assert body["requests"][1]["body"] == {"Rules": "x"}
        return {
            "responses": [
                {"id": "1", "status": 200, "body": {"Name": "a"}},
                {"id": "2", "status": 404, "body": {"error": {"code": "1", "message": "'b' can not be found"}}},
                {"id": "3", "status": 200, "body": {"value": [{"Name": "a"}]}},
            ]
        }

    fake.respond = respond
    batch = api.client.with_sandbox("s").batch()
    a = api.run(batch.client.cubes["a"].get())
    b = api.run(batch.client.cubes["b"].update(m.Cube(rules="x")))
    every = api.run(batch.client.cubes.list())
    with pytest.raises(RuntimeError, match="hasn't run"):
        a.result()
    assert len(batch) == 3
    api.run(batch.run())
    assert a.result().name == "a"
    with pytest.raises(Tm1NotFoundError):
        b.result()
    assert [c.name for c in every.result()] == ["a"]
    with pytest.raises(RuntimeError, match="already run"):
        api.run(batch.client.cubes["c"].get())
    assert len(fake.requests) == 1


def test_a_batch_splits_a_big_queue(client: Tm1Client, fake: FakeTm1) -> None:
    def respond(request: httpx.Request) -> Any:
        ids = [r["id"] for r in json.loads(request.content)["requests"]]
        return {"responses": [{"id": i, "status": 204} for i in ids]}

    fake.respond = respond
    with client.batch() as batch:
        results = [batch.client.cubes[f"c{i}"].delete() for i in range(5)]
        batch.run(per_request=2)
    assert len(fake.requests) == 3
    assert [r.result() for r in results] == [None] * 5


def test_bytes_cant_go_in_a_batch(client: Tm1Client) -> None:
    batch = client.batch()
    with pytest.raises(ValueError, match="Bytes"):
        batch.client.contents["Applications"].contents["x.blob"].as_document().read_content()


# Async operations (Prefer: respond-async)


def _message(status: int, body: bytes, gzipped: bool = False) -> bytes:
    headers = f"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\n"
    if gzipped:
        headers += "Content-Encoding: gzip\r\n"
        body = gzip.compress(body)
    return headers.encode() + b"\r\n" + body


def test_an_async_view_polls_the_monitor_and_reads_the_result(api: Api, fake: FakeTm1) -> None:
    polls = iter([httpx.Response(202), httpx.Response(200, content=_message(200, b'{"ProcessExecuteStatusCode":"CompletedSuccessfully"}', gzipped=True))])

    def respond(request: httpx.Request) -> Any:
        if request.method == "POST":
            assert request.headers["prefer"] == "respond-async"
            # Relative to the request's URL
            return httpx.Response(202, headers={"Location": "../_async('7')"})
        assert target(request) == "GET _async('7')"
        return next(polls)

    fake.respond = respond
    result = api.run(api.client.with_async(0.001).processes["p"].execute_with_return())
    assert result.process_execute_status_code == m.ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY
    assert [target(r) for r in fake.requests] == [
        "POST Processes('p')/tm1.ExecuteWithReturn",
        "GET _async('7')",
        "GET _async('7')",
    ]


def test_an_async_result_carries_tms_refusal(api: Api, fake: FakeTm1) -> None:
    def respond(request: httpx.Request) -> Any:
        if request.method == "POST":
            return httpx.Response(202, headers={"Location": "../_async('8')"})
        return httpx.Response(200, content=_message(500, b'{"error":{"code":"174","message":"TM1UserException: Cancel"}}'))

    fake.respond = respond
    from tm1_client import Tm1ErrorKind, Tm1ServerError

    with pytest.raises(Tm1ServerError) as refused:
        api.run(api.client.with_async(0.001).processes["p"].execute())
    assert refused.value.kind is Tm1ErrorKind.CANCELLED


def test_giving_up_on_an_async_operation_deletes_its_monitor(client: Tm1Client, fake: FakeTm1) -> None:
    def respond(request: httpx.Request) -> Any:
        if request.method == "POST":
            return httpx.Response(202, headers={"Location": "../_async('9')"})
        if request.method == "GET":
            raise KeyboardInterrupt  # someone pressed Ctrl-C while waiting
        return None

    fake.respond = respond
    with pytest.raises(KeyboardInterrupt):
        client.with_async(0.001).chores["c"].execute()
    # Deleting the monitor is what stops the work on TM1
    assert target(fake.last) == "DELETE _async('9')"


def test_tm1_may_answer_an_async_request_at_once(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"Name": "c"}
    assert api.run(api.client.with_async(0.001).cubes["c"].get()).name == "c"
    assert len(fake.requests) == 1


def test_async_monitor_messages() -> None:
    reply = p.async_result(_message(201, b'{"a":1}', gzipped=True))
    assert (reply.status, reply.json()) == (201, {"a": 1})
    assert p.async_result(b"HTTP/1.1 204 No Content\r\n\r\n").status == 204
    from tm1_client import Tm1ProtocolError

    with pytest.raises(Tm1ProtocolError):
        p.async_result(b"not http")


# Capabilities


def test_the_metadata_reader_makes_the_specs_inventory(spec: dict[str, Any]) -> None:
    """Python reads $metadata into the same 242 endpoint IDs as the Java client's Endpoints."""
    caps = p.capabilities(SNAPSHOT.read_bytes())
    assert caps.schema_version == "11.8.35"
    assert caps.endpoints == {e["id"] for e in spec["endpoints"]}


OLD_SERVER = b"""<?xml version="1.0" encoding="utf-8"?>
<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
  <edmx:DataServices>
    <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
      <Annotation Term="Core.SchemaVersion"><String>11.8.20</String></Annotation>
      <EntityType Name="Cube"><Key><PropertyRef Name="Name"/></Key><Property Name="Name" Type="Edm.String"/></EntityType>
      <EntityContainer Name="API"><EntitySet Name="Cubes" EntityType="tm1.Cube"/></EntityContainer>
    </Schema>
  </edmx:DataServices>
</edmx:Edmx>"""


def _old(check: bool) -> tuple[Tm1Client, FakeTm1]:
    fake = FakeTm1(metadata=OLD_SERVER)
    http = httpx.Client(transport=httpx.MockTransport(fake.handler))
    return Tm1Client(Tm1Session(BASE, auth.basic("a"), client=http, check_capabilities=check)), fake


def test_refuses_an_operation_the_server_doesnt_have() -> None:
    client, fake = _old(check=True)
    with pytest.raises(Tm1UnsupportedOperationError, match=r"Action Cube/Reserve isn't in this server's \$metadata \(schema 11.8.20\); TM1 added it in schema 11.8.34"):
        client.cubes["c"].reserve()
    assert fake.requests == []
    # Reading entities isn't checked, and $metadata is read once
    fake.respond = lambda r: {}
    client.cubes["c"].get()
    with pytest.raises(Tm1UnsupportedOperationError):
        client.cubes["c"].release()
    assert fake.metadata_reads == 1


def test_the_check_can_be_turned_off() -> None:
    client, fake = _old(check=False)
    fake.respond = lambda r: None
    client.cubes["c"].reserve()
    assert target(fake.last) == "POST Cubes('c')/tm1.Reserve"
    assert fake.metadata_reads == 0
