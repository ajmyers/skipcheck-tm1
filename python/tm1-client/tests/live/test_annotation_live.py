"""AnnotationService against a real TM1: a port of the Java client's AnnotationServiceTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import ODataQuery, Tm1Client, Tm1NotFoundError
from skipcheck_tm1.model import AnnotationPatch

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

CUBE = "}ClientGroups"
CELL = {"}Clients": "Admin", "}Groups": "ADMIN"}


def test_annotates_a_cell_and_finds_it_by_cube_cell_and_id(tm1: Tm1Client) -> None:
    made = tm1.annotations.create(CUBE, CELL, "first note")
    id = made.id
    try:
        assert id.startswith("C" + CUBE + "~")
        extra = made.model_extra or {}
        assert {"objectName", "commentValue", "commentLocation"} <= set(extra)
        assert extra["commentLocation"] == "Admin,ADMIN"

        assert tm1.annotations.list() == [], "TM1 lists none here"
        assert id in [a.id for a in tm1.annotations.cube_annotations(CUBE)]
        mdx = f"SELECT {{[}}Clients].[Admin]}} ON 0, {{[}}Groups].[ADMIN]}} ON 1 FROM [{CUBE}]"
        cellset = tm1.cellsets.execute(mdx, ODataQuery.none().select("ID"))
        try:
            assert [a.id for a in tm1.annotations.cell_annotations(cellset.id, 0)] == [id]
        finally:
            tm1.cellsets.delete(cellset.id)

        members = tm1.annotations.dimensional_context(id, ODataQuery.none().select("UniqueName"))
        assert [m.unique_name for m in members] == ["[}Clients].[}Clients].[Admin]", "[}Groups].[}Groups].[ADMIN]"]
        assert tm1.annotations.application_context(id) == [], "reads back empty, though the facet value exists"
        assert CUBE in [v.value for v in tm1.annotations.facet_values("}Cubes")]
        assert tm1.annotations.facet("}Cubes", CUBE).name == "}Cubes"
        assert "}Cubes" in [f.name for f in tm1.annotations.facets()]
        assert tm1.annotations.attachments(id) == []

        tm1.annotations.update(id, AnnotationPatch.builder().text("second note").build())
        changed = tm1.annotations.get(id)
        assert changed.text == "second note"
        assert changed.last_updated_by is not None
        assert (changed.model_extra or {})["commentValue"] == "first note", "kept apart from Text"
    finally:
        tm1.annotations.delete(id)
    with pytest.raises(Tm1NotFoundError):
        tm1.annotations.get(id)


def test_deletes_every_annotation_artifact(tm1: Tm1Client) -> None:
    tm1.annotations.create(CUBE, CELL, "to be swept")
    tm1.annotations.delete_annotation_artifacts()
    assert tm1.annotations.cube_annotations(CUBE) == []
