# SPDX-License-Identifier: Apache-2.0

"""Attributes and translations of processes and chores against a real TM1: a port of the Java
client's ProcessAttributeTm1Test."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest

from skipcheck_tm1 import ODataPath, Tm1Client
from skipcheck_tm1._sync.services.attribute import AttributeOwner, AttributeSet
from skipcheck_tm1.model import (
    ChoreCreate,
    ChoreExecutionMode,
    ChoreTaskPatch,
    ProcessCreate,
)

from .conftest import CONFIGURED, REASON
from .support import (
    french,
    french_value,
    label,
    string_attribute,
)

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


@pytest.fixture
def process_and_chore(tm1: Tm1Client, name: str) -> Iterator[tuple[str, str]]:
    process, chore = name, name + "-chore"
    tm1.processes.create(ProcessCreate.builder().name(process).prolog_procedure("nX = 1;\n").build())
    try:
        tm1.chores.create(
            ChoreCreate.builder()
            .name(chore)
            .start_time(datetime(2026, 1, 1, 2, 0, tzinfo=UTC))
            .dst_sensitive(False)
            .active(False)
            .execution_mode(ChoreExecutionMode.SINGLE_COMMIT)
            .frequency(timedelta(days=1))
            .tasks([ChoreTaskPatch.builder().bind_process(ODataPath.of("Processes").key(process)).build()])
            .build()
        )
        try:
            yield process, chore
        finally:
            tm1.chores.delete(chore)
    finally:
        tm1.processes.delete(process)


@pytest.mark.parametrize("kind", ["process", "chore"])
def test_defines_and_translates_attributes(tm1: Tm1Client, process_and_chore: tuple[str, str], kind: str) -> None:
    process, chore = process_and_chore
    attribute_set = AttributeSet.processes() if kind == "process" else AttributeSet.chores()
    owner = AttributeOwner.process(process) if kind == "process" else AttributeOwner.chore(chore)
    attribute = label()
    tm1.attributes.create(attribute_set, string_attribute(attribute))
    try:
        assert attribute in [a.name for a in tm1.attributes.list(attribute_set)]
        tm1.localized_attributes.create(owner, french(attribute, "Charger"))
        assert french_value(tm1, owner, attribute) == "Charger"
    finally:
        tm1.attributes.delete(attribute_set, attribute)
