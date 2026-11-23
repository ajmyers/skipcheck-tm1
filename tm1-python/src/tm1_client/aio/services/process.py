"""Services for processes and chores (the Java client's ``net.ajmyers.tm1.client.service.process``)."""

from __future__ import annotations

import datetime as _dt
from typing import Any, Callable, Mapping

from ..._calls import endpoint
from ..._generated.model import (
    Chore,
    ChoreTask,
    Process,
    ProcessDebugContext,
    ProcessDebugContextBreakpoint,
    ProcessDebugContextStackFrame,
    ProcessDebugContextStatus,
    ProcessDebugContextVariable,
    ProcessErrorLog,
    ProcessExecuteResult,
    ProcessSyntaxError,
    SQLDataSource,
    Thread,
)
from ..._generated.operations import Tm1Operations
from ...base import Tm1Model, Tm1Patch
from ...odata import ODataLiterals, ODataPath, ODataQuery, Raw
from ...operation import Tm1Operation
from ...request import Tm1Request, body_of
from ._base import Service, _require

__all__ = ["ChoreService", "ProcessDebugService", "ProcessService", "SqlDataSourceService"]

_PROCESSES = ODataPath.of("Processes")
_CHORES = ODataPath.of("Chores")
_CONTEXTS = ODataPath.of("ProcessDebugContexts")
_SOURCES = ODataPath.of("SQLDataSources")


def _with_parameters(body: Mapping[str, Any], parameters: Mapping[str, Any] | None) -> dict[str, Any]:
    """The body, with the parameters as ``Parameters`` (name and value pairs, in order) if any."""
    result = dict(body)
    if parameters:
        result["Parameters"] = [{"Name": key, "Value": value} for key, value in parameters.items()]
    return result


def _is_definition(value: Any) -> bool:
    """Whether a positional argument is a process definition rather than a name: a patch or
    a model (Java's overloads choose by type)."""
    return isinstance(value, (Tm1Patch, Tm1Model))


def _definition_or_name(name: Any, definition: Any, parameters: Any, query: Any = None) -> tuple[Any, Any, Any, Any]:
    """Java's overloads, as one signature: ``(name, parameters, query)`` or ``(definition,
    parameters, query)``, positionally as in Java, or by keyword."""
    if _is_definition(name):
        # (definition, parameters, query) positionally: they arrived one place to the right
        if definition is not None and not _is_definition(definition):
            name, definition, parameters, query = None, name, definition, parameters if query is None else query
        else:
            name, definition = None, name
    elif definition is not None and not _is_definition(definition) and isinstance(definition, Mapping):
        # (name, parameters, query) positionally
        if isinstance(parameters, ODataQuery) and query is None:
            query = parameters
        elif parameters is not None:
            raise TypeError("parameters given twice")
        definition, parameters = None, definition
    if (name is None) == (definition is None):
        raise TypeError("Give exactly one of a process's name and a definition")
    return name, definition, parameters, query


def _java_time(value: _dt.time) -> str:
    """A time as Java's ``LocalTime.toString`` writes it: ``02:00``, ``02:00:30``, ``02:00:30.500``."""
    text = f"{value.hour:02d}:{value.minute:02d}"
    if value.second or value.microsecond:
        text += f":{value.second:02d}"
        if value.microsecond:
            text += "." + (f"{value.microsecond // 1000:03d}" if value.microsecond % 1000 == 0 else f"{value.microsecond:06d}")
    return text


def _java_offset_datetime(value: _dt.datetime) -> str:
    """A date-time as Java's ``OffsetDateTime.toString`` writes it, as TM1 lists error logs."""
    text = value.date().isoformat() + "T" + _java_time(value.timetz())
    offset = value.utcoffset()
    if offset is None or offset == _dt.timedelta(0):
        return text + "Z"
    seconds = int(offset.total_seconds())
    sign = "+" if seconds >= 0 else "-"
    hours, minutes = divmod(abs(seconds) // 60, 60)
    return f"{text}{sign}{hours:02d}:{minutes:02d}"


class ProcessService(Service):
    """Processes (TurboIntegrator): ``/Processes``, compiling and running them, and their
    error logs.

    Parameters go as a mapping of name to value, in order; a numeric parameter needs a number,
    not text. Running a process returns when it ends, however long that takes: give the
    session a request timeout that fits (or run it through ``with_async``), and know that
    giving up on the request doesn't stop the process on the server (cancel its thread).

    Create a process, compiling it first, since TM1 saves code that doesn't compile::

        process = (
            ProcessPatch.builder()
            .name("Load Sales")
            .parameters([
                ProcessParameterPatch.builder().name("pVersion").value("Actual").type(ProcessVariableType.STRING).build(),
                ProcessParameterPatch.builder().name("pYear").value(2026).type(ProcessVariableType.NUMERIC).build(),
            ])
            .prolog_procedure("LogOutput('INFO', 'Loading ' | pVersion | ' for ' | NumberToString(pYear));\\n")
            .build()
        )
        errors = await tm1.processes.compile(process)
        if errors:
            raise ValueError(f"The process doesn't compile: {errors}")
        await tm1.processes.create(process)

    Run it with parameters, and fail if it wrote an error log::

        result = await tm1.processes.execute_with_return(
            "Load Sales", {"pVersion": "Forecast", "pYear": 2026},
            ODataQuery.none().expand(ProcessExecuteResult.ERROR_LOG_FILE),
        )
        if result.error_log_file is not None:
            raise RuntimeError(f"{result.process_execute_status_code}: see {result.error_log_file.filename}")

    Run code without saving it as a process::

        cleanup = ProcessPatch.builder().name("Clear Sales Staging").prolog_procedure("CubeClearData('Sales Staging');\\n").build()
        await tm1.processes.execute_with_return(cleanup)
    """

    @endpoint("EntitySet Processes")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Process]:
        """``GET Processes``."""
        return await self._session.collect(Tm1Request.list(_PROCESSES, ODataQuery.of(query, **options), Process))

    @endpoint("EntitySet Processes")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> Process:
        """``GET Processes('name')``: the process, or a ``Tm1NotFoundError`` if there's none.
        TM1 (11.8) leaves most of ``DataSource``, and ``UIData`` and ``VariablesUIData``, out
        of a ``$select=*``: select them by name, or select nothing, to get them."""
        return await self._session.execute(Tm1Request.get(_process(name), ODataQuery.of(query, **options), Process))

    @endpoint("EntitySet Processes")
    async def create(self, process: Any) -> Process:
        """``POST Processes``: creates a process; it needs a name. TM1 (11.8) saves code that
        doesn't compile: check it with ``compile(definition)`` first."""
        return await self._session.execute(Tm1Request.post(_PROCESSES, _require(process, "process"), Process))

    @endpoint("EntitySet Processes")
    async def update(self, name: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH Processes('name')``: changes the properties the patch sets, such as a
        procedure. TM1 (11.8) saves code that doesn't compile. With ``etag`` (``Process.etag``),
        sent as ``If-Match``: a ``Tm1PreconditionFailedError`` if the process had changed."""
        request = Tm1Request.patch(_process(name), _require(patch, "patch"))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("EntitySet Processes")
    async def delete(self, name: str) -> None:
        """``DELETE Processes('name')``."""
        await self._session.execute(Tm1Request.delete(_process(name)))

    @endpoint("Action Process/Compile", "ActionImport CompileProcess")
    async def compile(self, name: Any = None, definition: Any = None) -> list[ProcessSyntaxError]:
        """The syntax errors, by procedure and line; none if it compiles. Needs schema 11.0.0.

        * ``compile(name)``: ``tm1.Compile``, compiles a saved process;
        * ``compile(definition)``: ``CompileProcess``, compiles a process definition (a
          ``ProcessPatch`` or ``Process``, with its procedures and parameters) that needn't be
          saved, such as one about to be created."""
        if _is_definition(name):
            name, definition = None, name
        if (name is None) == (definition is None):
            raise TypeError("Give exactly one of a process's name and a definition")
        if name is not None:
            operation = Tm1Operations.PROCESS_COMPILE
            request = Tm1Request.post_list(_process(name).action(operation.name), {}, ProcessSyntaxError)
        else:
            operation = Tm1Operations.COMPILE_PROCESS
            request = Tm1Request.post_list(_root(operation), {"Process": definition}, ProcessSyntaxError)
        return await self._session.collect(request.requires(operation.capability()))

    @endpoint(
        "Action Process/ExecuteWithReturn",
        "ActionImport ExecuteProcessWithReturn",
        "Navigation ProcessExecuteResult/ErrorLogFile",
    )
    async def execute_with_return(
        self,
        name: Any = None,
        definition: Any = None,
        parameters: Mapping[str, Any] | None = None,
        query: ODataQuery | None = None,
        **options: Any,
    ) -> ProcessExecuteResult:
        """Runs a process and says how it ended: ``ProcessExecuteStatusCode``, and the error
        log's file if it wrote one (expand ``ErrorLogFile`` with ``query``). Needs schema 11.3.0.

        * ``execute_with_return(name, parameters)``: ``tm1.ExecuteWithReturn``, a saved process;
        * ``execute_with_return(definition, parameters)``: ``ExecuteProcessWithReturn``, a
          definition that needn't be saved.

        ``parameters`` are by name, in order, and may be left out. A ``ProcessError``, or a
        runtime error, ends it ``Aborted``, with an ``ErrorLogFile`` named
        ``tm1processerror_<time>_<process>.log``. A run cancelled through its thread
        (``ThreadService.cancel``) fails instead, with a ``Tm1ServerError``
        (``TM1UserException: Cancel``, code 174)."""
        name, definition, parameters, query = _definition_or_name(name, definition, parameters, query)
        if name is not None:
            operation = Tm1Operations.PROCESS_EXECUTE_WITH_RETURN
            path = _process(name).action(operation.name)
            body = _with_parameters({}, parameters)
        else:
            operation = Tm1Operations.EXECUTE_PROCESS_WITH_RETURN
            path = _root(operation)
            body = _with_parameters({"Process": definition}, parameters)
        request = Tm1Request.post(path, body, ProcessExecuteResult).with_query(ODataQuery.of(query, **options))
        return await self._session.execute(request.requires(operation.capability()))

    @endpoint("Action Process/Execute", "ActionImport ExecuteProcess")
    async def execute(self, name: Any = None, definition: Any = None, parameters: Mapping[str, Any] | None = None) -> None:
        """Runs a process without saying how it ended: ``tm1.Execute`` for a saved one
        (``execute(name, parameters)``), ``ExecuteProcess`` for a definition
        (``execute(definition, parameters)``, needs schema 11.0.0).

        .. deprecated:: 11.3.0 Use :meth:`execute_with_return`."""
        name, definition, parameters, _ = _definition_or_name(name, definition, parameters)
        if name is not None:
            path = _process(name).action(Tm1Operations.PROCESS_EXECUTE.name)
            body = _with_parameters({}, parameters)
        else:
            path = _root(Tm1Operations.EXECUTE_PROCESS)
            body = _with_parameters({"Process": definition}, parameters)
        await self._session.execute(Tm1Request.post(path, body, None))

    @endpoint("Navigation Process/ErrorLogs")
    async def error_logs(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[ProcessErrorLog]:
        """``GET Processes('name')/ErrorLogs``: the logs its failed runs wrote, keyed by their
        timestamps.

        .. deprecated:: 11.3.0 With ``ProcessErrorLog``: a run's log is the ``ErrorLogFile``
           that :meth:`execute_with_return` returns."""
        request = Tm1Request.list(_process(name).nav("ErrorLogs"), ODataQuery.of(query, **options), ProcessErrorLog)
        return await self._session.collect(request)

    @endpoint("Navigation Process/ErrorLogs")
    async def error_log_content(self, name: str, log: ProcessErrorLog) -> str:
        """``GET Processes('name')/ErrorLogs('timestamp')/Content``: an error log's text. TM1
        keys error logs by their timestamp as a quoted string, as it lists it.

        .. deprecated:: 11.3.0 With ``ProcessErrorLog``: a run's log is the ``ErrorLogFile``
           that :meth:`execute_with_return` returns."""
        timestamp = _require(_require(log, "log").timestamp, "the log's timestamp")
        text = timestamp if isinstance(timestamp, str) else _java_offset_datetime(timestamp)
        path = _process(name).nav("ErrorLogs").key(Raw(ODataLiterals.of(text))).nav("Content")
        return await self._session.execute(Tm1Request.text(path))


def _root(operation: Tm1Operation) -> ODataPath:
    return ODataPath.of(_require(operation.import_name, operation.name))


def _process(name: str) -> ODataPath:
    return _PROCESSES.key(_require(name, "name"))


class ProcessDebugService(Service):
    """Process debugging: a run of a process that stops at breakpoints and steps line by line,
    held on the server as a ``ProcessDebugContext`` until deleted.

    :meth:`debug` starts one, paused before the first line; :meth:`resume` runs to the next
    breakpoint (or the end), and the ``step_…`` methods move a line at a time. Each returns
    the context as it then is. TM1 (11.8) answers a start, a resume or a step without waiting
    for the run to stop again, so the status is ``RUNNING`` or ``PAUSED`` depending on which came first; :meth:`get` reads it
    again. A step past a procedure's last line either pauses with an empty call stack or
    completes the run.

    TM1 (11.8) refuses to delete a debug context: a run ends by running to completion, which
    :meth:`finish` does, and :meth:`debugging` does when its work is done. A finished context
    stays readable until the session ends. ``ProcessDebugContexts`` lists none, though each
    reads by ID.

    Stop at a breakpoint and see where the run is, with :meth:`debugging` finishing the run
    afterwards::

        debug = tm1.process_debugging

        async def where(context):
            line3 = (
                ProcessDebugContextLineBreakpointPatch.builder()
                .id(1)  # TM1 needs an ID chosen by the caller
                .process_name("Load Sales").procedure(ProcessProcedure.PROLOG).line_number(3).enabled(True)
                .build()
            )
            await debug.add_breakpoint(context.id, line3)
            await debug.resume(context.id)
            return [f"{f.procedure}, line {f.line_number}" for f in await debug.call_stack(context.id)]

        await debug.debugging("Load Sales", {"pVersion": "Forecast"}, where)

    Step through a run started with :meth:`debug`, and finish it::

        context = await debug.debug("Load Sales")
        await debug.step_over(context.id)
        await debug.step_over(context.id)
        await debug.finish(context.id)
    """

    MAX_RESUMES = 100
    """How many times :meth:`finish` resumes a run that keeps pausing before giving up."""

    @endpoint("Action Process/Debug")
    async def debug(
        self, process: str, parameters: Mapping[str, Any] | None = None, query: ODataQuery | None = None, **options: Any
    ) -> ProcessDebugContext:
        """``tm1.Debug``: starts debugging a process, with its parameters by name, in order;
        returns the debug context, with its ``ID``."""
        operation = Tm1Operations.PROCESS_DEBUG
        path = _PROCESSES.key(_require(process, "process")).action(operation.name)
        request = Tm1Request.post(path, _with_parameters({}, parameters), ProcessDebugContext)
        return await self._session.execute(request.with_query(ODataQuery.of(query, **options)).requires(operation.capability()))

    @endpoint("Action Process/Debug", "EntitySet ProcessDebugContexts")
    async def debugging(
        self, process: str, parameters: Mapping[str, Any] | None, work: Callable[[ProcessDebugContext], Any]
    ) -> Any:
        """Debugs a process for the length of ``work`` (given the context: set breakpoints,
        step, read variables; a coroutine function in the asyncio flavor), then finishes the run, however the work ends. TM1 (11.8) refuses
        to delete a debug context, so finishing it is how the run ends. Returns what the work
        does."""
        context = await self.debug(process, parameters)
        try:
            return await work(context)
        finally:
            if context.id is not None:
                await self.finish(context.id)

    @endpoint("EntitySet ProcessDebugContexts")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[ProcessDebugContext]:
        """``GET ProcessDebugContexts``. TM1 (11.8) lists none, though each reads by ID with :meth:`get`."""
        return await self._session.collect(Tm1Request.list(_CONTEXTS, ODataQuery.of(query, **options), ProcessDebugContext))

    @endpoint("EntitySet ProcessDebugContexts")
    async def get(self, id: str, query: ODataQuery | None = None, **options: Any) -> ProcessDebugContext:
        """``GET ProcessDebugContexts('id')``: the context, or a ``Tm1NotFoundError``."""
        return await self._session.execute(Tm1Request.get(_context(id), ODataQuery.of(query, **options), ProcessDebugContext))

    @endpoint("Action ProcessDebugContext/Continue", "Navigation ProcessDebugContext/Breakpoints")
    async def finish(self, id: str) -> ProcessDebugContext:
        """Runs a debug run to its end: removes its breakpoints, then resumes until it's
        ``Complete`` (at most :attr:`MAX_RESUMES` times). TM1 (11.8) refuses to delete a debug
        context, so finishing the run is how to end one; the context stays readable, complete,
        until the session ends. Returns the context, with the run's ``Result``."""
        for breakpoint in await self.breakpoints(id):
            if breakpoint.id is not None and breakpoint.id >= 0:
                await self.remove_breakpoint(id, breakpoint.id)
        context = await self.resume(id)
        for _ in range(self.MAX_RESUMES - 1):
            if context.status is ProcessDebugContextStatus.COMPLETE:
                break
            context = await self.resume(id)
        return context

    @endpoint("Action ProcessDebugContext/Continue")
    async def resume(self, id: str, query: ODataQuery | None = None, **options: Any) -> ProcessDebugContext:
        """``tm1.Continue``: runs to the next breakpoint, or to the end."""
        return await self._session.execute(_step(Tm1Operations.PROCESS_DEBUG_CONTEXT_CONTINUE, id, ODataQuery.of(query, **options)))

    @endpoint("Action ProcessDebugContext/StepIn")
    async def step_in(self, id: str, query: ODataQuery | None = None, **options: Any) -> ProcessDebugContext:
        """``tm1.StepIn``: runs one line, into a process it calls."""
        return await self._session.execute(_step(Tm1Operations.PROCESS_DEBUG_CONTEXT_STEP_IN, id, ODataQuery.of(query, **options)))

    @endpoint("Action ProcessDebugContext/StepOut")
    async def step_out(self, id: str, query: ODataQuery | None = None, **options: Any) -> ProcessDebugContext:
        """``tm1.StepOut``: runs to the end of the current procedure or called process."""
        return await self._session.execute(_step(Tm1Operations.PROCESS_DEBUG_CONTEXT_STEP_OUT, id, ODataQuery.of(query, **options)))

    @endpoint("Action ProcessDebugContext/StepOver")
    async def step_over(self, id: str, query: ODataQuery | None = None, **options: Any) -> ProcessDebugContext:
        """``tm1.StepOver``: runs one line, over any process it calls."""
        return await self._session.execute(_step(Tm1Operations.PROCESS_DEBUG_CONTEXT_STEP_OVER, id, ODataQuery.of(query, **options)))

    @endpoint("Navigation ProcessDebugContext/Breakpoints")
    async def breakpoints(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[ProcessDebugContextBreakpoint]:
        """``GET …/Breakpoints``, each as its kind: line, data or lock."""
        request = Tm1Request.list(_context(id).nav("Breakpoints"), ODataQuery.of(query, **options), ProcessDebugContextBreakpoint)
        return await self._session.collect(request)

    @endpoint("Navigation ProcessDebugContext/Breakpoints")
    async def add_breakpoint(self, id: str, breakpoint: Any) -> ProcessDebugContextBreakpoint:
        """``POST …/Breakpoints``: adds a breakpoint, its kind given by the patch
        (``ProcessDebugContextLineBreakpointPatch``, …Data…, …Lock…).

        The caller chooses the breakpoint's ``ID``: TM1 (11.8) stores one sent without an ID
        as -1, which it then refuses in a path, so it can't be changed or removed, and a
        second one without an ID conflicts (409). Raises ``ValueError`` if the patch has no
        ``ID`` of 0 or more."""
        body = body_of(_require(breakpoint, "breakpoint"))
        number = body.get("ID") if isinstance(body, dict) else None
        if isinstance(number, bool) or not isinstance(number, int) or number < 0:
            raise ValueError(
                "A breakpoint needs an ID of 0 or more: TM1 stores one without as -1, which can't be changed or removed"
            )
        request = Tm1Request.post(_context(id).nav("Breakpoints"), breakpoint, ProcessDebugContextBreakpoint)
        return await self._session.execute(request)

    @endpoint("Navigation ProcessDebugContext/Breakpoints")
    async def update_breakpoint(self, id: str, breakpoint: int, patch: Any) -> None:
        """``PATCH …/Breakpoints(n)``: changes a breakpoint, such as enabling or disabling it."""
        await self._session.execute(Tm1Request.patch(_context(id).nav("Breakpoints").key(breakpoint), _require(patch, "patch")))

    @endpoint("Navigation ProcessDebugContext/Breakpoints")
    async def remove_breakpoint(self, id: str, breakpoint: int) -> None:
        """``DELETE …/Breakpoints(n)``."""
        await self._session.execute(Tm1Request.delete(_context(id).nav("Breakpoints").key(breakpoint)))

    @endpoint("Navigation ProcessDebugContext/CurrentBreakpoint")
    async def current_breakpoint(
        self, id: str, query: ODataQuery | None = None, **options: Any
    ) -> ProcessDebugContextBreakpoint | None:
        """``GET …/CurrentBreakpoint``: the breakpoint the run is paused at, or None if it
        isn't paused at one."""
        request = Tm1Request.get(
            _context(id).nav("CurrentBreakpoint"), ODataQuery.of(query, **options), ProcessDebugContextBreakpoint | None
        )
        return await self._session.execute(request)

    @endpoint("Navigation ProcessDebugContext/CallStack")
    async def call_stack(self, id: str, query: ODataQuery | None = None, **options: Any) -> list[ProcessDebugContextStackFrame]:
        """``GET …/CallStack``: where the run is, innermost frame first."""
        request = Tm1Request.list(_context(id).nav("CallStack"), ODataQuery.of(query, **options), ProcessDebugContextStackFrame)
        return await self._session.collect(request)

    @endpoint("Navigation ProcessDebugContextStackFrame/Variables")
    async def variables(
        self, id: str, frame: str, query: ODataQuery | None = None, **options: Any
    ) -> list[ProcessDebugContextVariable]:
        """``GET …/CallStack('frame')/Variables``: the frame's variables and their values.
        TM1 (11.8) lists TurboIntegrator's own variables (``NVALUE``, ``DATASOURCE…``), not the
        process's local ones."""
        request = Tm1Request.list(_frame(id, frame).nav("Variables"), ODataQuery.of(query, **options), ProcessDebugContextVariable)
        return await self._session.collect(request)

    @endpoint("Navigation ProcessDebugContextStackFrame/Process")
    async def frame_process(self, id: str, frame: str, query: ODataQuery | None = None, **options: Any) -> Process:
        """``GET …/CallStack('frame')/Process``: the process the frame runs."""
        return await self._session.execute(Tm1Request.get(_frame(id, frame).nav("Process"), ODataQuery.of(query, **options), Process))

    @endpoint("Navigation ProcessDebugContext/Process")
    async def process(self, id: str, query: ODataQuery | None = None, **options: Any) -> Process:
        """``GET …/Process``: the process being debugged."""
        return await self._session.execute(Tm1Request.get(_context(id).nav("Process"), ODataQuery.of(query, **options), Process))

    @endpoint("Navigation ProcessDebugContext/Thread")
    async def thread(self, id: str, query: ODataQuery | None = None, **options: Any) -> Thread:
        """``GET …/Thread``: the server thread running the debug run."""
        return await self._session.execute(Tm1Request.get(_context(id).nav("Thread"), ODataQuery.of(query, **options), Thread))


def _step(operation: Tm1Operation, id: str, query: ODataQuery) -> Tm1Request[Any]:
    request = Tm1Request.post(_context(id).action(operation.name), {}, ProcessDebugContext)
    return request.with_query(query).requires(operation.capability())


def _context(id: str) -> ODataPath:
    return _CONTEXTS.key(_require(id, "id"))


def _frame(id: str, frame: str) -> ODataPath:
    return _context(id).nav("CallStack").key(_require(frame, "frame"))


class ChoreService(Service):
    """Chores: scheduled runs of processes, as ordered tasks. ``/Chores``, their tasks, and
    running them now.

    A task binds a process (``ChoreTaskPatch.Builder.bind_process``) and gives each of its
    parameters a value; its ``Step``, its place in the chore, is TM1's to number (from 0,
    renumbered when one is removed).

    Create a daily chore with one task::

        load_actuals = (
            ChoreTaskPatch.builder()
            .bind_process(ODataPath.of("Processes").key("Load Sales"))
            .parameters([
                ChoreTaskParameterPatch.builder().name("pVersion").value("Actual").build(),
                ChoreTaskParameterPatch.builder().name("pYear").value(2026).build(),
            ])
            .build()
        )
        await tm1.chores.create(
            ChorePatch.builder()
            .name("Nightly Load")
            .start_time(datetime(2026, 1, 1, 2, 0, tzinfo=timezone.utc))
            .dst_sensitive(False)
            .frequency(timedelta(days=1))
            .execution_mode(ChoreExecutionMode.SINGLE_COMMIT)
            .active(False)
            .tasks([load_actuals])
            .build()
        )

    Add a task at the end, and get the step TM1 gave it::

        step = (await tm1.chores.add_task("Nightly Load", load_forecast)).step

    Schedule the chore, by patching ``Active``::

        await tm1.chores.update("Nightly Load", ChorePatch.builder().active(True).build())
    """

    @endpoint("EntitySet Chores")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[Chore]:
        """``GET Chores``."""
        return await self._session.collect(Tm1Request.list(_CHORES, ODataQuery.of(query, **options), Chore))

    @endpoint("EntitySet Chores")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> Chore:
        """``GET Chores('name')``, with what the query expands, such as ``Tasks($expand=Process)``;
        a ``Tm1NotFoundError`` if there's none."""
        return await self._session.execute(Tm1Request.get(_chore(name), ODataQuery.of(query, **options), Chore))

    @endpoint("EntitySet Chores")
    async def create(self, chore: Any) -> Chore:
        """``POST Chores``: creates a chore, with its tasks if the patch has them. It needs a
        name, start time, frequency and execution mode. A task must list every parameter of its
        process."""
        return await self._session.execute(Tm1Request.post(_CHORES, _require(chore, "chore"), Chore))

    @endpoint("EntitySet Chores")
    async def update(self, name: str, patch: Any, etag: str | None = None) -> None:
        """``PATCH Chores('name')``: changes the properties the patch sets, ``Active`` included,
        which IBM recommends over the deprecated :meth:`activate`. Adding, changing or removing
        a task changes the chore's ETag too. With ``etag``, sent as ``If-Match``. TM1 (11.8):
        binding ``Tasks`` in a chore PATCH appends them."""
        request = Tm1Request.patch(_chore(name), _require(patch, "patch"))
        await self._session.execute(request if etag is None else request.if_match(etag))

    @endpoint("EntitySet Chores")
    async def delete(self, name: str) -> None:
        """``DELETE Chores('name')``; its processes stay."""
        await self._session.execute(Tm1Request.delete(_chore(name)))

    @endpoint("Navigation Chore/Tasks")
    async def tasks(self, name: str, query: ODataQuery | None = None, **options: Any) -> list[ChoreTask]:
        """``GET Chores('name')/Tasks``, in step order."""
        return await self._session.collect(Tm1Request.list(_chore(name).nav("Tasks"), ODataQuery.of(query, **options), ChoreTask))

    @endpoint("Navigation Chore/Tasks")
    async def add_task(self, name: str, task: Any) -> ChoreTask:
        """``POST Chores('name')/Tasks``: adds a task at the end (its bound process and a value
        for each of the process's parameters); returns it with the ``Step`` TM1 gave it."""
        return await self._session.execute(Tm1Request.post(_chore(name).nav("Tasks"), _require(task, "task"), ChoreTask))

    @endpoint("Navigation Chore/Tasks")
    async def update_task(self, name: str, step: int, patch: Any) -> None:
        """``PATCH Chores('name')/Tasks(step)``: changes a task, such as its parameter values."""
        await self._session.execute(Tm1Request.patch(_chore(name).nav("Tasks").key(step), _require(patch, "patch")))

    @endpoint("Navigation Chore/Tasks")
    async def delete_task(self, name: str, step: int) -> None:
        """``DELETE Chores('name')/Tasks(step)``; TM1 renumbers the later steps."""
        await self._session.execute(Tm1Request.delete(_chore(name).nav("Tasks").key(step)))

    @endpoint("Navigation ChoreTask/Process")
    async def task_process(self, name: str, step: int, query: ODataQuery | None = None, **options: Any) -> Process:
        """``GET Chores('name')/Tasks(step)/Process``: the process the task runs."""
        path = _chore(name).nav("Tasks").key(step).nav("Process")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Process))

    @endpoint("Navigation ChoreTask/Chore")
    async def task_chore(self, name: str, step: int, query: ODataQuery | None = None, **options: Any) -> Chore:
        """``GET Chores('name')/Tasks(step)/Chore``: the task's chore."""
        path = _chore(name).nav("Tasks").key(step).nav("Chore")
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), Chore))

    @endpoint("Action Chore/Execute")
    async def execute(self, name: str) -> None:
        """``tm1.Execute``: runs the chore now, whether or not it's active, and returns when its
        tasks have run. Needs schema 10.2.2.2."""
        await self._session.execute(_chore_action(Tm1Operations.CHORE_EXECUTE, name, {}))

    @endpoint("Action Chore/Activate")
    async def activate(self, name: str) -> None:
        """``tm1.Activate``: schedules the chore.

        .. deprecated:: 11.8.3 Patch ``Active`` with :meth:`update`."""
        await self._session.execute(_chore_action(Tm1Operations.CHORE_ACTIVATE, name, {}))

    @endpoint("Action Chore/Deactivate")
    async def deactivate(self, name: str) -> None:
        """``tm1.Deactivate``: unschedules the chore.

        .. deprecated:: 11.8.3 Patch ``Active`` with :meth:`update`."""
        await self._session.execute(_chore_action(Tm1Operations.CHORE_DEACTIVATE, name, {}))

    @endpoint("Action Chore/SetServerLocalStartTime")
    async def set_server_local_start_time(self, name: str, date: _dt.date, time: _dt.time) -> None:
        """``tm1.SetServerLocalStartTime``: sets the chore's start in the server's time zone.
        TM1 (11.8) refuses it while the chore is active ("Can't duplicate active chore", code
        7): deactivate it first.

        .. deprecated:: 11.8.3 Patch ``StartTime`` with :meth:`update`."""
        body = {"StartDate": _require(date, "date").isoformat(), "StartTime": _java_time(_require(time, "time"))}
        await self._session.execute(_chore_action(Tm1Operations.CHORE_SET_SERVER_LOCAL_START_TIME, name, body))


def _chore(name: str) -> ODataPath:
    return _CHORES.key(_require(name, "name"))


def _chore_action(operation: Tm1Operation, name: str, body: Mapping[str, Any]) -> Tm1Request[Any]:
    return Tm1Request.post(_chore(name).action(operation.name), dict(body), None).requires(operation.capability())


class SqlDataSourceService(Service):
    """The ODBC data sources on TM1's host, which processes can read from (``DataSource.Type``
    ``ODBC``). They're the host's ODBC configuration, not TM1's: read only here.

    List the data sources::

        names = [s.name for s in await tm1.sql_data_sources.list()]
    """

    @endpoint("EntitySet SQLDataSources")
    async def list(self, query: ODataQuery | None = None, **options: Any) -> list[SQLDataSource]:
        """``GET SQLDataSources``: their names and descriptions."""
        return await self._session.collect(Tm1Request.list(_SOURCES, ODataQuery.of(query, **options), SQLDataSource))

    @endpoint("EntitySet SQLDataSources")
    async def get(self, name: str, query: ODataQuery | None = None, **options: Any) -> SQLDataSource:
        """``GET SQLDataSources('name')``: a ``Tm1NotFoundError`` if the host has none by that name."""
        path = _SOURCES.key(_require(name, "name"))
        return await self._session.execute(Tm1Request.get(path, ODataQuery.of(query, **options), SQLDataSource))
