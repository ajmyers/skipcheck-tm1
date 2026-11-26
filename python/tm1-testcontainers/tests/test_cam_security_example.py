# SPDX-License-Identifier: Apache-2.0

"""Java's CamSecurityExampleTest: three users, three TM1 security profiles, through Cognos (CAM)
security: an administrator, an analyst who may write to a cube, and a viewer who may only read it.
Cognos takes a couple of minutes to start, so there is one test. Skipped without TM1 and Cognos
sources."""

from __future__ import annotations

import json
from contextlib import closing

import pytest
from tm1_rest import Tm1Rest

from skipcheck_tm1_testcontainers import CognosContainer, CognosSource, Tm1ServerContainer, Tm1Source


@pytest.mark.requires_cognos
def test_each_user_gets_their_groups_rights(cognos: CognosSource) -> None:
    tm1_source = Tm1Source.discover()
    assert tm1_source is not None
    container = (
        CognosContainer(cognos)
        .with_user("admin", "adminpw", "Admins")
        .with_user("analyst", "analystpw", "Analysts", "Readers")
        .with_user("viewer", "viewerpw", "Readers")
    )
    tm1 = Tm1ServerContainer(tm1_source).with_cam_security(container, "admin")
    with closing(container), closing(tm1):
        tm1.start()

        with (
            Tm1Rest(tm1, container.cam_authorization("admin")) as admin,
            Tm1Rest(tm1, container.cam_authorization("analyst")) as analyst,
            Tm1Rest(tm1, container.cam_authorization("viewer")) as viewer,
        ):
            # TM1 knows users by CAMID, and puts them in their Cognos groups at logon
            assert '"Type":"Admin"' in admin.get("ActiveUser?$select=Type")
            groups = analyst.get("ActiveUser/Groups?$select=Name")
            assert r"CAMID(\"LDAP:g:cn=analysts,ou=groups\")" in groups
            assert r"CAMID(\"LDAP:g:cn=readers,ou=groups\")" in groups

            # The administrator builds a cube and its security: Analysts write, Readers read
            assert admin.post("ExecuteProcessWithReturn", set_up_security()).status_code == 201

            assert analyst.post("Cubes('Sales')/tm1.Update", write("150")).status_code == 204
            cells = viewer.post("ExecuteMDX?$expand=Cells($select=Value)", '{"MDX":"SELECT {[Measure].[Amount]} ON 0, {[Region].[North]} ON 1 FROM [Sales]"}')
            assert '"Value":150' in cells.text
            refused = viewer.post("Cubes('Sales')/tm1.Update", write("999"))
            assert refused.status_code == 400
            assert "CubeCellWriteStatusCubeNoWriteAccess" in refused.text


def set_up_security() -> str:
    """A TurboIntegrator process that creates the Sales cube and gives the groups access."""
    analysts = r"CAMID(\"LDAP:g:cn=analysts,ou=groups\")"
    readers = r"CAMID(\"LDAP:g:cn=readers,ou=groups\")"
    prolog = r"\n".join(
        [
            "DimensionCreate('Region');",
            "DimensionElementInsert('Region', '', 'North', 'N');",
            "DimensionCreate('Measure');",
            "DimensionElementInsert('Measure', '', 'Amount', 'N');",
            "CubeCreate('Sales', 'Region', 'Measure');",
            "If(CubeExists('}CubeSecurity') = 0);",
            "  CubeCreate('}CubeSecurity', '}Cubes', '}Groups');",
            "EndIf;",
            "CellPutS('WRITE', '}CubeSecurity', 'Sales', '" + analysts + "');",
            "CellPutS('READ', '}CubeSecurity', 'Sales', '" + readers + "');",
            "SecurityRefresh;",
        ]
    )
    return '{"Process":{"Name":"zSetUpSecurity","PrologProcedure":"' + prolog + '"}}'


def write(value: str) -> str:
    north = "Dimensions('Region')/Hierarchies('Region')/Elements('North')"
    amount = "Dimensions('Measure')/Hierarchies('Measure')/Elements('Amount')"
    return json.dumps({"Cells": [{"Tuple@odata.bind": [north, amount]}], "Value": value})
