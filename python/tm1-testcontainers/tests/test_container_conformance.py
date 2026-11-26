# SPDX-License-Identifier: Apache-2.0

"""Java's ContainerConformanceTest: starts each scenario of testcontainers-conformance/scenarios, a
pa-environment.yaml, and writes what Docker says it got (docker inspect of each container and of
the network) to build/container-conformance/python/<scenario>.json. scripts/compare-containers.py
then requires every language to have got the same containers (./gradlew containerConformance)."""

from __future__ import annotations

import subprocess
from contextlib import closing
from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import PlanningAnalyticsEnvironment

ROOT = Path(__file__).resolve().parents[3]


@pytest.mark.requires_tm1
def test_one_server() -> None:
    _record("one-server")


@pytest.mark.requires_tm1_web
def test_servers_and_tm1_web() -> None:
    _record("servers-and-web")


@pytest.mark.requires_cognos
def test_servers_in_cam_security() -> None:
    _record("cam-security")


@pytest.mark.requires_tm1_engine(share_database=True)
def test_engine() -> None:
    _record("engine")


def _record(scenario: str) -> None:
    home = ROOT / "testcontainers-conformance" / "scenarios"
    env = PlanningAnalyticsEnvironment.from_config(home / f"{scenario}.yaml").build()
    with closing(env):
        env.start()

        containers = {f"tm1/{name}": server for name, server in env.tm1_servers().items()}
        admin = env.admin_server()
        if admin is not None:
            containers["adminServer"] = admin
        if scenario == "servers-and-web":
            containers["tm1Web"] = env.tm1_web()
        if scenario == "engine":
            containers["tm1Engine"] = env.tm1_engine()
            containers["tm1EngineDatastore"] = env.tm1_engine().datastore
        if scenario == "cam-security":
            containers["cognos"] = env.cognos()
            containers["keycloak"] = env.keycloak()
        # Each inspection as Docker's CLI prints it, so every language writes the same text and
        # none reads it through its own Docker client's types
        parts = ",\n".join(f'"{role}": {_docker("inspect", container.container_id)}' for role, container in containers.items())
        network = _docker("network", "inspect", env.network().name)
        log = f'{{"scenario": "{scenario}", "language": "python",\n"network": {network},\n"containers": {{\n{parts}}}}}\n'
        file = ROOT / "build" / "container-conformance" / "python" / f"{scenario}.json"
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(log, encoding="utf-8")
        assert containers


def _docker(*arguments: str) -> str:
    return subprocess.run(["docker", *arguments], check=True, capture_output=True, encoding="utf-8").stdout.strip()
