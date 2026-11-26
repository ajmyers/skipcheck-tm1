# SPDX-License-Identifier: Apache-2.0

"""Java's EnvironmentExampleTest: a whole environment started in Docker. Skipped without a TM1
source."""

from __future__ import annotations

from contextlib import closing

import pytest
from testcontainers.core.docker_client import DockerClient
from tm1_rest import Tm1Rest
from tm1_web_session import Tm1WebSession

from skipcheck_tm1_testcontainers import Namespace, PlanningAnalyticsEnvironment, Tm1Seed, reuse_enabled


@pytest.mark.requires_tm1_web
def test_two_servers_and_tm1_web() -> None:
    env = PlanningAnalyticsEnvironment.builder().tm1("planning", lambda tm1: tm1.with_seed(Tm1Seed.SDATA)).tm1("reporting").tm1_web().build()
    with closing(env):
        env.start()

        # Two servers share one Admin Server, and TM1 Web lists both
        listed = Tm1WebSession(env.tm1_web()).servers(env.tm1_web().admin_host)
        assert "planning" in listed and "reporting" in listed
        with Tm1Rest(env.tm1("planning")) as planning, Tm1Rest(env.tm1("reporting")) as reporting:
            assert planning.model_cube_count() > 0
            assert reporting.model_cube_count() == 0


@pytest.mark.requires_cognos
def test_two_servers_in_cam_security_with_ldap_and_oidc() -> None:
    env = (
        PlanningAnalyticsEnvironment.builder()
        .tm1("planning")
        .tm1("reporting")
        .cognos(lambda cognos: cognos.with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers"))
        .keycloak()
        .cam_security(Namespace.OIDC, "admin")
        .build()
    )
    with closing(env):
        env.start()

        cognos = env.cognos()
        viewer_passport = "CAMPassport " + cognos.passport(env.keycloak().id_token("viewer", "viewerpw"))
        for server in env.tm1_servers().values():
            with Tm1Rest(server, cognos.cam_authorization("admin", Namespace.OIDC)) as admin, Tm1Rest(server, viewer_passport) as viewer:
                assert '"Type":"Admin"' in admin.get("ActiveUser?$select=Type")
                assert r"CAMID(\"OIDC:g:readers\")" in viewer.get("ActiveUser/Groups?$select=Name")


@pytest.mark.requires_tm1
def test_a_reused_environment_keeps_its_containers_for_the_next_run() -> None:
    if not reuse_enabled():
        pytest.skip("Testcontainers reuse is off: set testcontainers.reuse.enable=true in ~/.testcontainers.properties")
    # A configuration of its own, so it can't pick up a developer's reused environment
    builder = PlanningAnalyticsEnvironment.builder().tm1("reuse-test", lambda tm1: tm1.with_env("TM1_CFG_MaxThreads", "3")).reuse(True)
    first = builder.build()
    first.start()
    container = first.tm1("reuse-test").container_id
    first.stop()
    second = builder.build()
    try:
        second.start()
        assert second.tm1("reuse-test").container_id == container
    finally:
        docker = DockerClient().client
        docker.containers.get(container).remove(force=True, v=True)
        docker.networks.get(second.network().name).remove()
