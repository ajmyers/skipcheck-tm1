# SPDX-License-Identifier: Apache-2.0

"""Java's DatabasePerClassExampleTest: a database per test module, or one shared. Java's test class
is pytest's module here, the unit a test file shares set-up over: by default each module gets a new
database, so it can create what it likes under any name and leave it there.
``requires_tm1_engine(share_database=True)`` uses the engine's one shared database instead, which
costs nothing to get but is shared with every other such test. Skipped without a TM1 v12 source."""

from __future__ import annotations

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import Tm1Database, Tm1EngineContainer

pytestmark = pytest.mark.requires_tm1_engine

DIMENSION = '{"Name":"Region","Hierarchies":[{"Name":"Region"}]}'


# The tests run in the order they're written, as Java's do with @Order
def test_the_class_has_a_database_of_its_own(tm1_database: Tm1Database, tm1_engine: Tm1EngineContainer) -> None:
    db = tm1_database
    # Named after the module, as far as a name's length allows, with a few random characters
    assert db.name.startswith("testdatabaseperclassexam_")
    databases = tm1_engine.databases()
    assert db in databases and tm1_engine.database("tm1") in databases

    # No need for a unique name: nothing else is in here
    assert tm1_engine_rest.post(db, db.login(), "Dimensions", DIMENSION).status_code == 201


def test_every_test_of_the_class_sees_the_same_database(tm1_database: Tm1Database) -> None:
    db = tm1_database
    assert tm1_engine_rest.get(db, db.login(), "Dimensions('Region')?$select=Name").status_code == 200


# Java's nested class: the marker nearest the test counts, so this one shares
@pytest.mark.requires_tm1_engine(share_database=True)
def test_a_sharing_class_gets_the_engines_own_database(tm1_database: Tm1Database) -> None:
    db = tm1_database
    assert db.name == Tm1EngineContainer.DEFAULT_DATABASE
    # Which the module's dimension isn't in
    assert tm1_engine_rest.get(db, db.login(), "Dimensions('Region')?$select=Name").status_code == 404
