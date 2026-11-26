# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineEnvironmentExampleTest: TM1 11 and TM1 v12 in one environment.
PlanningAnalyticsEnvironment starts a TM1 server and a TM1 v12 engine on one network, for tests of
both versions, or of moving from one to the other. It needs a source for each, and is skipped
without either."""

from __future__ import annotations

from contextlib import closing

import pytest
import tm1_engine_rest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import PlanningAnalyticsEnvironment


@pytest.mark.requires_tm1
@pytest.mark.requires_tm1_engine(share_database=True)
def test_a_server_and_an_engine_side_by_side() -> None:
    env = (
        PlanningAnalyticsEnvironment.builder()
        .tm1("legacy")
        .tm1_engine(lambda engine: engine.with_database("planning").with_user("analyst", "Analysts"))
        .build()
    )
    with closing(env):
        env.start()

        with Tm1Rest(env.tm1("legacy")) as legacy:
            assert legacy.model_cube_count() == 0
        planning = env.tm1_database("planning")
        response = tm1_engine_rest.get(planning, planning.login("analyst"), "ActiveUser?$select=Name")
        assert response.status_code == 200
        assert '"name":"analyst"' in response.text.lower()
