"""Services for security (the Java client's ``net.ajmyers.tm1.client.service.security``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['GroupService', 'OidcKeyService', 'SessionService', 'ThreadService', 'UserService']


class GroupService(Service):
    """Security groups: ``/Groups`` and their users. A user's membership is set from the user's side, with ``UserService#setGroups``."""

    # Not ported yet: see tests/test_java_parity.py


class OidcKeyService(Service):
    """The OpenID Connect signing keys TM1 has cached, for servers that check OIDC tokens themselves. Removing a key makes TM1 fetch it from the provider again, as after the provider rotates its keys. On a server that checks no tokens, the cache is empty."""

    # Not ported yet: see tests/test_java_parity.py


class SessionService(Service):
    """Server sessions: every session logged in to the server (``/Sessions``), this one's (``ActiveSession``), their users and threads, and closing another session."""

    # Not ported yet: see tests/test_java_parity.py


class ThreadService(Service):
    """Server threads: what the server is doing right now (``/Threads``), and cancelling an operation, which is how to stop a running process: cancelling a client's request doesn't."""

    # Not ported yet: see tests/test_java_parity.py


class UserService(Service):
    """Users: ``/Users``, the session's own user, their groups, and disconnecting them."""

    # Not ported yet: see tests/test_java_parity.py
