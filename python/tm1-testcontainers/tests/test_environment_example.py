# SPDX-License-Identifier: Apache-2.0

"""Java's EnvironmentExampleTest: a whole environment started in Docker. Skipped without a TM1
source."""

from __future__ import annotations

import pytest
from testcontainers.core.docker_client import DockerClient

from skipcheck_tm1_testcontainers import PlanningAnalyticsEnvironment, reuse_enabled


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
