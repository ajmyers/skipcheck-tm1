# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EnginePassThroughCamTest: pass-through logins to a TM1 11 server in Cognos (CAM)
security, against real TM1 v12, TM1 11 and Cognos. Cognos's users log in to TM1 v12 as they do to
TM1 11. Cognos takes minutes to start, so this module has one test. Skipped without TM1, Cognos and
TM1 v12 sources."""

from __future__ import annotations

from contextlib import closing

import pytest
import tm1_engine_rest

from skipcheck_tm1_testcontainers import CognosContainer, CognosSource, Tm1EngineContainer, Tm1EngineSource, Tm1ServerContainer, Tm1Source

pytestmark = [pytest.mark.requires_cognos, pytest.mark.requires_tm1, pytest.mark.requires_tm1_engine(share_database=True)]


def test_cognos_users_log_in_to_the_engine() -> None:
    cognos_source, tm1, source = CognosSource.discover(), Tm1Source.discover(), Tm1EngineSource.discover()
    assert cognos_source is not None and tm1 is not None and source is not None
    # Made, not started: the engine starts the server first, and the server its Cognos
    cognos = (
        CognosContainer(cognos_source)
        .with_user("admin", "adminpw", "Admins")
        .with_user("analyst", "analystpw", "Analysts")
        .with_user("viewer", "viewerpw", "Viewers")
    )
    server = Tm1ServerContainer(tm1).with_cam_security(cognos, "admin")
    engine = Tm1EngineContainer(source).with_user("analyst", "Analysts").with_pass_through(server)
    # Entering the block starts all three; leaving it stops the engine, the server, then Cognos
    with closing(cognos), closing(server), engine:
        db = engine.database("tm1")

        # A user the engine was given: under its CAMID, in the group it was given
        analyst = tm1_engine_rest.get(db, db.pass_through_login("analyst"), "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)")
        assert analyst.status_code == 200
        assert r"CAMID(\"LDAP:u:uid=analyst,ou=people\")" in analyst.text
        assert '"Type":"User"' in analyst.text
        assert "Analysts" in analyst.text

        # The server's administrator is one here too
        admin = tm1_engine_rest.get(db, db.pass_through_login(), "ActiveUser?$select=Name,Type")
        assert admin.status_code == 200
        assert "uid=admin," in admin.text and '"Type":"Admin"' in admin.text

        # Cognos knows the viewer, and TM1 11 logs them in; the engine has no such user
        with pytest.raises(RuntimeError, match="HTTP 403"):
            db.pass_through_login("viewer").cookie()
        # Cognos checks the password
        with pytest.raises(RuntimeError, match="HTTP 401"):
            db.pass_through_login("analyst", "not-it").cookie()
        # The database's own admin still logs in through the application
        assert tm1_engine_rest.get(db, db.login(), "ActiveUser").status_code == 200
