#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""pa-setup: run a whole IBM Planning Analytics environment in Docker from one file.

Runs in its own container (see setup.sh / setup.ps1), with the Docker socket and the
folder holding pa-environment.yaml mounted at /work:

  pa-setup init               write a starting pa-environment.yaml
  pa-setup up                 generate compose.yaml, start everything, wait, print URLs
  pa-setup generate           only write compose.yaml
  pa-setup urls               print every service's address and login
  pa-setup status             list the containers and their health
  pa-setup logs [service...]  recent logs
  pa-setup down               stop and remove the containers (data is kept)

Options: -f FILE (default pa-environment.yaml in the current folder).

The file says what to run; pre-signed download links and other secrets go in .env next
to it, never in the file. See README.md for the format, and pa-environment.schema.json.
"""
import argparse
import json
import ntpath
import os
import posixpath
import re
import subprocess
import sys

import yaml

WORK = "/work"
HOST_DIR = os.environ.get("PA_HOST_DIR") or WORK
HOST_HOME = os.environ.get("PA_HOST_HOME") or ""
# Until the first release there are only :edge images (built from master), so that's the
# default; PA_IMAGE_TAG picks a release, e.g. 1.0.0.
SELF_IMAGE = os.environ.get("PA_SETUP_IMAGE") or "ghcr.io/ajmyers/pa-setup:edge"
IMAGE_TAG = os.environ.get("PA_IMAGE_TAG") or "edge"
NAME = re.compile(r"[A-Za-z0-9_-]+$")
USER = re.compile(r"[A-Za-z0-9._-]+$")
SEEDS = ("empty", "sdata", "24retail")


class ConfigError(Exception):
    pass


def log(message=""):
    print(message, flush=True)


# ---- host paths -------------------------------------------------------------------
# Paths in the file are the host's (the Docker daemon resolves bind mounts), which may
# be Windows paths. They're joined in the host's style and never touched from here.

def host_join(base, *parts):
    windows = bool(re.match(r"^[A-Za-z]:[\\/]", base)) or "\\" in base
    style = ntpath if windows else posixpath
    return style.normpath(style.join(base, *parts))


def host_path(value, what):
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{what} must be a path")
    value = value.strip()
    if value == "~" or value.startswith("~/") or value.startswith("~\\"):
        if not HOST_HOME:
            raise ConfigError(f"{what}: can't expand ~ (PA_HOST_HOME isn't set); use a full path")
        value = host_join(HOST_HOME, value[2:]) if len(value) > 1 else HOST_HOME
    if posixpath.isabs(value) or re.match(r"^[A-Za-z]:[\\/]", value) or value.startswith("\\\\"):
        return host_join(value)
    return host_join(HOST_DIR, value)


# ---- the file -------------------------------------------------------------------

def check_keys(section, allowed, where):
    if not isinstance(section, dict):
        raise ConfigError(f"{where} must be a mapping")
    unknown = sorted(set(section) - set(allowed))
    if unknown:
        raise ConfigError(f"{where}: unknown setting(s) {', '.join(unknown)} (allowed: {', '.join(allowed)})")


def port(value, where, default):
    value = default if value is None else value
    if not isinstance(value, int) or not 1 <= value <= 65535:
        raise ConfigError(f"{where}: port must be a number from 1 to 65535, not {value!r}")
    return value


def section(cfg, key):
    """A component's section: absent or false = off; true = on with defaults."""
    value = cfg.get(key)
    if value in (None, False):
        return None
    if value is True:
        return {}
    return value


def parse_user(entry, where):
    if isinstance(entry, str):
        parts = entry.split(":")
        if len(parts) not in (2, 3):
            raise ConfigError(f"{where}: expected user:password[:group,group], not {entry!r}")
        name, password = parts[0], parts[1]
        groups = [g for g in parts[2].split(",") if g] if len(parts) == 3 else []
    elif isinstance(entry, dict):
        check_keys(entry, ("name", "password", "groups"), where)
        name, password, groups = entry.get("name"), entry.get("password"), entry.get("groups") or []
    else:
        raise ConfigError(f"{where}: a user is user:password[:groups] or a mapping")
    if not isinstance(name, str) or not USER.match(name):
        raise ConfigError(f"{where}: user name {name!r} must be letters, digits, '.', '_' and '-'")
    if not isinstance(password, str) or not password or re.search(r"[:\s]", password):
        raise ConfigError(f"{where}: {name}'s password must be non-empty, without ':' or spaces")
    for g in groups:
        if not isinstance(g, str) or not USER.match(g):
            raise ConfigError(f"{where}: group {g!r} must be letters, digits, '.', '_' and '-'")
    return {"name": name, "password": password, "groups": list(groups)}


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            cfg = yaml.safe_load(f) or {}
    except FileNotFoundError:
        raise ConfigError(f"{os.path.basename(path)} not found here; run 'init' to write one")
    except yaml.YAMLError as e:
        raise ConfigError(f"{os.path.basename(path)} isn't valid YAML: {e}")
    return normalize(cfg)


def normalize(cfg):
    """Checks the file and fills in every default. Returns the model generate() uses."""
    check_keys(cfg, ("version", "name", "ibm", "files", "images", "tm1", "tm1Web", "paw", "cognos"), "the file")
    if cfg.get("version") != 1:
        raise ConfigError("version: must be 1")
    name = cfg.get("name") or os.path.basename(HOST_DIR.rstrip("/\\")) or "pa"
    name = re.sub(r"[^a-z0-9_-]", "-", str(name).lower())
    m = {"name": name, "ibm": host_path(cfg["ibm"], "ibm") if cfg.get("ibm") else None,
         "files": host_path(cfg.get("files") or "pa-files", "files")}
    images = cfg.get("images") or {}
    check_keys(images, ("tm1Server", "tm1Web", "cognosServer", "keycloak"), "images")
    m["images"] = {
        "tm1Server": images.get("tm1Server") or f"ghcr.io/ajmyers/tm1-server:{IMAGE_TAG}",
        "tm1Web": images.get("tm1Web") or f"ghcr.io/ajmyers/tm1-web:{IMAGE_TAG}",
        "cognosServer": images.get("cognosServer") or f"ghcr.io/ajmyers/cognos-server:{IMAGE_TAG}",
        "keycloak": images.get("keycloak") or "quay.io/keycloak/keycloak:26.4",
    }

    tm1 = section(cfg, "tm1")
    servers = {}
    admin = None
    if tm1 is not None:
        check_keys(tm1, ("servers", "adminServer", "adminPort"), "tm1")
        raw = tm1.get("servers") or {}
        if not isinstance(raw, dict) or not raw:
            raise ConfigError("tm1.servers: name at least one server, e.g. tm1: {servers: {tm1: {}}}")
        used_ports = set()
        lower = set()
        for i, (sname, s) in enumerate(raw.items()):
            where = f"tm1.servers.{sname}"
            if not isinstance(sname, str) or not NAME.match(sname):
                raise ConfigError(f"{where}: server names are letters, digits, _ and -")
            if sname.lower() in lower:
                raise ConfigError(f"{where}: two servers differ only in case")
            lower.add(sname.lower())
            s = s or {}
            check_keys(s, ("port", "seed", "cfg"), where)
            p = port(s.get("port"), where, 8010 + i)
            if p in used_ports:
                raise ConfigError(f"{where}: port {p} is used twice")
            used_ports.add(p)
            seed = s.get("seed") or "empty"
            if seed not in SEEDS:
                raise ConfigError(f"{where}.seed: one of {', '.join(SEEDS)}")
            extra = s.get("cfg") or {}
            check_keys(extra, list(extra), where + ".cfg")
            for k in extra:
                if not NAME.match(str(k)):
                    raise ConfigError(f"{where}.cfg: {k!r} isn't a tm1s.cfg parameter name")
            servers[sname] = {"port": p, "seed": seed, "cfg": {str(k): str(v) for k, v in extra.items()}}
        wants_admin = tm1.get("adminServer")
        if wants_admin is True or (wants_admin is None and len(servers) > 1):
            admin = {"port": port(tm1.get("adminPort"), "tm1.adminPort", 5895)}
        elif wants_admin is False and len(servers) > 1:
            raise ConfigError("tm1.adminServer: several servers need a shared Admin Server")
    m["tm1"] = servers
    m["admin"] = admin

    web = section(cfg, "tm1Web")
    if web is not None:
        check_keys(web, ("port", "heap"), "tm1Web")
        if not servers:
            raise ConfigError("tm1Web: needs a TM1 server (tm1.servers)")
        web = {"port": port(web.get("port"), "tm1Web", 9510), "heap": str(web.get("heap") or "1g")}
    m["tm1Web"] = web

    cognos = section(cfg, "cognos")
    if cognos is not None:
        check_keys(cognos, ("port", "users", "oidc", "cam", "memory"), "cognos")
        users = [parse_user(u, f"cognos.users[{i}]") for i, u in enumerate(cognos.get("users") or [])]
        names = [u["name"] for u in users]
        if len(set(names)) != len(names):
            raise ConfigError("cognos.users: a user is listed twice")
        oidc = cognos.get("oidc")
        if oidc is True:
            oidc = {}
        if oidc:
            check_keys(oidc, ("keycloakPort",), "cognos.oidc")
            if not users:
                raise ConfigError("cognos.oidc: needs cognos.users")
            oidc = {"keycloakPort": port(oidc.get("keycloakPort"), "cognos.oidc", 8180)}
        cam = cognos.get("cam") or {}
        check_keys(cam, ("namespace", "admins"), "cognos.cam")
        namespace = (cam.get("namespace") or "ldap").lower()
        if namespace not in ("ldap", "oidc"):
            raise ConfigError("cognos.cam.namespace: ldap or oidc")
        if namespace == "oidc" and not oidc:
            raise ConfigError("cognos.cam.namespace: oidc needs cognos.oidc")
        admins = cam.get("admins") or (names[:1])
        if servers and not users:
            raise ConfigError("cognos.users: TM1 in CAM security needs test users")
        for a in admins:
            if a not in names:
                raise ConfigError(f"cognos.cam.admins: {a} isn't one of cognos.users")
        memory = cognos.get("memory") or 6144
        if not isinstance(memory, int) or memory < 2048:
            raise ConfigError("cognos.memory: the dispatcher heap in MB, at least 2048 (6144 recommended)")
        cognos = {"port": port(cognos.get("port"), "cognos", 9300), "users": users, "oidc": oidc or None,
                  "namespace": namespace, "admins": admins, "memory": memory}
    m["cognos"] = cognos

    paw = section(cfg, "paw")
    if paw is not None:
        check_keys(paw, ("port", "acceptLicense", "zip"), "paw")
        if not servers:
            raise ConfigError("paw: needs a TM1 server (tm1.servers)")
        if paw.get("acceptLicense") is not True:
            raise ConfigError("paw.acceptLicense: PAW starts only once you accept IBM's license (true); "
                              "read it with: unzip -p ipa_workspace_local_*.zip licenses/LA_en")
        if not m["ibm"]:
            raise ConfigError("paw: needs ibm, the folder with the PAW Local zip")
        paw = {"port": port(paw.get("port"), "paw", 8800), "zip": paw.get("zip")}
    m["paw"] = paw

    if not (servers or cognos):
        raise ConfigError("nothing to run: add tm1.servers and/or cognos")
    ports = [s["port"] for s in servers.values()] + ([admin["port"]] if admin else []) + \
        ([web["port"]] if web else []) + ([cognos["port"]] if cognos else []) + \
        ([cognos["oidc"]["keycloakPort"]] if cognos and cognos["oidc"] else []) + ([paw["port"]] if paw else [])
    dupes = sorted({p for p in ports if ports.count(p) > 1})
    if dupes:
        raise ConfigError(f"port(s) {', '.join(map(str, dupes))} used by more than one service")
    return m


# ---- compose --------------------------------------------------------------------

def service_name(server):
    return server.lower()


def generate(m):
    services, volumes = {}, {}
    ibm = [f"{m['ibm']}:/ibm:ro"] if m["ibm"] else []
    cognos = m["cognos"]
    if cognos and cognos["oidc"]:
        kc_port = cognos["oidc"]["keycloakPort"]
        services["keycloak"] = {
            "image": m["images"]["keycloak"], "command": ["start-dev"],
            "environment": {"KC_BOOTSTRAP_ADMIN_USERNAME": "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD": "admin",
                            "KC_HTTP_ENABLED": "true", "KC_HOSTNAME": f"http://localhost:{kc_port}",
                            "KC_HOSTNAME_BACKCHANNEL_DYNAMIC": "true"},
            "ports": [f"{kc_port}:8080"],
            "healthcheck": {"test": ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080"],
                            "interval": "10s", "timeout": "5s", "retries": 30, "start_period": "30s"},
        }
    if cognos:
        env = {"COGNOS_MAX_MEMORY": str(cognos["memory"]),
               "COGNOS_PUBLIC_URL": f"http://localhost:{cognos['port']}",
               "COGNOS_ZIP_URL": "${COGNOS_ZIP_URL:-}", "COGNOS_ZIP_SHA256": "${COGNOS_ZIP_SHA256:-}"}
        # Browsers come back from Cognos's logon to PAW and TM1 Web (IBM's pmhub.html)
        back = ([f"http://localhost:{m['paw']['port']}"] if m["paw"] else []) + \
            ([f"http://localhost:{m['tm1Web']['port']}"] if m["tm1Web"] else [])
        if back:
            env["COGNOS_PMHUB_URLS"] = " ".join(back)
        if cognos["users"]:
            env["COGNOS_USERS"] = " ".join(
                u["name"] + ":" + u["password"] + (":" + ",".join(u["groups"]) if u["groups"] else "")
                for u in cognos["users"])
        svc = {"image": m["images"]["cognosServer"], "platform": "linux/amd64", "hostname": "cognos",
               "stop_grace_period": "3m", "environment": env, "ports": [f"{cognos['port']}:9300"],
               "volumes": ["cognos-install:/opt/ibm/cognos/analytics", "cognos-content-store:/cognos/postgres"] + ibm}
        if cognos["oidc"]:
            env["COGNOS_KEYCLOAK_URL"] = "http://keycloak:8080"
            svc["depends_on"] = {"keycloak": {"condition": "service_healthy"}}
        services["cognos"] = svc
        volumes.update({"cognos-install": {}, "cognos-content-store": {}})

    tm1_common = {"image": m["images"]["tm1Server"], "platform": "linux/amd64", "stop_signal": "SIGINT",
                  "stop_grace_period": "2m"}
    tm1_env = {"TM1_BUNDLE_URL": "${TM1_BUNDLE_URL:-}", "TM1_BUNDLE_SHA256": "${TM1_BUNDLE_SHA256:-}"}
    if m["tm1"]:
        volumes["tm1-install"] = {}
    if m["admin"]:
        services["tm1-admin"] = dict(tm1_common, command=["admin"], hostname="tm1-admin",
                                     environment=dict(tm1_env), ports=[f"{m['admin']['port']}:5895"],
                                     volumes=["tm1-install:/opt/ibm/cognos/tm1_64"] + ibm +
                                     [f"{host_join(m['files'], 'tm1-admin', 'logs')}:/tm1/logs"])
    groups = []
    if cognos:
        for u in cognos["users"]:
            groups += [g for g in u["groups"] if g not in groups]
    for sname, s in m["tm1"].items():
        env = dict(tm1_env, TM1_SERVER_NAME=sname, TM1_SEED=s["seed"])
        env.update({f"TM1_CFG_{k}": v for k, v in s["cfg"].items()})
        depends = {}
        if m["admin"]:
            env["TM1_ADMIN_HOST"] = "tm1-admin"
            depends["tm1-admin"] = {"condition": "service_healthy"}
        if cognos:
            env.update({"TM1_CAM_URI": "http://cognos:9300", "TM1_CAM_NAMESPACE": cognos["namespace"].upper(),
                        "TM1_CAM_NAMESPACE_TYPE": cognos["namespace"], "TM1_CAM_ADMINS": " ".join(cognos["admins"])})
            if groups:
                env["TM1_CAM_GROUPS"] = " ".join(groups)
            depends["cognos"] = {"condition": "service_healthy"}
        files = host_join(m["files"], sname)
        svc = dict(tm1_common, hostname=service_name(sname), environment=env, ports=[f"{s['port']}:8010"],
                   volumes=["tm1-install:/opt/ibm/cognos/tm1_64"] + ibm +
                   [f"{host_join(files, d)}:/tm1/{d}" for d in ("config", "data", "logs")])
        if depends:
            svc["depends_on"] = depends
        services[service_name(sname)] = svc

    web = m["tm1Web"]
    if web:
        first = service_name(next(iter(m["tm1"])))
        services["tm1-web"] = {
            "image": m["images"]["tm1Web"], "platform": "linux/amd64",
            "environment": {"TM1_ADMIN_HOST": "tm1-admin" if m["admin"] else first, "TM1_WEB_HEAP": web["heap"],
                            "TM1_WEB_BUNDLE_URL": "${TM1_WEB_BUNDLE_URL:-}",
                            "TM1_WEB_BUNDLE_SHA256": "${TM1_WEB_BUNDLE_SHA256:-}"},
            "ports": [f"{web['port']}:9510"],
            "volumes": ["tm1-web-cache:/tm1-web/cache"] + ibm,
            "depends_on": {service_name(n): {"condition": "service_healthy"} for n in m["tm1"]},
        }
        volumes["tm1-web-cache"] = {}
    return {"name": m["name"], "services": services, "volumes": volumes}


HEADER = """# Generated by pa-setup from pa-environment.yaml: change that file and run 'up' again,
# rather than editing this one. Plain docker compose works on it too.
"""


def write_compose(m):
    text = HEADER + yaml.safe_dump(generate(m), sort_keys=False, default_flow_style=False)
    with open(os.path.join(WORK, "compose.yaml"), "w", encoding="utf-8") as f:
        f.write(text)
    env_file = os.path.join(WORK, ".env")
    if not os.path.exists(env_file):
        with open(env_file, "w", encoding="utf-8") as f:
            f.write("# Secrets for pa-setup, kept out of pa-environment.yaml. Uncomment what you need.\n"
                    "# Download links (e.g. pre-signed S3 URLs) instead of the files in ibm:\n"
                    "# TM1_BUNDLE_URL=https://...\n# TM1_WEB_BUNDLE_URL=https://...\n# COGNOS_ZIP_URL=https://...\n")


# ---- running ---------------------------------------------------------------------

def compose(m, *args, check=True):
    cmd = ["docker", "compose", "-p", m["name"], "-f", os.path.join(WORK, "compose.yaml"),
           "--project-directory", WORK, *args]
    return subprocess.run(cmd, check=check)


def paw_env(m):
    first = service_name(next(iter(m["tm1"])))
    # PAW_WAIT=0: paw-local.sh would wait on localhost, which inside the runner container
    # is the runner; wait_for_paw() checks from the project network instead.
    env = {"PAW_ACCEPT_LICENSE": "true", "PAW_WAIT": "0", "PAW_HTTP_PORT": str(m["paw"]["port"]),
           "PAW_HTTPS_PORT": str(m["paw"]["port"] + 43), "PAW_NETWORK": f"{m['name']}_default",
           "PAW_TM1_ADMIN_URL": f"http://{'tm1-admin' if m['admin'] else first}:5895",
           "PAW_TM1_LOGIN_URL": f"http://{first}:8010", "PAW_ZIP": "/ibm/" + (m["paw"]["zip"] or "")}
    if m["tm1Web"]:
        env["PAW_TM1WEB_URL"] = "http://tm1-web:9510"
    if m["cognos"]:
        # The gateway is where PAW sends browsers to log on, so it's Cognos's address on the
        # host: Cognos's own logon page, and through the OIDC namespace, the provider's.
        env.update({"PAW_AUTH_MODE": "cam", "PAW_CAM_SERVLET": "http://cognos:9300/p2pd/servlet/dispatch",
                    "PAW_CAM_GATEWAY": f"http://localhost:{m['cognos']['port']}/bi/v1/disp",
                    "PAW_CAM_NAMESPACE": m["cognos"]["namespace"].upper()})
    return env


def run_paw(m, command):
    """paw-local.sh in a runner container (this image), as tm1-testcontainers does: it
    needs the Docker socket and PAW's home volume at its path on the Docker host."""
    subprocess.run(["docker", "volume", "create", "tm1-paw-home"], check=True, stdout=subprocess.DEVNULL)
    home = subprocess.run(["docker", "volume", "inspect", "-f", "{{.Mountpoint}}", "tm1-paw-home"], check=True,
                          capture_output=True, text=True).stdout.strip()
    env = paw_env(m)
    if not m["paw"]["zip"]:
        found = subprocess.run(["docker", "run", "--rm", "-v", f"{m['ibm']}:/ibm:ro", "--entrypoint", "sh", SELF_IMAGE,
                                "-c", "ls -t /ibm/ipa_workspace_local_*.zip 2>/dev/null | head -1"],
                               capture_output=True, text=True).stdout.strip()
        if not found:
            raise ConfigError(f"paw: no ipa_workspace_local_*.zip in {m['ibm']}")
        env["PAW_ZIP"] = found
    cmd = ["docker", "run", "--rm", "-v", "/var/run/docker.sock:/var/run/docker.sock",
           "-v", f"tm1-paw-home:{home}", "-v", f"{m['ibm']}:/ibm:ro", "-e", f"PAW_HOME={home}"]
    for k, v in env.items():
        cmd += ["-e", f"{k}={v}"]
    subprocess.run(cmd + ["--entrypoint", "bash", SELF_IMAGE, "/usr/local/lib/pa-setup/paw-local.sh", command],
                   check=True)


def memory_needed(m):
    """Rough memory for the chosen parts, in GB, from what they used in testing."""
    gb = 0.5 * len(m["tm1"]) + (0.3 if m["admin"] else 0) + (1.0 if m["tm1Web"] else 0)
    if m["cognos"]:
        gb += 8 + (0.7 if m["cognos"]["oidc"] else 0)
    if m["paw"]:
        gb += 15
    return gb


def check_memory(m):
    try:
        total = int(subprocess.run(["docker", "info", "--format", "{{.MemTotal}}"], capture_output=True,
                                   text=True, check=True).stdout.strip()) / 1024 ** 3
    except (subprocess.CalledProcessError, ValueError):
        return
    need = memory_needed(m)
    if total < need + 2:
        log(f"WARNING: Docker has {total:.0f} GB of memory; this environment needs about {need:.0f} GB plus room "
            "for anything else running. Raise it in Docker Desktop (Settings > Resources), or things will stall.")


def wait_for_paw(m, seconds=600):
    """Polls PAW's gateway on the project network until its logon page answers."""
    script = ("for i in $(seq 1 %d); do curl -fs -o /dev/null -m 5 http://pa-gateway:8000/login && exit 0; "
              "sleep 5; done; exit 1") % (seconds // 5)
    result = subprocess.run(["docker", "run", "--rm", "--network", f"{m['name']}_default", "--entrypoint", "sh",
                             SELF_IMAGE, "-c", script])
    if result.returncode != 0:
        raise ConfigError(f"PAW didn't answer within {seconds}s; see: docker logs pa-gateway")


def urls(m):
    rows = []
    c = m["cognos"]
    logins = "native: admin, no password"
    if c:
        logins = "CAM, namespace " + c["namespace"].upper() + ": " + ", ".join(u["name"] for u in c["users"])
    for sname, s in m["tm1"].items():
        login = logins if c else ("admin / apple" if s["seed"] == "sdata" else
                                  "admin / (none)" if s["seed"] == "empty" else "admin")
        rows.append((f"TM1 {sname}", f"http://localhost:{s['port']}/api/v1/", login))
    if m["admin"]:
        rows.append(("TM1 Admin Server", f"http://localhost:{m['admin']['port']}/api/v1/Servers", ""))
    if m["tm1Web"]:
        rows.append(("TM1 Web", f"http://localhost:{m['tm1Web']['port']}/tm1web/", "as for TM1"))
    if c:
        rows.append(("Cognos", f"http://localhost:{c['port']}/bi/",
                     "namespaces LDAP" + (" and OIDC" if c["oidc"] else "") + "; passwords in pa-environment.yaml"))
        if c["oidc"]:
            rows.append(("Keycloak", f"http://localhost:{c['oidc']['keycloakPort']}/", "console: admin / admin"))
    if m["paw"]:
        rows.append(("PAW", f"http://localhost:{m['paw']['port']}/", "as for TM1"))
    width = max(len(r[0]) for r in rows)
    for what, url, login in rows:
        log(f"  {what.ljust(width)}  {url}" + (f"   ({login})" if login else ""))


SAMPLE = """# pa-environment.yaml: what pa-setup runs. Then run: setup.sh up
# Every section is optional. Format: https://github.com/ajmyers/skipcheck-tm1/tree/master/docker/pa-setup
# Download links and other secrets go in .env, not here.
version: 1
ibm: ./ibm                 # the folder holding your IBM downloads (bundles, the Cognos zip)
tm1:
  servers:
    tm1: { port: 8010 }    # seed: sdata for IBM's sample
# tm1Web: { port: 9510 }
# cognos:
#   port: 9300
#   users:
#     - admin:adminpw:Admins
#     - analyst:analystpw:Analysts,Readers
#   oidc: { keycloakPort: 8180 }
#   cam: { namespace: ldap, admins: [admin] }
# paw: { port: 8800, acceptLicense: true }
"""


def main():
    parser = argparse.ArgumentParser(prog="setup", description=__doc__.split("\n\n")[0])
    parser.add_argument("command", nargs="?", default="up",
                        choices=["init", "up", "generate", "urls", "status", "logs", "down"])
    parser.add_argument("services", nargs="*")
    parser.add_argument("-f", "--file", default="pa-environment.yaml")
    a = parser.parse_args()
    path = os.path.join(WORK, a.file)
    try:
        if a.command == "init":
            if os.path.exists(path):
                raise ConfigError(f"{a.file} already exists; edit it, then run 'up'")
            with open(path, "w", encoding="utf-8") as f:
                f.write(SAMPLE)
            log(f"Wrote {a.file}. Edit it, then run: setup.sh up")
            return 0
        m = load(path)
        if a.command == "generate":
            write_compose(m)
            log("Wrote compose.yaml")
        elif a.command == "up":
            write_compose(m)
            check_memory(m)
            log(f"Starting {', '.join(generate(m)['services'])}" + (" (Cognos takes a few minutes)" if m["cognos"] else "") + "...")
            compose(m, "up", "-d", "--wait", "--wait-timeout", "1800")
            if m["paw"]:
                log("Starting PAW (the first start loads IBM's images, a few minutes)...")
                run_paw(m, "up")
                wait_for_paw(m)
            log("\nReady:")
            urls(m)
        elif a.command == "urls":
            urls(m)
        elif a.command == "status":
            compose(m, "ps")
        elif a.command == "logs":
            compose(m, "logs", "--tail", "100", *a.services)
        elif a.command == "down":
            if m["paw"]:
                run_paw(m, "down")
            compose(m, "down")
        return 0
    except ConfigError as e:
        print(f"pa-setup: {e}", file=sys.stderr)
        return 2
    except subprocess.CalledProcessError as e:
        print(f"pa-setup: {' '.join(e.cmd[:3])} failed (exit {e.returncode})", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
