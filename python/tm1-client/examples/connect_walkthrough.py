# SPDX-License-Identifier: Apache-2.0

"""The walkthrough "Connect and list your cubes" (api-site/content/walkthroughs/tm1-client). The live
tests run its steps against IBM's SData sample and record what they print
(tests/live/test_walkthrough_examples.py)."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cube


def main() -> None:
    tm1 = connect()
    try:
        print_version(tm1)
        list_cubes(tm1)
    finally:
        close(tm1)


def connect() -> Tm1Client:
    # @start region = connect
    # The SData sample's address and login
    tm1 = Tm1Client.builder().base_url("http://localhost:8011/api/v1/").basic_auth("admin", "apple").build()
    # @end
    return tm1


def print_version(tm1: Tm1Client) -> None:
    # @start region = version
    # The client logs in on its first call
    version = tm1.session.version()
    print(f"Connected to TM1 {version}")
    # @end


def list_cubes(tm1: Tm1Client) -> None:
    # @start region = list-cubes
    # The cubes you made, without TM1's control cubes; select asks for their names alone
    for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME)):
        print(cube.name)
    # @end


def close(tm1: Tm1Client) -> None:
    # @start region = close
    # Ends the session on the server; a with block around the client does it for you
    tm1.close()
    # @end


if __name__ == "__main__":
    main()
