# SPDX-License-Identifier: Apache-2.0

"""Several Planning Analytics parts wired together: Java's ``PlanningAnalyticsEnvironment``."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Callable
from pathlib import Path
from typing import Any, Self, TypeVar

from . import _pa_environment
from ._container import GenericContainer, Network, reuse_enabled
from .cognos import NO_SOURCE_MESSAGE as NO_COGNOS_MESSAGE
from .cognos import CognosContainer, KeycloakContainer, Namespace
from .engine import NO_SOURCE_MESSAGE as NO_TM1_ENGINE_MESSAGE
from .engine import Tm1Database, Tm1EngineContainer
from .paw import NO_SOURCE_MESSAGE as NO_PAW_MESSAGE
from .paw import PawContainer
from .sources import CognosSource, PawSource, Tm1EngineSource, Tm1Source, Tm1WebSource
from .tm1 import NO_SOURCE_MESSAGE as NO_TM1_MESSAGE
from .tm1 import Tm1AdminServerContainer, Tm1ServerContainer
from .web import NO_SOURCE_MESSAGE as NO_TM1_WEB_MESSAGE
from .web import Tm1WebContainer

__all__ = ["PlanningAnalyticsEnvironment"]

REUSE_LABEL = "io.skipcheck.tm1.environment"
"""The label on a reused environment's containers and network. Its value is the environment's
identity, a hash of its configuration."""

NETWORK_PREFIX = "tm1-environment-"
"""The start of a reused environment's network name; the identity follows."""

T = TypeVar("T")
Part = KeycloakContainer | CognosContainer | Tm1AdminServerContainer | Tm1ServerContainer | Tm1WebContainer | Tm1EngineContainer | PawContainer


def _nothing(_: Any) -> None:
    pass


class PlanningAnalyticsEnvironment:
    """TM1 servers, and optionally a shared Admin Server, TM1 Web, PAW, Cognos (CAM security) and
    Keycloak (OIDC), on one network and wired to each other. Build one with :meth:`builder`.

    A TM1 v12 engine (:meth:`Builder.tm1_engine`) runs beside the TM1 servers, or alone: an
    environment needs a server or an engine. The Admin Server, TM1 Web, PAW and Cognos security are
    TM1 11's, and need a TM1 server. An environment with an engine can't be reused.

    Cognos takes minutes to start, and PAW longer. With :meth:`Builder.reuse`, the environment
    keeps its containers running between test runs, so the next run starts in seconds. Reuse also
    needs your own switch, as for any reusable container: ``testcontainers.reuse.enable=true`` in
    ``~/.testcontainers.properties``, or ``TESTCONTAINERS_REUSE_ENABLE=true`` (:func:`reuse_enabled`).
    Without it, ``reuse`` has no effect. With both:

    * The containers and the network are named after the environment's configuration (its
      identity, a hash): the label ``io.skipcheck.tm1.environment`` on each container, and the
      network ``tm1-environment-<identity>``. A run with the same configuration finds them; a
      different configuration gets its own, and never another's.
    * :meth:`stop` leaves them running, PAW too.
    * What tests leave behind stays: objects they created in TM1, sessions, users in Cognos. Tests
      should create uniquely named objects and delete them, as with
      :meth:`Tm1ServerContainer.shared`.
    * A configuration you no longer use keeps its containers running, and Cognos holds about 8 GB.
      To start clean, remove them all:
      ``docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)``, then
      ``docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)``.
    * The identity covers each part's image, source, environment variables, command, network
      aliases, labels, exposed ports, copied files and ``docker create`` arguments
      (``with_kwargs``, volumes), the Cognos users and the CAM settings.
    * PAW isn't a Testcontainers container: IBM's compose files give it fixed names, so there's one
      per Docker host whatever the identity. Each start runs IBM's compose again, which takes about
      a minute."""

    def __init__(self, b: PlanningAnalyticsEnvironment.Builder, reuse_enabled: bool) -> None:
        self._reused = b._reuse and reuse_enabled
        self._identity_hash: str | None = None
        given = b._network
        if given is not None:
            network = given
        elif self._reused:
            network = _ReusableNetwork(lambda: NETWORK_PREFIX + self._identity())
        else:
            network = Network.new_network()
        self._network = network
        self._owns_network = given is None
        self._keycloak = KeycloakContainer(b._keycloak_image).with_network(network) if b._keycloak is not None else None
        cognos: CognosContainer | None = None
        if b._cognos is not None:
            cognos_source = b._get(
                b._cognos_source, lambda ibm: CognosSource.Zip(ibm), CognosSource.discover, CognosSource.PrivateImage, b._cognos_image, NO_COGNOS_MESSAGE
            )
            cognos_image = b._cognos_image if _runs(b._cognos_image, isinstance(cognos_source, CognosSource.PrivateImage)) else None
            cognos = CognosContainer(cognos_source, cognos_image).with_network(network)
            if self._keycloak is not None:
                cognos.with_keycloak(self._keycloak)
            # First: the TM1 servers' CAM security is wired from the test users
            b._cognos(cognos)
        self._cognos = cognos
        # TM1 11's parts, when there's a server: an environment may be a TM1 v12 engine alone
        tm1_source = (
            b._get(b._tm1_source, lambda ibm: Tm1Source.Bundle(ibm), Tm1Source.discover, Tm1Source.PrivateImage, b._tm1_image, NO_TM1_MESSAGE)
            if b._tm1
            else None
        )
        tm1_image = b._tm1_image if _runs(b._tm1_image, isinstance(tm1_source, Tm1Source.PrivateImage)) else None
        self._admin_server = (
            Tm1AdminServerContainer(tm1_source, tm1_image).with_network(network)
            if tm1_source is not None and (b._admin_server is not None or len(b._tm1) > 1)
            else None
        )
        servers: dict[str, Tm1ServerContainer] = {}
        for name in b._tm1:
            assert tm1_source is not None
            server = Tm1ServerContainer(tm1_source, tm1_image).with_network(network).with_network_aliases(name).with_env("TM1_SERVER_NAME", name)
            if self._admin_server is not None:
                server.with_admin_server(self._admin_server)
            if cognos is not None:
                server.with_cam_security(cognos, b._cam_namespace, *b._cam_admins_for(cognos))
            servers[name] = server
        self._tm1_servers = servers
        # _build has checked that TM1 Web and PAW come with a server
        first = next(iter(servers.values()), None)
        web: Tm1WebContainer | None = None
        if b._tm1_web is not None and first is not None:
            web_source = b._get(
                b._tm1_web_source, lambda ibm: Tm1WebSource.Bundle(ibm), Tm1WebSource.discover, Tm1WebSource.PrivateImage, b._tm1_web_image, NO_TM1_WEB_MESSAGE
            )
            web_image = b._tm1_web_image if _runs(b._tm1_web_image, isinstance(web_source, Tm1WebSource.PrivateImage)) else None
            web = Tm1WebContainer(web_source, web_image).with_tm1_server(first)
            if self._admin_server is not None:
                web.with_admin_server(self._admin_server)
            # List every server: start them all before TM1 Web
            web.depends_on(*servers.values())
        self._tm1_web = web
        paw: PawContainer | None = None
        if b._paw is not None and first is not None:
            paw = PawContainer(b._get(b._paw_source, b._paw_from_ibm, PawSource.discover, None, None, NO_PAW_MESSAGE)).with_tm1_server(first)
            if self._admin_server is not None:
                paw.with_admin_server(self._admin_server)
        self._paw = paw
        engine: Tm1EngineContainer | None = None
        if b._tm1_engine is not None:
            engine_source = b._get(
                b._tm1_engine_source,
                lambda ibm: Tm1EngineSource.Bundle(ibm),
                Tm1EngineSource.discover,
                Tm1EngineSource.PrivateImage,
                b._tm1_engine_image,
                NO_TM1_ENGINE_MESSAGE,
            )
            engine_image = b._tm1_engine_image if _runs(b._tm1_engine_image, isinstance(engine_source, Tm1EngineSource.PrivateImage)) else None
            # Its datastore joins the same network with it, and the network stays the environment's to remove
            engine = Tm1EngineContainer(engine_source, engine_image).with_network(network)
        self._tm1_engine = engine
        # The callers' own settings last, so they can change anything set above
        _apply(b._admin_server, self._admin_server)
        for name, customizer in b._tm1.items():
            customizer(servers[name])
        _apply(b._tm1_web, self._tm1_web)
        _apply(b._paw, self._paw)
        _apply(b._keycloak, self._keycloak)
        _apply(b._tm1_engine, self._tm1_engine)

    @staticmethod
    def builder() -> PlanningAnalyticsEnvironment.Builder:
        """Starts building an environment."""
        return PlanningAnalyticsEnvironment.Builder()

    @staticmethod
    def from_config(path: Path | str) -> PlanningAnalyticsEnvironment.Builder:
        """Starts building the environment a ``pa-environment.yaml`` describes, the file
        ``pa-setup`` reads (``docker/pa-setup``), so tests run what developers run. The file is
        checked as ``pa-setup`` checks it, with its defaults. The builder comes back configured;
        change it further, or call :meth:`Builder.build`.

        How each setting maps:

        * ``tm1.servers.<name>``: :meth:`Builder.tm1`, with ``seed`` as
          :meth:`Tm1ServerContainer.with_seed` and each ``cfg`` parameter as ``TM1_CFG_<name>``,
          written as ``pa-setup`` writes it (``true`` is ``True``);
        * ``tm1.adminServer``: :meth:`Builder.admin_server`; with more than one server there's one
          anyway, and ``false`` then fails, as in ``pa-setup``;
        * ``tm1Web``: :meth:`Builder.tm1_web`, with ``heap`` as ``TM1_WEB_HEAP`` (``1g``);
        * ``cognos``: :meth:`Builder.cognos`, with ``users`` as :meth:`CognosContainer.with_user`
          and ``memory`` as ``COGNOS_MAX_MEMORY`` (``6144``); ``oidc`` adds
          :meth:`Builder.keycloak`; ``cam`` is :meth:`Builder.cam_security`, by default LDAP with
          the first user as administrator;
        * ``paw``: :meth:`Builder.paw` with :meth:`PawContainer.accept_license`, which the file
          must say (``acceptLicense: true``); the zip is ``zip`` in ``ibm``, or the newest there;
        * ``tm1Engine``: :meth:`Builder.tm1_engine`, with each of ``databases`` as
          :meth:`Tm1EngineContainer.with_database` in its ``instance``, and ``users`` as
          :meth:`Tm1EngineContainer.with_user` (a user's password is checked and not used: TM1 v12
          keeps none). It runs beside ``tm1``, or alone;
        * ``ibm``: each part's source, the folder as :class:`Tm1Source.Bundle`,
          :class:`Tm1WebSource.Bundle`, :class:`CognosSource.Zip` and :class:`Tm1EngineSource.Bundle`. A relative path is from the
          file's folder; ``~`` is the user's home;
        * ``images``: the image each part runs, as a container's image argument. A part with no
          other source takes its image as the private image with IBM's software built in
          (:class:`Tm1Source.PrivateImage` and the like).

        A part's source is the one set on the builder, else ``ibm``, else what ``discover()``
        finds (the variables pa-setup's ``.env`` sets, such as ``TM1_BUNDLE_URL``), else its
        image as a private image.

        Ignored, because they place things on the host that Testcontainers manages itself:
        ``name`` (the compose project; the environment has its own network), every ``port``,
        ``adminPort`` and ``keycloakPort`` (Testcontainers maps random host ports),
        ``tm1Engine.datastore`` and ``images.mongo`` (the engine's MongoDB is its own here), and
        ``files`` (the host folder pa-setup keeps databases in; each test environment starts from a
        new database, or one copied in with :meth:`Tm1ServerContainer.with_data`). They're still
        checked as ``pa-setup`` checks them.

        Refused, because the environment can't run them yet: ``cognos`` without
        ``tm1.servers``, and ``seed: 24retail``.

        A ValueError if the file can't be read, isn't valid, or asks for what the environment can't
        run; the message starts with the file's name and names the setting."""
        if path is None:
            raise ValueError("path must not be None")
        return _pa_environment.read(path)

    def start(self) -> None:
        """Starts every part, each after what it depends on; PAW last."""
        if self._reused:
            self._prepare_for_reuse()
        for part in self.containers():
            if part is not self._paw and not part.is_running:
                part.start()
        if self._paw is not None:
            self._paw.start()

    def stop(self) -> None:
        """Stops every part, the dependents first, and removes the network if it made it. A
        :meth:`reused <Builder.reuse>` environment is left running for the next run."""
        if self._reused:
            return
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

    def tm1_engine(self) -> Tm1EngineContainer:
        """The TM1 v12 engine. A RuntimeError if the environment has none."""
        return _require(self._tm1_engine, "TM1 v12 engine", "tm1_engine()")

    def tm1_database(self, name: str) -> Tm1Database:
        """A database of the TM1 v12 engine, in the instance ``tm1``; for another instance's, use
        ``tm1_engine().database(instance, name)``. A RuntimeError if the environment has no engine,
        and a ValueError if the engine has no such database."""
        return self.tm1_engine().database(name)

    def network(self) -> Any:
        """The Docker network every part is on."""
        return self._network

    def _identity(self) -> str:
        """The environment's identity: a hash of every part's configuration, the same for the same
        configuration in every run. Fixed the first time it's asked for, which :meth:`start` does
        before anything starts."""
        if self._identity_hash is None:
            described = "".join(_describe(part) + "\n" for part in self.containers())
            self._identity_hash = hashlib.sha256(described.encode()).hexdigest()[:16]
        return self._identity_hash

    def _prepare_for_reuse(self) -> None:
        """Marks every container reusable, with the identity as a label. A container is found
        again by a hash of everything it's created with (GenericContainer's reuse), its network's
        name too, which is why a reused environment's network is named after its identity.
        testcontainers-python adds no random network alias, so there's none to take away, as
        Java must."""
        identity = self._identity()
        for part in self.containers():
            if isinstance(part, GenericContainer):
                part.with_reuse(True).with_label(REUSE_LABEL, identity)

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
        # Beside TM1 11's parts: nothing of theirs depends on it, nor it on them
        if self._tm1_engine is not None:
            parts.append(self._tm1_engine)
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
            self._tm1_engine: Callable[[Tm1EngineContainer], Any] | None = None
            self._tm1_engine_source: Tm1EngineSource | None = None
            self._cam_namespace = Namespace.LDAP
            self._cam_admins: list[str] = []
            self._tm1_source: Tm1Source | None = None
            self._tm1_web_source: Tm1WebSource | None = None
            self._paw_source: PawSource | None = None
            self._cognos_source: CognosSource | None = None
            self._network: Any = None
            self._reuse = False
            # From pa-environment.yaml (from_config): the IBM downloads folder, PAW's zip in it,
            # and each part's image
            self._ibm: Path | None = None
            self._paw_zip: str | None = None
            self._tm1_image: str | None = None
            self._tm1_web_image: str | None = None
            self._cognos_image: str | None = None
            self._keycloak_image: str | None = None
            self._tm1_engine_image: str | None = None

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
            ``PAW_ACCEPT_LICENSE=true``). With :meth:`cognos`, users log on to PAW through Cognos,
            in the CAM namespace (:meth:`cam_security`)."""
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

        def tm1_engine(self, customizer: Callable[[Tm1EngineContainer], Any] = _nothing) -> Self:
            """Adds a TM1 v12 engine, with one database, ``tm1``, unless ``customizer`` configures it:
            its databases and users, for instance. It runs beside the TM1 servers, or alone: an
            environment needs a server or an engine."""
            self._tm1_engine = _required(customizer)
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

        def tm1_engine_source(self, source: Tm1EngineSource) -> Self:
            """Where the engine gets TM1 v12 from, instead of what :meth:`Tm1EngineSource.discover`
            finds."""
            self._tm1_engine_source = _required(source)
            return self

        def network(self, network: Any) -> Self:
            """The Docker network to put everything on; a new one, removed by
            :meth:`PlanningAnalyticsEnvironment.close`, by default."""
            self._network = _required(network)
            return self

        def reuse(self, reuse: bool) -> Self:
            """Keeps the containers running between test runs, when you've also turned on reuse
            (``testcontainers.reuse.enable=true`` in ``~/.testcontainers.properties``, or
            ``TESTCONTAINERS_REUSE_ENABLE=true``). A later run with the same configuration finds
            them, and objects tests left behind are still there. Without the switch it has no
            effect. See :class:`PlanningAnalyticsEnvironment` for what's reused and how to start
            clean.

            With your own :meth:`network`, reuse only works if it's the same network each run: a
            container is found by everything it's created with, including its network's name."""
            self._reuse = bool(reuse)
            return self

        def build(self) -> PlanningAnalyticsEnvironment:
            """Creates the containers and wires them together. Nothing starts until
            :meth:`PlanningAnalyticsEnvironment.start`. A RuntimeError if a part needs one that's
            missing, or a source can't be found."""
            return self._build(reuse_enabled())

        def _build(self, reuse_enabled: bool) -> PlanningAnalyticsEnvironment:
            """Builds, with the developer's reuse switch given, so tests needn't set it."""
            if not self._tm1 and self._tm1_engine is None:
                raise RuntimeError("Add at least one TM1 server with tm1(name), or a TM1 v12 engine with tm1_engine()")
            if not self._tm1 and (self._admin_server is not None or self._tm1_web is not None or self._paw is not None or self._cognos is not None):
                raise RuntimeError("The Admin Server, TM1 Web, PAW and Cognos security are TM1 11's: add a TM1 server with tm1(name)")
            if self._reuse and self._tm1_engine is not None:
                raise RuntimeError(
                    "An environment with a TM1 v12 engine can't be reused: the engine and its datastore belong together, "
                    "and a kept one keeps every database tests left"
                )
            if self._keycloak is not None and self._cognos is None:
                raise RuntimeError("Keycloak gives Cognos its OIDC namespace: add cognos(...) too")
            if self._cam_namespace is Namespace.OIDC and self._keycloak is None:
                raise RuntimeError("CAM security through OIDC needs keycloak()")
            return PlanningAnalyticsEnvironment(self, reuse_enabled)

        def _cam_admins_for(self, cognos: CognosContainer) -> list[str]:
            if self._cam_admins:
                return list(self._cam_admins)
            users = cognos.users
            if not users:
                raise RuntimeError("Cognos needs test users for CAM security: add them with with_user")
            return [users[0].name]

        def _get(
            self,
            configured: T | None,
            from_ibm: Callable[[Path], T],
            discover: Callable[[], T | None],
            private_image: Callable[[str], T] | None,
            image: str | None,
            message: str,
        ) -> T:
            """A part's source: the one set on the builder, else the file's ``ibm`` folder, else
            what ``discover`` finds, else the file's image as a private image."""
            if configured is not None:
                return configured
            if self._ibm is not None:
                if not self._ibm.is_dir():
                    raise RuntimeError(f"ibm in pa-environment.yaml isn't a folder: {self._ibm}")
                return from_ibm(self._ibm)
            found = discover()
            if found is not None:
                return found
            if private_image is not None and image is not None:
                return private_image(image)
            raise RuntimeError(message)

        def _paw_from_ibm(self, ibm: Path) -> PawSource:
            if self._paw_zip is not None:
                return PawSource(ibm / self._paw_zip)
            found = PawSource.discover(lambda name: str(ibm) if name == PawSource.BUNDLE_DIR else None)
            if found is None:
                raise RuntimeError(f"No PAW Local zip in {ibm}")
            return found


class _ReusableNetwork(Network):
    """A reused environment's network: named after its identity, found if it's there and created
    if not. It carries none of testcontainers' session labels, so the reaper doesn't remove it
    after the run, and :meth:`close` keeps it for the next."""

    def __init__(self, name: Callable[[], str]) -> None:
        # Not Network's: it names the network with a random UUID
        self._name = name
        self._docker_client_kw: dict[str, Any] = {}
        self._lazy_docker = None
        self._docker_network_kw: dict[str, Any] = {}
        self._network = None

    @property  # type: ignore[override]
    def name(self) -> str:
        """``tm1-environment-<identity>``."""
        return self._name()

    def create(self) -> Self:
        """Finds the network by its name, or creates it."""
        client = self._docker.client
        name = self.name
        found = _find_network(client, name)
        if found is None:
            import docker.errors

            try:
                found = client.networks.create(name, check_duplicate=True, labels={REUSE_LABEL: name[len(NETWORK_PREFIX) :]})
            except docker.errors.APIError as e:
                # Another test run created it first
                found = _find_network(client, name) if e.status_code == 409 else None
                if found is None:
                    raise
        self._network = found
        return self

    def close(self) -> None:
        """Keeps the network for the next run."""


def _find_network(client: Any, name: str) -> Any:
    # Docker's name filter matches part of a name, so check the whole
    return next((n for n in client.networks.list(names=[name]) if n.name == name), None)


def _describe(part: Part) -> str:
    """One part's configuration, as far as it can be read before it starts: deterministic, so the
    same configuration gives the same text in every run."""
    if isinstance(part, PawContainer):
        return f"PawContainer source={part.source!r} settings={sorted(part._settings.items())} tm1={_server_name(part.tm1_server)}"
    labels = sorted((k, v) for k, v in part._labels.items() if k != REUSE_LABEL)
    files = sorted((destination, _copied(transferable)) for transferable, destination, *_ in part._transferable_specs)
    created = {"kwargs": part._kwargs, "volumes": part.volumes, "tmpfs": part.tmpfs, "extra_hosts": part._extra_hosts}
    d = (
        f"{type(part).__name__} image={part.image} aliases={sorted(part.network_aliases)} command={part.command_parts}"
        f" ports={sorted(str(p) for p in part.ports)} env={sorted(part.env.items())} labels={labels} files={files}"
        f" create={json.dumps(created, sort_keys=True, default=str)}"
    )
    if isinstance(part, Tm1ServerContainer):
        d += f" source={part.source!r} cam={part.cam_namespace}{part._cam_admins}"
    elif isinstance(part, Tm1AdminServerContainer):
        d += f" source={part.source!r}"
    elif isinstance(part, Tm1WebContainer):
        d += f" source={part.source!r} tm1={_server_name(part.tm1_server)}"
    elif isinstance(part, CognosContainer):
        d += f" source={part.source!r} users={part.users_spec()}"
    return d


def _server_name(server: Tm1ServerContainer | None) -> str | None:
    """A TM1 server's name in the environment, its ``TM1_SERVER_NAME``."""
    return None if server is None else server.env.get("TM1_SERVER_NAME")


def _copied(transferable: Any) -> str:
    """What's copied into a container: a file's path, or a hash of bytes."""
    if isinstance(transferable, bytes):
        return "sha256:" + hashlib.sha256(transferable).hexdigest()
    return str(transferable)


def _runs(image: str | None, private_image: bool) -> bool:
    """Whether a part runs pa-setup's image setting: not when its source is a private image, which
    is the image."""
    return image is not None and not private_image


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
