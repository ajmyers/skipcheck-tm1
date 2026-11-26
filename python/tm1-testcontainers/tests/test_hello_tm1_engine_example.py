# SPDX-License-Identifier: Apache-2.0

"""Java's HelloTm1EngineExampleTest: a first test against TM1 v12. The requires_tm1_engine marker
starts one engine for the run, and the tm1_database fixture gives this module a database of its
own. TM1 v12 has no passwords: ``db.login()`` logs in as ``admin`` through the engine's application.
Skipped without a TM1 v12 source."""

from __future__ import annotations

import pytest
import tm1_engine_rest

from skipcheck_tm1 import Tm1Version
from skipcheck_tm1_testcontainers import Tm1Database

pytestmark = pytest.mark.requires_tm1_engine


def test_reads_the_version(tm1_database: Tm1Database) -> None:
    db = tm1_database
    response = tm1_engine_rest.get(db, db.login(), "Configuration/ProductVersion/$value")

    assert response.status_code == 200
    assert Tm1Version.parse(response.text) == db.version
    assert db.version.is_at_least(Tm1Version.parse("12"))


def test_logs_in_as_the_administrator(tm1_database: Tm1Database) -> None:
    db = tm1_database
    response = tm1_engine_rest.get(db, db.login(), "ActiveUser?$select=Name,Type")

    assert response.status_code == 200
    assert '"Type":"Admin"' in response.text
