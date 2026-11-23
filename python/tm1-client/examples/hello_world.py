# SPDX-License-Identifier: Apache-2.0

"""The quick start's example on the client's landing page. The live tests run :func:`hello` against a new
tm1-server and record what it prints (tests/live/test_hello_world.py), which the page shows under the code."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cube


# @start region = hello-world
def main() -> None:
    # tm1-server's default login; leaving the block closes the session
    with Tm1Client.builder().base_url("http://localhost:8010/api/v1/").basic_auth("admin", "").build() as tm1:
        hello(tm1)


def hello(tm1: Tm1Client) -> None:
    print(f"Hello from TM1 {tm1.session.version()}")
    for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME)):
        print(f"  {cube.name}")


if __name__ == "__main__":
    main()
# @end
