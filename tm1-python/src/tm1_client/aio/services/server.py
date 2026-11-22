"""Services for server (the Java client's ``net.ajmyers.tm1.client.service.server``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['LogService', 'ServerService']


class LogService(Service):
    """The server's logs: the message log, the transaction log (cell changes), the audit log (changes to objects and security), and the error logs processes write."""

    # Not ported yet: see tests/test_java_parity.py


class ServerService(Service):
    """The server: what it is (``Server``), how it's configured, and its loggers."""

    # Not ported yet: see tests/test_java_parity.py
