# SPDX-License-Identifier: Apache-2.0

"""Java's HttpOrExitWaitStrategyTest: the wait every container uses, without Docker. A target that
has exited, one whose HTTP answers, and one whose never does."""

from __future__ import annotations

import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

import pytest

from skipcheck_tm1_testcontainers._container import wait_for_http


class _Wrapped:
    attrs: dict[str, Any] = {"State": {"ExitCode": 3}}

    def logs(self, tail: int = 0, **_: Any) -> bytes:
        # Docker cuts the log to its tail, so this does: the wait must ask for the last 30 lines
        lines = [f"line {i}\n" for i in range(1, 41)]
        return "".join(lines[-tail:] if tail else lines).encode()


class _Target:
    """What the wait asks a container: Java's WaitStrategyTarget."""

    def __init__(self, running: bool, mapped_port: int) -> None:
        self.status = "running" if running else "exited"
        self._mapped_port = mapped_port

    def reload(self) -> None:
        pass

    def get_wrapped_container(self) -> _Wrapped:
        return _Wrapped()

    def get_container_host_ip(self) -> str:
        return "localhost"

    def get_exposed_port(self, port: int) -> int:
        assert port == 8010
        return self._mapped_port


class _Ready(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        self.send_response(200 if self.path == "/ready" else 404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, format: str, *args: Any) -> None:
        pass


def _wait(timeout: float) -> Any:
    return wait_for_http("/ready", 8010, timeout=timeout, accept=lambda code: code == 200)


def _closed_port() -> int:
    with socket.socket() as s:
        s.bind(("localhost", 0))
        return int(s.getsockname()[1])


def test_fails_at_once_with_the_exit_code_and_the_end_of_the_log_when_the_container_has_exited() -> None:
    started = time.monotonic()
    with pytest.raises(RuntimeError) as failed:
        _wait(300).wait_until_ready(_Target(False, 1))
    assert str(failed.value).startswith("The container exited while starting, with code 3. The end of its log:\nline 11\n")
    assert str(failed.value).endswith("line 40")
    assert "line 10\n" not in str(failed.value)
    assert time.monotonic() - started < 10


def test_returns_once_the_path_answers_as_wanted() -> None:
    server = HTTPServer(("localhost", 0), _Ready)
    serving = threading.Thread(target=server.serve_forever, daemon=True)
    serving.start()
    try:
        _wait(30).wait_until_ready(_Target(True, server.server_address[1]))
    finally:
        server.shutdown()
        server.server_close()
        serving.join()


def test_times_out_when_the_path_never_answers() -> None:
    # A port nothing listens on: a closed one, found by binding and closing it
    port = _closed_port()
    started = time.monotonic()
    # testcontainers-python's own timeout and message, where Java's says "Timed out after PT2S
    # waiting for http://localhost:…": it names how long it waited and the URL too
    with pytest.raises(TimeoutError) as failed:
        _wait(2).wait_until_ready(_Target(True, port))
    assert str(failed.value).startswith(f"HTTP endpoint not ready within 2.0 seconds. Endpoint: http://localhost:{port}/ready.")
    assert time.monotonic() - started < 30
