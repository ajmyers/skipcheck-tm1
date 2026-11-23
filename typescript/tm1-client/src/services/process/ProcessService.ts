import { Process, ProcessErrorLog, ProcessExecuteResult, ProcessSyntaxError, Tm1Operations, type ProcessPatch } from "../../generated/index.js";
import { ODataLiterals, ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required, type Tm1Map } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { javaOffsetDateTime, withParameters } from "./formats.js";

const PROCESSES = ODataPath.of("Processes");

/** Processes (TurboIntegrator): `/Processes`, compiling and running them, and their error logs.
 *
 * Parameters go as a map of name to value, in order (a `Map`, or an object whose keys don't look
 * like integers); a numeric parameter needs a number, not text. Running a process returns when it
 * ends, however long that takes; aborting the call doesn't stop the process on the server.
 *
 * Create a process, compiling it first, since TM1 saves code that doesn't compile:
 * ```ts
 * const process = ProcessPatch.builder().name("Load").prologProcedure("x = 1;").build();
 * if ((await tm1.processes.compile(process).toArray()).length === 0) await tm1.processes.create(process);
 * ```
 * Run it with parameters, and fail if it wrote an error log:
 * ```ts
 * const result = await tm1.processes.executeWithReturn("Load", new Map([["pYear", 2026]]), ODataQuery.none().expand("ErrorLogFile"));
 * if (result.ErrorLogFile) throw new Error(await tm1.logs.errorLogContent(result.ErrorLogFile.Filename!));
 * ```
 * Run code without saving it as a process:
 * ```ts
 * await tm1.processes.executeWithReturn(ProcessPatch.builder().name("adhoc").prologProcedure("x = 1;").build());
 * ```
 */
export class ProcessService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Processes"],
    get: ["EntitySet Processes"],
    create: ["EntitySet Processes"],
    update: ["EntitySet Processes"],
    delete: ["EntitySet Processes"],
    compile: ["Action Process/Compile", "ActionImport CompileProcess"],
    executeWithReturn: ["Action Process/ExecuteWithReturn", "ActionImport ExecuteProcessWithReturn", "Navigation ProcessExecuteResult/ErrorLogFile"],
    execute: ["Action Process/Execute", "ActionImport ExecuteProcess"],
    errorLogs: ["Navigation Process/ErrorLogs"],
    errorLogContent: ["Navigation Process/ErrorLogs"],
  } as const satisfies Tm1Endpoints<ProcessService>;

  /** `GET Processes`, streamed. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Process> {
    return this.session.stream(Tm1Request.list(PROCESSES, query, Process), options);
  }

  /** `GET Processes('name')`: the process, or a `Tm1NotFoundException` if there's none. TM1 (11.8)
   * leaves most of `DataSource`, and `UIData` and `VariablesUIData`, out of a `$select=*`: select
   * them by name, or select nothing, to get them. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Process> {
    return this.session.execute(Tm1Request.get(processPath(name), query, Process), options);
  }

  /** `POST Processes`: creates a process (it needs a name); returns it as created. TM1 (11.8) saves
   * code that doesn't compile: check it with {@link compile} first. */
  create(process: ProcessPatch, options?: Tm1CallOptions): Promise<Process> {
    return this.session.execute(Tm1Request.post(PROCESSES, required(process, "process"), Process), options);
  }

  /** `PATCH Processes('name')`: changes the properties the patch sets, such as a procedure. With
   * `etag` (`process["@odata.etag"]`), sent as `If-Match`: only if the process hasn't changed since,
   * else a `Tm1PreconditionFailedException`. TM1 (11.8) saves code that doesn't compile. */
  async update(name: string, patch: ProcessPatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(processPath(name), required(patch, "patch"));
    await this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `DELETE Processes('name')`. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(processPath(name)), options);
  }

  /** `tm1.Compile`: compiles a saved process. Needs schema 11.0.0. The syntax errors, by procedure
   * and line; none if it compiles. */
  compile(name: string, options?: Tm1CallOptions): Tm1Stream<ProcessSyntaxError>;
  /** `CompileProcess`: compiles a process definition that needn't be saved, such as one about to be
   * created. Needs schema 11.0.0. */
  compile(definition: ProcessPatch, options?: Tm1CallOptions): Tm1Stream<ProcessSyntaxError>;
  compile(nameOrDefinition: string | ProcessPatch, options?: Tm1CallOptions): Tm1Stream<ProcessSyntaxError> {
    if (typeof nameOrDefinition === "string") {
      const operation = Tm1Operations.ProcessCompile;
      return this.session.stream(
        Tm1Request.postList(processPath(nameOrDefinition).action(operation.name), {}, ProcessSyntaxError).requires(operation.capability()),
        options,
      );
    }
    const operation = Tm1Operations.CompileProcess;
    return this.session.stream(
      Tm1Request.postList(root(operation), { Process: required(nameOrDefinition, "definition") }, ProcessSyntaxError).requires(operation.capability()),
      options,
    );
  }

  /** `tm1.ExecuteWithReturn`: runs a process and says how it ended (`ProcessExecuteStatusCode`, and
   * the error log's file if it wrote one). Needs schema 11.3.0. A `ProcessError`, or a runtime
   * error, ends it `Aborted`, with an `ErrorLogFile` named `tm1processerror_<time>_<process>.log`
   * (expand it with `query`). A run cancelled through its thread (`ThreadService.cancel`) fails
   * instead, with a `Tm1ServerException` (`TM1UserException: Cancel`, code 174). */
  executeWithReturn(name: string, parameters?: Tm1Map<unknown> | null, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessExecuteResult>;
  /** `ExecuteProcessWithReturn`: runs a process definition that needn't be saved. Needs schema 11.3.0. */
  executeWithReturn(
    definition: ProcessPatch,
    parameters?: Tm1Map<unknown> | null,
    query?: ODataQuery | null,
    options?: Tm1CallOptions,
  ): Promise<ProcessExecuteResult>;
  executeWithReturn(
    nameOrDefinition: string | ProcessPatch,
    parameters?: Tm1Map<unknown> | null,
    query?: ODataQuery | null,
    options?: Tm1CallOptions,
  ): Promise<ProcessExecuteResult> {
    if (typeof nameOrDefinition === "string") {
      const operation = Tm1Operations.ProcessExecuteWithReturn;
      const request = Tm1Request.post(processPath(nameOrDefinition).action(operation.name), withParameters({}, parameters), ProcessExecuteResult);
      return this.session.execute(request.withQuery(query).requires(operation.capability()), options);
    }
    const operation = Tm1Operations.ExecuteProcessWithReturn;
    const body = withParameters({ Process: required(nameOrDefinition, "definition") }, parameters);
    return this.session.execute(Tm1Request.post(root(operation), body, ProcessExecuteResult).withQuery(query).requires(operation.capability()), options);
  }

  /** `tm1.Execute`: runs a process, without saying how it ended.
   * @deprecated Deprecated in TM1 schema 11.3.0: use {@link executeWithReturn}. */
  execute(name: string, parameters?: Tm1Map<unknown> | null, options?: Tm1CallOptions): Promise<void>;
  /** `ExecuteProcess`: runs a process definition, without saying how it ended. Needs schema 11.0.0.
   * @deprecated Deprecated in TM1 schema 11.3.0: use {@link executeWithReturn}. */
  execute(definition: ProcessPatch, parameters?: Tm1Map<unknown> | null, options?: Tm1CallOptions): Promise<void>;
  execute(nameOrDefinition: string | ProcessPatch, parameters?: Tm1Map<unknown> | null, options?: Tm1CallOptions): Promise<void> {
    // As the Java client's, neither is checked against $metadata first
    if (typeof nameOrDefinition === "string") {
      return this.session.execute(
        Tm1Request.post(processPath(nameOrDefinition).action(Tm1Operations.ProcessExecute.name), withParameters({}, parameters)),
        options,
      );
    }
    const body = withParameters({ Process: required(nameOrDefinition, "definition") }, parameters);
    return this.session.execute(Tm1Request.post(root(Tm1Operations.ExecuteProcess), body), options);
  }

  /** `GET Processes('name')/ErrorLogs`, streamed: the logs its failed runs wrote, keyed by their
   * timestamps.
   * @deprecated Deprecated in TM1 schema 11.3.0, with `ProcessErrorLog`: a run's log is the
   * `ErrorLogFile` that {@link executeWithReturn} returns. */
  errorLogs(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ProcessErrorLog> {
    return this.session.stream(Tm1Request.list(processPath(name).nav("ErrorLogs"), query, ProcessErrorLog), options);
  }

  /** `GET Processes('name')/ErrorLogs('timestamp')/Content`: an error log's text. TM1 keys error logs
   * by their timestamp as a quoted string, written as the Java client writes it (`OffsetDateTime`'s
   * `toString`: `2026-01-02T03:04Z`, `2026-01-02T03:04:05.120Z`).
   * @deprecated Deprecated in TM1 schema 11.3.0, with `ProcessErrorLog`: a run's log is the
   * `ErrorLogFile` that {@link executeWithReturn} returns. */
  errorLogContent(name: string, log: ProcessErrorLog, options?: Tm1CallOptions): Promise<string> {
    const timestamp = javaOffsetDateTime(required(required(log, "log").Timestamp, "the log's timestamp"));
    const path = processPath(name)
      .nav("ErrorLogs")
      .key(new ODataLiterals.Raw(ODataLiterals.of(timestamp)))
      .nav("Content");
    return this.session.execute(Tm1Request.text(path), options);
  }
}

function root(operation: Tm1Operation): ODataPath {
  return ODataPath.of(required(operation.importName, operation.name));
}

function processPath(name: string): ODataPath {
  return PROCESSES.key(required(name, "name"));
}
