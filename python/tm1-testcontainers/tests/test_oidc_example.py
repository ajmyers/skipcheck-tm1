# SPDX-License-Identifier: Apache-2.0

"""Java's OidcExampleTest: TM1 in CAM security through Cognos's OIDC namespace, with Keycloak as
the provider. Two ways in, without a browser: a password, which Cognos checks with Keycloak
(CAMNamespace ...:OIDC), and an ID token an application already holds, exchanged at Cognos for a
CAM passport (CAMPassport). Skipped without TM1 and Cognos sources."""

from __future__ import annotations

from contextlib import closing

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import CognosContainer, CognosSource, KeycloakContainer, Namespace, Tm1ServerContainer, Tm1Source


@pytest.mark.requires_cognos
def test_passwords_and_tokens_log_on_to_tm1(cognos: CognosSource) -> None:
    tm1_source = Tm1Source.discover()
    assert tm1_source is not None
    keycloak = KeycloakContainer()
    container = CognosContainer(cognos).with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers").with_keycloak(keycloak)
    tm1 = Tm1ServerContainer(tm1_source).with_cam_security(container, Namespace.OIDC, "admin")
    with closing(keycloak), closing(container), closing(tm1):
        tm1.start()

        # A password: TM1 hands it to Cognos, which checks it with Keycloak
        with Tm1Rest(tm1, container.cam_authorization("admin", Namespace.OIDC)) as admin:
            user = admin.get("ActiveUser?$select=Name,Type")
            assert r"CAMID(\"OIDC:u:admin\")" in user
            assert '"Type":"Admin"' in user

        # A token: the app holds viewer's ID token, and Cognos turns it into a passport
        passport = container.passport(keycloak.id_token("viewer", "viewerpw"))
        with Tm1Rest(tm1, "CAMPassport " + passport) as viewer:
            assert r"CAMID(\"OIDC:g:readers\")" in viewer.get("ActiveUser/Groups?$select=Name")
