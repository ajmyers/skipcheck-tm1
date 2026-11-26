# SPDX-License-Identifier: Apache-2.0

"""The wait every container uses, without Docker: it stops once the container has exited, as Java's
HttpOrExitWaitStrategyTest checks."""

from __future__ import annotations

from typing import Any

import pytest

from skipcheck_tm1_testcontainers._container import _HttpWait


class _Wrapped:
    attrs: dict[str, Any] = {"State": {"ExitCode": 3}}

    def logs(self, **_: Any) -> bytes:
        return "".join(f"line {i}\n" for i in range(11, 41)).encode()


class _Exited:
    status = "exited"

    def reload(self) -> None:
        pass

    def get_wrapped_container(self) -> _Wrapped:
        return _Wrapped()


def test_fails_at_once_with_the_exit_code_and_the_end_of_the_log_when_the_container_has_exited() -> None:
    wait = _HttpWait(8010, "/ready")
    wait._container = _Exited()
    with pytest.raises(RuntimeError) as failed:
        wait._try_http_request("http://localhost:1/ready", {}, None)
    assert str(failed.value).startswith("The container exited while starting, with code 3. The end of its log:\nline 11\n")
    assert str(failed.value).endswith("line 40")
