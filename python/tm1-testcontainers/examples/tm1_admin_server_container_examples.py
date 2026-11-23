# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1AdminServerContainer, quoted in the guide. Test code, never run here."""

from __future__ import annotations

from skipcheck_tm1_testcontainers import Tm1AdminServerContainer, Tm1ServerContainer, Tm1Source


def several_servers(tm1_source: Tm1Source) -> str:
    # @start region = several-servers
    with Tm1AdminServerContainer(tm1_source) as admin:
        planning = Tm1ServerContainer(tm1_source).with_env("TM1_SERVER_NAME", "planning").with_admin_server(admin)
        reporting = Tm1ServerContainer(tm1_source).with_env("TM1_SERVER_NAME", "reporting").with_admin_server(admin)
        with planning, reporting:
            return admin.rest_url + "Servers"  # lists planning and reporting
    # @end
