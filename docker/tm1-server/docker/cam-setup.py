"""Give a TM1 database its Cognos (CAM) administrators and groups.

Usage: cam-setup.py <TM1 REST base URL, e.g. http://localhost:18010>
       cam-setup.py --print        (just print the CAMIDs it would use)

Run by the entrypoint against a short-lived TM1 started in native security
(IntegratedSecurityMode=1): in CAM mode nobody can log on as an administrator
until a CAM user is in TM1's ADMIN group, and only a native admin can put one
there. Environment:
  TM1_CAM_ADMINS     Cognos users to make TM1 administrators (ADMIN group)
  TM1_CAM_GROUPS     Cognos groups to add to TM1, so their members get them at logon
  TM1_CAM_NAMESPACE  the Cognos namespace ID (default LDAP)
  TM1_CAM_NAMESPACE_TYPE  ldap (default) or oidc: how short names become CAMIDs
  TM1_NATIVE_ADMIN_PASSWORD   password of TM1's native Admin (default: none)

Entries are separated by spaces or commas. A short name assumes cognos-server's
namespaces: LDAP (users uid=<name>,ou=people; groups cn=<name>,ou=groups) or OIDC
(users and groups by name); anything else, give the full CAMID("...") as Cognos
shows it.
"""
import base64
import http.cookiejar
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

NS = os.environ.get("TM1_CAM_NAMESPACE") or "LDAP"
TYPE = (os.environ.get("TM1_CAM_NAMESPACE_TYPE") or "ldap").lower()
if TYPE not in ("ldap", "oidc"):
    sys.exit(f"TM1_CAM_NAMESPACE_TYPE must be ldap or oidc, not '{TYPE}'")


def entries(var):
    # A full CAMID("...") stays whole: DN-style ones contain commas and spaces.
    return re.findall(r'CAMID\("[^"]*"\)|[^\s,]+', os.environ.get(var, ""))


def camid(kind, name):
    if name.startswith("CAMID("):
        return name
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        sys.exit(f"'{name}' isn't a plain name; give the full CAMID(\"...\") instead")
    # Cognos writes group names in lower case (cn=analysts for group Analysts).
    if TYPE == "oidc":
        return f'CAMID("{NS}:u:{name}")' if kind == "user" else f'CAMID("{NS}:g:{name.lower()}")'
    if kind == "user":
        return f'CAMID("{NS}:u:uid={name},ou=people")'
    return f'CAMID("{NS}:g:cn={name.lower()},ou=groups")'


admins = [camid("user", u) for u in entries("TM1_CAM_ADMINS")]
groups = [camid("group", g) for g in entries("TM1_CAM_GROUPS")]
if sys.argv[1:] == ["--print"]:
    for a in admins:
        print("admin", a)
    for g in groups:
        print("group", g)
    sys.exit(0)

base = sys.argv[1].rstrip("/") + "/api/v1/"
auth = "Basic " + base64.b64encode(f"Admin:{os.environ.get('TM1_NATIVE_ADMIN_PASSWORD', '')}".encode()).decode()
opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))


class NotReady(Exception):
    pass


def call(method, path, body=None):
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    try:
        with opener.open(req, timeout=30) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return 404
        if e.code == 503:
            raise NotReady() from e
        raise SystemExit(f"{method} {path} failed: HTTP {e.code} {e.read()[:300].decode(errors='replace')}")


def key(collection, name):
    return f"{collection}('{urllib.parse.quote(name.replace(chr(39), chr(39) * 2), safe='')}')"


for _ in range(120):
    try:
        call("GET", "Configuration/ProductVersion")
        break
    except (NotReady, urllib.error.URLError, ConnectionError, OSError):
        time.sleep(1)
else:
    sys.exit("TM1 didn't come up for the CAM setup")

for g in groups:
    if call("GET", key("Groups", g)) == 404:
        call("POST", "Groups", {"Name": g})
        print(f"  group {g} added")
for a in admins:
    if call("GET", key("Users", a)) == 404:
        call("POST", "Users", {"Name": a, "Groups@odata.bind": ["Groups('ADMIN')"]})
    else:
        call("PATCH", key("Users", a), {"Groups@odata.bind": ["Groups('ADMIN')"]})
    print(f"  {a} is a TM1 administrator")
call("POST", "ActiveSession/tm1.Close")
