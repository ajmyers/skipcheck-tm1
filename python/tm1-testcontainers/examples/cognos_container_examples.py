# SPDX-License-Identifier: Apache-2.0

"""Examples for CognosContainer, quoted in the guide. Test code, never run here."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client, auth
from skipcheck_tm1.model import User
from skipcheck_tm1_testcontainers import CognosContainer, CognosSource, Tm1ServerContainer, Tm1Source


def cam_security(cognos_source: CognosSource, tm1_source: Tm1Source) -> str | None:
    # @start region = cam-security
    cognos = CognosContainer(cognos_source).with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers")
    # Entering the block starts the server, and Cognos first; leaving it stops the server
    with cognos, Tm1ServerContainer(tm1_source).with_cam_security(cognos, "admin") as server:
        # Each test user logs in with their own identity, through the LDAP namespace
        viewer = auth.cam_namespace("viewer", "viewerpw", CognosContainer.NAMESPACE)
        with Tm1Client.builder().base_url(server.rest_url).authenticator(viewer).build() as tm1:
            # TM1 knows the user by their CAMID
            return tm1.users.active_user(ODataQuery.none().select(User.NAME)).name
    # @end
