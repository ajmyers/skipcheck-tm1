# SPDX-License-Identifier: Apache-2.0

"""Runs the quick start's example (examples/hello_world.py) against a real TM1, and records what it prints
for the landing page, which shows it under the code (tests/example_output.py): a port of the Java client's
HelloWorldTm1Test. The example logs in as a new tm1-server does (admin, no password), so this starts a server
of its own with the empty database, rather than use the run's shared one, which other tests fill, and skips
without a TM1 source and Docker to start it from (TM1_URL alone isn't enough). The server has no cubes, so the
example prints the version alone."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import Tm1Client

from ..example_output import ExampleOutput, example

try:
    from skipcheck_tm1_testcontainers import Tm1ServerContainer, Tm1Source, docker_available
except ImportError:  # tm1-testcontainers isn't installed
    _SOURCE = None
    _DOCKER = False
else:
    _SOURCE = Tm1Source.discover()
    _DOCKER = _SOURCE is not None and docker_available()

pytestmark = pytest.mark.skipif(not _DOCKER, reason="Needs a TM1 source and Docker, to start a server with the empty database")


def test_prints_the_version_and_the_cubes() -> None:
    assert _SOURCE is not None
    hello_world = example("HelloWorld")
    with Tm1ServerContainer(_SOURCE) as server:
        with ExampleOutput("tm1-client", "HelloWorld") as output:
            with Tm1Client.builder().base_url(server.rest_url).basic_auth(server.username, server.password).build() as tm1:
                output.record("hello-world", lambda: hello_world.hello(tm1))
        assert output.output("hello-world") == [f"Hello from TM1 {server.tm1_version}"]
