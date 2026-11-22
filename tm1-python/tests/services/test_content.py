"""ContentService, ContentPath and ContentScope, in both flavors, against a fake TM1."""

from __future__ import annotations

import json

import httpx
import pytest

from tm1_client import ODataPath, ODataQuery
from tm1_client._sync.services.content import ContentPath, ContentScope
from tm1_client.aio.services.content import ContentPath as AsyncContentPath
from tm1_client.model import CubeReference, CubeReferencePatch, DocumentReference, Folder, FolderPatch, LinkPatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx.Request) -> object:
    return json.loads(request.content) if request.content else None


PLANNING = ContentPath.applications().child("Planning")


def test_paths_find_children_by_id() -> None:
    notes = ContentPath.applications().child("Planning").child("notes.txt.blob")
    assert str(notes.path()) == "Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')"
    assert str(notes) == "Applications/Planning/notes.txt.blob"
    private = PLANNING.child("mine", ContentScope.PRIVATE)
    assert str(private.path()) == "Contents('Applications')/Contents('Planning')/PrivateContents('mine')"
    assert str(private) == "Applications/Planning/(private) mine"
    assert PLANNING.child(scope=ContentScope.PRIVATE, id="x") == PLANNING.child("x", ContentScope.PRIVATE)
    # As TM1 returned it: by its ID, not its name
    assert PLANNING.child(Folder(name="Guide", id="Guide.extr")) == PLANNING.child("Guide.extr")
    with pytest.raises(ValueError, match="no ID"):
        PLANNING.child(Folder(name="Guide"))
    assert ContentPath.blobs().in_blobs() and not PLANNING.in_blobs()
    assert ContentPath.root("Blobs") == ContentPath.blobs()
    assert ContentScope.PRIVATE.navigation() == "PrivateContents"
    # One class for both flavors' behaviour: the asyncio one is the same code
    assert str(AsyncContentPath.applications().child("x").path()) == "Contents('Applications')/Contents('x')"


def test_reads_the_tree(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"@odata.type": "#ibm.tm1.api.v1.Folder", "Name": "Applications", "ID": "Applications"}]}
    roots = api.run(api.client.contents.roots())
    assert isinstance(roots[0], Folder) and target(fake.last) == "GET Contents"
    fake.respond = lambda r: {"value": [{"@odata.type": "#ibm.tm1.api.v1.CubeReference", "Name": "Sales", "Cube": {"Name": "Sales"}}]}
    entries = api.run(
        api.client.contents.contents(PLANNING, ContentScope.PUBLIC, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)"))
    )
    assert isinstance(entries[0], CubeReference) and entries[0].cube.name == "Sales"
    assert target(fake.last) == "GET Contents('Applications')/Contents('Planning')/Contents?$expand=tm1.CubeReference/Cube($select%3DName)"
    api.run(api.client.contents.contents(PLANNING, ContentScope.PRIVATE))
    assert target(fake.last) == "GET Contents('Applications')/Contents('Planning')/PrivateContents"
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.Folder", "Name": "Planning"}
    assert isinstance(api.run(api.client.contents.get(PLANNING)), Folder)
    assert target(fake.last) == "GET Contents('Applications')/Contents('Planning')"
    api.run(api.client.contents.contained_by(PLANNING.child("x")))
    assert target(fake.last) == "GET Contents('Applications')/Contents('Planning')/Contents('x')/ContainedBy"
    fake.respond = lambda r: {"value": []}
    api.run(api.client.contents.localized_attributes(PLANNING))
    assert target(fake.last) == "GET Contents('Applications')/Contents('Planning')/LocalizedAttributes"


def test_makes_copies_moves_and_deletes(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.Folder", "Name": "Planning", "ID": "Planning"}
    api.run(api.client.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name("Planning").build()))
    assert (target(fake.last), body(fake.last)) == (
        "POST Contents('Applications')/Contents",
        {"@odata.type": "#ibm.tm1.api.v1.Folder", "Name": "Planning"},
    )
    api.run(api.client.contents.create(PLANNING, ContentScope.PRIVATE, LinkPatch.builder().name("Guide").url("https://example.com").build()))
    assert target(fake.last) == "POST Contents('Applications')/Contents('Planning')/PrivateContents"
    api.run(
        api.client.contents.create(
            PLANNING, ContentScope.PUBLIC, CubeReferencePatch.builder().name("Sales").bind_cube(ODataPath.of("Cubes").key("Sales")).build()
        )
    )
    assert body(fake.last) == {"@odata.type": "#ibm.tm1.api.v1.CubeReference", "Name": "Sales", "Cube@odata.bind": "Cubes('Sales')"}

    api.run(api.client.contents.copy(PLANNING.child("a.blob"), ContentPath.applications(), ContentScope.PRIVATE, "b"))
    assert (target(fake.last), body(fake.last)) == (
        "POST Contents('Applications')/Contents('Planning')/Contents('a.blob')/tm1.Copy",
        {"Target@odata.bind": "Contents('Applications')", "Private": True, "Name": "b"},
    )
    api.run(api.client.contents.move(PLANNING.child("a.blob"), ContentPath.applications(), ContentScope.PUBLIC))
    assert (target(fake.last), body(fake.last)) == (
        "POST Contents('Applications')/Contents('Planning')/Contents('a.blob')/tm1.Move",
        {"Target@odata.bind": "Contents('Applications')", "Private": False},
    )
    fake.respond = lambda r: None
    api.run(api.client.contents.delete(PLANNING))
    assert target(fake.last) == "DELETE Contents('Applications')/Contents('Planning')"


def test_document_content_goes_through_the_reference(api: Api, fake: FakeTm1) -> None:
    notes = PLANNING.child("notes.txt.blob")
    fake.respond = lambda r: {"Name": "notes_1.txt", "Size": 3}
    assert api.run(api.client.contents.document(notes)).size == 3
    assert target(fake.last) == "GET Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document"
    fake.respond = lambda r: httpx.Response(200, content=b"\x00abc")
    assert api.run(api.client.contents.content(notes)) == b"\x00abc"
    assert target(fake.last).endswith("/tm1.DocumentReference/Document/Content")
    # In Blobs, the path is the document itself
    api.run(api.client.contents.content(ContentPath.blobs().child("notes_1.txt")))
    assert target(fake.last) == "GET Contents('Blobs')/Contents('notes_1.txt')/tm1.Document/Content"
    fake.respond = lambda r: None
    api.run(api.client.contents.upload(notes, b"hello"))
    assert target(fake.last).startswith("PUT ") and fake.last.content == b"hello"
    assert fake.last.headers["content-length"] == "5"


def test_a_document_reference_reads_as_one(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.DocumentReference", "Name": "notes.txt", "ID": "notes.txt.blob"}
    entry = api.run(api.client.contents.get(PLANNING.child("notes.txt.blob")))
    assert isinstance(entry, DocumentReference)
    assert PLANNING.child(entry) == PLANNING.child("notes.txt.blob")
