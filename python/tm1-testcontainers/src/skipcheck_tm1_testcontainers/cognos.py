# SPDX-License-Identifier: Apache-2.0

"""Cognos Analytics and Keycloak in Docker, for TM1 in CAM security: Java's ``CognosContainer``
and ``KeycloakContainer``."""

from __future__ import annotations

import base64
import enum
import json
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import ClassVar, Self

import httpx2

from ._container import GenericContainer, share_network, wait_for_http
from .sources import CognosSource, setting
from .tm1 import default_image

__all__ = ["CognosContainer", "KeycloakContainer", "Namespace", "User"]

NO_SOURCE_MESSAGE = (
    "No Cognos source configured. Set one of COGNOS_PRIVATE_IMAGE, COGNOS_INSTALL_VOLUME, COGNOS_ZIP_DIR or "
    "COGNOS_ZIP_URL, or put a casrv-*-linuxi38664h.zip in TM1_BUNDLE_DIR (as an environment variable or system "
    "property) to run tests against Cognos."
)
"""Java's ``RequiresCognos.NO_SOURCE_MESSAGE``."""

_NAME = re.compile(r"[A-Za-z0-9._-]+\Z")
_INSTALL_PATH = "/opt/ibm/cognos/analytics"
_STARTUP_TIMEOUT = 15 * 60.0


class Namespace(enum.Enum):
    """A Cognos namespace TM1 can authenticate against."""

    LDAP = "LDAP"
    """The image's own LDAP, holding the test users."""
    OIDC = "OIDC"
    """Keycloak, through OpenID Connect: needs :meth:`CognosContainer.with_keycloak`."""

    @property
    def id(self) -> str:
        """The namespace's ID: the ``CAMNamespace`` users log on with."""
        return str(self.value)

    @property
    def type(self) -> str:
        """The image's ``TM1_CAM_NAMESPACE_TYPE``."""
        return str(self.value).lower()


@dataclass(frozen=True)
class User:
    """A Cognos test user."""

    name: str
    password: str
    groups: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        object.__setattr__(self, "groups", tuple(self.groups))


def _require_name(name: str, what: str) -> None:
    if name is None or not _NAME.match(name):
        raise ValueError(f"Bad {what} '{name}': use letters, digits, '.', '_' and '-'")


class CognosContainer(GenericContainer):
    """Cognos Analytics in Docker, from the ``cognos-server`` image and a :class:`CognosSource`,
    with test users in its LDAP namespace (:meth:`with_user`)."""

    DEFAULT_IMAGE: ClassVar[str] = "ghcr.io/ajmyers/cognos-server"
    SERVER_IMAGE: ClassVar[str] = "COGNOS_SERVER_IMAGE"
    DISPATCHER_PORT: ClassVar[int] = 9300
    NETWORK_ALIAS: ClassVar[str] = "cognos"
    NAMESPACE: ClassVar[str] = "LDAP"

    Namespace: ClassVar[type[Namespace]] = Namespace
    User: ClassVar[type[User]] = User

    def __init__(self, source: CognosSource, image: str | None = None) -> None:
        if source is None:
            raise ValueError("source must not be None")
        super().__init__(image or default_image(source, self.DEFAULT_IMAGE, self.SERVER_IMAGE))
        self._source = source
        self._users: dict[str, User] = {}
        self._keycloak: KeycloakContainer | None = None
        self.with_exposed_ports(self.DISPATCHER_PORT)
        self.with_network_aliases(self.NETWORK_ALIAS)
        self.with_env("COGNOS_HOST", self.NETWORK_ALIAS)
        # Cognos for Linux is x86-64 only. On Apple Silicon this runs under Rosetta.
        self.with_platform("linux/amd64").with_kwargs(hostname=self.NETWORK_ALIAS)
        # The image's healthcheck: 200 (anonymous session) or 441 (logon prompt, with users)
        self.waiting_for(wait_for_http("/bi/v1/login", self.DISPATCHER_PORT, 200, 441, timeout=_STARTUP_TIMEOUT))
        if isinstance(source, CognosSource.InstallVolume):
            self.with_volume_mapping(source.name, _INSTALL_PATH, "rw")
        elif isinstance(source, CognosSource.Zip):
            self.with_volume_mapping(str(source.dir), "/ibm", "ro")
            if source.file is not None:
                self.with_env("COGNOS_ZIP", source.file)
        elif isinstance(source, CognosSource.ZipUrl):
            self.with_env("COGNOS_ZIP_URL", source.url)
            if source.sha256 is not None:
                self.with_env("COGNOS_ZIP_SHA256", source.sha256)
        # CognosSource.PrivateImage: Cognos is already in the image

    def with_user(self, name: str, password: str, *groups: str) -> Self:
        """Adds a test user to the LDAP namespace, in groups that are created as needed."""
        _require_name(name, "user name")
        if password is None:
            raise ValueError("password must not be None")
        if not password or ":" in password or any(c.isspace() for c in password):
            raise ValueError(f"The password for {name} must be non-empty, without ':' or spaces")
        for group in groups:
            _require_name(group, "group name")
        if name in self._users:
            raise ValueError(f"User {name} was already added")
        self._users[name] = User(name, password, tuple(groups))
        return self

    def with_keycloak(self, keycloak: KeycloakContainer) -> Self:
        """Adds an OIDC namespace backed by Keycloak, on one network with it."""
        if keycloak is None:
            raise ValueError("keycloak must not be None")
        self._keycloak = keycloak
        share_network(self, keycloak)
        self.depends_on(keycloak)
        self.with_env("COGNOS_KEYCLOAK_URL", keycloak.network_url)
        return self

    @property
    def keycloak(self) -> KeycloakContainer | None:
        """The Keycloak set with :meth:`with_keycloak`, or None if there's no OIDC namespace."""
        return self._keycloak

    @property
    def users(self) -> list[User]:
        """The test users, in the order they were added."""
        return list(self._users.values())

    def get_user(self, name: str) -> User:
        """A test user by name. A ValueError if there's no such user."""
        user = self._users.get(name)
        if user is None:
            raise ValueError(f"No test user {name}; add it with with_user")
        return user

    @property
    def groups(self) -> list[str]:
        """Every test user's groups, once each, in the order they were added."""
        return list(dict.fromkeys(g for user in self._users.values() for g in user.groups))

    @property
    def source(self) -> CognosSource:
        """Where Cognos comes from."""
        return self._source

    @property
    def url(self) -> str:
        """Cognos as seen from the host, such as ``http://localhost:54321/bi/``, ending in ``/``."""
        return f"http://{self.host}:{self.get_mapped_port(self.DISPATCHER_PORT)}/bi/"

    @property
    def network_url(self) -> str:
        """The dispatcher, as other containers on the network reach it."""
        return f"http://{self.NETWORK_ALIAS}:{self.DISPATCHER_PORT}"

    def cam_authorization(self, user: str, namespace: Namespace = Namespace.LDAP) -> str:
        """A test user's ``CAMNamespace`` logon, for TM1 in CAM security."""
        return CognosContainer.cam_authorization_for(user, self.get_user(user).password, namespace.id)

    def passport(self, id_token: str) -> str:
        """A CAM passport for an OIDC ID token (from :meth:`KeycloakContainer.id_token`)."""
        body = json.dumps({"parameters": [{"name": "CAMNamespace", "value": Namespace.OIDC.id}, {"name": "CAMOIDCIdToken", "value": id_token}]})
        url = f"http://{self.host}:{self.get_mapped_port(self.DISPATCHER_PORT)}/api/v1/session"
        response = httpx2.put(url, content=body, headers={"Content-Type": "application/json"}, timeout=60)
        passport = response.cookies.get("cam_passport")
        if passport is None:
            raise RuntimeError(f"Cognos refused the ID token (HTTP {response.status_code}): {response.text}")
        return passport

    @staticmethod
    def cam_authorization_for(user: str, password: str, namespace: str) -> str:
        """``CAMNamespace`` with ``user:password:namespace``: Java's static ``camAuthorization``."""
        return "CAMNamespace " + base64.b64encode(f"{user}:{password}:{namespace}".encode()).decode("ascii")

    def users_spec(self) -> str:
        """The users as the image's ``COGNOS_USERS`` takes them."""
        return " ".join(u.name + ":" + u.password + (":" + ",".join(u.groups) if u.groups else "") for u in self._users.values())

    def configure(self) -> None:
        """Called by testcontainers before the container is created: passes the test users to the
        image as ``COGNOS_USERS``."""
        if self._users:
            self.with_env("COGNOS_USERS", self.users_spec())

    def container_is_started(self) -> None:
        """Called once the container is up: logs where it is and what it came from."""
        self.logger.info("Cognos is up at %s (%s), with %d test user(s) in namespace %s", self.url, self._source.describe(), len(self._users), self.NAMESPACE)


class KeycloakContainer(GenericContainer):
    """Keycloak, for Cognos's OIDC namespace (:meth:`CognosContainer.with_keycloak`), whose
    ``cognos`` realm the image loads itself, and for a TM1 v12 engine's OAuth 2.0 logins
    (:meth:`Tm1EngineContainer.with_oauth2`), whose ``tm1`` realm it imports when it starts."""

    DEFAULT_IMAGE: ClassVar[str] = "quay.io/keycloak/keycloak:26.4"
    IMAGE: ClassVar[str] = "KEYCLOAK_IMAGE"
    HTTP_PORT: ClassVar[int] = 8080
    NETWORK_ALIAS: ClassVar[str] = "keycloak"
    REALM: ClassVar[str] = "cognos"
    APP_CLIENT: ClassVar[str] = "tm1-app"

    def __init__(self, image: str | None = None) -> None:
        override = setting(self.IMAGE)
        super().__init__(image or (override.strip() if override and override.strip() else self.DEFAULT_IMAGE))
        self.with_command(["start-dev"])
        self.with_exposed_ports(self.HTTP_PORT)
        self.with_network_aliases(self.NETWORK_ALIAS)
        self.with_env("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        self.with_env("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        self.with_env("KC_HTTP_ENABLED", "true")
        # One issuer for Cognos and the tests, whichever address they use
        self.with_env("KC_HOSTNAME", self.network_url)
        self.waiting_for(wait_for_http("/realms/master", self.HTTP_PORT, 200, timeout=3 * 60.0))
        # Realms to import at start, by name: a TM1 v12 engine's, for OAuth 2.0 logins
        self._realms: dict[str, Callable[[], str]] = {}

    def _with_realm(self, name: str, json: Callable[[], str]) -> Self:
        """A realm for this Keycloak to import when it starts, as JSON. The JSON is asked for when
        Keycloak starts, so whoever gives it can still be changed until then."""
        self._realms[name] = json
        return self

    def configure(self) -> None:
        """Called before the container is created: has Keycloak import the realms it was given."""
        if not self._realms:
            return
        self.with_command(["start-dev", "--import-realm"])
        for name, realm in self._realms.items():
            destination = f"/opt/keycloak/data/import/{name}-realm.json"
            # Once, however often this is called: the realm as it is now replaces an earlier copy
            self._transferable_specs[:] = [spec for spec in self._transferable_specs if spec[1] != destination]
            self.with_copy_into_container(realm().encode(), destination)

    def _access_token(self, realm: str, client: str, user: str, password: str) -> str:
        """An access token for a user of a realm, by the password grant, as a program holding the
        user's password gets one."""
        response = httpx2.post(
            f"{self.url}/realms/{realm}/protocol/openid-connect/token",
            data={"grant_type": "password", "client_id": client, "username": user, "password": password},
            timeout=30,
        )
        match = re.search(r'"access_token"\s*:\s*"([^"]+)"', response.text)
        if response.status_code != 200 or match is None:
            raise RuntimeError(f"Keycloak refused {user} (HTTP {response.status_code}): {response.text}")
        return match.group(1)

    @property
    def network_url(self) -> str:
        """Keycloak as other containers on its network reach it: ``http://keycloak:8080``. Also its
        issuer's base. No trailing slash."""
        return f"http://{self.NETWORK_ALIAS}:{self.HTTP_PORT}"

    @property
    def url(self) -> str:
        """Keycloak as seen from the host, such as ``http://localhost:54321``, without a trailing
        slash. Its admin console logs on as ``admin`` / ``admin``."""
        return f"http://{self.host}:{self.get_mapped_port(self.HTTP_PORT)}"

    def id_token(self, user: str, password: str) -> str:
        """An ID token for a user of the realm, by password."""
        response = httpx2.post(
            f"{self.url}/realms/{self.REALM}/protocol/openid-connect/token",
            data={"grant_type": "password", "client_id": self.APP_CLIENT, "scope": "openid", "username": user, "password": password},
            timeout=30,
        )
        match = re.search(r'"id_token"\s*:\s*"([^"]+)"', response.text)
        if response.status_code != 200 or match is None:
            raise RuntimeError(f"Keycloak refused {user} (HTTP {response.status_code}): {response.text}")
        return match.group(1)
