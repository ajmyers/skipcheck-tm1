"""Services for process (the Java client's ``net.ajmyers.tm1.client.service.process``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['ChoreService', 'ProcessDebugService', 'ProcessService', 'SqlDataSourceService']


class ChoreService(Service):
    """Chores: scheduled runs of processes, as ordered tasks. ``/Chores``, their tasks, and running them now."""

    # Not ported yet: see tests/test_java_parity.py


class ProcessDebugService(Service):
    """Process debugging: a run of a process that stops at breakpoints and steps line by line, held on the server as a ``ProcessDebugContext`` until deleted."""

    # Not ported yet: see tests/test_java_parity.py


class ProcessService(Service):
    """Processes (TurboIntegrator): ``/Processes``, compiling and running them, and their error logs."""

    # Not ported yet: see tests/test_java_parity.py


class SqlDataSourceService(Service):
    """The ODBC data sources on TM1's host, which processes can read from (``DataSource.Type`` ``ODBC``). They're the host's ODBC configuration, not TM1's: read only here."""

    # Not ported yet: see tests/test_java_parity.py
