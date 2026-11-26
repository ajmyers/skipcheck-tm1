# SPDX-License-Identifier: Apache-2.0

"""ContentService against a real TM1: a port of the Java client's ContentServiceTm1Test."""

from __future__ import annotations

import os
from collections.abc import Iterator

import httpx2
import pytest

from skipcheck_tm1 import ODataPath, Tm1BadRequestError, Tm1Client, Tm1NotFoundError, Tm1Request
from skipcheck_tm1._sync.services.content import ContentPath, ContentScope
from skipcheck_tm1.model import CubeReference, CubeReferenceCreate, DocumentCreate, DocumentReference, Folder, FolderCreate, Link, LinkCreate

from .conftest import CONFIGURED, REASON, target

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_lists_the_roots(tm1: Tm1Client) -> None:
    assert {"Applications", "Blobs"} <= {e.name for e in tm1.contents.roots()}


def test_keeps_a_document_in_blobs_behind_a_reference(tm1: Tm1Client, name: str) -> None:
    folder = ContentPath.applications().child(name)
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
    # TM1 keeps a document as }externals/<name>_<second>.<ext>, server-wide: two made with one name
    # in one second collide (400, code 226), so each test names its own
    doc = f"{name}-notes"
    try:
        made = tm1.contents.create(folder, ContentScope.PUBLIC, DocumentCreate.builder().name(f"{doc}.txt").build())
        assert isinstance(made, DocumentReference), "a document made in an application is a reference"
        assert made.id == f"{doc}.txt.blob", "found by ID, the name plus a suffix"
        notes = folder.child(made)
        with pytest.raises(Tm1NotFoundError):
            tm1.contents.get(folder.child(f"{doc}.txt"))  # not by name

        tm1.contents.upload(notes, b"hello there")
        assert tm1.contents.content(notes) == b"hello there"
        document = tm1.contents.document(notes)
        assert document.size == 11
        assert document.name.startswith(f"{doc}_") and document.name.endswith(".txt"), "named by TM1, with a timestamp"
        blob = ContentPath.blobs().child(document.id)
        assert tm1.contents.content(blob) == b"hello there"

        copy = tm1.contents.copy(notes, folder, ContentScope.PUBLIC, f"{doc}-copy.txt")
        assert copy.id == f"{doc}-copy.txt.blob"
        assert tm1.contents.document(folder.child(copy.id)).id == document.id, "a copy refers to the same document"
        tm1.contents.delete(notes)
        assert tm1.contents.content(blob) == b"hello there", "kept while the copy refers to it"
    finally:
        tm1.contents.delete(folder)


def test_makes_links_and_references_and_moves_them(tm1: Tm1Client, name: str) -> None:
    folder = ContentPath.applications().child(name)
    other = ContentPath.applications().child(name + "-2")
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name + "-2").build())
    try:
        link = tm1.contents.create(folder, ContentScope.PUBLIC, LinkCreate.builder().name("ibm").url("https://www.ibm.com").build())
        assert link.id == "ibm.extr"
        reference = tm1.contents.create(
            folder,
            ContentScope.PUBLIC,
            CubeReferenceCreate.builder().name("}ClientGroups").bind_cube(ODataPath.of("Cubes").key("}ClientGroups")).build(),
        )
        assert reference.id == "}ClientGroups.cube"
        listed = tm1.contents.contents(folder, ContentScope.PUBLIC, expand="tm1.CubeReference/Cube($select=Name)")
        references = [e for e in listed if isinstance(e, CubeReference)]
        assert [r.cube.name for r in references] == ["}ClientGroups"]
        got = tm1.contents.get(folder.child(link.id))
        assert isinstance(got, Link) and got.url == "https://www.ibm.com"
        assert tm1.contents.contained_by(folder.child(link.id)).id == name

        with pytest.raises(Tm1BadRequestError):
            # Entries can't be changed
            tm1.session.execute(Tm1Request.patch(folder.child(link.id).path(), {"Name": "renamed"}))

        moved = tm1.contents.move(folder.child(link.id), other, ContentScope.PUBLIC, None)
        assert moved.id == "ibm.extr"
        assert [e.id for e in tm1.contents.contents(other, ContentScope.PUBLIC)] == ["ibm.extr"]

        tm1.contents.create(folder, ContentScope.PRIVATE, FolderCreate.builder().name("mine").build())
        assert [e.id for e in tm1.contents.contents(folder, ContentScope.PRIVATE)] == ["mine"]
        assert isinstance(tm1.contents.get(folder.child("mine", ContentScope.PRIVATE)), Folder)
        assert tm1.contents.localized_attributes(folder) == []
    finally:
        tm1.contents.delete(folder)
        tm1.contents.delete(other)


def test_refuses_a_chunked_upload(tm1: Tm1Client, name: str) -> None:
    folder = ContentPath.applications().child(name)
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
    try:
        # A name of its own: see test_keeps_a_document_in_blobs_behind_a_reference
        made = tm1.contents.create(folder, ContentScope.PUBLIC, DocumentCreate.builder().name(f"{name}-notes.txt").build())
        url, user, password = target()
        content = f"{url.rstrip('/')}/{folder.child(made.id).path()}/tm1.DocumentReference/Document/Content"

        def pieces() -> Iterator[bytes]:
            yield b"hello"

        # A body of unknown length is sent chunked
        chunked = httpx2.put(
            content,
            content=pieces(),
            auth=(user, password),
            headers={"Content-Type": "application/octet-stream"},
            verify=os.environ.get("TM1_VERIFY", "true").lower() != "false",
        )
        assert chunked.request.headers["Transfer-Encoding"] == "chunked"
        assert chunked.status_code == 400
    finally:
        tm1.contents.delete(folder)
