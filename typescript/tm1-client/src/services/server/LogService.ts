import { AuditLogEntry, ErrorLogFile, MessageLogEntry, Tm1Operations, TransactionLogEntry } from "../../generated/index.js";
import { Json, type Tm1Model } from "../../model.js";
import { ODataPath, ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import { Tm1Stream } from "../../stream.js";
import { delay, required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const ERROR_LOG_FILES = ODataPath.of("ErrorLogFiles");
const BYTE_ORDER_MARK = "﻿";

/** The server's logs: the message log, the transaction log (cell changes), the audit log (changes
 * to objects and security), and the error logs processes write.
 *
 * What TM1 (11.8) does here that you might not expect:
 * - An entry's `ID` numbers it within the response, not in the log: the same entry gets another ID
 *   in the next request, and TM1 refuses to fetch an entry by its ID (501, "Indexing of message log
 *   entries is not supported"). So there's no get by ID here.
 * - A message log entry without a time, such as the log's first line, has the `TimeStamp`
 *   `0000-00-00T00:00Z`; it's read as `null`.
 * - The transaction and audit logs only fill when they're switched on (a cube's logging, and
 *   `AuditLog` in the configuration).
 *
 * Read the newest message log entries:
 * ```ts
 * const newest = await tm1.logs.messageLog(true, ODataQuery.none().top(10)).toArray();
 * ```
 * Follow the message log for errors, until aborted:
 * ```ts
 * for await (const entry of tm1.logs.tailMessageLog(5000, { signal })) if (entry.Level === "Error") console.log(entry.Message);
 * ```
 * Read a process's error logs:
 * ```ts
 * for await (const file of tm1.logs.errorLogFiles(ODataQuery.none().filter(Filter.contains(ErrorLogFile.Fields.Filename, "Load")))) {
 *   console.log(await tm1.logs.errorLogContent(file.Filename!));
 * }
 * ```
 * Count the cell changes in the transaction log:
 * ```ts
 * const changes = (await tm1.logs.transactionLog(false, true).toArray()).length;
 * ```
 */
export class LogService extends Tm1Service {
  static readonly endpoints = {
    messageLogEntries: ["EntitySet MessageLogEntries"],
    messageLog: ["FunctionImport MessageLog"],
    transactionLogEntries: ["EntitySet TransactionLogEntries"],
    transactionLog: ["FunctionImport TransactionLog"],
    auditLogEntries: ["EntitySet AuditLogEntries", "Navigation AuditLogEntry/AuditDetails"],
    tailMessageLog: ["FunctionImport TailMessageLog"],
    tailTransactionLog: ["FunctionImport TailTransactionLog"],
    tailAuditLog: ["FunctionImport TailAuditLog"],
    errorLogFiles: ["EntitySet ErrorLogFiles"],
    errorLogFile: ["EntitySet ErrorLogFiles"],
    errorLogContent: ["EntitySet ErrorLogFiles"],
  } as const satisfies Tm1Endpoints<LogService>;

  /** `GET MessageLogEntries`, streamed, oldest first unless ordered otherwise; filter on `Level` or
   * `TimeStamp`, and say how many, with `query`. */
  messageLogEntries(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<MessageLogEntry> {
    return this.session.stream(Tm1Request.list(ODataPath.of("MessageLogEntries"), query, MessageLogEntry), options);
  }

  /** `MessageLog(Reverse=…)`: the message log, streamed, newest first if `reverse`. */
  messageLog(reverse: boolean, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<MessageLogEntry> {
    const operation = Tm1Operations.MessageLog;
    const path = ODataPath.functionImport(importName(operation), { Reverse: required(reverse, "reverse") });
    return this.session.stream(Tm1Request.list(path, query, MessageLogEntry).requires(operation.capability()), options);
  }

  /** `GET TransactionLogEntries`, streamed. Besides cell changes, the log holds status lines (such as
   * `CubeSerialized: …`) with only `StatusMessage` set. */
  transactionLogEntries(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<TransactionLogEntry> {
    return this.session.stream(Tm1Request.list(ODataPath.of("TransactionLogEntries"), query, TransactionLogEntry), options);
  }

  /** `TransactionLog(Reverse=…,IgnoreStatusMessages=…)`: the transaction log, streamed, newest first
   * if `reverse`, and without status lines, keeping cell changes, if `ignoreStatusMessages`. */
  transactionLog(reverse: boolean, ignoreStatusMessages: boolean, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<TransactionLogEntry> {
    const operation = Tm1Operations.TransactionLog;
    const parameters = new Map<string, unknown>([
      ["Reverse", required(reverse, "reverse")],
      ["IgnoreStatusMessages", required(ignoreStatusMessages, "ignoreStatusMessages")],
    ]);
    return this.session.stream(
      Tm1Request.list(ODataPath.functionImport(importName(operation), parameters), query, TransactionLogEntry).requires(operation.capability()),
      options,
    );
  }

  /** `GET AuditLogEntries`, streamed; empty unless the audit log is on. `query` can expand
   * `AuditDetails`, the entry's details. */
  auditLogEntries(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<AuditLogEntry> {
    return this.session.stream(Tm1Request.list(ODataPath.of("AuditLogEntries"), query, AuditLogEntry), options);
  }

  /** Follows the message log: the entries written from the moment it's iterated, polling every
   * `every` milliseconds until the iteration stops or `options.signal` aborts (which ends it with
   * the signal's reason). Built on `TailMessageLog()` (schema 11.3.0).
   *
   * `TailMessageLog()` returns a delta link, but TM1 (11.8) answers it with every entry since the
   * link was made, each time, and with no next link. So this keeps the one link and skips the
   * entries it has already yielded, and each poll fetches more the longer it runs. For a long watch,
   * start afresh now and then. */
  tailMessageLog(every: number, options?: Tm1CallOptions): Tm1Stream<MessageLogEntry> {
    return this.tail(Tm1Operations.TailMessageLog, MessageLogEntry, every, options);
  }

  /** Follows the transaction log, as {@link tailMessageLog} does the message log. Built on
   * `TailTransactionLog()` (schema 11.3.0). */
  tailTransactionLog(every: number, options?: Tm1CallOptions): Tm1Stream<TransactionLogEntry> {
    return this.tail(Tm1Operations.TailTransactionLog, TransactionLogEntry, every, options);
  }

  /** Follows the audit log, as {@link tailMessageLog} does the message log. Built on
   * `TailAuditLog()` (schema 11.6.0). */
  tailAuditLog(every: number, options?: Tm1CallOptions): Tm1Stream<AuditLogEntry> {
    return this.tail(Tm1Operations.TailAuditLog, AuditLogEntry, every, options);
  }

  /** `GET ErrorLogFiles`, streamed: the error logs processes have written, such as
   * `tm1processerror_<time>_<process>.log`. */
  errorLogFiles(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ErrorLogFile> {
    return this.session.stream(Tm1Request.list(ERROR_LOG_FILES, query, ErrorLogFile), options);
  }

  /** `GET ErrorLogFiles('name')`: the file, or a `Tm1NotFoundException` if there's none. */
  errorLogFile(filename: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ErrorLogFile> {
    return this.session.execute(Tm1Request.get(ERROR_LOG_FILES.key(required(filename, "filename")), query, ErrorLogFile), options);
  }

  /** `GET ErrorLogFiles('name')/Content`: what the file says, as text, read whole. TM1 (11.8) starts
   * it with a byte order mark, which is dropped. */
  async errorLogContent(filename: string, options?: Tm1CallOptions): Promise<string> {
    const text = await this.session.execute(Tm1Request.text(ERROR_LOG_FILES.key(required(filename, "filename")).nav("Content")), options);
    return text.startsWith(BYTE_ORDER_MARK) ? text.slice(1) : text;
  }

  private tail<T>(operation: Tm1Operation, type: Tm1Model<T>, every: number, options: Tm1CallOptions | undefined): Tm1Stream<T> {
    if (typeof required(every, "every") !== "number" || !(every >= 0)) throw new RangeError("every is milliseconds, 0 or more: " + String(every));
    const session = this.session;
    return Tm1Stream.from(async function* () {
      const request = Tm1Request.get(ODataPath.functionImport(importName(operation), {}), ODataQuery.none(), Json).requires(operation.capability());
      const answer = (await session.execute(request, options)) as { value?: unknown } | undefined;
      const link = ODataPath.link(typeof answer?.value === "string" ? answer.value : "");
      let seen = 0;
      for (;;) {
        let index = 0;
        for await (const entry of session.stream(Tm1Request.list(link, ODataQuery.none(), type), options)) {
          if (++index > seen) {
            seen = index;
            yield entry;
          }
        }
        await delay(every, options?.signal);
      }
    });
  }
}

function importName(operation: Tm1Operation): string {
  return required(operation.importName, operation.name);
}
