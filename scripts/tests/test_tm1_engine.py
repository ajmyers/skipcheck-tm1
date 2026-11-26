# SPDX-License-Identifier: Apache-2.0

"""docker/tm1-engine's setup tool (docker/engine.py), which runs inside the image: what it works out
from the environment, what it writes to TM1's configuration, and what it creates, against a
stand-in for TM1's gateway (CI has no IBM software). The image itself is tested by
docker/tm1-engine/ci-smoke-test.sh, and against real TM1 by hand."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pytest
from conftest import load

engine = load("docker/tm1-engine/docker/engine.py")
stand_in = load("docker/tm1-engine/test/fake_tm1sd.py")
FakeTm1 = stand_in.FakeTm1


# ---- what to create -------------------------------------------------------------------


def test_plan_defaults_to_one_database_and_application() -> None:
    assert engine.plan({}) == {
        "instances": {"tm1": {"applications": ["tm1"], "databases": ["tm1"]}},
        "users": [],
    }


def test_plan_reads_databases_applications_and_users() -> None:
    plan = engine.plan(
        {
            "TM1_DATABASES": "planning, finance/sales",
            "TM1_APPLICATIONS": "paw,finance/reports",
            "TM1_USERS": "analyst:Analysts:Readers, viewer",
        }
    )
    assert plan["instances"] == {
        "tm1": {"applications": ["paw"], "databases": ["planning"]},
        "finance": {"applications": ["reports"], "databases": ["sales"]},
    }
    assert plan["users"] == [
        {"name": "analyst", "groups": ["Analysts", "Readers"]},
        {"name": "viewer", "groups": []},
    ]


def test_plan_takes_a_group_named_twice_as_one() -> None:
    assert engine.plan({"TM1_USERS": "analyst:Analysts:analysts:Readers"})["users"] == [
        {"name": "analyst", "groups": ["Analysts", "Readers"]}
    ]


ANALYST = 'CAMID("LDAP:u:uid=analyst,ou=people")'


def test_plan_takes_a_camid_whole_with_its_groups() -> None:
    # A CAMID has the commas and colons that part users and groups everywhere else
    plan = engine.plan({"TM1_USERS": f'viewer, {ANALYST}:Analysts:Readers ,CAMID("OIDC:u:alice"), admin:Ops'})
    assert plan["users"] == [
        {"name": "viewer", "groups": []},
        {"name": ANALYST, "groups": ["Analysts", "Readers"]},
        {"name": 'CAMID("OIDC:u:alice")', "groups": []},
        {"name": "admin", "groups": ["Ops"]},
    ]


def test_plan_names_users_by_their_camid_in_a_cognos_namespace() -> None:
    # The name a TM1 11 server in CAM security answers a pass-through login with, and its friendly name
    plan = engine.plan({"TM1_USERS": "analyst:Analysts,admin:ADMIN", "TM1_CAM_NAMESPACE": "LDAP"})
    assert plan["users"] == [
        {"name": ANALYST, "groups": ["Analysts"], "friendlyName": "LDAP/analyst"},
        {"name": 'CAMID("LDAP:u:uid=admin,ou=people")', "groups": ["ADMIN"], "friendlyName": "LDAP/admin"},
    ]


def test_plan_names_an_oidc_namespaces_users_its_way_and_leaves_a_full_camid_alone() -> None:
    env = {"TM1_USERS": f"alice,{ANALYST}", "TM1_CAM_NAMESPACE": "OIDC", "TM1_CAM_NAMESPACE_TYPE": "oidc"}
    assert engine.plan(env)["users"] == [
        {"name": 'CAMID("OIDC:u:alice")', "groups": [], "friendlyName": "OIDC/alice"},
        {"name": ANALYST, "groups": []},
    ]


def test_plan_gives_each_instance_an_application_by_default() -> None:
    plan = engine.plan({"TM1_DATABASES": "planning,finance/sales"})
    assert [entry["applications"] for entry in plan["instances"].values()] == [
        ["tm1"],
        ["tm1"],
    ]


def test_plan_with_no_databases_still_has_the_instance_and_its_application() -> None:
    assert engine.plan({"TM1_DATABASES": ""}) == {
        "instances": {"tm1": {"applications": ["tm1"], "databases": []}},
        "users": [],
    }


def test_plan_takes_names_as_tm1_does_and_an_empty_application_list_as_unset() -> None:
    # TM1's database names are case-sensitive; compose passes a variable that isn't set as empty
    plan = engine.plan({"TM1_DATABASES": "sales,Sales", "TM1_APPLICATIONS": " "})
    assert plan["instances"] == {
        "tm1": {"applications": ["tm1"], "databases": ["sales", "Sales"]}
    }


@pytest.mark.parametrize(
    ("env", "message"),
    [
        ({"TM1_DATABASES": "ab"}, "at least 3 characters"),
        ({"TM1_DATABASES": "sales,sales"}, "listed twice"),
        ({"TM1_DATABASES": "my db"}, "names are letters"),
        ({"TM1_DATABASES": "a/b/sales"}, "names are letters"),
        ({"TM1_USERS": "analyst:"}, "USER[:GROUP[:GROUP]]"),
        ({"TM1_USERS": "analyst,Analyst"}, "listed twice"),
        ({"TM1_USERS": f"{ANALYST},{ANALYST}:Analysts"}, "listed twice"),
        ({"TM1_USERS": f"{ANALYST}Analysts"}, 'expected CAMID("...")[:GROUP[:GROUP]]'),
        ({"TM1_USERS": f"{ANALYST}:"}, "USER[:GROUP[:GROUP]]"),
        ({"TM1_USERS": "john smith", "TM1_CAM_NAMESPACE": "LDAP"}, 'give the full CAMID("...") instead'),
        ({"TM1_USERS": "a", "TM1_CAM_NAMESPACE": "LDAP", "TM1_CAM_NAMESPACE_TYPE": "saml"}, "ldap or oidc"),
        ({"TM1_USERS": "a", "TM1_CAM_NAMESPACE_TYPE": "oidc"}, "set without TM1_CAM_NAMESPACE"),
        ({"TM1_USERS": "a", "TM1_CAM_NAMESPACE": "my ns"}, "a namespace's ID is letters"),
    ],
)
def test_plan_refuses(env: dict[str, str], message: str) -> None:
    with pytest.raises(engine.Failure, match=re.escape(message)):
        engine.plan(env)


# ---- configuration ----------------------------------------------------------------------


def template() -> dict:
    return {
        "datastore": {
            "external": False,
            "protocol": "mongodb",
            "host": "127.0.0.1:27027",
            "authMechanism": "SCRAM-SHA-256",
            "authSource": "admin",
            "user": "",
            "pass": "",
            "replicaSet": "",
            "controlDatabase": "tm1-control-default",
            "tlsPath": "",
        },
        "services": {
            "tm1-gateway": {"host": "127.0.0.1", "port": "10000", "serverPort": "9999"}
        },
    }


def test_datastore_url_becomes_the_datastore_section() -> None:
    assert engine.parse_datastore_url(
        "mongodb://root:p%40ss@a:27017,b:27017/?replicaSet=rs0&authSource=users&tls=true&tlsCAFile=/certs/ca.pem"
    ) == {
        "external": True,
        "protocol": "mongodb",
        "host": "a:27017,b:27017",
        "user": "root",
        "pass": "p%40ss",
        "authMechanism": "SCRAM-SHA-256",
        "authSource": "users",
        "replicaSet": "rs0",
        "tlsPath": "/certs/ca.pem",
    }
    assert (
        engine.parse_datastore_url("mongodb+srv://cluster.example")["protocol"]
        == "mongodb+srv"
    )
    assert engine.parse_datastore_url("mongodb://mongo:27017")["user"] == ""


def test_datastore_url_forms_mongodb_accepts() -> None:
    # A password holding / or @ isn't cut short (and so isn't logged as part of the host)
    odd = engine.parse_datastore_url("mongodb://tm1:ab/c@d@mongo:27017")
    assert (odd["user"], odd["pass"], odd["host"]) == ("tm1", "ab/c@d", "mongo:27017")
    # Options straight after the host, and the path's database as where the user is defined
    bare = engine.parse_datastore_url("mongodb://host:27017?replicaSet=rs0")
    assert (bare["host"], bare["replicaSet"], bare["authSource"]) == (
        "host:27017",
        "rs0",
        "admin",
    )
    assert (
        engine.parse_datastore_url("mongodb://u:pw@host/mydb")["authSource"] == "mydb"
    )
    assert (
        engine.parse_datastore_url("mongodb://u:pw@host/mydb?authSource=other")[
            "authSource"
        ]
        == "other"
    )


@pytest.mark.parametrize(
    "url", ["mongo:27017", "http://mongo:27017", "mongodb://user:pw@/"]
)
def test_datastore_url_refuses(url: str) -> None:
    with pytest.raises(engine.Failure):
        engine.parse_datastore_url(url)


def test_configure_fixes_the_port_and_leaves_tm1s_own_mongodb_alone() -> None:
    config = template()
    changes = engine.configure(config, {})
    assert config["services"]["tm1-gateway"]["serverPort"] == "4444"
    assert config["datastore"] == template()["datastore"]
    assert changes == ["services.tm1-gateway.serverPort: '9999' -> '4444'"]


def test_configure_turns_oauth2_on_from_the_settings() -> None:
    config: dict[str, Any] = {"authentication": {"jwtIssuer": "TM1"}}
    changes = engine.configure(
        config,
        {
            "TM1_OAUTH2_ISSUER_URL": " http://keycloak:8080/realms/tm1 ",
            "TM1_OAUTH2_AUDIENCE": "tm1",
            "TM1_OAUTH2_ID_CLAIMS": "preferred_username, email",
        },
    )
    assert config["authentication"] == {
        "jwtIssuer": "TM1",
        "oauth2": {
            "issuerURL": "http://keycloak:8080/realms/tm1",
            "audience": "tm1",
            "idClaims": ["preferred_username", "email"],
        },
    }
    assert "authentication.oauth2.audience: None -> 'tm1'" in changes
    # The same settings again change nothing
    again = engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": "http://keycloak:8080/realms/tm1", "TM1_OAUTH2_AUDIENCE": "tm1", "TM1_OAUTH2_ID_CLAIMS": "preferred_username,email"})
    assert [change for change in again if "oauth2" in change] == []


def test_configure_leaves_oauth2_alone_unless_asked() -> None:
    # A config.internal.json someone mounted, with OAuth 2.0 set by hand
    mounted = {"issuerURL": "https://login.example.com", "audience": "tm1", "idClaims": ["sub"]}
    config: dict[str, Any] = {"authentication": {"oauth2": dict(mounted)}}
    # compose passes an unset variable as empty
    engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": "", "TM1_OAUTH2_AUDIENCE": ""})
    assert config["authentication"]["oauth2"] == mounted


def test_configure_takes_oauth2_off_when_told_to_and_drops_claims_no_longer_asked_for() -> None:
    config: dict[str, Any] = {
        "authentication": {
            "oauth2": {"issuerURL": "http://old", "audience": "old", "idClaims": ["email"], "friendlyNameClaims": ["name"]}
        }
    }
    changes = engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": "http://keycloak:8080/realms/tm1", "TM1_OAUTH2_AUDIENCE": "tm1", "TM1_OAUTH2_NAME_CLAIMS": "name"})
    # idClaims unset is TM1's own default (sub), not what an earlier start asked for
    assert config["authentication"]["oauth2"] == {
        "issuerURL": "http://keycloak:8080/realms/tm1",
        "audience": "tm1",
        "friendlyNameClaims": ["name"],
    }
    assert "authentication.oauth2.idClaims: removed" in changes

    changes = engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": "none"})
    assert "oauth2" not in config["authentication"]
    assert "authentication.oauth2: removed" in changes
    # Already off: nothing more to change, however "none" is written
    assert [c for c in engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": "None"}) if "oauth2" in c] == []


@pytest.mark.parametrize(
    ("env", "message"),
    [
        ({"TM1_OAUTH2_AUDIENCE": "tm1"}, "TM1_OAUTH2_AUDIENCE is set without TM1_OAUTH2_ISSUER_URL"),
        ({"TM1_OAUTH2_ID_CLAIMS": "sub"}, "TM1_OAUTH2_ID_CLAIMS is set without"),
        ({"TM1_OAUTH2_ISSUER_URL": "keycloak:8080"}, "must be an http:// or https:// address"),
        ({"TM1_OAUTH2_ISSUER_URL": "http://", "TM1_OAUTH2_AUDIENCE": "tm1"}, "must be an http:// or https:// address"),
        ({"TM1_OAUTH2_ISSUER_URL": "http://key cloak/realms", "TM1_OAUTH2_AUDIENCE": "tm1"}, "must be an http://"),
        ({"TM1_OAUTH2_ISSUER_URL": "https://login.example.com extra", "TM1_OAUTH2_AUDIENCE": "tm1"}, "must be an http://"),
        (
            {"TM1_OAUTH2_ISSUER_URL": "http://kc/r", "TM1_OAUTH2_AUDIENCE": "tm1", "TM1_OAUTH2_ID_CLAIMS": " , "},
            "TM1_OAUTH2_ID_CLAIMS names no claim",
        ),
        ({"TM1_OAUTH2_ISSUER_URL": "http://keycloak:8080/realms/tm1"}, "TM1_OAUTH2_AUDIENCE is needed"),
    ],
)
def test_configure_refuses_oauth2_settings_that_cant_work(env: dict[str, str], message: str) -> None:
    with pytest.raises(engine.Failure, match=message):
        engine.configure({}, env)


@pytest.mark.parametrize(
    "issuer",
    [
        # Hosts that start with s: a slip in the check once took the letter for whitespace
        "https://sts.windows.net/00000000-0000-0000-0000-000000000000/",
        "http://sso.example.com/realms/tm1",
        "https://samples.auth0.com/",
        "https://login.microsoftonline.com/common/v2.0",
        "http://keycloak:8080/realms/tm1",
        "HTTPS://Login.Example.Com".lower(),
    ],
)
def test_configure_takes_any_http_address_as_the_issuer(issuer: str) -> None:
    config: dict[str, Any] = {}
    engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": issuer, "TM1_OAUTH2_AUDIENCE": "tm1"})
    assert config["authentication"]["oauth2"] == {"issuerURL": issuer, "audience": "tm1"}


def test_none_turns_oauth2_off_whatever_else_is_still_set() -> None:
    config: dict[str, Any] = {"authentication": {"oauth2": {"issuerURL": "http://old", "audience": "old"}}}
    left = {"TM1_OAUTH2_ISSUER_URL": "none", "TM1_OAUTH2_AUDIENCE": "tm1", "TM1_OAUTH2_ID_CLAIMS": "sub"}
    assert "authentication.oauth2: removed" in engine.configure(config, left)
    assert config["authentication"] == {}


def test_configure_says_when_oauth2_stays_on_without_the_settings() -> None:
    config: dict[str, Any] = {"authentication": {"oauth2": {"issuerURL": "http://old/realm", "audience": "tm1"}}}
    notices: list[str] = []
    changes = engine.configure(config, {}, notices)
    # Said, but not a change: the file isn't written for it
    assert [c for c in changes if "oauth2" in c] == []
    assert notices == [
        "authentication.oauth2: left on (issuer http://old/realm); TM1_OAUTH2_ISSUER_URL=none turns OAuth 2.0 logins off"
    ]
    assert config["authentication"]["oauth2"]["issuerURL"] == "http://old/realm"


def test_configure_adds_no_authentication_section_unless_oauth2_is_asked_for() -> None:
    config: dict[str, Any] = {}
    engine.configure(config, {})
    assert "authentication" not in config
    engine.configure(config, {"TM1_OAUTH2_ISSUER_URL": "none"})
    assert "authentication" not in config


@pytest.mark.parametrize("held", [{"authentication": None}, {"authentication": {"oauth2": None}}, {"authentication": {"oauth2": "x"}}])
def test_configure_replaces_an_oauth2_section_that_isnt_one(held: dict[str, Any]) -> None:
    # A file someone mounted, with the section emptied out rather than removed
    engine.configure(held, {"TM1_OAUTH2_ISSUER_URL": "http://kc/r", "TM1_OAUTH2_AUDIENCE": "tm1"})
    assert held["authentication"]["oauth2"] == {"issuerURL": "http://kc/r", "audience": "tm1"}
    assert engine.configure({"authentication": {"oauth2": None}}, {}) is not None


def test_a_mistake_in_the_oauth2_settings_is_caught_before_anything_is_installed() -> None:
    # The entrypoint runs 'plan' first
    with pytest.raises(engine.Failure, match="TM1_OAUTH2_AUDIENCE is needed"):
        engine.plan({"TM1_OAUTH2_ISSUER_URL": "http://kc/r"})
    assert engine.plan({"TM1_OAUTH2_ISSUER_URL": "http://kc/r", "TM1_OAUTH2_AUDIENCE": "tm1"})["users"] == []


V11 = {
    "serviceRootURL": "http://tm1-server:8010/api/v1/",
    "authEndpoint": "ActiveUser",
    "authJSONPointer": "/Name",
    "finalizeEndpoint": "ActiveSession/tm1.Close",
    "finalizeMethod": "POST",
}


@pytest.mark.parametrize("url", ["http://tm1-server:8010/api/v1/", " http://tm1-server:8010/api/v1 "])
def test_configure_forwards_logins_to_a_tm1_11_server_by_default(url: str) -> None:
    config: dict[str, Any] = {"authentication": {"jwtIssuer": "TM1"}}
    changes = engine.configure(config, {"TM1_PASSTHROUGH_URL": url})
    # The defaults are a TM1 11 server's: who the user is, and the session there closed again
    assert config["authentication"] == {"jwtIssuer": "TM1", "http": V11}
    assert "authentication.http.authEndpoint: None -> 'ActiveUser'" in changes
    assert [c for c in engine.configure(config, {"TM1_PASSTHROUGH_URL": url}) if "http" in c] == []


def test_configure_takes_another_servers_ways() -> None:
    config: dict[str, Any] = {}
    engine.configure(
        config,
        {
            "TM1_PASSTHROUGH_URL": "https://auth.example.com/v2",
            "TM1_PASSTHROUGH_ENDPOINT": "/whoami",
            "TM1_PASSTHROUGH_POINTER": "/user/login",
            "TM1_PASSTHROUGH_FINALIZE": "/logout",
            "TM1_PASSTHROUGH_FINALIZE_METHOD": "delete",
        },
    )
    assert config["authentication"]["http"] == {
        "serviceRootURL": "https://auth.example.com/v2/",
        "authEndpoint": "whoami",
        "authJSONPointer": "/user/login",
        "finalizeEndpoint": "logout",
        "finalizeMethod": "DELETE",
    }
    # No finalize asked for is none, an earlier start's included
    changes = engine.configure(config, {"TM1_PASSTHROUGH_URL": "https://auth.example.com/v2", "TM1_PASSTHROUGH_FINALIZE": "none"})
    assert "finalizeEndpoint" not in config["authentication"]["http"]
    assert "authentication.http.finalizeMethod: removed" in changes


def test_configure_leaves_pass_through_alone_unless_asked_and_says_when_it_stays_on() -> None:
    config: dict[str, Any] = {"authentication": {"http": dict(V11)}}
    notices: list[str] = []
    changes = engine.configure(config, {"TM1_PASSTHROUGH_URL": ""}, notices)
    assert config["authentication"]["http"] == V11
    # Said, but not a change: the file, which may be read-only, isn't written for it
    assert [c for c in changes if "http" in c] == []
    assert notices == [
        "authentication.http: left on (pass-through to http://tm1-server:8010/api/v1/); "
        "TM1_PASSTHROUGH_URL=none turns pass-through logins off"
    ]
    # none is off, whatever else is still set
    assert "authentication.http: removed" in engine.configure(config, {"TM1_PASSTHROUGH_URL": "none", "TM1_PASSTHROUGH_ENDPOINT": "x"})
    assert config["authentication"] == {}
    untouched: dict[str, Any] = {}
    engine.configure(untouched, {"TM1_PASSTHROUGH_URL": "none"})
    assert "authentication" not in untouched


@pytest.mark.parametrize(
    ("env", "message"),
    [
        ({"TM1_PASSTHROUGH_POINTER": "/Name"}, "TM1_PASSTHROUGH_POINTER is set without TM1_PASSTHROUGH_URL"),
        ({"TM1_PASSTHROUGH_URL": "tm1-server:8010"}, "must be an http:// or https:// address"),
        ({"TM1_PASSTHROUGH_URL": "http://tm1 server/api"}, "must be an http:// or https:// address"),
        # TM1 joins the endpoint onto the address as it is
        ({"TM1_PASSTHROUGH_URL": "http://s/api?x=1"}, "without a query"),
        ({"TM1_PASSTHROUGH_URL": "http://s/api/#frag"}, "without a query"),
        ({"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_PASSTHROUGH_ENDPOINT": "/"}, "TM1_PASSTHROUGH_ENDPOINT is a path"),
        ({"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_PASSTHROUGH_ENDPOINT": "Active User"}, "without spaces"),
        ({"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_PASSTHROUGH_FINALIZE": "/"}, "TM1_PASSTHROUGH_FINALIZE is a path"),
        ({"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_PASSTHROUGH_POINTER": "Name"}, "starts with /"),
        ({"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_PASSTHROUGH_FINALIZE_METHOD": "P O S T"}, "is an HTTP method"),
        (
            {"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_PASSTHROUGH_FINALIZE": "none", "TM1_PASSTHROUGH_FINALIZE_METHOD": "POST"},
            "is set with TM1_PASSTHROUGH_FINALIZE=none",
        ),
    ],
)
def test_configure_refuses_pass_through_settings_that_cant_work(env: dict[str, str], message: str) -> None:
    with pytest.raises(engine.Failure, match=message):
        engine.configure({}, env)


def test_another_servers_endpoint_gets_no_tm1_11_finalize_unless_asked() -> None:
    config: dict[str, Any] = {}
    engine.configure(config, {"TM1_PASSTHROUGH_URL": "HTTPS://auth.example.com/v2", "TM1_PASSTHROUGH_ENDPOINT": "whoami"})
    # ActiveSession/tm1.Close is TM1 11's: it isn't sent to a server that isn't one
    assert config["authentication"]["http"] == {
        "serviceRootURL": "HTTPS://auth.example.com/v2/",
        "authEndpoint": "whoami",
        "authJSONPointer": "/Name",
    }


def test_an_address_is_logged_without_the_login_it_carries() -> None:
    notices: list[str] = []
    changes = engine.configure({}, {"TM1_PASSTHROUGH_URL": "http://svc:secret@tm1-server:8010/api/v1/"}, notices)
    assert "authentication.http.serviceRootURL: 'None' -> 'http://tm1-server:8010/api/v1/'" in changes
    kept: dict[str, Any] = {"authentication": {"http": {"serviceRootURL": "http://svc:secret@tm1-server:8010/api/v1/"}}}
    engine.configure(kept, {}, notices)
    assert not [line for line in changes + notices if "secret" in line] and notices


def test_a_mistake_in_the_pass_through_settings_is_caught_before_anything_is_installed() -> None:
    # The entrypoint runs 'plan' first, with or without OAuth 2.0 set
    with pytest.raises(engine.Failure, match="must be an http:// or https:// address"):
        engine.plan({"TM1_PASSTHROUGH_URL": "tm1-server:8010"})
    with pytest.raises(engine.Failure, match="TM1_PASSTHROUGH_POINTER is set without"):
        engine.plan({"TM1_PASSTHROUGH_POINTER": "/Name"})
    assert engine.plan({"TM1_PASSTHROUGH_URL": "http://tm1-server:8010/api/v1/"})["users"] == []


def test_both_logins_kept_in_the_file_start_as_they_are_and_are_said() -> None:
    # Set by hand, or by an earlier image: nothing asked for now, so nothing is refused or written
    both = {"oauth2": {"issuerURL": "http://kc/r", "audience": "tm1"}, "http": dict(V11)}
    config: dict[str, Any] = {"services": {"tm1-gateway": {"serverPort": "4444"}}, "authentication": dict(both)}
    notices: list[str] = []
    assert engine.configure(config, {}, notices) == []
    assert config["authentication"] == both
    assert any("are both on" in line for line in notices)


def test_oauth2_and_pass_through_cant_both_be_on() -> None:
    both = {"TM1_OAUTH2_ISSUER_URL": "http://kc/r", "TM1_OAUTH2_AUDIENCE": "tm1", "TM1_PASSTHROUGH_URL": "http://s/api"}
    # Caught by plan, which the entrypoint runs before it installs anything
    with pytest.raises(engine.Failure, match="both set, and TM1 takes one of the two"):
        engine.plan(both)
    with pytest.raises(engine.Failure, match="are both on. TM1 takes tokens and never asks the server"):
        engine.configure({}, both)
    # One from an earlier start, kept in the file, and the other asked for now
    kept: dict[str, Any] = {"authentication": {"oauth2": {"issuerURL": "http://kc/r", "audience": "tm1"}}}
    with pytest.raises(engine.Failure, match="TM1_OAUTH2_ISSUER_URL=none or TM1_PASSTHROUGH_URL=none"):
        engine.configure(kept, {"TM1_PASSTHROUGH_URL": "http://s/api"})
    # Turning the kept one off in the same start is fine
    switched: dict[str, Any] = {"authentication": {"oauth2": {"issuerURL": "http://kc/r", "audience": "tm1"}}}
    engine.configure(switched, {"TM1_PASSTHROUGH_URL": "http://s/api", "TM1_OAUTH2_ISSUER_URL": "none"})
    assert "oauth2" not in switched["authentication"] and switched["authentication"]["http"]["authEndpoint"] == "ActiveUser"


def test_configure_points_tm1_at_a_mongodb_with_a_control_database_of_its_own() -> None:
    config = template()
    changes = engine.configure(
        config, {"TM1_DATASTORE_URL": "mongodb://root:secret@mongo:27017"}
    )
    datastore = config["datastore"]
    assert (
        datastore["external"],
        datastore["host"],
        datastore["user"],
        datastore["pass"],
    ) == (True, "mongo:27017", "root", "secret")
    assert re.fullmatch(r"tm1-control-[0-9a-f-]{36}", datastore["controlDatabase"])
    assert "secret" not in " ".join(changes)  # the password isn't logged

    # A restart keeps the control database: a new one would lose every instance
    before = datastore["controlDatabase"]
    assert (
        engine.configure(
            config, {"TM1_DATASTORE_URL": "mongodb://root:secret@mongo:27017"}
        )
        == []
    )
    assert config["datastore"]["controlDatabase"] == before

    # A changed password is applied, and neither the old nor the new one is logged
    changes = engine.configure(
        config, {"TM1_DATASTORE_URL": "mongodb://root:rotated@mongo:27017"}
    )
    assert config["datastore"]["pass"] == "rotated"
    assert changes == ["datastore.pass: (changed)"]


# ---- setup, against a stand-in for TM1 --------------------------------------------------------


@pytest.fixture
def tm1(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    fake = stand_in.FakeTm1()
    server = stand_in.serve(fake)
    deployment, credentials = tmp_path / "deployment", tmp_path / "credentials"
    deployment.mkdir()
    (deployment / "root-creds.json").write_text(
        json.dumps(
            {"name": "Root", "clientID": fake.root[0], "clientSecret": fake.root[1]}
        )
    )
    monkeypatch.setattr(engine, "BASE", f"http://127.0.0.1:{server.server_address[1]}")
    monkeypatch.setattr(engine, "DEPLOYMENT", str(deployment))
    monkeypatch.setattr(engine, "CREDENTIALS", str(credentials))
    monkeypatch.setattr(engine, "READY", str(tmp_path / "ready.json"))
    monkeypatch.setattr(engine, "STARTED", str(tmp_path / "started"))
    monkeypatch.setattr(engine, "STOPPED", str(deployment / ".tm1-engine-stopped.json"))
    monkeypatch.setattr(engine, "running_databases", list)
    for name in (
        "TM1_DATABASES",
        "TM1_APPLICATIONS",
        "TM1_USERS",
        "TM1_CAM_NAMESPACE",
        "TM1_CAM_NAMESPACE_TYPE",
        "TM1_ENGINE_START_TIMEOUT",
    ):
        monkeypatch.delenv(name, raising=False)
    fake.credentials = credentials
    yield fake
    server.shutdown()


def test_setup_creates_what_was_asked_for(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("TM1_DATABASES", "planning,finance/sales")
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts,viewer")
    engine.setup()

    assert set(tm1.instances) == {"tm1", "finance"}
    planning = tm1.instances["tm1"]["databases"]["planning"]
    assert planning["users"] == {
        "Admin": ["ADMIN"],
        "analyst": ["Analysts"],
        "viewer": [],
    }
    assert "Analysts" in planning["groups"]
    assert tm1.instances["finance"]["databases"]["sales"]["users"]["analyst"] == [
        "Analysts"
    ]

    saved = json.loads((tm1.credentials / "tm1" / "tm1.json").read_text())
    app = tm1.instances["tm1"]["applications"]["tm1"]
    assert saved == {
        "instance": "tm1",
        "name": "tm1",
        "clientId": app["ClientID"],
        "clientSecret": app["ClientSecret"],
    }
    assert (tm1.credentials / "tm1" / "tm1.json").stat().st_mode & 0o077 == 0
    assert json.loads((tm1.credentials / "root.json").read_text()) == {
        "clientId": "root-id",
        "clientSecret": "root-secret",
    }
    assert engine.command_health([]) == 0


def test_setup_creates_cognos_users_under_their_camids(tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts,admin:ADMIN")
    monkeypatch.setenv("TM1_CAM_NAMESPACE", "LDAP")
    engine.setup()
    engine.setup()  # and again: a CAMID is found as itself, not made twice

    users = tm1.instances["tm1"]["databases"]["tm1"]["users"]
    assert users == {
        "Admin": ["ADMIN"],
        ANALYST: ["Analysts"],
        'CAMID("LDAP:u:uid=admin,ou=people")': ["ADMIN"],
    }


def test_setup_again_changes_nothing(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts")
    engine.setup()
    secret = tm1.instances["tm1"]["applications"]["tm1"]["ClientSecret"]
    tm1.requests.clear()
    engine.setup()
    assert [
        request
        for request in tm1.requests
        if request[0] != "GET" and "/auth/" not in request[1]
    ] == []
    assert tm1.instances["tm1"]["applications"]["tm1"]["ClientSecret"] == secret


def test_setup_puts_a_user_back_in_the_groups_asked_for(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts,viewer")
    engine.setup()
    users = tm1.instances["tm1"]["databases"]["tm1"]["users"]
    users["analyst"] = ["ADMIN"]
    users["viewer"] = ["ADMIN"]
    engine.setup()
    assert users["analyst"] == ["Analysts"]
    assert users["viewer"] == ["ADMIN"]  # no groups were asked for: left as it is


def test_setup_replaces_an_application_whose_secret_is_lost(tm1: FakeTm1) -> None:
    engine.setup()
    before = tm1.instances["tm1"]["applications"]["tm1"]["ClientID"]
    (tm1.credentials / "tm1" / "tm1.json").unlink()
    engine.setup()
    after = tm1.instances["tm1"]["applications"]["tm1"]
    assert after["ClientID"] != before
    assert (
        json.loads((tm1.credentials / "tm1" / "tm1.json").read_text())["clientSecret"]
        == after["ClientSecret"]
    )


def test_setup_keeps_the_administrator_an_administrator(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("TM1_USERS", "admin:Analysts")
    engine.setup()
    assert sorted(tm1.instances["tm1"]["databases"]["tm1"]["users"]["Admin"]) == [
        "ADMIN",
        "Analysts",
    ]


def test_stopping_scales_every_database_down_and_setup_brings_them_back(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine.setup()
    engine.command_database(["create", "scratch"])  # not one setup was asked for
    assert engine.command_stop_databases(["5"]) == 0
    databases = tm1.instances["tm1"]["databases"]
    assert {name: database["Replicas"] for name, database in databases.items()} == {
        "tm1": 0,
        "scratch": 0,
    }

    # A second stop before anything came back (a failed start, say) keeps the first one's list
    assert engine.command_stop_databases(["5"]) == 0
    assert sorted(json.loads(Path(engine.STOPPED).read_text())) == [
        ["tm1", "scratch"],
        ["tm1", "tm1"],
    ]

    engine.setup()
    assert {name: database["Replicas"] for name, database in databases.items()} == {
        "tm1": 1,
        "scratch": 1,
    }
    assert not Path(engine.STOPPED).exists()


def test_timeouts_that_arent_numbers_fall_back_with_a_warning(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("TM1_ENGINE_START_TIMEOUT", "5m")
    engine.setup()
    assert (
        "TM1_ENGINE_START_TIMEOUT='5m' isn't a whole number of seconds; using 300"
        in capsys.readouterr().err
    )
    assert (
        engine.command_stop_databases(["60s"]) == 0
    )  # the databases are still stopped through TM1
    assert tm1.instances["tm1"]["databases"]["tm1"]["Replicas"] == 0


def test_the_gateway_is_never_reached_through_a_proxy(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    for name in ("http_proxy", "HTTP_PROXY"):
        monkeypatch.setenv(name, "http://127.0.0.1:9")  # nothing listens there
    monkeypatch.delenv("no_proxy", raising=False)
    monkeypatch.delenv("NO_PROXY", raising=False)
    engine.setup()
    assert engine.command_health([]) == 0


def test_a_user_name_with_a_slash_is_one_path_segment(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("TM1_USERS", "corp/alice:Analysts")
    engine.setup()
    users = tm1.instances["tm1"]["databases"]["tm1"]["users"]
    users["corp/alice"] = []
    engine.setup()  # puts the group back, with a PATCH to Users('corp/alice')
    assert users["corp/alice"] == ["Analysts"]
    assert ("PATCH", "/tm1/api/v1/Databases('tm1')/Users('corp/alice')") in tm1.requests


def test_a_database_made_by_hand_that_wont_come_back_doesnt_stop_the_start(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    engine.setup()
    engine.command_database(["create", "scratch"])
    assert engine.command_stop_databases(["1"]) == 0
    monkeypatch.setattr(
        tm1,
        "shown",
        lambda database: {
            "Name": database["Name"],
            "Replicas": database["Replicas"],
            "ActiveReplicas": [{"State": "ready"}]
            if database["Name"] == "tm1" and database["Replicas"]
            else [],
        },
    )
    monkeypatch.setenv("TM1_ENGINE_START_TIMEOUT", "1")
    engine.setup()  # tm1 is ready; scratch never is
    assert "WARNING: not ready within 1s: tm1/scratch" in capsys.readouterr().err
    assert engine.command_health([]) == 0
    assert not Path(engine.STOPPED).exists()


def test_setup_failure_says_what_tm1_logged(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    logs = Path(engine.DEPLOYMENT) / "logs"
    logs.mkdir()
    Path(engine.STARTED).write_text("2026-11-25T10:00:00\n")
    lines = [
        {
            "level": "error",
            "date": "2026-11-18T09:00:00.000Z",
            "message": "Last week's trouble",
        },  # before this start: not shown
        {"level": "info", "date": "2026-11-25T10:00:01.000Z", "message": "Starting"},
        {
            "level": "error",
            "date": "2026-11-25T10:00:30.000Z",
            "message": "Failed to connect to the datastore, aborting...",
            "error": "Authentication failed.",
        },
        {
            "level": "error",
            "date": "2026-11-25T10:00:40.000Z",
            "message": "Failed to connect to the datastore, aborting...",
            "error": "Authentication failed.",
        },
    ]
    (logs / "tm1-gateway.jsonl").write_text(
        "".join(json.dumps(line) + "\n" for line in lines) + "not json\n"
    )
    monkeypatch.setattr(engine, "BASE", "http://127.0.0.1:9")  # nothing listens there
    with pytest.raises(engine.Failure) as failure:
        engine.wait_for_gateway(1)
    text = str(failure.value)
    assert "didn't answer on port 4444 within 1s" in text
    assert (
        text.count(
            "TM1's log: Failed to connect to the datastore, aborting... Authentication failed."
        )
        == 1
    )
    assert "Last week's trouble" not in text


def test_stopping_when_tm1_isnt_answering_stops_nothing(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine, "BASE", "http://127.0.0.1:9")
    assert engine.command_stop_databases(["1"]) == 1
    assert not Path(engine.STOPPED).exists()


def test_health_waits_for_setup_and_for_every_database(tm1: FakeTm1) -> None:
    assert engine.command_health([]) == 1  # setup hasn't run
    engine.setup()
    assert engine.command_health([]) == 0
    tm1.instances["tm1"]["databases"]["tm1"]["Replicas"] = 0
    assert engine.command_health([]) == 1


def test_database_command_creates_with_the_users_and_drops(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst:Analysts")
    engine.setup()
    capsys.readouterr()
    engine.command_database(["create", "scratch"])
    assert capsys.readouterr().out.strip() == "/tm1/api/v1/Databases('scratch')/"
    assert tm1.instances["tm1"]["databases"]["scratch"]["users"]["analyst"] == [
        "Analysts"
    ]
    engine.command_database(["drop", "tm1"])
    assert "tm1" not in tm1.instances["tm1"]["databases"]
    assert (
        engine.command_health([]) == 0
    )  # health no longer waits for the dropped database


def test_session_logs_in_through_the_saved_application(
    tm1: FakeTm1, capsys: pytest.CaptureFixture[str]
) -> None:
    engine.setup()
    capsys.readouterr()
    engine.command_session(["tm1", "analyst"])
    assert capsys.readouterr().out.strip() == "TM1SessionId=tm1:analyst"
    with pytest.raises(engine.Failure, match="no saved application credentials"):
        engine.session("other", "admin")


def test_session_skips_a_saved_application_tm1_no_longer_has(tm1: FakeTm1) -> None:
    engine.setup()
    (tm1.credentials / "tm1" / "aaa.json").write_text(
        json.dumps(
            {
                "instance": "tm1",
                "name": "aaa",
                "clientId": "gone",
                "clientSecret": "gone",
            }
        )
    )
    assert engine.session("tm1", "admin") == "TM1SessionId=tm1:admin"
    (tm1.credentials / "tm1" / "tm1.json").unlink()
    with pytest.raises(
        engine.Failure, match="logging in to instance 'tm1' as 'admin': 401"
    ):
        engine.session("tm1", "admin")


def test_database_create_without_an_application_warns_and_still_creates(
    tm1: FakeTm1, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("TM1_USERS", "analyst")
    engine.setup()
    engine.manage("POST", "Instances", {"Name": "bare"})
    capsys.readouterr()
    engine.command_database(["create", "bare/scratch"])
    captured = capsys.readouterr()
    assert captured.out.strip() == "/bare/api/v1/Databases('scratch')/"
    assert "no application in instance bare" in captured.err


def test_credentials_lists_everything_and_only_secrets_it_holds(
    tm1: FakeTm1, capsys: pytest.CaptureFixture[str]
) -> None:
    engine.setup()
    tm1.application(
        "tm1", "elsewhere"
    )  # made outside this container: its secret isn't known here
    capsys.readouterr()
    engine.command_credentials(["--json"])
    shown = json.loads(capsys.readouterr().out)
    assert shown["port"] == 4444
    assert shown["root"] == {"clientId": "root-id", "clientSecret": "root-secret"}
    apps = {app["name"]: app for app in shown["instances"][0]["applications"]}
    assert (
        apps["tm1"]["clientSecret"]
        == tm1.instances["tm1"]["applications"]["tm1"]["ClientSecret"]
    )
    assert apps["elsewhere"]["clientSecret"] is None
    assert shown["instances"][0]["databases"] == [
        {"name": "tm1", "path": "/tm1/api/v1/Databases('tm1')/"}
    ]
