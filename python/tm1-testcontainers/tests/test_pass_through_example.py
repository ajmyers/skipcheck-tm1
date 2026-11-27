# SPDX-License-Identifier: Apache-2.0

"""Java's PassThroughExampleTest: pass-through logins, to a TM1 11 server, the way TM1 v12 logs in
the users a TM1 11 server already has. The engine asks the server beside it who a name and password
are, so a test logs in to TM1 v12 with a login of TM1 11's. Skipped without a TM1 source and a TM1
v12 source."""

from __future__ import annotations

from contextlib import closing

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import Tm1EngineContainer, Tm1EngineSource, Tm1Seed, Tm1ServerContainer, Tm1Source


@pytest.mark.requires_tm1
@pytest.mark.requires_tm1_engine(share_database=True)
def test_a_tm1_servers_user_logs_in_to_the_engine() -> None:
    tm1, source = Tm1Source.discover(), Tm1EngineSource.discover()
    assert tm1 is not None and source is not None
    # Made, not started: the engine starts its TM1 server first, under the name it reaches it by
    server = Tm1ServerContainer(tm1).with_seed(Tm1Seed.SDATA)
    engine = Tm1EngineContainer(source).with_pass_through(server)
    # Entering the block starts both; leaving it stops the engine, then the server
    with closing(server), engine:
        db = engine.database("tm1")

        # admin / apple of the sample database: TM1 v12 keeps no password of its own
        response = tm1_engine_rest.get(db, db.pass_through_login(), "ActiveUser?$select=Name,Type")

        assert response.status_code == 200
        assert '"Type":"Admin"' in response.text
