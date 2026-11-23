"""The Getting started example of the client's guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cube


# @start region = hello-world
def main() -> None:
    # tm1-server's default login; leaving the block closes the session
    with Tm1Client.builder().base_url("http://localhost:8010/api/v1/").basic_auth("admin", "").build() as tm1:
        print(f"Hello from TM1 {tm1.session.version()}")
        for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME)):
            print(f"  {cube.name}")


if __name__ == "__main__":
    main()
# @end
