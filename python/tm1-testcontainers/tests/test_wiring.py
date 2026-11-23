"""Java's AdminServerWiringTest, CognosContainerTest and PlanningAnalyticsEnvironmentTest: how the
containers are wired, without starting any (and without Docker)."""

from __future__ import annotations

import base64

import pytest

from skipcheck_tm1_testcontainers import (
    CognosContainer,
    CognosSource,
    KeycloakContainer,
    Namespace,
    Network,
    PlanningAnalyticsEnvironment,
    Tm1AdminServerContainer,
    Tm1Seed,
    Tm1ServerContainer,
    Tm1Source,
    Tm1WebContainer,
    Tm1WebSource,
)

SOURCE = Tm1Source.PrivateImage("tm1-server:test")

# --- AdminServerWiringTest ---------------------------------------------------------------------


def test_servers_register_with_the_admin_server_on_its_network() -> None:
    admin = Tm1AdminServerContainer(SOURCE)
    planning = Tm1ServerContainer(SOURCE).with_admin_server(admin)
    reporting = Tm1ServerContainer(SOURCE).with_admin_server(admin)
    assert admin.command_parts == ["admin"]
    assert Tm1AdminServerContainer.NETWORK_ALIAS in admin.network_aliases
    assert planning.env_map["TM1_ADMIN_HOST"] == Tm1AdminServerContainer.NETWORK_ALIAS
    assert planning.network is not None
    assert planning.network is admin.network is reporting.network
    assert admin in planning.dependencies
    assert planning.admin_server is admin


def test_tm1_web_lists_servers_from_the_admin_server() -> None:
    admin = Tm1AdminServerContainer(SOURCE)
    web = Tm1WebContainer(Tm1WebSource.PrivateImage("tm1-web:test")).with_admin_server(admin)
    web.configure()
    assert web.env_map["TM1_ADMIN_HOST"] == Tm1AdminServerContainer.NETWORK_ALIAS
    assert web.network is admin.network
    assert web.admin_server is admin


def test_joins_a_network_either_side_already_has() -> None:
    network = Network.new_network()
    admin = Tm1AdminServerContainer(SOURCE)
    tm1 = Tm1ServerContainer(SOURCE).with_network(network).with_admin_server(admin)
    assert admin.network is network
    assert tm1.network is network


def test_refuses_containers_on_different_networks() -> None:
    admin = Tm1AdminServerContainer(SOURCE).with_network(Network.new_network())
    tm1 = Tm1ServerContainer(SOURCE).with_network(Network.new_network())
    with pytest.raises(RuntimeError, match="share one Network"):
        tm1.with_admin_server(admin)


# --- CognosContainerTest -----------------------------------------------------------------------


@pytest.fixture
def cognos() -> CognosContainer:  # shadows the plugin's session fixture here, on purpose
    return (
        CognosContainer(CognosSource.PrivateImage("cognos-server:test"))
        .with_user("admin", "adminpw", "Admins")
        .with_user("analyst", "analyst#pw", "Analysts", "Readers")
        .with_user("viewer", "viewerpw", "Readers")
    )


def test_passes_the_users_to_the_image(cognos: CognosContainer) -> None:
    assert cognos.users_spec() == "admin:adminpw:Admins analyst:analyst#pw:Analysts,Readers viewer:viewerpw:Readers"
    assert cognos.groups == ["Admins", "Analysts", "Readers"]


def test_builds_cam_logons(cognos: CognosContainer) -> None:
    auth = cognos.cam_authorization("analyst")
    assert auth.startswith("CAMNamespace ")
    assert base64.b64decode(auth[len("CAMNamespace ") :]).decode() == "analyst:analyst#pw:LDAP"
    with pytest.raises(ValueError, match="nobody"):
        cognos.cam_authorization("nobody")


def test_refuses_users_the_image_cant_take_apart(cognos: CognosContainer) -> None:
    for args in (("two words", "pw"), ("x", "pa:ss"), ("x", "pa ss"), ("x", "pw", "Bad Group")):
        with pytest.raises(ValueError):
            cognos.with_user(*args)
    with pytest.raises(ValueError, match="already"):
        cognos.with_user("viewer", "again")


def test_tm1_in_cam_security_logs_on_as_the_first_admin(cognos: CognosContainer) -> None:
    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("tm1-server:test")).with_cam_security(cognos, "admin")
    tm1.configure()
    assert tm1.username == "admin"
    assert tm1.authorization == cognos.cam_authorization("admin")
    assert tm1.env_map["TM1_CAM_URI"] == "http://cognos:9300"
    assert tm1.env_map["TM1_CAM_ADMINS"] == "admin"
    assert tm1.env_map["TM1_CAM_GROUPS"] == "Admins Analysts Readers"
    assert tm1.network is cognos.network
    assert tm1.cognos is cognos


def test_tm1_through_oidc_uses_the_oidc_namespace() -> None:
    keycloak = KeycloakContainer()
    with_oidc = CognosContainer(CognosSource.PrivateImage("cognos-server:test")).with_user("admin", "adminpw", "Admins").with_keycloak(keycloak)
    assert with_oidc.env_map["COGNOS_KEYCLOAK_URL"] == "http://keycloak:8080"
    assert with_oidc.network is keycloak.network
    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("tm1-server:test")).with_cam_security(with_oidc, Namespace.OIDC, "admin")
    tm1.configure()
    assert tm1.env_map["TM1_CAM_NAMESPACE"] == "OIDC"
    assert tm1.env_map["TM1_CAM_NAMESPACE_TYPE"] == "oidc"
    assert tm1.authorization == with_oidc.cam_authorization("admin", Namespace.OIDC)


def test_oidc_needs_keycloak(cognos: CognosContainer) -> None:
    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("tm1-server:test")).with_cam_security(cognos, Namespace.OIDC, "admin")
    with pytest.raises(RuntimeError, match="with_keycloak"):
        tm1.configure()


def test_needs_an_admin_who_is_a_test_user(cognos: CognosContainer) -> None:
    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("tm1-server:test"))
    with pytest.raises(ValueError):
        tm1.with_cam_security(cognos)
    tm1.with_cam_security(cognos, "nobody")
    with pytest.raises(ValueError, match="nobody"):
        tm1.configure()


def test_native_security_keeps_basic_logons() -> None:
    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("tm1-server:test"))
    assert tm1.authorization == "Basic " + base64.b64encode(b"admin:").decode()


# --- PlanningAnalyticsEnvironmentTest ----------------------------------------------------------


def builder() -> PlanningAnalyticsEnvironment.Builder:
    return (
        PlanningAnalyticsEnvironment.builder()
        .tm1_source(Tm1Source.PrivateImage("tm1-server:test"))
        .tm1_web_source(Tm1WebSource.PrivateImage("tm1-web:test"))
        .cognos_source(CognosSource.PrivateImage("cognos-server:test"))
    )


def test_one_server_uses_its_own_admin_server() -> None:
    env = builder().tm1("planning").build()
    planning = env.tm1("planning")
    assert env.admin_server() is None
    assert planning.env_map["TM1_SERVER_NAME"] == "planning"
    assert "TM1_ADMIN_HOST" not in planning.env_map
    assert "planning" in planning.network_aliases
    assert planning.network is env.network()


def test_several_servers_share_an_admin_server_that_tm1_web_lists_from() -> None:
    env = builder().tm1("planning").tm1("reporting").tm1_web().build()
    admin = env.admin_server()
    assert admin is not None
    assert list(env.tm1_servers()) == ["planning", "reporting"]
    for server in env.tm1_servers().values():
        assert server.admin_server is admin
        assert server.network is env.network()
    assert env.tm1_web().admin_server is admin
    assert env.tm1("planning") in env.tm1_web().dependencies and env.tm1("reporting") in env.tm1_web().dependencies
    assert env.containers()[0] is admin and env.containers()[-1] is env.tm1_web()


def test_cognos_puts_every_server_in_cam_security() -> None:
    env = (
        builder()
        .tm1("planning")
        .tm1("reporting")
        .cognos(lambda cognos: cognos.with_user("admin", "adminpw", "Admins").with_user("viewer", "viewerpw", "Readers"))
        .build()
    )
    for server in env.tm1_servers().values():
        server.configure()
        assert server.cognos is env.cognos()
        # The first test user is the TM1 administrator unless cam_security names others
        assert server.env_map["TM1_CAM_ADMINS"] == "admin"
        assert server.env_map["TM1_CAM_GROUPS"] == "Admins Readers"
        assert server.env_map["TM1_CAM_NAMESPACE"] == "LDAP"


def test_keycloak_adds_oidc() -> None:
    env = (
        builder()
        .tm1("planning")
        .cognos(lambda cognos: cognos.with_user("admin", "adminpw").with_user("boss", "bosspw"))
        .keycloak()
        .cam_security(Namespace.OIDC, "boss")
        .build()
    )
    planning = env.tm1("planning")
    planning.configure()
    assert env.cognos().keycloak is env.keycloak()
    assert planning.env_map["TM1_CAM_NAMESPACE"] == "OIDC"
    assert planning.env_map["TM1_CAM_ADMINS"] == "boss"
    assert env.containers()[:2] == [env.keycloak(), env.cognos()]


def test_customizers_see_the_wired_container_and_can_change_it() -> None:
    env = builder().tm1("planning", lambda tm1: tm1.with_seed(Tm1Seed.SDATA).with_env("TM1_CFG_MaxThreads", "4")).build()
    env_map = env.tm1("planning").env_map
    assert env_map["TM1_SEED"] == "sdata"
    assert env_map["TM1_CFG_MaxThreads"] == "4"
    assert env_map["TM1_SERVER_NAME"] == "planning"
    assert env.tm1("planning").username == Tm1Seed.SDATA.username


def test_refuses_missing_or_unsupported_parts() -> None:
    with pytest.raises(RuntimeError, match="at least one"):
        builder().build()
    with pytest.raises(RuntimeError, match="cognos"):
        builder().tm1("a").keycloak().build()
    with pytest.raises(RuntimeError, match="keycloak"):
        builder().tm1("a").cognos(lambda c: c.with_user("u", "p")).cam_security(Namespace.OIDC).build()
    with pytest.raises(RuntimeError, match="PAW"):
        builder().tm1("a").cognos(lambda c: c.with_user("u", "p")).paw(lambda paw: None).build()
    with pytest.raises(RuntimeError, match="test users"):
        builder().tm1("a").cognos(lambda c: None).build()
    with pytest.raises(ValueError, match="already"):
        builder().tm1("a").tm1("a")
    with pytest.raises(ValueError):
        builder().tm1("two words")
    env = builder().tm1("a").build()
    with pytest.raises(RuntimeError, match=r"tm1_web\(\)"):
        env.tm1_web()
    with pytest.raises(ValueError, match=r"\['a'\]"):
        env.tm1("b")
