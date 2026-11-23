# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1ServerContainer, quoted in the guide. Test code, never run here."""

from __future__ import annotations

from pathlib import Path

from skipcheck_tm1 import ODataQuery, Tm1Client, Tm1Version
from skipcheck_tm1.model import Cube
from skipcheck_tm1_testcontainers import Tm1Seed, Tm1ServerContainer, Tm1Source


def client(server: Tm1ServerContainer) -> Tm1Client:
    # @start region = client
    # The container's REST URL and login, in TM1's own security
    return Tm1Client.builder().base_url(server.rest_url).basic_auth(server.username, server.password).build()
    # @end


def sample() -> list[str | None]:
    # @start region = sample
    source = Tm1Source.discover()
    if source is None:
        raise RuntimeError("Set TM1_HOME_DIR or TM1_BUNDLE_DIR")
    # SData's login is admin / apple, and the server must be this version
    container = Tm1ServerContainer(source).with_seed(Tm1Seed.SDATA).with_expected_version(Tm1Version.parse("11.8.03500.6"))
    # Entering the block starts the container, and leaving it stops it
    with container as server:
        # The client's block is the inner one, so its session closes before the container stops
        with Tm1Client.builder().base_url(server.rest_url).basic_auth(server.username, server.password).build() as tm1:
            # SData's cubes, such as SalesCube
            return [cube.name for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME))]
    # @end


def own_database(source: Tm1Source) -> str:
    # @start region = own-database
    # A fixture model (.cub, .dim, .pro files and so on), copied in before TM1 starts,
    # and a login the database has
    container = Tm1ServerContainer(source).with_data(Path("tests/tm1/sales")).with_credentials("admin", "apple")
    with container as server:
        return server.rest_url  # http://localhost:54321/api/v1/
    # @end
