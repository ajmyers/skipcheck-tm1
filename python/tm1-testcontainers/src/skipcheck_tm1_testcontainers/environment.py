# SPDX-License-Identifier: Apache-2.0

"""Several Planning Analytics parts wired together: Java's ``PlanningAnalyticsEnvironment``."""

from __future__ import annotations

import re
from collections.abc import Callable
from typing import Any, TypeVar

from typing_extensions import Self

from ._container import Network
from .cognos import NO_SOURCE_MESSAGE as NO_COGNOS_MESSAGE
from .cognos import CognosContainer, KeycloakContainer, Namespace
from .paw import NO_SOURCE_MESSAGE as NO_PAW_MESSAGE
from .paw import PawContainer
from .sources import CognosSource, PawSource, Tm1Source, Tm1WebSource
from .tm1 import NO_SOURCE_MESSAGE as NO_TM1_MESSAGE
from .tm1 import Tm1AdminServerContainer, Tm1ServerContainer
from .web import NO_SOURCE_MESSAGE as NO_TM1_WEB_MESSAGE
from .web import Tm1WebContainer

__all__ = ["PlanningAnalyticsEnvironment"]

T = TypeVar("T")
Part = KeycloakContainer | CognosContainer | Tm1AdminServerContainer | Tm1ServerContainer | Tm1WebContainer | PawContainer


def _nothing(_: Any) -> None:
    pass


class PlanningAnalyticsEnvironment:
    """TM1 servers, and optionally a shared Admin Server, TM1 Web, PAW, Cognos (CAM security) and
    Keycloak (OIDC), on one network and wired to each other. Build one with :meth:`builder`."""

    def __init__(self, b: PlanningAnalyticsEnvironment.Builder, network: Any) -> None:
        self._network = network
        self._owns_network = b._network is None
        self._keycloak = KeycloakContainer().with_network(network) if b._keycloak is not None else None
        cognos: CognosContainer | None = None
        if b._cognos is not None:
            cognos = CognosContainer(b._get(b._cognos_source, CognosSource.discover, NO_COGNOS_MESSAGE)).with_network(network)
            if self._keycloak is not None:
                cognos.with_keycloak(self._keycloak)
            # First: the TM1 servers' CAM security is wired from the test users
            b._cognos(cognos)
        self._cognos = cognos
        tm1_source = b._get(b._tm1_source, Tm1Source.discover, NO_TM1_MESSAGE)
        self._admin_server = Tm1AdminServerContainer(tm1_source).with_network(network) if b._admin_server is not None or len(b._tm1) > 1 else None
        servers: dict[str, Tm1ServerContainer] = {}
        for name in b._tm1:
            server = Tm1ServerContainer(tm1_source).with_network(network).with_network_aliases(name).with_env("TM1_SERVER_NAME", name)
            if self._admin_server is not None:
                server.with_admin_server(self._admin_server)
            if cognos is not None:
                server.with_cam_security(cognos, b._cam_namespace, *b._cam_admins_for(cognos))
            servers[name] = server
        self._tm1_servers = servers
        first = next(iter(servers.values()))
        web: Tm1WebContainer | None = None
        if b._tm1_web is not None:
            web = Tm1WebContainer(b._get(b._tm1_web_source, Tm1WebSource.discover, NO_TM1_WEB_MESSAGE)).with_tm1_server(first)
            if self._admin_server is not None:
                web.with_admin_server(self._admin_server)
            # List every server: start them all before TM1 Web
            web.depends_on(*servers.values())
        self._tm1_web = web
        paw: PawContainer | None = None
        if b._paw is not None:
            paw = PawContainer(b._get(b._paw_source, PawSource.discover, NO_PAW_MESSAGE)).with_tm1_server(first)
            if self._admin_server is not None:
                paw.with_admin_server(self._admin_server)
        self._paw = paw
        # The callers' own settings last, so they can change anything set above
        _apply(b._admin_server, self._admin_server)
        for name, customizer in b._tm1.items():
            customizer(servers[name])
        _apply(b._tm1_web, self._tm1_web)
        _apply(b._paw, self._paw)
        _apply(b._keycloak, self._keycloak)

    @staticmethod
    def builder() -> PlanningAnalyticsEnvironment.Builder:
        """Starts building an environment."""
        return PlanningAnalyticsEnvironment.Builder()

    def start(self) -> None:
        """Starts every part, each after what it depends on; PAW last."""
        for part in self.containers():
            if part is not self._paw and not part.is_running:
                part.start()
        if self._paw is not None:
            self._paw.start()

    def stop(self) -> None:
        """Stops every part, the dependents first, and removes the network if it made it."""
        for part in reversed(self.containers()):
            part.stop()
        if self._owns_network and isinstance(self._network, Network):
            self._network.close()

    def close(self) -> None:
        """The same as :meth:`stop`."""
        self.stop()

    def __enter__(self) -> Self:
        self.start()
        return self

    def __exit__(self, *exc: Any) -> None:
        self.stop()

    def tm1(self, name: str) -> Tm1ServerContainer:
        """A TM1 server by the name it was added with. A ValueError if there's no such server."""
        server = self._tm1_servers.get(name)
        if server is None:
            raise ValueError(f"No TM1 server {name}; there's {list(self._tm1_servers)}")
        return server

    def tm1_servers(self) -> dict[str, Tm1ServerContainer]:
        """The TM1 servers, by name, in the order they were added."""
        return dict(self._tm1_servers)

    def admin_server(self) -> Tm1AdminServerContainer | None:
        """The shared Admin Server, present with more than one TM1 server or when asked for; None
        otherwise."""
        return self._admin_server

    def tm1_web(self) -> Tm1WebContainer:
        """TM1 Web. A RuntimeError if it wasn't added."""
        return _require(self._tm1_web, "TM1 Web", "tm1_web()")

    def paw(self) -> PawContainer:
        """PAW. A RuntimeError if it wasn't added."""
        return _require(self._paw, "PAW", "paw(...)")

    def cognos(self) -> CognosContainer:
        """Cognos, with the test users. A RuntimeError if it wasn't added."""
        return _require(self._cognos, "Cognos", "cognos(...)")

    def keycloak(self) -> KeycloakContainer:
        """Keycloak, the OIDC provider. A RuntimeError if it wasn't added."""
        return _require(self._keycloak, "Keycloak", "keycloak()")

    def network(self) -> Any:
        """The Docker network every part is on."""
        return self._network

    def containers(self) -> list[Part]:
        """Every part, in start order."""
        parts: list[Part] = []
        if self._keycloak is not None:
            parts.append(self._keycloak)
        if self._cognos is not None:
            parts.append(self._cognos)
        if self._admin_server is not None:
            parts.append(self._admin_server)
        parts.extend(self._tm1_servers.values())
        if self._tm1_web is not None:
            parts.append(self._tm1_web)
        if self._paw is not None:
            parts.append(self._paw)
        return parts

    class Builder:
        """Builds a :class:`PlanningAnalyticsEnvironment`. Every part is optional."""

        def __init__(self) -> None:
            self._tm1: dict[str, Callable[[Tm1ServerContainer], Any]] = {}
            self._admin_server: Callable[[Tm1AdminServerContainer], Any] | None = None
            self._tm1_web: Callable[[Tm1WebContainer], Any] | None = None
            self._paw: Callable[[PawContainer], Any] | None = None
            self._cognos: Callable[[CognosContainer], Any] | None = None
            self._keycloak: Callable[[KeycloakContainer], Any] | None = None
            self._cam_namespace = Namespace.LDAP
            self._cam_admins: list[str] = []
            self._tm1_source: Tm1Source | None = None
            self._tm1_web_source: Tm1WebSource | None = None
            self._paw_source: PawSource | None = None
            self._cognos_source: CognosSource | None = None
            self._network: Any = None

        def tm1(self, name: str, customizer: Callable[[Tm1ServerContainer], Any] = _nothing) -> Self:
            """A TM1 server, its network alias and server name ``name``."""
            if name is None or not re.fullmatch(r"[A-Za-z0-9_-]+", name):
                raise ValueError(f"Bad TM1 server name '{name}': use letters, digits, _ and -")
            if name in self._tm1:
                raise ValueError(f"TM1 server {name} was already added")
            self._tm1[name] = _required(customizer)
            return self

        def admin_server(self, customizer: Callable[[Tm1AdminServerContainer], Any] = _nothing) -> Self:
            """A shared Admin Server even for one TM1 server (several always share one)."""
            self._admin_server = _required(customizer)
            return self

        def tm1_web(self, customizer: Callable[[Tm1WebContainer], Any] = _nothing) -> Self:
            """Adds TM1 Web, listing every TM1 server. ``customizer`` configures the container."""
            self._tm1_web = _required(customizer)
            return self

        def paw(self, customizer: Callable[[PawContainer], Any]) -> Self:
            """Adds PAW, logging users on to the first TM1 server and listing every one. IBM's
            license must be accepted (:meth:`PawContainer.accept_license` in the customizer, or
            ``PAW_ACCEPT_LICENSE=true``). Not available with Cognos yet: PAW logs users on with
            TM1's own security."""
            self._paw = _required(customizer)
            return self

        def cognos(self, customizer: Callable[[CognosContainer], Any]) -> Self:
            """Cognos, and every TM1 server in CAM security against it; add its test users here."""
            self._cognos = _required(customizer)
            return self

        def keycloak(self, customizer: Callable[[KeycloakContainer], Any] = _nothing) -> Self:
            """Adds Keycloak, giving Cognos an OIDC namespace with the same test users. Needs
            :meth:`cognos`."""
            self._keycloak = _required(customizer)
            return self

        def cam_security(self, namespace: Namespace, *admins: str) -> Self:
            """The namespace TM1 authenticates against, and its administrators (default: the
            first test user)."""
            if namespace is None:
                raise ValueError("namespace must not be None")
            self._cam_namespace = namespace
            self._cam_admins = list(admins)
            return self

        def tm1_source(self, source: Tm1Source) -> Self:
            """Where TM1 comes from; :meth:`Tm1Source.discover` by default."""
            self._tm1_source = _required(source)
            return self

        def tm1_web_source(self, source: Tm1WebSource) -> Self:
            """Where TM1 Web comes from; :meth:`Tm1WebSource.discover` by default."""
            self._tm1_web_source = _required(source)
            return self

        def paw_source(self, source: PawSource) -> Self:
            """Where PAW comes from; :meth:`PawSource.discover` by default."""
            self._paw_source = _required(source)
            return self

        def cognos_source(self, source: CognosSource) -> Self:
            """Where Cognos comes from; :meth:`CognosSource.discover` by default."""
            self._cognos_source = _required(source)
            return self

        def network(self, network: Any) -> Self:
            """The Docker network to put everything on; a new one, removed by
            :meth:`PlanningAnalyticsEnvironment.close`, by default."""
            self._network = _required(network)
            return self

        def build(self) -> PlanningAnalyticsEnvironment:
            """Creates the containers and wires them together. Nothing starts until
            :meth:`PlanningAnalyticsEnvironment.start`. A RuntimeError if a part needs one that's
            missing, or a source can't be found."""
            if not self._tm1:
                raise RuntimeError("Add at least one TM1 server with tm1(name)")
            if self._keycloak is not None and self._cognos is None:
                raise RuntimeError("Keycloak gives Cognos its OIDC namespace: add cognos(...) too")
            if self._cam_namespace is Namespace.OIDC and self._keycloak is None:
                raise RuntimeError("CAM security through OIDC needs keycloak()")
            if self._paw is not None and self._cognos is not None:
                raise RuntimeError("PAW with Cognos (CAM) security isn't supported yet: PAW logs users on with TM1's own security")
            return PlanningAnalyticsEnvironment(self, self._network if self._network is not None else Network.new_network())

        def _cam_admins_for(self, cognos: CognosContainer) -> list[str]:
            if self._cam_admins:
                return list(self._cam_admins)
            users = cognos.users
            if not users:
                raise RuntimeError("Cognos needs test users for CAM security: add them with with_user")
            return [users[0].name]

        @staticmethod
        def _get(configured: T | None, discover: Callable[[], T | None], message: str) -> T:
            if configured is not None:
                return configured
            found = discover()
            if found is None:
                raise RuntimeError(message)
            return found


def _required(value: T) -> T:
    if value is None:
        raise ValueError("must not be None")
    return value


def _apply(customizer: Callable[[T], Any] | None, part: T | None) -> None:
    if customizer is not None and part is not None:
        customizer(part)


def _require(part: T | None, what: str, how: str) -> T:
    if part is None:
        raise RuntimeError(f"This environment has no {what}; add it with {how}")
    return part
