# SPDX-License-Identifier: Apache-2.0

"""Services for the server and its logs (the Java client's ``io.skipcheck.tm1.client.service.server``)."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import timedelta
from typing import Any

from ..._calls import endpoint
from ..._compat import AsyncIO as runtime
from ..._generated.model import (
    AuditLogEntry,
    Configuration,
    ErrorLogFile,
    Logger,
    LogLevel,
    MessageLogEntry,
    Server,
    ServerSettings,
    TransactionLogEntry,
)
from ..._generated.operations import Tm1Operations
from ..._views import poll_interval
from ...odata import ODataPath, ODataQuery
from ...operation import Tm1Operation
from ...request import Tm1Request
from ._base import Service, _require

__all__ = ["LogService", "ServerService"]

_LOGGERS = ODataPath.of("Loggers")
_ERROR_LOG_FILES = ODataPath.of("ErrorLogFiles")
_BYTE_ORDER_MARK = "﻿"


class ServerService(Service):
    """The server: what it is (``Server``), how it's configured, and its loggers.

    The static configuration is ``tm1s.cfg``'s, and the one to change; the active one is what
    the server runs with now, read only.

    Read the server's name and version::

        server = await tm1.server.server(ODataQuery.none().select(Server.NAME, Server.PRODUCT_VERSION))
        print(f"{server.name} runs TM1 {server.product_version}")

    Read a setting the server runs with now (which can differ from ``tm1s.cfg`` until it restarts)::

        settings = await tm1.server.active_configuration()
        directory = settings.administration.data_base_directory if settings.administration else None

    Change a logger's level, which takes effect at once, without a restart::

        await tm1.server.set_logger_level("TM1.Process", LogLevel.DEBUG)
    """

    @endpoint("Singleton Server")
    async def server(self, query: ODataQuery | None = None, **options: Any) -> Server:
        """``GET Server``: the server's name, version and ports. TM1 (11.8) sends no ETag for it."""
        return await self._session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.of(query, **options), Server))

    @endpoint("Singleton StaticConfiguration")
    async def static_configuration(self, query: ODataQuery | None = None, **options: Any) -> ServerSettings:
        """``GET StaticConfiguration``: the settings the server started with, from ``tm1s.cfg``."""
        request = Tm1Request.get(ODataPath.of("StaticConfiguration"), ODataQuery.of(query, **options), ServerSettings)
        return await self._session.execute(request)

    @endpoint("Singleton StaticConfiguration")
    async def update_static_configuration(self, patch: Any) -> None:
        """``PATCH StaticConfiguration``: changes ``tm1s.cfg``; most settings take effect when
        the server next starts. ``patch`` is a ``ServerSettingsPatch``, a model or a mapping."""
        await self._session.execute(Tm1Request.patch(ODataPath.of("StaticConfiguration"), _require(patch, "patch")))

    @endpoint("Singleton ActiveConfiguration")
    async def active_configuration(self, query: ODataQuery | None = None, **options: Any) -> ServerSettings:
        """``GET ActiveConfiguration``: the settings the server runs with now. Read only: TM1
        (11.8) refuses a PATCH here ("PATCH not allowed on this resource"); settings change
        through :meth:`update_static_configuration`."""
        request = Tm1Request.get(ODataPath.of("ActiveConfiguration"), ODataQuery.of(query, **options), ServerSettings)
        return await self._session.execute(request)

    @endpoint("Singleton Configuration")
    async def configuration(self, query: ODataQuery | None = None, **options: Any) -> Configuration:
        """``GET Configuration``: the server's configuration in the older, flat form.

        .. deprecated:: 11.0.0 Deprecated in TM1 schema 11.0.0: use :meth:`static_configuration`
           and :meth:`active_configuration`."""
        request = Tm1Request.get(ODataPath.of("Configuration"), ODataQuery.of(query, **options), Configuration)
        return await self._session.execute(request)

    @endpoint("EntitySet Loggers")
    async def loggers(self, query: ODataQuery | None = None, **options: Any) -> list[Logger]:
        """``GET Loggers``: the server's loggers and their levels."""
        return await self._session.collect(Tm1Request.list(_LOGGERS, ODataQuery.of(query, **options), Logger))

    @endpoint("EntitySet Loggers")
    async def logger(self, name: str, query: ODataQuery | None = None, **options: Any) -> Logger:
        """``GET Loggers('name')``, such as ``TM1.Server``; a ``Tm1NotFoundError`` if there's none."""
        request = Tm1Request.get(_LOGGERS.key(_require(name, "name")), ODataQuery.of(query, **options), Logger)
        return await self._session.execute(request)

    @endpoint("EntitySet Loggers")
    async def set_logger_level(self, name: str, level: LogLevel) -> None:
        """``PATCH Loggers('name')``: changes a logger's level, at once."""
        if not _require(level, "level").known:
            # As the Java client's: a level this client doesn't know isn't sent
            raise ValueError(f"LogLevel.{level.name} stands for a level this client doesn't know, and isn't sent")
        body = {"Level": level.value}
        await self._session.execute(Tm1Request.patch(_LOGGERS.key(_require(name, "name")), body))


class LogService(Service):
    """The server's logs: the message log, the transaction log (cell changes), the audit log
    (changes to objects and security), and the error logs processes write.

    What TM1 (11.8) does here that you might not expect:

    * An entry's ``ID`` numbers it within the response, not in the log: the same entry gets
      another ID in the next request, and TM1 refuses to fetch an entry by its ID (501,
      "Indexing of message log entries is not supported"). So there's no get by ID here.
    * A message log entry without a time, such as the log's first line, has the ``TimeStamp``
      ``0000-00-00T00:00Z``; it's read as None.
    * The transaction and audit logs only fill when they're switched on (a cube's logging, and
      ``AuditLog`` in the configuration).

    The entry methods return lists (ask for fewer with ``top``); the ``tail_…`` methods follow
    a log, yielding entries as they're written, until the caller stops iterating.

    Read the newest message log entries, newest first::

        newest = await tm1.logs.message_log(True, ODataQuery.none().top(50))

    Follow the message log for errors, polling every 5 s::

        async for entry in tm1.logs.tail_message_log(5):
            if entry.level == LogLevel.ERROR:
                print(f"{entry.logger}: {entry.message}")

    Read a process's error logs::

        for file in await tm1.logs.error_log_files():
            if file.filename and file.filename.endswith("_Load Sales.log"):
                print(await tm1.logs.error_log_content(file.filename))

    Count the cell changes in the transaction log, leaving out its status lines::

        changes = len(await tm1.logs.transaction_log(False, True))
    """

    @endpoint("EntitySet MessageLogEntries")
    async def message_log_entries(self, query: ODataQuery | None = None, **options: Any) -> list[MessageLogEntry]:
        """``GET MessageLogEntries``, oldest first unless ordered otherwise; filter on ``Level``
        or ``TimeStamp``."""
        request = Tm1Request.list(ODataPath.of("MessageLogEntries"), ODataQuery.of(query, **options), MessageLogEntry)
        return await self._session.collect(request)

    @endpoint("FunctionImport MessageLog")
    async def message_log(self, reverse: bool, query: ODataQuery | None = None, **options: Any) -> list[MessageLogEntry]:
        """``MessageLog(Reverse=…)``: the message log, newest first if ``reverse``."""
        operation = Tm1Operations.MESSAGE_LOG
        path = ODataPath.function_import(_import_name(operation), {"Reverse": bool(reverse)})
        request = Tm1Request.list(path, ODataQuery.of(query, **options), MessageLogEntry)
        return await self._session.collect(request.requires(operation.capability()))

    @endpoint("EntitySet TransactionLogEntries")
    async def transaction_log_entries(self, query: ODataQuery | None = None, **options: Any) -> list[TransactionLogEntry]:
        """``GET TransactionLogEntries``. Besides cell changes, the log holds status lines (such
        as ``CubeSerialized: …``) with only ``StatusMessage`` set."""
        request = Tm1Request.list(ODataPath.of("TransactionLogEntries"), ODataQuery.of(query, **options), TransactionLogEntry)
        return await self._session.collect(request)

    @endpoint("FunctionImport TransactionLog")
    async def transaction_log(self, reverse: bool, ignore_status_messages: bool, query: ODataQuery | None = None, **options: Any) -> list[TransactionLogEntry]:
        """``TransactionLog(Reverse=…,IgnoreStatusMessages=…)``: the transaction log, newest
        first if ``reverse``, without status lines if ``ignore_status_messages``."""
        operation = Tm1Operations.TRANSACTION_LOG
        parameters = {"Reverse": bool(reverse), "IgnoreStatusMessages": bool(ignore_status_messages)}
        path = ODataPath.function_import(_import_name(operation), parameters)
        request = Tm1Request.list(path, ODataQuery.of(query, **options), TransactionLogEntry)
        return await self._session.collect(request.requires(operation.capability()))

    @endpoint("EntitySet AuditLogEntries", "Navigation AuditLogEntry/AuditDetails")
    async def audit_log_entries(self, query: ODataQuery | None = None, **options: Any) -> list[AuditLogEntry]:
        """``GET AuditLogEntries``; empty unless the audit log is on. Expand ``AuditDetails``
        for each entry's details."""
        request = Tm1Request.list(ODataPath.of("AuditLogEntries"), ODataQuery.of(query, **options), AuditLogEntry)
        return await self._session.collect(request)

    @endpoint("FunctionImport TailMessageLog")
    def tail_message_log(self, every: float | timedelta) -> AsyncIterator[MessageLogEntry]:
        """Follows the message log: the entries written from now on, polling every ``every``
        (seconds) until the caller stops iterating. Built on ``TailMessageLog()`` (schema 11.3.0).

        ``TailMessageLog()`` returns a delta link, but TM1 (11.8) answers it with every entry
        since the link was made, each time, and with no next link. So this keeps the one link
        and skips the entries it has already yielded, and each poll fetches more the longer it
        runs. For a long watch, start afresh now and then."""
        return self._tail(Tm1Operations.TAIL_MESSAGE_LOG, MessageLogEntry, every)

    @endpoint("FunctionImport TailTransactionLog")
    def tail_transaction_log(self, every: float | timedelta) -> AsyncIterator[TransactionLogEntry]:
        """Follows the transaction log, as :meth:`tail_message_log` does the message log. Built on
        ``TailTransactionLog()`` (schema 11.3.0)."""
        return self._tail(Tm1Operations.TAIL_TRANSACTION_LOG, TransactionLogEntry, every)

    @endpoint("FunctionImport TailAuditLog")
    def tail_audit_log(self, every: float | timedelta) -> AsyncIterator[AuditLogEntry]:
        """Follows the audit log, as :meth:`tail_message_log` does the message log. Built on
        ``TailAuditLog()`` (schema 11.6.0)."""
        return self._tail(Tm1Operations.TAIL_AUDIT_LOG, AuditLogEntry, every)

    @endpoint("EntitySet ErrorLogFiles")
    async def error_log_files(self, query: ODataQuery | None = None, **options: Any) -> list[ErrorLogFile]:
        """``GET ErrorLogFiles``: the error logs processes have written, such as
        ``tm1processerror_<time>_<process>.log``."""
        return await self._session.collect(Tm1Request.list(_ERROR_LOG_FILES, ODataQuery.of(query, **options), ErrorLogFile))

    @endpoint("EntitySet ErrorLogFiles")
    async def error_log_file(self, filename: str, query: ODataQuery | None = None, **options: Any) -> ErrorLogFile:
        """``GET ErrorLogFiles('name')``; a ``Tm1NotFoundError`` if there's none."""
        path = _ERROR_LOG_FILES.key(_require(filename, "filename"))
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), ErrorLogFile))

    @endpoint("EntitySet ErrorLogFiles")
    async def error_log_content(self, filename: str) -> str:
        """``GET ErrorLogFiles('name')/Content``: what the file says, as text, read whole.
        TM1 (11.8) starts it with a byte order mark, which is dropped."""
        path = _ERROR_LOG_FILES.key(_require(filename, "filename")).nav("Content")
        text = await self._session.execute(Tm1Request.text(path))
        return text[1:] if text.startswith(_BYTE_ORDER_MARK) else text

    async def _tail(self, operation: Tm1Operation, type: Any, every: float | timedelta) -> AsyncIterator[Any]:
        # As the Java client's: no wait between polls is allowed (a poll waits for its answer anyway)
        seconds = 0.0 if _is_zero(_require(every, "every")) else poll_interval(every)
        request = Tm1Request.get(ODataPath.function_import(_import_name(operation), {}), ODataQuery.none(), dict)
        doc = await self._session.execute(request.requires(operation.capability()))
        link = ODataPath.link(str((doc or {}).get("value", "")))
        seen = 0
        while True:
            index = 0
            async for entry in self._session.stream(Tm1Request.list(link, ODataQuery.none(), type)):
                index += 1
                if index > seen:
                    seen = index
                    yield entry
            await runtime.sleep(seconds)


def _is_zero(every: float | timedelta) -> bool:
    return (every.total_seconds() if isinstance(every, timedelta) else float(every)) == 0


def _import_name(operation: Tm1Operation) -> str:
    return _require(operation.import_name, operation.name)
