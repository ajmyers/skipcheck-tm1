# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1WebExampleTest: TM1 Web against a TM1 server of its own, here with IBM's SData sample,
for tests that need data in TM1 Web or a server no other test touches. with_tm1_server gives the
TM1 server a Docker network shared with TM1 Web, and starting TM1 Web starts the server first.
Skipped without TM1 and TM1 Web sources."""

from __future__ import annotations

from contextlib import closing

import pytest
from tm1_web_session import Tm1WebSession

from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer, Tm1WebContainer


@pytest.mark.requires_tm1_web
def test_logs_on_to_its_own_server_through_tm1_web(tm1_web: Tm1WebContainer, tm1_server: Tm1ServerContainer) -> None:
    # Same sources as the shared containers; pass your own Tm1Source/Tm1WebSource instead
    tm1 = Tm1ServerContainer(tm1_server.source).with_seed(Tm1Seed.SDATA)
    web = Tm1WebContainer(tm1_web.source).with_tm1_server(tm1)
    with closing(tm1), closing(web):
        web.start()

        # TM1 Web drives a browser; Tm1WebSession stands in for one here
        session = Tm1WebSession(web)
        assert session.servers(web.admin_host) == ["tm1"]
        tm1_session_id = session.login(web.admin_host, "tm1", tm1.username, tm1.password)
        assert tm1_session_id.strip()

        # What a universal report does: evaluate MDX through TM1 Web's evalservice
        service_root = f"http://{tm1.container_info['Config']['Hostname']}:{Tm1ServerContainer.REST_PORT}"
        result = session.evaluate_mdx(
            service_root,
            "tm1",
            tm1_session_id,
            "SELECT {[Model].[Model].Members} ON 0 FROM [SalesCube] WHERE ([Actvsbud].[Actual], [Region].[World], [Account1].[Sales], [Month].[Year])",
        )
        assert '"success":true' in result
