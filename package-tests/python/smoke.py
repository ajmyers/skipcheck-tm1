# SPDX-License-Identifier: Apache-2.0

"""Runs the installed packages, as someone who installed them from PyPI would: the guide's Hello
World against a fake TM1 on localhost, a generated model type, and the testcontainers package's
imports and the script it ships. It's run with the interpreter of an environment that has only
the built wheels (or sdists) installed, from outside the repository, so nothing here can come from
the sources. See package-tests/README.md.
"""

from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import metadata, resources
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SNAPSHOT = REPO / "java" / "tm1-client" / "src" / "model" / "metadata" / "tm1-schema-11.8.35.xml"


class FakeTm1(BaseHTTPRequestHandler):
    """Answers what Hello World asks: a login, the version, $metadata and the cubes."""

    requests: list[str] = []

    def do_GET(self) -> None:  # noqa: N802 (http.server's name)
        self.requests.append(f"GET {self.path}")
        if self.path.startswith("/api/v1/ActiveSession"):
            self.answer({"ID": 1}, {"Set-Cookie": "TM1SessionId=s1; Path=/api/; HttpOnly"})
        elif self.path.startswith("/api/v1/Server"):
            self.answer({"ProductVersion": "11.8.03500.6"})
        elif self.path == "/api/v1/$metadata":
            self.answer(SNAPSHOT.read_bytes(), content_type="application/xml")
        elif self.path.startswith("/api/v1/ModelCubes()") or self.path.startswith("/api/v1/Cubes"):
            self.answer({"value": [{"Name": "Sales"}, {"Name": "Plan"}]})
        else:
            self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        self.requests.append(f"POST {self.path}")
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        self.send_response(204)
        self.end_headers()

    def answer(self, body: object, headers: dict[str, str] | None = None, content_type: str = "application/json") -> None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        pass


def main() -> None:
    import skipcheck_tm1
    import skipcheck_tm1_testcontainers
    from skipcheck_tm1 import ODataQuery, Tm1Client
    from skipcheck_tm1.aio import AsyncTm1Client
    from skipcheck_tm1.model import Cube

    for module in (skipcheck_tm1, skipcheck_tm1_testcontainers):
        location = Path(module.__file__).resolve()
        assert REPO / "python" not in location.parents, f"{module.__name__} is imported from the sources ({location}), not from an installed package"

    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeTm1)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        # The guide's Hello World (python/tm1-client/examples/hello_world.py)
        with Tm1Client.builder().base_url(f"http://127.0.0.1:{server.server_port}/api/v1/").basic_auth("admin", "").build() as tm1:
            version = str(tm1.session.version())
            cubes = [cube.name for cube in tm1.cubes.model_cubes(ODataQuery.none().select(Cube.NAME))]
    finally:
        server.shutdown()
    assert version.startswith("11.8"), version
    assert cubes == ["Sales", "Plan"], cubes
    assert any(request.startswith("POST /api/v1/ActiveSession/tm1.Close") for request in FakeTm1.requests), FakeTm1.requests

    # The asyncio flavor and the generated API are in the package too
    assert AsyncTm1Client.builder is not None
    assert Cube(name="Sales").name == "Sales"

    # tm1-testcontainers: its public names, the script it ships, and its pytest plugin
    from skipcheck_tm1_testcontainers import PawContainer, RequiresTm1, Tm1ServerContainer, Tm1Source

    assert all((PawContainer, RequiresTm1, Tm1ServerContainer, Tm1Source))
    assert resources.files("skipcheck_tm1_testcontainers").joinpath("paw-local.sh").is_file(), "paw-local.sh isn't in the package"
    plugins = [entry.name for entry in metadata.entry_points(group="pytest11")]
    assert "skipcheck_tm1_testcontainers" in plugins, plugins

    versions = {name: metadata.version(name) for name in ("skipcheck-tm1", "skipcheck-tm1-testcontainers")}
    for name in versions:
        licenses = [str(file) for file in metadata.files(name) or [] if file.name in ("LICENSE", "NOTICE")]
        assert len(licenses) == 2, f"{name} doesn't carry LICENSE and NOTICE: {licenses}"
    print(f"ok: Hello from TM1 {version}, cubes {cubes}, packages {versions}")


if __name__ == "__main__":
    sys.exit(main())
