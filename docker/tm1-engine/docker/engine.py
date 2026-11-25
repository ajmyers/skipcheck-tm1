#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""tm1-engine: sets up and inspects the TM1 v12 engine running in this container.

The entrypoint uses it, and so can you, with docker exec:

  tm1-engine credentials [--json]       every address and credential
  tm1-engine session [INSTANCE] [USER]  log in (service-to-service) and print the session
                                        cookie, e.g. for curl -b (default: tm1, admin)
  tm1-engine database create NAME       create a database, with the users of TM1_USERS
  tm1-engine database drop NAME         delete a database
  tm1-engine health                     exit 0 once setup is done and the databases are ready

NAME is [INSTANCE/]DATABASE; the instance defaults to tm1.

Used by the entrypoint:

  tm1-engine configure        apply the container's settings to config.internal.json
  tm1-engine setup            create the instances, applications, databases and users
  tm1-engine stop-databases   stop every database cleanly, before TM1 itself stops
  tm1-engine plan             print what setup would create, as JSON

What setup creates comes from the environment:

  TM1_DATABASES     [INSTANCE/]DATABASE,...     default: tm1 (in instance tm1); empty for none
  TM1_APPLICATIONS  [INSTANCE/]APPLICATION,...  default: one named tm1 in each instance
  TM1_USERS         USER[:GROUP[:GROUP]],...    users (and their groups) for every database

TM1 v12 is one engine holding instances; an instance holds databases. An application is
an instance's client ID and secret, which log any user in (service-to-service). Users and
groups belong to each database.
"""
import base64
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

DEPLOYMENT = os.environ.get("TM1_ENGINE_DEPLOYMENT", "/tm1/deployment")
CREDENTIALS = os.environ.get("TM1_ENGINE_CREDENTIALS", "/tm1/credentials")
PORT = 4444  # the gateway's port inside the container; fixed, like tm1-server's 8010
BASE = f"http://127.0.0.1:{PORT}"
READY = "/tmp/tm1-engine-ready.json"  # written when setup is done; read by health
STOPPED = os.path.join(DEPLOYMENT, ".tm1-engine-stopped.json")  # databases stop-databases stopped
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*$")
DEFAULT_INSTANCE = "tm1"


class Failure(Exception):
    """Something to tell the user, without a stack trace."""


def log(message):
    print(f"[tm1-engine] {message}", file=sys.stderr, flush=True)


# ---- what to create ---------------------------------------------------------------

def _items(value):
    return [item.strip() for item in value.split(",") if item.strip()]


def _qualified(item, what):
    instance, _, name = item.rpartition("/")
    instance = instance or DEFAULT_INSTANCE
    for part, kind in ((instance, "instance"), (name, what)):
        if not NAME.match(part):
            raise Failure(f"{item!r}: {kind} names are letters, digits, _ and -, starting with a letter or digit")
    return instance, name


def plan(env=None):
    """What setup creates: {"instances": {name: {"applications": [...], "databases": [...]}}, "users": [...]}."""
    env = os.environ if env is None else env
    instances = {}

    def instance(name):
        return instances.setdefault(name, {"applications": [], "databases": []})

    for item in _items(env.get("TM1_DATABASES", DEFAULT_INSTANCE)):
        name, database = _qualified(item, "database")
        if len(database) < 3:
            raise Failure(f"TM1_DATABASES: {database!r}: TM1 needs database names of at least 3 characters")
        if database.lower() in (d.lower() for d in instance(name)["databases"]):
            raise Failure(f"TM1_DATABASES: {item!r} is listed twice")
        instance(name)["databases"].append(database)
    applications = env.get("TM1_APPLICATIONS")
    if applications is None:
        if not instances:
            instance(DEFAULT_INSTANCE)
        for entry in instances.values():
            entry["applications"].append("tm1")
    else:
        for item in _items(applications):
            name, application = _qualified(item, "application")
            if application not in instance(name)["applications"]:
                instance(name)["applications"].append(application)
    users = []
    for item in _items(env.get("TM1_USERS", "")):
        name, *groups = [part.strip() for part in item.split(":")]
        if not name or any(not group for group in groups):
            raise Failure(f"TM1_USERS: {item!r}: expected USER[:GROUP[:GROUP]]")
        if name.lower() in (user["name"].lower() for user in users):
            raise Failure(f"TM1_USERS: {name!r} is listed twice")
        users.append({"name": name, "groups": groups})
    return {"instances": instances, "users": users}


# ---- HTTP ---------------------------------------------------------------------------

def call(method, path, basic=None, cookie=None, body=None, timeout=120):
    """One request to the gateway. Returns (status, parsed JSON or text, headers)."""
    headers = {"Accept": "application/json"}
    if basic:
        headers["Authorization"] = "Basic " + base64.b64encode(":".join(basic).encode()).decode()
    if cookie:
        headers["Cookie"] = cookie
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status, raw, got = response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        status, raw, got = error.code, error.read(), error.headers
    text = raw.decode("utf-8", "replace")
    try:
        return status, json.loads(text) if text else None, got
    except ValueError:
        return status, text, got


def message(payload):
    if isinstance(payload, dict):
        error = payload.get("error")
        if isinstance(error, dict):
            return error.get("message") or json.dumps(payload)
        return payload.get("error_details") or json.dumps(payload)
    return str(payload or "").strip()


def key(name):
    """A name as an OData key: quoted, with any quote in it doubled."""
    return "'" + name.replace("'", "''") + "'"


def quote(name):
    """key(name) for use in a URL."""
    return urllib.parse.quote(key(name))


def root():
    path = os.path.join(DEPLOYMENT, "root-creds.json")
    try:
        with open(path) as file:
            creds = json.load(file)
        return creds["clientID"], creds["clientSecret"]
    except (OSError, ValueError, KeyError):
        raise Failure(f"no root credentials in {path}: TM1 writes them on its first start") from None


def manage(method, path, body=None, expect=(200, 201, 204)):
    status, payload, _ = call(method, "/manage/v1/" + path, basic=root(), body=body)
    if status not in expect:
        raise Failure(f"{method} /manage/v1/{path}: {status} {message(payload)}")
    return payload


def names(path):
    return [entry["Name"] for entry in manage("GET", path)["value"]]


# ---- credentials --------------------------------------------------------------------

def _write_secret(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as file:
        json.dump(content, file, indent=2)
        file.write("\n")


def application_file(instance, name):
    return os.path.join(CREDENTIALS, instance, name + ".json")


def held_application(instance, name=None):
    """The saved credentials of an application in the instance (the first, if not named)."""
    folder = os.path.join(CREDENTIALS, instance)
    wanted = [name + ".json"] if name else sorted(os.listdir(folder)) if os.path.isdir(folder) else []
    for file_name in wanted:
        try:
            with open(os.path.join(folder, file_name)) as file:
                return json.load(file)
        except (OSError, ValueError):
            continue
    return None


def session(instance, user, application=None):
    """Logs user in through the instance's application. Returns the session cookie, name=value."""
    held = held_application(instance, application)
    if not held:
        raise Failure(f"no saved application credentials for instance {instance!r} in {CREDENTIALS}")
    status, payload, headers = call("POST", f"/{instance}/auth/v1/session",
                                    basic=(held["clientId"], held["clientSecret"]), body={"User": user})
    cookie = (headers.get("Set-Cookie") or "").split(";")[0]
    if status != 201 or not cookie:
        raise Failure(f"logging in to instance {instance!r} as {user!r}: {status} {message(payload)}")
    return cookie


# ---- setup --------------------------------------------------------------------------

def recent_errors(limit=5):
    """The last distinct errors in TM1's own logs: what TM1 says went wrong."""
    folder = os.path.join(DEPLOYMENT, "logs")
    found = []
    try:
        files = sorted(name for name in os.listdir(folder) if name.endswith(".jsonl"))
    except OSError:
        return found
    for name in files:
        try:
            with open(os.path.join(folder, name), errors="replace") as file:
                lines = file.readlines()[-200:]
        except OSError:
            continue
        for line in lines:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if isinstance(entry, dict) and entry.get("level") in ("error", "fatal"):
                text = " ".join(str(entry[key]) for key in ("message", "error") if entry.get(key))
                if text and text not in found:
                    found.append(text)
    return found[-limit:]


def wait_for_gateway(seconds):
    deadline = time.monotonic() + seconds
    last = "TM1 hasn't written its root credentials yet"
    while time.monotonic() < deadline:
        try:
            status, payload, _ = call("GET", "/manage/v1/Instances", basic=root(), timeout=10)
            if status == 200:
                return
            last = f"{status} {message(payload)}"
        except Failure as failure:
            last = str(failure)
        except OSError as error:
            last = str(error)
        time.sleep(1)
    said = "".join(f"\n  TM1's log: {error}" for error in recent_errors())
    raise Failure(f"TM1's gateway didn't answer on port {PORT} within {seconds}s ({last}); see {DEPLOYMENT}/logs{said}")


def ensure_application(instance, name, existing):
    """Creates the application unless its saved credentials still match. TM1 shows a secret once."""
    held = held_application(instance, name)
    if name in existing:
        if held and held.get("clientId") == existing[name]:
            return
        log(f"Application {instance}/{name}: its secret isn't saved in {CREDENTIALS}; replacing it")
        manage("DELETE", f"Instances({quote(instance)})/Applications({quote(name)})")
    created = manage("POST", f"Instances({quote(instance)})/Applications", {"Name": name})
    _write_secret(application_file(instance, name), {
        "instance": instance, "name": name,
        "clientId": created["ClientID"], "clientSecret": created["ClientSecret"]})
    log(f"Created application {instance}/{name} (credentials: {application_file(instance, name)})")


def database_state(instance, name):
    entry = manage("GET", f"Instances({quote(instance)})/Databases({quote(name)})")
    states = [replica.get("State") for replica in entry.get("ActiveReplicas") or []]
    return entry.get("Replicas", 0), states


def wait_ready(databases, seconds):
    deadline = time.monotonic() + seconds
    waiting = list(databases)
    while waiting and time.monotonic() < deadline:
        waiting = [pair for pair in waiting if "ready" not in database_state(*pair)[1]]
        if waiting:
            time.sleep(1)
    if waiting:
        raise Failure("not ready within %ds: %s" % (seconds, ", ".join("/".join(pair) for pair in waiting)))


def apply_users(instance, database, users):
    """Makes each user exist in the database, in exactly the groups given (none given: left as it is)."""
    if not users:
        return
    cookie = session(instance, "admin")
    root_url = f"/{instance}/api/v1/Databases({quote(database)})/"

    def request(method, path, body=None, expect=(200, 201, 204)):
        status, payload, _ = call(method, root_url + path, cookie=cookie, body=body)
        if status not in expect:
            raise Failure(f"{method} {path} in {instance}/{database}: {status} {message(payload)}")
        return payload

    have_groups = {group["Name"].lower(): group["Name"] for group in request("GET", "Groups?$select=Name")["value"]}
    have_users = {user["Name"].lower(): user["Name"] for user in request("GET", "Users?$select=Name")["value"]}
    for user in users:
        groups = list(user["groups"])
        if user["name"].lower() == "admin" and groups and "admin" not in (group.lower() for group in groups):
            groups.append("ADMIN")  # never lock the administrator out of its own database
        bind = []
        for group in groups:
            if group.lower() not in have_groups:
                request("POST", "Groups", {"Name": group})
                have_groups[group.lower()] = group
            bind.append(f"Groups({key(have_groups[group.lower()])})")
        existing = have_users.get(user["name"].lower())
        if existing is None:
            body = {"Name": user["name"]}
            if bind:
                body["Groups@odata.bind"] = bind
            request("POST", "Users", body)
        elif bind:
            request("PATCH", f"Users({quote(existing)})", {"Groups@odata.bind": bind})


def setup():
    wanted = plan()
    wait_for_gateway(int(os.environ.get("TM1_ENGINE_START_TIMEOUT", "300")))
    client_id, client_secret = root()
    _write_secret(os.path.join(CREDENTIALS, "root.json"), {"clientId": client_id, "clientSecret": client_secret})

    # Databases the last stop scaled down come back first, whoever made them.
    try:
        with open(STOPPED) as file:
            stopped = [tuple(pair) for pair in json.load(file)]
    except (OSError, ValueError):
        stopped = []
    have_instances = names("Instances")
    for instance, database in stopped:
        if instance in have_instances and database in names(f"Instances({quote(instance)})/Databases"):
            manage("PATCH", f"Instances({quote(instance)})/Databases({quote(database)})", {"Replicas": 1})
    if os.path.exists(STOPPED):
        os.remove(STOPPED)

    databases = []
    for instance, entry in wanted["instances"].items():
        if instance not in have_instances:
            manage("POST", "Instances", {"Name": instance})
            log(f"Created instance {instance}")
        existing = {app["Name"]: app.get("ClientID") for app in
                    manage("GET", f"Instances({quote(instance)})/Applications")["value"]}
        for application in entry["applications"]:
            ensure_application(instance, application, existing)
        have_databases = names(f"Instances({quote(instance)})/Databases")
        for database in entry["databases"]:
            if database not in have_databases:
                log(f"Creating database {instance}/{database}")
                manage("POST", f"Instances({quote(instance)})/Databases", {"Name": database})
            elif database_state(instance, database)[0] == 0:
                manage("PATCH", f"Instances({quote(instance)})/Databases({quote(database)})", {"Replicas": 1})
            databases.append((instance, database))
    wait_ready(databases + [pair for pair in stopped if pair not in databases and pair[0] in have_instances
                            and pair[1] in names(f"Instances({quote(pair[0])})/Databases")], 300)
    for instance, database in databases:
        if held_application(instance):
            apply_users(instance, database, wanted["users"])
        elif wanted["users"]:
            log(f"WARNING: no application in instance {instance}, so TM1_USERS wasn't applied to {database}")
    with open(READY, "w") as file:
        json.dump({"databases": databases}, file)
    log("TM1 is ready:")
    for line in describe():
        log("  " + line)


# ---- commands -----------------------------------------------------------------------

def inventory():
    """Everything in the engine: instances, their databases, and the applications whose secrets are saved."""
    result = []
    for instance in names("Instances"):
        applications = []
        for app in manage("GET", f"Instances({quote(instance)})/Applications")["value"]:
            held = held_application(instance, app["Name"])
            known = bool(held and held.get("clientId") == app.get("ClientID"))
            applications.append({"name": app["Name"], "clientId": app.get("ClientID"),
                                 "clientSecret": held["clientSecret"] if known else None,
                                 "file": application_file(instance, app["Name"]) if known else None})
        databases = [{"name": database, "path": f"/{instance}/api/v1/Databases('{database}')/"}
                     for database in names(f"Instances({quote(instance)})/Databases")]
        result.append({"name": instance, "applications": applications, "databases": databases})
    return result


def describe():
    lines = [f"Management API   /manage/v1/   root credentials: {os.path.join(CREDENTIALS, 'root.json')}"]
    for instance in inventory():
        for database in instance["databases"]:
            lines.append(f"Database         {database['path']}")
        for app in instance["applications"]:
            where = f"secret in {app['file']}" if app["file"] else "secret not saved here"
            lines.append(f"Application      {instance['name']}/{app['name']}   client ID {app['clientId']}, {where}")
    lines.append(f"All on port {PORT} of this container. Log in: tm1-engine session [INSTANCE] [USER]")
    return lines


def command_credentials(args):
    if args == ["--json"]:
        client_id, client_secret = root()
        print(json.dumps({"port": PORT, "root": {"clientId": client_id, "clientSecret": client_secret},
                          "instances": inventory()}, indent=2))
    elif args:
        raise Failure("usage: tm1-engine credentials [--json]")
    else:
        print("\n".join(describe()))


def command_session(args):
    if len(args) > 2:
        raise Failure("usage: tm1-engine session [INSTANCE] [USER]")
    instance = args[0] if args else DEFAULT_INSTANCE
    print(session(instance, args[1] if len(args) > 1 else "admin"))


def command_database(args):
    if len(args) != 2 or args[0] not in ("create", "drop"):
        raise Failure("usage: tm1-engine database create|drop [INSTANCE/]NAME")
    instance, database = _qualified(args[1], "database")
    if args[0] == "create":
        if len(database) < 3:
            raise Failure(f"{database!r}: TM1 needs database names of at least 3 characters")
        manage("POST", f"Instances({quote(instance)})/Databases", {"Name": database})
        wait_ready([(instance, database)], 300)
        apply_users(instance, database, plan()["users"])
        print(f"/{instance}/api/v1/Databases('{database}')/")
    else:
        manage("DELETE", f"Instances({quote(instance)})/Databases({quote(database)})")
        try:  # health no longer waits for it
            with open(READY) as file:
                ready = json.load(file)
            ready["databases"] = [pair for pair in ready["databases"] if pair != [instance, database]]
            with open(READY, "w") as file:
                json.dump(ready, file)
        except (OSError, ValueError):
            pass


def command_health(_args):
    try:
        with open(READY) as file:
            databases = json.load(file)["databases"]
    except (OSError, ValueError):
        return 1
    try:
        if not databases:
            manage("GET", "Instances")
        return 0 if all("ready" in database_state(*pair)[1] for pair in databases) else 1
    except (Failure, OSError):
        return 1


def running_databases():
    """PIDs of the database processes (tm1s.exe), from /proc: no ps needed."""
    pids = []
    for pid in filter(str.isdigit, os.listdir("/proc")):
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as file:
                first = file.read().split(b"\0")[0]
        except OSError:
            continue
        if os.path.basename(first) == b"tm1s.exe":
            pids.append(int(pid))
    return pids


def command_stop_databases(args):
    """Scales every running database to no replicas: TM1 commits and shuts each one down. tm1sd
    alone would leave them running when it stops."""
    seconds = int(args[0]) if args else 60
    running = []
    try:
        for instance in names("Instances"):
            for entry in manage("GET", f"Instances({quote(instance)})/Databases")["value"]:
                if entry.get("Replicas", 0) > 0:
                    running.append((instance, entry["Name"]))
    except (Failure, OSError):
        log("TM1 isn't answering, so there are no databases to stop through it")
        return 1
    with open(STOPPED, "w") as file:
        json.dump(running, file)
    failures = []

    def stop(instance, database):
        try:
            manage("PATCH", f"Instances({quote(instance)})/Databases({quote(database)})", {"Replicas": 0})
        except (Failure, OSError) as error:
            failures.append(f"{instance}/{database}: {error}")

    threads = [threading.Thread(target=stop, args=pair) for pair in running]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    deadline = time.monotonic() + seconds
    while running_databases() and time.monotonic() < deadline:
        time.sleep(0.5)
    for failure in failures:
        log("WARNING: " + failure)
    left = running_databases()
    if left:
        log(f"WARNING: {len(left)} database process(es) still running after {seconds}s")
        return 1
    log(f"Stopped {len(running)} database(s) cleanly")
    return 0


# ---- configuration ------------------------------------------------------------------

def parse_datastore_url(url):
    """A MongoDB URL as the fields of config.internal.json's datastore section (as IBM's installer maps it)."""
    match = re.match(r"^(mongodb(?:\+srv)?)://(.*)$", url)
    if not match:
        raise Failure("TM1_DATASTORE_URL must start with mongodb:// or mongodb+srv://")
    protocol, rest = match.groups()
    location, _, tail = rest.partition("/")
    auth, at, hosts = location.rpartition("@")
    user, _, password = auth.partition(":") if at else ("", "", "")
    if not hosts:
        raise Failure("TM1_DATASTORE_URL names no host")
    options = dict(urllib.parse.parse_qsl(tail.partition("?")[2], keep_blank_values=True))
    return {
        "external": True, "protocol": protocol, "host": hosts, "user": user, "pass": password,
        "authMechanism": options.get("authMechanism", "SCRAM-SHA-256"),
        "authSource": options.get("authSource", "admin"),
        "replicaSet": options.get("replicaSet", ""),
        "tlsPath": options.get("tlsCAFile", options.get("sslCAFile", "")),
    }


def configure(config, env=None):
    """Applies the container's settings to a parsed config.internal.json. Returns what it changed."""
    env = os.environ if env is None else env
    changes = []

    def put(section, key, value, shown=None):
        if section.get(key) != value:
            changes.append(f"{shown or key}: {section.get(key)!r} -> " + ("(set)" if key == "pass" else repr(value)))
            section[key] = value

    gateway = config.setdefault("services", {}).setdefault("tm1-gateway", {})
    put(gateway, "serverPort", str(PORT), "services.tm1-gateway.serverPort")
    url = env.get("TM1_DATASTORE_URL")
    if url:
        datastore = config.setdefault("datastore", {})
        fields = parse_datastore_url(url)
        if not datastore.get("external"):
            # A new control database, so engines sharing one MongoDB don't share TM1's default name
            fields["controlDatabase"] = "tm1-control-" + str(uuid.uuid4())
        for key, value in fields.items():
            put(datastore, key, value, "datastore." + key)
    return changes


def command_configure(_args):
    path = os.path.join(DEPLOYMENT, "config.internal.json")
    with open(path) as file:
        config = json.load(file)
    changes = configure(config)
    if changes:
        with open(path, "w") as file:
            json.dump(config, file, indent="\t")
            file.write("\n")
        for change in changes:
            log("config.internal.json: " + change)
    print("external" if config.get("datastore", {}).get("external") else "embedded")


def main(argv):
    commands = {
        "credentials": command_credentials, "session": command_session, "database": command_database,
        "health": command_health, "stop-databases": command_stop_databases, "configure": command_configure,
        "setup": lambda _args: setup(), "plan": lambda _args: print(json.dumps(plan(), indent=2)),
    }
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__.strip())
        return 0
    command = commands.get(argv[0])
    if command is None:
        print(f"tm1-engine: unknown command {argv[0]!r} (see tm1-engine --help)", file=sys.stderr)
        return 2
    try:
        return command(argv[1:]) or 0
    except Failure as failure:
        print(f"[tm1-engine] ERROR: {failure}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"[tm1-engine] ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
