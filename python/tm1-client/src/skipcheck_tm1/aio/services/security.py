# SPDX-License-Identifier: Apache-2.0

"""Services for security: users, groups, sessions, threads and OIDC keys (the Java client's
``io.skipcheck.tm1.client.service.security``)."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from ..._calls import endpoint
from ..._generated.model import Group, Session, Thread, User
from ..._generated.operations import Tm1Operations
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request, body_of
from ._base import Service, _require

__all__ = ["GroupService", "OidcKeyService", "SessionService", "ThreadService", "UserService"]

_USERS = ODataPath.of("Users")
_GROUPS = ODataPath.of("Groups")
_SESSIONS = ODataPath.of("Sessions")
_THREADS = ODataPath.of("Threads")


class UserService(Service):
    """Users: ``/Users``, the session's own user, their groups, and disconnecting them.

    Group membership is set whole, with :meth:`set_groups`: binding ``Groups`` in a user PATCH
    replaces them. TM1 (11.8) has no safe way to leave one group: a
    ``DELETE Users('u')/Groups('g')/$ref``, which OData means as "remove the link", *deletes
    the group*, for everyone. This service never sends it.

    Create a user in some groups, binding them by path::

        groups = ODataPath.of("Groups")
        await tm1.users.create(
            UserCreate.builder().name("alice").password("initial-password").bind_groups(groups.key("Planners"), groups.key("Sales")).build()
        )

    Take a user out of one group by setting the rest::

        rest = [g.name for g in await tm1.users.groups("alice", ODataQuery.none().select(Group.NAME)) if g.name != "Planners"]
        await tm1.users.set_groups("alice", rest)

    Lock a user out: disable them, then close their sessions::

        await tm1.users.update("alice", UserPatch.builder().enabled(False).build())
        await tm1.users.disconnect("alice")
    """

    @endpoint("EntitySet Users")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[User]:
        """``GET Users``."""
        return await self._session.collect(Tm1Request.list(_USERS, ODataQuery.of(query, **options), User))

    @endpoint("EntitySet Users")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> User:
        """``GET Users('name')``; user names match ignoring case and spaces. A
        ``Tm1NotFoundError`` if there's none."""
        return await self._session.execute(Tm1Request.get(_user(name), ODataQuery.of(query, **options), User))

    @endpoint("Singleton ActiveUser")
    async def active_user(self, query: ODataQuery | None = None, **options: Any) -> User:
        """``GET ActiveUser``: the user this session is for."""
        return await self._session.execute(Tm1Request.get(ODataPath.of("ActiveUser"), ODataQuery.of(query, **options), User))

    @endpoint("EntitySet Users")
    async def create(self, user: Any) -> User:
        """``POST Users``: creates a user, in the groups the patch binds
        (``UserCreate.Builder.bind_groups``). It needs a name, and a password for Basic security."""
        return await self._session.execute(Tm1Request.post(_USERS, _require(user, "user"), User))

    @endpoint("EntitySet Users")
    async def update(self, name: str, patch: Any) -> None:
        """``PATCH Users('name')``: changes the properties the patch sets, such as ``Password``
        or ``Enabled``. TM1 (11.8) sends no ETag for a user, so there's no ``If-Match`` form.

        Its groups are :meth:`set_groups`': a PATCH with them replaces them, so ``UserPatch`` has no
        setter for them. A mapping that sets or binds ``Groups`` raises ``ValueError``, sending nothing."""
        body = body_of(_require(patch, "patch"))
        if isinstance(body, dict) and any(k == "Groups" or k.startswith("Groups@") for k in body):
            raise ValueError("A user PATCH with Groups replaces them: use set_groups")
        await self._session.execute(Tm1Request.patch(_user(name), patch))

    @endpoint("EntitySet Users", "Navigation User/Groups")
    async def set_groups(self, name: str, groups: Sequence[str]) -> None:
        """``PATCH Users('name')`` binding ``Groups``: makes the user's groups exactly these,
        leaving any not listed. Never a ``$ref`` delete, which deletes the group itself."""
        bindings = [str(_GROUPS.key(_require(group, "group"))) for group in groups]
        await self._session.execute(Tm1Request.patch(_user(name), {"Groups@odata.bind": bindings}))

    @endpoint("EntitySet Users")
    async def delete(self, name: str) -> None:
        """``DELETE Users('name')``."""
        await self._session.execute(Tm1Request.delete(_user(name)))

    @endpoint("Navigation User/Groups")
    async def groups(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[Group]:
        """``GET Users('name')/Groups``."""
        return await self._session.collect(Tm1Request.list(_user(name).nav("Groups"), ODataQuery.of(query, **options), Group))

    @endpoint("Navigation User/Sessions")
    async def sessions(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[Session]:
        """``GET Users('name')/Sessions``: the user's sessions on the server."""
        return await self._session.collect(Tm1Request.list(_user(name).nav("Sessions"), ODataQuery.of(query, **options), Session))

    @endpoint("Action User/Disconnect")
    async def disconnect(self, name: str) -> None:
        """``tm1.Disconnect``: closes every session the user has. Needs schema 10.2.2.6."""
        await self._session.execute(_action(Tm1Operations.USER_DISCONNECT, _user(name)))


def _user(name: str) -> ODataPath:
    return _USERS.key(_require(name, "name"))


def _action(operation: Tm1Operation, path: ODataPath) -> Tm1Request[Any]:
    return Tm1Request.post(path.action(operation.name), {}, None).requires(operation.capability())


class GroupService(Service):
    """Security groups: ``/Groups`` and their users. A user's membership is set from the user's
    side, with ``UserService.set_groups``.

    List a group's members::

        names = [u.name for u in await tm1.groups.users("Planners", ODataQuery.none().select(User.NAME))]

    Create a group and add a user to it, from the user's side::

        await tm1.groups.create(GroupCreate.builder().name("Planners").build())
        groups = [g.name for g in await tm1.users.groups("alice", ODataQuery.none().select(Group.NAME))]
        await tm1.users.set_groups("alice", [*groups, "Planners"])
    """

    @endpoint("EntitySet Groups")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Group]:
        """``GET Groups``."""
        return await self._session.collect(Tm1Request.list(_GROUPS, ODataQuery.of(query, **options), Group))

    @endpoint("EntitySet Groups")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> Group:
        """``GET Groups('name')``: the group, or a ``Tm1NotFoundError``."""
        return await self._session.execute(Tm1Request.get(_group(name), ODataQuery.of(query, **options), Group))

    @endpoint("EntitySet Groups")
    async def create(self, group: Any) -> Group:
        """``POST Groups``: creates a group, with the users the create binds
        (``GroupCreate.Builder.bind_users``) or nests (new users, created with it). It needs a name.
        Afterwards, a user's membership is set from the user's side, ``UserService.set_groups``: a
        group PATCH would replace its users."""
        return await self._session.execute(Tm1Request.post(_GROUPS, _require(group, "group"), Group))

    @endpoint("EntitySet Groups")
    async def delete(self, name: str) -> None:
        """``DELETE Groups('name')``: deletes the group; its users stay, without it."""
        await self._session.execute(Tm1Request.delete(_group(name)))

    @endpoint("Navigation Group/Users")
    async def users(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[User]:
        """``GET Groups('name')/Users``."""
        return await self._session.collect(Tm1Request.list(_group(name).nav("Users"), ODataQuery.of(query, **options), User))


def _group(name: str) -> ODataPath:
    return _GROUPS.key(_require(name, "name"))


class SessionService(Service):
    """Server sessions: every session logged in to the server (``/Sessions``), this one's
    (``ActiveSession``), their users and threads, and closing another session.

    To close this client's own session, close the session or client: it sends
    ``ActiveSession/tm1.Close``. A closed session stays listed in ``Sessions`` for a few
    seconds (5 to 9 in TM1 11.8).

    List who's logged in, expanding each session's user::

        sessions = await tm1.sessions.list(
            ODataQuery.none().select(Session.ID, Session.CONTEXT).expand(Session.USER, lambda u: u.select(User.NAME))
        )
        lines = [f"{s.id} {s.user.name} {s.context}" for s in sessions]

    Close other clients' sessions by their label, sparing this one::

        own = (await tm1.sessions.active_session(ODataQuery.none().select(Session.ID))).id
        for s in await tm1.sessions.list(ODataQuery.none().select(Session.ID).filter(Session.CONTEXT.eq("Nightly load"))):
            if s.id != own:
                await tm1.sessions.close(s.id)
    """

    @endpoint("EntitySet Sessions")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Session]:
        """``GET Sessions``: every session on the server, for an admin; expand ``User`` and
        ``Threads``."""
        return await self._session.collect(Tm1Request.list(_SESSIONS, ODataQuery.of(query, **options), Session))

    @endpoint("EntitySet Sessions")
    async def get(self, id: int, query: ODataQuery | None = None, **options: Any) -> Session:
        """``GET Sessions(id)``: the session, or a ``Tm1NotFoundError``."""
        return await self._session.execute(Tm1Request.get(_SESSIONS.key(id), ODataQuery.of(query, **options), Session))

    @endpoint("Singleton ActiveSession")
    async def active_session(self, query: ODataQuery | None = None, **options: Any) -> Session:
        """``GET ActiveSession``: this client's own session on the server."""
        return await self._session.execute(Tm1Request.get(ODataPath.of("ActiveSession"), ODataQuery.of(query, **options), Session))

    @endpoint("Navigation Session/User")
    async def user(self, id: int, query: ODataQuery | None = None, **options: Any) -> User:
        """``GET Sessions(id)/User``."""
        return await self._session.execute(Tm1Request.get(_SESSIONS.key(id).nav("User"), ODataQuery.of(query, **options), User))

    @endpoint("Navigation Session/Threads")
    async def threads(self, id: int, query: ODataQuery | None = None, **options: Any) -> list[Thread]:
        """``GET Sessions(id)/Threads``: what the session is running now."""
        request = Tm1Request.list(_SESSIONS.key(id).nav("Threads"), ODataQuery.of(query, **options), Thread)
        return await self._session.collect(request)

    @endpoint("Action Session/Close")
    async def close(self, id: int) -> None:
        """``tm1.Close``: ends another session; its client must log in again. Needs schema 10.2.2.5."""
        await self._session.execute(_action(Tm1Operations.SESSION_CLOSE, _SESSIONS.key(id)))


class ThreadService(Service):
    """Server threads: what the server is doing right now (``/Threads``), and cancelling an
    operation, which is how to stop a running process: giving up on a client's request doesn't.

    See what the server is busy with::

        busy = await tm1.threads.list(
            ODataQuery.none().select(Thread.ID, Thread.NAME, Thread.FUNCTION, Thread.ELAPSED_TIME).filter(Thread.STATE.ne("Idle"))
        )

    Stop a running process by cancelling its thread, found by its request::

        for t in await tm1.threads.list(
            ODataQuery.none().select(Thread.ID).filter(Filter.contains(Thread.FUNCTION, "Processes('Sales.Load')/tm1.ExecuteWithReturn"))
        ):
            await tm1.threads.cancel(t.id)
    """

    @endpoint("EntitySet Threads")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Thread]:
        """``GET Threads``. A process run through REST shows as its request: ``Function`` is
        ``POST /api/v1/Processes('name')/tm1.ExecuteWithReturn``, and TM1 (11.8) leaves
        ``ObjectName`` empty."""
        return await self._session.collect(Tm1Request.list(_THREADS, ODataQuery.of(query, **options), Thread))

    @endpoint("EntitySet Threads")
    async def get(self, id: int, query: ODataQuery | None = None, **options: Any) -> Thread:
        """``GET Threads(id)``: the thread, or a ``Tm1NotFoundError`` if it's gone."""
        return await self._session.execute(Tm1Request.get(_THREADS.key(id), ODataQuery.of(query, **options), Thread))

    @endpoint("Navigation Thread/Session")
    async def session(self, id: int, query: ODataQuery | None = None, **options: Any) -> Session:  # type: ignore[override]
        """``GET Threads(id)/Session``: the thread's session. (This is Java's ``session(id)``;
        the service's own session is ``_session``.)"""
        return await self._session.execute(Tm1Request.get(_THREADS.key(id).nav("Session"), ODataQuery.of(query, **options), Session))

    @endpoint("Action Thread/CancelOperation")
    async def cancel(self, id: int) -> None:
        """``tm1.CancelOperation``: asks the thread to stop what it's doing, such as a running
        process; returns when the request is made, and the thread stops soon after. TM1 (11.8)
        then fails the process's ``ExecuteWithReturn`` request with 500,
        ``TM1UserException: Cancel`` (code 174), rather than reporting it ``Aborted``. Needs
        schema 10.2.2.1."""
        await self._session.execute(_action(Tm1Operations.THREAD_CANCEL_OPERATION, _THREADS.key(id)))


class OidcKeyService(Service):
    """The OpenID Connect signing keys TM1 has cached, for servers that check OIDC tokens
    themselves. Removing a key makes TM1 fetch it from the provider again, as after the
    provider rotates its keys. On a server that checks no tokens, the cache is empty.

    Refresh the cache after the provider rotates its keys::

        await tm1.oidc_keys.remove(await tm1.oidc_keys.keys())
    """

    @endpoint("FunctionImport GetOIDCKeys")
    async def keys(self) -> list[str]:
        """``GetOIDCKeys()``: the IDs of the cached keys."""
        operation = Tm1Operations.GET_OIDC_KEYS
        path = ODataPath.function_import(_import_name(operation), {})
        request = Tm1Request.list(path, ODataQuery.none(), str).requires(operation.capability())
        return await self._session.collect(request)

    @endpoint("ActionImport RemoveOIDCKeyFromCache")
    async def remove(self, key_ids: Sequence[str]) -> None:
        """``RemoveOIDCKeyFromCache``: drops keys from the cache. A key TM1 hasn't cached fails
        the call with a ``Tm1NotFoundError``: TM1 answers 400 with ``ObjectNotFound`` (code 55),
        which the client reads as not found."""
        operation = Tm1Operations.REMOVE_OIDC_KEY_FROM_CACHE
        keys = [_require(key, "key_ids") for key in _require(key_ids, "key_ids")]
        request = Tm1Request.post(ODataPath.of(_import_name(operation)), {"KeyId": keys}, None)
        await self._session.execute(request.requires(operation.capability()))


def _import_name(operation: Tm1Operation) -> str:
    return _require(operation.import_name, operation.name)
