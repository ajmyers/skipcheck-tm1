# SPDX-License-Identifier: Apache-2.0

"""ContentService, ContentPath and ContentScope, in both flavors, against a fake TM1."""

from __future__ import annotations

import json

import httpx2
import pytest

from skipcheck_tm1 import ODataPath, ODataQuery
from skipcheck_tm1._sync.services.content import ContentPath, ContentScope
from skipcheck_tm1.aio.services.content import ContentPath as AsyncContentPath
from skipcheck_tm1.model import CubeReference, CubeReferenceCreate, DocumentReference, Folder, FolderCreate, LinkCreate

from ..conftest import Api, FakeTm1, target


def body(request: httpx2.Request) -> object:
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
    # As TM1 returned it: by its ID on TM1 11, falling back to Name on TM1 12
    assert PLANNING.child(Folder(name="Guide", id="Guide.extr")) == PLANNING.child("Guide.extr")
    assert PLANNING.child(Folder(name="Guide")) == PLANNING.child("Guide")
    assert PLANNING.child(Folder(name="Guide", id="")) == PLANNING.child("Guide")
    assert PLANNING.child(Folder(name="Guide"), ContentScope.PRIVATE) == PLANNING.child("Guide", ContentScope.PRIVATE)
    for missing in (Folder(), Folder(id=""), Folder(name=""), Folder(id="", name="")):
        with pytest.raises(ValueError, match="neither an ID nor a Name"):
            PLANNING.child(missing)
    with pytest.raises(TypeError):
        PLANNING.child(None)
    with pytest.raises(TypeError):
        PLANNING.child(Folder(name="Guide"), scope=None)  # type: ignore[arg-type]
    assert ContentPath.blobs().in_blobs() and not PLANNING.in_blobs()
    assert not ContentPath.files().in_blobs()
    assert not ContentPath.home().in_blobs()
    assert not ContentPath.session().in_blobs()
    assert ContentPath.root("Blobs") == ContentPath.blobs()
    assert str(ContentPath.files().path()) == "Contents('Files')"
    assert str(ContentPath.home().path()) == "Contents('Home')"
    assert str(ContentPath.session().path()) == "Contents('Session')"
    assert ContentScope.PRIVATE.navigation() == "PrivateContents"
    # One class for both flavors' behaviour: the asyncio one is the same code
    assert str(AsyncContentPath.applications().child("x").path()) == "Contents('Applications')/Contents('x')"


def test_reads_the_tree(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"value": [{"@odata.type": "#ibm.tm1.api.v1.Folder", "Name": "Applications", "ID": "Applications"}]}
    roots = api.run(api.client.contents.roots())
    assert isinstance(roots[0], Folder) and target(fake.last) == "GET Contents"
    fake.respond = lambda r: {"value": [{"@odata.type": "#ibm.tm1.api.v1.CubeReference", "Name": "Sales", "Cube": {"Name": "Sales"}}]}
    entries = api.run(api.client.contents.contents(PLANNING, ContentScope.PUBLIC, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)")))
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
    api.run(api.client.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name("Planning").build()))
    assert (target(fake.last), body(fake.last)) == (
        "POST Contents('Applications')/Contents",
        {"@odata.type": "#ibm.tm1.api.v1.Folder", "Name": "Planning"},
    )
    api.run(api.client.contents.create(PLANNING, ContentScope.PRIVATE, LinkCreate.builder().name("Guide").url("https://example.com").build()))
    assert target(fake.last) == "POST Contents('Applications')/Contents('Planning')/PrivateContents"
    api.run(
        api.client.contents.create(
            PLANNING, ContentScope.PUBLIC, CubeReferenceCreate.builder().name("Sales").bind_cube(ODataPath.of("Cubes").key("Sales")).build()
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
    fake.respond = lambda r: httpx2.Response(200, content=b"\x00abc")
    assert api.run(api.client.contents.content(notes)) == b"\x00abc"
    assert target(fake.last).endswith("/tm1.DocumentReference/Document/Content")
    # In Blobs, Files, Home, and Session, the path is the direct Document itself
    api.run(api.client.contents.content(ContentPath.blobs().child("notes_1.txt")))
    assert target(fake.last) == "GET Contents('Blobs')/Contents('notes_1.txt')/tm1.Document/Content"
    fake.respond = lambda r: {"Name": "q1.csv", "Size": 7}
    assert api.run(api.client.contents.document(ContentPath.files().child("reports").child("q1.csv"))).size == 7
    assert target(fake.last) == "GET Contents('Files')/Contents('reports')/Contents('q1.csv')/tm1.Document"
    fake.respond = lambda r: httpx2.Response(200, content=b"abc")
    assert api.run(api.client.contents.content(ContentPath.home().child("scratch.txt"))) == b"abc"
    assert target(fake.last) == "GET Contents('Home')/Contents('scratch.txt')/tm1.Document/Content"
    fake.respond = lambda r: None
    api.run(api.client.contents.upload(ContentPath.session().child("temp.bin"), b"ok"))
    assert target(fake.last) == "PUT Contents('Session')/Contents('temp.bin')/tm1.Document/Content"
    fake.respond = lambda r: {"Size": 4}
    api.run(api.client.contents.document(ContentPath.root("applications").child("Planning").child("notes.txt")))
    assert target(fake.last) == "GET Contents('applications')/Contents('Planning')/Contents('notes.txt')/tm1.DocumentReference/Document"
    api.run(api.client.contents.document(ContentPath.root("Custom").child("data.bin")))
    assert target(fake.last) == "GET Contents('Custom')/Contents('data.bin')/tm1.Document"
    with pytest.raises(TypeError):
        api.run(api.client.contents.document(None))  # type: ignore[arg-type]
    fake.respond = lambda r: None
    api.run(api.client.contents.upload(notes, b"hello"))
    assert target(fake.last).startswith("PUT ") and fake.last.content == b"hello"
    assert fake.last.headers["content-length"] == "5"


def test_a_document_reference_reads_as_one(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"@odata.type": "#ibm.tm1.api.v1.DocumentReference", "Name": "notes.txt", "ID": "notes.txt.blob"}
    entry = api.run(api.client.contents.get(PLANNING.child("notes.txt.blob")))
    assert isinstance(entry, DocumentReference)
    assert PLANNING.child(entry) == PLANNING.child("notes.txt.blob")
