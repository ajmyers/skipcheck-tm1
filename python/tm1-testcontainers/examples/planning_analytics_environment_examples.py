"""Examples for PlanningAnalyticsEnvironment, quoted in the guide. Test code, never run here."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client, auth
from skipcheck_tm1.model import Cube
from skipcheck_tm1_testcontainers import CognosContainer, PlanningAnalyticsEnvironment, Tm1Seed


def servers() -> dict[str, list[str | None]]:
    # @start region = servers
    # Two servers on one Admin Server, and TM1 Web listing both
    builder = PlanningAnalyticsEnvironment.builder().tm1("planning", lambda tm1: tm1.with_seed(Tm1Seed.SDATA)).tm1("reporting").tm1_web()
    # Entering the block starts everything: TM1 Web is at env.tm1_web().url
    with builder.build() as env:
        cubes = {}
        for name, server in env.tm1_servers().items():
            with Tm1Client.builder().base_url(server.rest_url).basic_auth(server.username, server.password).build() as tm1:
                cubes[name] = [cube.name for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME))]
        return cubes  # planning: SData's cubes; reporting: none
    # @end


def cam_security() -> list[str | None]:
    # @start region = cam-security
    # Every server in CAM security, with Cognos's test users as its users
    builder = (
        PlanningAnalyticsEnvironment.builder()
        .tm1("planning")
        .cognos(lambda cognos: cognos.with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers"))
    )
    with builder.build() as env:
        viewer = auth.cam_namespace("viewer", "viewerpw", CognosContainer.NAMESPACE)
        with Tm1Client.builder().base_url(env.tm1("planning").rest_url).authenticator(viewer).build() as tm1:
            # The cubes the viewer's groups may see
            return [cube.name for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME))]
    # @end
