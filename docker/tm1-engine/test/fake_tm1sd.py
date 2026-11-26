#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""A stand-in for TM1 v12, for tests where there's no IBM software (CI).

- As a module: FakeTm1 and serve(), used by scripts/tests/test_tm1_engine.py.
- As a program: it takes the place of bin/tm1sd.exe in the made-up package that
  docker/tm1-engine/ci-smoke-test.sh installs, so the image's entrypoint can be run from
  start to stop. Run as tm1sd is (tm1sd.exe -z <deployment>), it writes root-creds.json,
  answers on port 4444, keeps what's created in the deployment, and exits on SIGTERM.

It answers as TM1 12.6.7 was seen to: the management API, service-to-service logins, and
each database's users and groups. Nothing more.
"""

from __future__ import annotations

import base64
import json
import os
import re
import signal
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote


class FakeTm1:
    """Enough of TM1's gateway for setup: the management API, service-to-service logins, and each
    database's users and groups. Replies as TM1 12.6.7 was seen to."""

    def __init__(self, state: str | None = None) -> None:
        self.root = ("root-id", "root-secret")
        self.instances: dict[str, dict] = {}
        self.secrets = 0
        self.requests: list[tuple[str, str]] = []
        self.state = state  # a file to keep everything in, as TM1 keeps it in its datastore
        if state and os.path.exists(state):
            with open(state) as file:
                saved = json.load(file)
            self.instances, self.secrets = saved["instances"], saved["secrets"]

    def save(self) -> None:
        if self.state:
            with open(self.state, "w") as file:
                json.dump({"instances": self.instances, "secrets": self.secrets}, file)

    def application(self, instance: str, name: str) -> dict:
        self.secrets += 1
        app = {"Name": name, "ClientID": f"id-{self.secrets}", "ClientSecret": f"secret-{self.secrets}"}
        self.instances[instance]["applications"][name] = app
        return app

    def database(self, instance: str, name: str) -> dict:
        database = {"Name": name, "Replicas": 1, "users": {"Admin": ["ADMIN"]}, "groups": ["ADMIN", "DataAdmin"],
                    "friendly": {}}  # the friendly names given; without one TM1 shows the name
        self.instances[instance]["databases"][name] = database
        return database

    def handle(self, method: str, path: str, auth: str | None, cookie: str | None, body: dict | None) -> tuple[int, object, dict]:
        self.requests.append((method, path))
        basic = tuple(base64.b64decode(auth.split()[1]).decode().split(":", 1)) if auth else None
        key = r"\('([^']*)'\)"
        if path.startswith("/manage/v1/"):
            if basic != self.root:
                return 401, {"error": {"message": "Authentication required"}}, {}
            rest = path[len("/manage/v1/") :]
            if rest == "Instances":
                if method == "GET":
                    return 200, {"value": [{"Name": name} for name in self.instances]}, {}
                if body["Name"] in self.instances:
                    return 400, {"error": {"message": "exists"}}, {}
                self.instances[body["Name"]] = {"applications": {}, "databases": {}}
                return 201, {"Name": body["Name"]}, {}
            match = re.fullmatch(rf"Instances{key}/(Applications|Databases)(?:{key})?", rest)
            assert match, rest
            instance, kind, name = self.instances[match[1]], match[2].lower(), match[3]
            if kind == "applications":
                if method == "GET":
                    return 200, {"value": [{"Name": app["Name"], "ClientID": app["ClientID"]} for app in instance[kind].values()]}, {}
                if method == "DELETE":
                    del instance[kind][name]
                    return 204, None, {}
                return 201, self.application(match[1], body["Name"]), {}
            if method == "POST":
                if body["Name"] in instance[kind]:
                    return 400, {"error": {"message": f"A database named {body['Name']} already exists."}}, {}
                self.database(match[1], body["Name"])
                return 201, {"Name": body["Name"]}, {}
            if name is None:
                return 200, {"value": [self.shown(database) for database in instance[kind].values()]}, {}
            if method == "DELETE":
                del instance[kind][name]
                return 204, None, {}
            if method == "PATCH":
                instance[kind][name]["Replicas"] = body["Replicas"]
            return 200, self.shown(instance[kind][name]), {}
        match = re.fullmatch(r"/([^/]+)/auth/v1/session", path)
        if match:
            apps = self.instances.get(match[1], {"applications": {}})["applications"].values()
            if not any(basic == (app["ClientID"], app["ClientSecret"]) for app in apps):
                return 401, {"error": "invalid_client"}, {}
            return 201, None, {"Set-Cookie": f"TM1SessionId={match[1]}:{body['User']}; Path=/; HttpOnly"}
        match = re.fullmatch(rf"/([^/]+)/api/v1/Databases{key}/(Users|Groups)(?:{key})?(?:\?.*)?", path)
        assert match, path
        if cookie != f"TM1SessionId={match[1]}:admin":
            return 401, {"error": {"message": "Authentication required"}}, {}
        database = self.instances[match[1]]["databases"][match[2]]
        if match[3] == "Groups":
            if method == "POST":
                database["groups"].append(body["Name"])
                return 201, {"Name": body["Name"]}, {}
            return 200, {"value": [{"Name": group} for group in database["groups"]]}, {}
        if method == "GET":
            return 200, {"value": [{"Name": user, "FriendlyName": database["friendly"].get(user, user),
                                    "Groups": [{"Name": group} for group in groups]}
                                   for user, groups in database["users"].items()]}, {}
        groups = [re.fullmatch(rf"Groups{key}", bound)[1] for bound in body.get("Groups@odata.bind", [])]
        assert all(group in database["groups"] for group in groups), groups
        if method == "POST":
            # TM1 compares names without regard to case or spaces, and refuses a second of one
            taken = {"".join(user.split()).lower() for user in database["users"]}
            if "".join(body["Name"].split()).lower() in taken:
                return 400, {"error": {"message": f"A user with Name \"{body['Name']}\" already exists."}}, {}
            database["users"][body["Name"]] = groups
            if "FriendlyName" in body:
                database["friendly"][body["Name"]] = body["FriendlyName"]
            return 201, {"Name": body["Name"]}, {}
        if "Groups@odata.bind" in body:
            database["users"][match[4]] = groups  # PATCH replaces the groups, as TM1 does
        if "FriendlyName" in body:
            database["friendly"][match[4]] = body["FriendlyName"]
        return 200, {"Name": match[4]}, {}

    @staticmethod
    def shown(database: dict) -> dict:
        replicas = [{"ID": "0", "State": "ready"}] if database["Replicas"] else []
        return {"Name": database["Name"], "Replicas": database["Replicas"], "ActiveReplicas": replicas}


def serve(fake: FakeTm1, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    """Starts answering for fake on a thread. The port is server.server_address[1]."""

    class Handler(BaseHTTPRequestHandler):
        def respond(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            status, payload, headers = fake.handle(self.command, unquote(self.path), self.headers.get("Authorization"), self.headers.get("Cookie"), body)
            if self.command != "GET":
                fake.save()
            raw = b"" if payload is None else json.dumps(payload).encode()
            self.send_response(status)
            for name, value in headers.items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = do_PATCH = do_DELETE = respond

        def log_message(self, *_args: object) -> None:
            pass

    server = ThreadingHTTPServer((host, port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[0] not in ("-z", "--config"):
        print("usage: tm1sd.exe -z <deployment>", file=sys.stderr)
        return 2
    deployment = argv[1]
    fake = FakeTm1(os.path.join(deployment, "fake-tm1.json"))
    with open(os.path.join(deployment, "root-creds.json"), "w") as file:
        json.dump({"name": "Root", "clientID": fake.root[0], "clientSecret": fake.root[1]}, file)
    serve(fake, "0.0.0.0", 4444)
    print("Stand-in TM1 Service is ready.", flush=True)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    stop.wait()
    print("Stand-in TM1 Service is shutting down.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
