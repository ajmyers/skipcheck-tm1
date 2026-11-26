# SPDX-License-Identifier: Apache-2.0

"""Java's OwnDatabaseExampleTest: a server running your own database, a TM1 data folder copied into
the container before TM1 starts. It uses IBM's SData sample folder from the TM1 install as "your"
database, so it only runs when TM1 comes from an install folder (TM1_HOME_DIR)."""

from __future__ import annotations

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import Tm1ServerContainer, Tm1Source


@pytest.mark.requires_tm1
def test_runs_a_database_from_a_folder(tm1_server: Tm1ServerContainer) -> None:
    source = tm1_server.source
    if not isinstance(source, Tm1Source.InstallDir):
        pytest.skip("needs TM1_HOME_DIR, to find a sample database folder")
    my_database = source.dir / "samples" / "tm1" / "SData"
    if not my_database.is_dir():
        pytest.skip("this install has no SData sample")

    with Tm1ServerContainer(source).with_data(my_database).with_credentials("admin", "apple") as server:
        with Tm1Rest(server) as tm1:
            assert "salescube" in tm1.get("Cubes('SalesCube')?$select=Name").lower()
