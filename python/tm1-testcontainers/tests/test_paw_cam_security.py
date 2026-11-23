# SPDX-License-Identifier: Apache-2.0

"""Java's PawCamSecurityTest: PAW with Cognos (CAM) security, in a PlanningAnalyticsEnvironment.
PAW and Cognos together need most of a developer machine's Docker memory, so nothing else should
run beside it. Skipped without TM1, Cognos and PAW sources."""

from __future__ import annotations

import pytest

from skipcheck_tm1_testcontainers import PawSession, PlanningAnalyticsEnvironment


@pytest.mark.requires_cognos
@pytest.mark.requires_paw
def test_logs_on_through_cognos_and_calls_tm1() -> None:
    env = (
        PlanningAnalyticsEnvironment.builder()
        .tm1("planning")
        .cognos(lambda cognos: cognos.with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers"))
        .paw(lambda paw: paw.accept_license())
        .build()
    )
    with env:
        env.start()
        url = env.paw().url

        # Browsers are sent to Cognos, as the host reaches it, to log on
        page = PawSession(url).get("login")
        assert f'extLoginUrl = "{env.cognos().url}v1/disp?b_action=xts.run' in page.text

        # A test user logs on with their Cognos password, and calls TM1 as themselves
        viewer = PawSession(url).login("viewer", "viewerpw")
        assert "planning" in viewer.servers()
        user = viewer.tm1_get("planning", "ActiveUser?$select=Name")
        assert user.status_code == 200
        assert "CAMID" in user.text and "viewer" in user.text.lower()

        # Cognos checks the password
        with pytest.raises(OSError):
            PawSession(url).login("viewer", "not-the-password")
