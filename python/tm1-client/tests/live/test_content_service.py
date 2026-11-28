# SPDX-License-Identifier: Apache-2.0

"""ContentService against a real TM1: a port of the Java client's ContentServiceTm1Test."""

from __future__ import annotations

import os
from collections.abc import Iterator

import httpx2
import pytest

from skipcheck_tm1 import ODataPath, Tm1BadRequestError, Tm1Client, Tm1NotFoundError, Tm1Request
from skipcheck_tm1._sync.services.content import ContentPath, ContentScope
from skipcheck_tm1.model import CubeReference, CubeReferenceCreate, Document, DocumentCreate, DocumentReference, Folder, FolderCreate, Link, LinkCreate

from .conftest import CONFIGURED, IS_ENGINE, REASON, target
from .support import is_v12

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_lists_the_roots(tm1: Tm1Client) -> None:
    roots = {e.name for e in tm1.contents.roots()}
    if is_v12(tm1):
        assert {"Applications", "Files", "Home", "Session"} <= roots
    else:
        assert {"Applications", "Blobs"} <= roots


def test_keeps_a_document_in_blobs_behind_a_reference(tm1: Tm1Client, name: str) -> None:
    v12 = is_v12(tm1)
    folder = ContentPath.applications().child(name)
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
    # TM1 keeps a document as }externals/<name>_<second>.<ext>, server-wide: two made with one name
    # in one second collide (400, code 226), so each test names its own
    doc = f"{name}-notes"
    try:
        made = tm1.contents.create(folder, ContentScope.PUBLIC, DocumentCreate.builder().name(f"{doc}.txt").build())
        assert isinstance(made, DocumentReference), "a document made in an application is a reference"
        if v12:
            assert made.id is None, "TM1 v12 omits deprecated Entry.ID"
            assert made.name == f"{doc}.txt"
        else:
            assert made.id == f"{doc}.txt.blob", "found by ID, the name plus a suffix"
        notes = folder.child(made)
        if not v12:
            with pytest.raises(Tm1NotFoundError):
                tm1.contents.get(folder.child(f"{doc}.txt"))  # not by name

        tm1.contents.upload(notes, b"hello there")
        assert tm1.contents.content(notes) == b"hello there"
        document = tm1.contents.document(notes)
        assert document.size == 11
        assert document.name.startswith(f"{doc}_") and document.name.endswith(".txt"), "named by TM1, with a timestamp"
        blob = None if v12 else ContentPath.blobs().child(document.id)
        if blob is not None:
            assert tm1.contents.content(blob) == b"hello there"

        copy = tm1.contents.copy(notes, folder, ContentScope.PUBLIC, f"{doc}-copy.txt")
        if v12:
            assert copy.name == f"{doc}-copy.txt"
            copied_doc = tm1.contents.document(folder.child(copy))
            assert copied_doc.name.startswith(f"{doc}-copy_") and copied_doc.name.endswith(".txt")
        else:
            assert copy.id == f"{doc}-copy.txt.blob"
            assert tm1.contents.document(folder.child(copy.id)).id == document.id, "a copy refers to the same document"
        tm1.contents.delete(notes)
        assert tm1.contents.content(blob if blob is not None else folder.child(copy)) == b"hello there", "kept while the copy refers to it"
    finally:
        tm1.contents.delete(folder)


def test_makes_links_and_references_and_moves_them(tm1: Tm1Client, name: str) -> None:
    v12 = is_v12(tm1)
    folder = ContentPath.applications().child(name)
    other = ContentPath.applications().child(name + "-2")
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name + "-2").build())
    try:
        link = tm1.contents.create(folder, ContentScope.PUBLIC, LinkCreate.builder().name("ibm").url("https://www.ibm.com").build())
        assert (link.name if v12 else link.id) == ("ibm" if v12 else "ibm.extr")
        reference = tm1.contents.create(
            folder,
            ContentScope.PUBLIC,
            CubeReferenceCreate.builder().name("}ClientGroups").bind_cube(ODataPath.of("Cubes").key("}ClientGroups")).build(),
        )
        assert (reference.name if v12 else reference.id) == ("}ClientGroups" if v12 else "}ClientGroups.cube")
        listed = tm1.contents.contents(folder, ContentScope.PUBLIC, expand="tm1.CubeReference/Cube($select=Name)")
        references = [e for e in listed if isinstance(e, CubeReference)]
        assert [r.cube.name for r in references] == ["}ClientGroups"]
        link_path = folder.child(link)
        got = tm1.contents.get(link_path)
        assert isinstance(got, Link) and got.url == "https://www.ibm.com"
        parent = tm1.contents.contained_by(link_path)
        assert (parent.name if v12 else parent.id) == name

        with pytest.raises(Tm1BadRequestError):
            # Entries can't be changed
            tm1.session.execute(Tm1Request.patch(link_path.path(), {"Name": "renamed"}))

        moved = tm1.contents.move(link_path, other, ContentScope.PUBLIC, None)
        assert (moved.name if v12 else moved.id) == ("ibm" if v12 else "ibm.extr")
        assert [(e.name if v12 else e.id) for e in tm1.contents.contents(other, ContentScope.PUBLIC)] == ["ibm" if v12 else "ibm.extr"]

        tm1.contents.create(folder, ContentScope.PRIVATE, FolderCreate.builder().name("mine").build())
        assert [(e.name if v12 else e.id) for e in tm1.contents.contents(folder, ContentScope.PRIVATE)] == ["mine"]
        assert isinstance(tm1.contents.get(folder.child("mine", ContentScope.PRIVATE)), Folder)
        assert tm1.contents.localized_attributes(folder) == []
    finally:
        tm1.contents.delete(folder)
        tm1.contents.delete(other)


def test_reads_and_writes_documents_in_files_home_and_session(tm1: Tm1Client, name: str) -> None:
    if not is_v12(tm1):
        pytest.skip("Files, Home and Session direct-document roots are TM1 v12")
    filename = f"{name}.txt"
    for root in (ContentPath.files(), ContentPath.home(), ContentPath.session()):
        created = tm1.contents.create(root, ContentScope.PUBLIC, DocumentCreate.builder().name(filename).build())
        assert isinstance(created, Document)
        doc = root.child(created)
        try:
            tm1.contents.upload(doc, b"direct content")
            assert tm1.contents.content(doc) == b"direct content"
            assert tm1.contents.document(doc).size == 14
        finally:
            tm1.contents.delete(doc)


def test_refuses_a_chunked_upload(tm1: Tm1Client, name: str) -> None:
    folder = ContentPath.applications().child(name)
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
    try:
        # A name of its own: see test_keeps_a_document_in_blobs_behind_a_reference
        made = tm1.contents.create(folder, ContentScope.PUBLIC, DocumentCreate.builder().name(f"{name}-notes.txt").build())
        url, user, password = target()
        content = f"{url.rstrip('/')}/{folder.child(made).path()}/tm1.DocumentReference/Document/Content"

        def pieces() -> Iterator[bytes]:
            yield b"hello"

        headers = {"Content-Type": "application/octet-stream"}
        auth_arg = None
        if IS_ENGINE:
            from skipcheck_tm1_testcontainers import Tm1EngineContainer

            db = Tm1EngineContainer.shared().database(Tm1EngineContainer.DEFAULT_DATABASE)
            headers["Cookie"] = db.login().cookie()
        else:
            auth_arg = (user, password)

        # A body of unknown length is sent chunked
        chunked = httpx2.put(
            content,
            content=pieces(),
            auth=auth_arg,
            headers=headers,
            verify=os.environ.get("TM1_VERIFY", "true").lower() != "false",
        )
        assert chunked.request.headers["Transfer-Encoding"] == "chunked"
        # TM1 11 refuses chunked uploads (400); TM1 v12's gateway accepts them (204)
        assert chunked.status_code == (204 if is_v12(tm1) else 400)
    finally:
        tm1.contents.delete(folder)
