"""Examples for ContentService and ContentPath, quoted in the guide."""

from __future__ import annotations

from pathlib import Path

from skipcheck_tm1 import ODataPath, Tm1Client
from skipcheck_tm1.model import Cube, CubeReference, CubeReferencePatch, DocumentPatch, Entry, FolderPatch, LinkPatch
from skipcheck_tm1.services import ContentPath, ContentScope


def path() -> ContentPath:
    # @start region = path
    # Children by ID, which for most entries is the name plus a suffix for the kind
    notes = ContentPath.applications().child("Planning").child("notes.txt.blob")
    # Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')
    # @end
    return notes


def application(tm1: Tm1Client) -> Entry:
    # @start region = application
    # An application: a folder with a link and a reference to a cube
    planning = ContentPath.applications().child("Planning")
    tm1.contents.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name("Planning").build())
    tm1.contents.create(planning, ContentScope.PUBLIC, LinkPatch.builder().name("Guide").url("https://example.com/planning-guide").build())
    sales = CubeReferencePatch.builder().name("Sales").bind_cube(ODataPath.of("Cubes").key("Sales")).build()
    return tm1.contents.create(planning, ContentScope.PUBLIC, sales)
    # @end


def upload(tm1: Tm1Client, file: Path) -> None:
    # @start region = upload
    # A document made in an application is a reference to one TM1 keeps in Blobs; the
    # reference, as create returns it, is where its content goes
    planning = ContentPath.applications().child("Planning")
    reference = tm1.contents.create(planning, ContentScope.PUBLIC, DocumentPatch.builder().name("notes.txt").build())
    tm1.contents.upload(planning.child(reference), file.read_bytes())
    # @end


def download(tm1: Tm1Client, file: Path) -> None:
    # @start region = download
    # The document's content comes back whole, as bytes
    notes = ContentPath.applications().child("Planning").child("notes.txt.blob")
    file.write_bytes(tm1.contents.content(notes))
    # @end


def cubes(tm1: Tm1Client) -> list[str | None]:
    # @start region = cubes
    # The cubes an application refers to, by a cast expand
    entries = tm1.contents.contents(ContentPath.applications().child("Planning"), ContentScope.PUBLIC, expand="tm1.CubeReference/Cube($select=Name)")
    return [entry.cube.name for entry in entries if isinstance(entry, CubeReference) and isinstance(entry.cube, Cube)]
    # @end
