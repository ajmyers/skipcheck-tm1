# SPDX-License-Identifier: Apache-2.0

"""Create a Keycloak realm for Cognos's OIDC namespace from the test users.

Usage: keycloak-setup.py     (prints the COGNOS_OIDC_* settings for the namespace)

The realm is rebuilt on every start (deleted, then created), so the user list
(COGNOS_USERS / COGNOS_USERS_FILE, see users.py) stays the only source of truth:
- users with their passwords, email <user>@example.com, and their groups;
- a confidential client for Cognos ("cognos") with the authorization-code flow
  (browsers), direct access grants (Cognos's password grant), and a "groups"
  claim with the user's group names;
- a public client for other applications ("tm1-app") whose ID tokens Cognos
  accepts: Cognos checks a token's audience against its own client, so this
  client adds "cognos" to the audience. An app gets a user's ID token from it and
  logs on to Cognos with CAMOIDCIdToken.

Environment:
  COGNOS_KEYCLOAK_URL             Keycloak as Cognos reaches it, e.g. http://keycloak:8080
  COGNOS_KEYCLOAK_ADMIN           its admin user (default admin)
  COGNOS_KEYCLOAK_ADMIN_PASSWORD  its admin password (default admin)
  COGNOS_KEYCLOAK_REALM           the realm (default cognos)
  COGNOS_PUBLIC_URL               Cognos as browsers reach it (default http://localhost:9300)
"""
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import users as user_list  # noqa: E402

env = os.environ.get
base = env("COGNOS_KEYCLOAK_URL").rstrip("/")
realm = env("COGNOS_KEYCLOAK_REALM") or "cognos"
public = (env("COGNOS_PUBLIC_URL") or "http://localhost:9300").rstrip("/")
client_id = "cognos"


def log(message):
    print(f"[cognos-keycloak] {message}", file=sys.stderr, flush=True)


def call(method, path, body=None, token=None, form=None):
    data, headers = None, {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text.strip().startswith("{") else text
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def admin_token():
    status, reply = call("POST", "/realms/master/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "admin-cli",
        "username": env("COGNOS_KEYCLOAK_ADMIN") or "admin",
        "password": env("COGNOS_KEYCLOAK_ADMIN_PASSWORD") or "admin"})
    if status != 200:
        sys.exit(f"Keycloak refused its admin login (HTTP {status}): {str(reply)[:200]}")
    return reply["access_token"]


users, groups = user_list.parse()
log(f"Waiting for Keycloak at {base}")
for _ in range(300):
    try:
        if call("GET", "/realms/master")[0] == 200:
            break
    except (urllib.error.URLError, OSError):
        pass
    time.sleep(1)
else:
    sys.exit(f"Keycloak didn't answer at {base}")

token = admin_token()
call("DELETE", f"/admin/realms/{realm}", token=token)
secret = secrets.token_urlsafe(24)
groups_claim = {
    "name": "groups", "protocol": "openid-connect", "protocolMapper": "oidc-group-membership-mapper",
    "config": {"claim.name": "groups", "full.path": "false", "id.token.claim": "true",
               "access.token.claim": "true", "userinfo.token.claim": "true"},
}
representation = {
    "realm": realm,
    "enabled": True,
    "sslRequired": "none",
    "groups": [{"name": g} for g in groups],
    "users": [{
        "username": u, "enabled": True, "email": f"{u}@example.com", "emailVerified": True,
        "firstName": u, "lastName": u,
        "credentials": [{"type": "password", "value": p, "temporary": False}],
        "groups": ["/" + g for g, members in groups.items() if u in members],
    } for u, p in users.items()],
    "clients": [{
        "clientId": client_id, "secret": secret, "enabled": True, "publicClient": False,
        "standardFlowEnabled": True, "directAccessGrantsEnabled": True,
        "redirectUris": [public + "/bi/completeAuth.jsp", public + "/*"], "webOrigins": ["+"],
        "protocolMappers": [groups_claim],
    }, {
        "clientId": "tm1-app", "enabled": True, "publicClient": True,
        "standardFlowEnabled": True, "directAccessGrantsEnabled": True,
        "redirectUris": ["*"], "webOrigins": ["+"],
        "protocolMappers": [groups_claim, {
            "name": "cognos-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
            "config": {"included.client.audience": client_id, "id.token.claim": "true", "access.token.claim": "true"},
        }],
    }],
}
status, reply = call("POST", "/admin/realms", body=representation, token=token)
if status != 201:
    sys.exit(f"Keycloak didn't create realm {realm} (HTTP {status}): {str(reply)[:300]}")
log(f"Realm {realm} ready with {len(users)} user(s), and clients {client_id} (Cognos) and tm1-app (other apps)")
print(f"COGNOS_OIDC_DISCOVERY_URL={base}/realms/{realm}/.well-known/openid-configuration")
print(f"COGNOS_OIDC_CLIENT_ID={client_id}")
print(f"COGNOS_OIDC_CLIENT_SECRET={secret}")
