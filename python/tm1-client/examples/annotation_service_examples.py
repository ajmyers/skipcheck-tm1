# SPDX-License-Identifier: Apache-2.0

"""Examples for AnnotationService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import Annotation, AnnotationPatch


def annotate(tm1: Tm1Client) -> Annotation:
    # @start region = annotate
    # A comment on one cell, as Planning Analytics Workspace makes it: the cell's
    # elements by dimension, in the cube's dimension order
    cell = {"Version": "Forecast", "Region": "North", "Measure": "Amount"}
    return tm1.annotations.create("Sales", cell, "Includes the March promotion")
    # @end


def read(tm1: Tm1Client) -> list[str]:
    # @start region = read
    # GET Annotations lists none on TM1 11.8, so read them by cube
    return [f"{annotation.creator}: {annotation.text}" for annotation in tm1.annotations.cube_annotations("Sales")]
    # @end


def edit(tm1: Tm1Client, id: str) -> None:
    # @start region = edit
    # TM1 keeps Text and the commentValue PAW shows apart, so change both
    text = "Includes the March and April promotions"
    tm1.annotations.update(id, AnnotationPatch.builder().text(text).property("commentValue", text).build())
    # @end
