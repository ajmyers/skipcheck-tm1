import {
  Process,
  ProcessDebugContext,
  ProcessDebugContextBreakpoint,
  ProcessDebugContextStackFrame,
  ProcessDebugContextVariable,
  Thread,
  Tm1Operations,
  type ProcessDebugContextBreakpointPatch,
} from "../../generated/index.js";
import { ODataPath, ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import { Tm1Stream } from "../../stream.js";
import { required, type Tm1Map } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { withParameters } from "./formats.js";

const CONTEXTS = ODataPath.of("ProcessDebugContexts");

/** How many times {@link ProcessDebugService.finish} resumes a run that keeps pausing before giving up. */
const MAX_RESUMES = 100;

/** What {@link ProcessDebugService.debugging}'s work gives: items, one value, or nothing. */
export type ProcessDebugWork<R> = AsyncIterable<R> | PromiseLike<R | void> | R | void;

/** Process debugging: a run of a process that stops at breakpoints and steps line by line, held on
 * the server as a `ProcessDebugContext` until deleted.
 *
 * {@link debug} starts one, paused before the first line; {@link resume} runs to the next
 * breakpoint (or the end), and the `step…` methods move a line at a time. Each returns the context
 * as it then is. TM1 (11.8) answers a step without waiting for it to land, so the status is
 * `Running` or `Paused` depending on which came first; {@link get} reads it again. A step past a
 * procedure's last line pauses with an empty call stack before the run completes.
 *
 * TM1 (11.8) refuses to delete a debug context: a run ends by running to completion, which
 * {@link finish} does, and {@link debugging} does when its work is done. A finished context stays
 * readable until the session ends. `ProcessDebugContexts` lists none, though each reads by ID.
 *
 * Stop at a breakpoint and see where the run is, with {@link debugging} finishing the run afterwards:
 * ```ts
 * const frames = await tm1.processDebugging.debugging("Load", {}, async (context) => {
 *   const id = context.ID!;
 *   await tm1.processDebugging.addBreakpoint(id, ProcessDebugContextLineBreakpointPatch.builder().id(0).enabled(true).processName("Load").procedure("Prolog").lineNumber(3).build());
 *   await tm1.processDebugging.resume(id);
 *   return tm1.processDebugging.callStack(id).toArray();
 * }).first();
 * ```
 * Step through a run started with {@link debug}, and finish it:
 * ```ts
 * const context = await tm1.processDebugging.debug("Load", {});
 * await tm1.processDebugging.stepOver(context.ID!);
 * const done = await tm1.processDebugging.finish(context.ID!);
 * ```
 */
export class ProcessDebugService extends Tm1Service {
  static readonly endpoints = {
    debug: ["Action Process/Debug"],
    debugging: ["Action Process/Debug", "EntitySet ProcessDebugContexts"],
    list: ["EntitySet ProcessDebugContexts"],
    get: ["EntitySet ProcessDebugContexts"],
    finish: ["Action ProcessDebugContext/Continue", "Navigation ProcessDebugContext/Breakpoints"],
    resume: ["Action ProcessDebugContext/Continue"],
    stepIn: ["Action ProcessDebugContext/StepIn"],
    stepOut: ["Action ProcessDebugContext/StepOut"],
    stepOver: ["Action ProcessDebugContext/StepOver"],
    breakpoints: ["Navigation ProcessDebugContext/Breakpoints"],
    addBreakpoint: ["Navigation ProcessDebugContext/Breakpoints"],
    updateBreakpoint: ["Navigation ProcessDebugContext/Breakpoints"],
    removeBreakpoint: ["Navigation ProcessDebugContext/Breakpoints"],
    currentBreakpoint: ["Navigation ProcessDebugContext/CurrentBreakpoint"],
    callStack: ["Navigation ProcessDebugContext/CallStack"],
    variables: ["Navigation ProcessDebugContextStackFrame/Variables"],
    frameProcess: ["Navigation ProcessDebugContextStackFrame/Process"],
    process: ["Navigation ProcessDebugContext/Process"],
    thread: ["Navigation ProcessDebugContext/Thread"],
  } as const satisfies Tm1Endpoints<ProcessDebugService>;

  /** `tm1.Debug`: starts debugging a process, with its parameters by name, in order; returns the
   * debug context, with its `ID`. */
  debug(process: string, parameters?: Tm1Map<unknown> | null, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    const operation = Tm1Operations.ProcessDebug;
    const path = ODataPath.of("Processes").key(required(process, "process")).action(operation.name);
    return this.session.execute(Tm1Request.post(path, withParameters({}, parameters), ProcessDebugContext).withQuery(query).requires(operation.capability()), options);
  }

  /** Debugs a process for the length of `work` (given the context: set breakpoints, step, read
   * variables), then finishes the run ({@link finish}): when the work completes, fails, or the
   * stream is left early. TM1 (11.8) refuses to delete a debug context, so finishing it is how the
   * run ends. Streams what the work gives, as Java's `Flux`: an async iterable's items, or a
   * promise's value (nothing for `undefined` or `null`); `.first()` reads a single answer. */
  debugging<R>(process: string, parameters: Tm1Map<unknown> | null | undefined, work: (context: ProcessDebugContext) => ProcessDebugWork<R>, options?: Tm1CallOptions): Tm1Stream<R> {
    required(process, "process");
    required(work, "work");
    const self = this;
    return Tm1Stream.from(async function* () {
      const context = await self.debug(process, parameters, ODataQuery.none(), options);
      let failed = false;
      try {
        const result = work(context);
        if (result != null && typeof result === "object" && Symbol.asyncIterator in result) {
          yield* result as AsyncIterable<R>;
        } else {
          const value = await (result as PromiseLike<R | void> | R | void);
          if (value != null) yield value as R;
        }
      } catch (e) {
        failed = true;
        throw e;
      } finally {
        // Finished without the signal: a cancelled run is finished too
        if (context.ID != null) {
          if (failed) await self.finish(context.ID).catch(() => undefined);
          else await self.finish(context.ID);
        }
      }
    });
  }

  /** `GET ProcessDebugContexts`, streamed. TM1 (11.8) lists none, though each reads by ID with {@link get}. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ProcessDebugContext> {
    return this.session.stream(Tm1Request.list(CONTEXTS, query, ProcessDebugContext), options);
  }

  /** `GET ProcessDebugContexts('id')`: the context, or a `Tm1NotFoundException` if there's none. */
  get(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    return this.session.execute(Tm1Request.get(contextPath(id), query, ProcessDebugContext), options);
  }

  /** Runs a debug run to its end: removes its breakpoints, then resumes until it's `Complete` (at
   * most a hundred times). TM1 (11.8) refuses to delete a debug context, so finishing the run is how
   * to end one; the context stays readable, complete, until the session ends. Returns the context as
   * the last resume left it: complete, with the run's `Result`. */
  async finish(id: string, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    required(id, "id");
    for await (const breakpoint of this.breakpoints(id, ODataQuery.none(), options)) {
      // TM1 stores a breakpoint sent without an ID as -1, which it refuses in a path
      if (breakpoint.ID != null && breakpoint.ID >= 0) await this.removeBreakpoint(id, breakpoint.ID, options);
    }
    let context = await this.resume(id, ODataQuery.none(), options);
    for (let resumes = 1; context.Status !== "Complete" && resumes < MAX_RESUMES; resumes++) {
      context = await this.resume(id, ODataQuery.none(), options);
    }
    return context;
  }

  /** `tm1.Continue`: runs to the next breakpoint, or to the end; the context, paused or complete. */
  resume(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    return this.session.execute(step(Tm1Operations.ProcessDebugContextContinue, id, query), options);
  }

  /** `tm1.StepIn`: runs one line, into a process it calls. */
  stepIn(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    return this.session.execute(step(Tm1Operations.ProcessDebugContextStepIn, id, query), options);
  }

  /** `tm1.StepOut`: runs to the end of the current procedure or called process. */
  stepOut(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    return this.session.execute(step(Tm1Operations.ProcessDebugContextStepOut, id, query), options);
  }

  /** `tm1.StepOver`: runs one line, over any process it calls. */
  stepOver(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContext> {
    return this.session.execute(step(Tm1Operations.ProcessDebugContextStepOver, id, query), options);
  }

  /** `GET …/Breakpoints`, streamed, each as its kind (line, data or lock: `typeOf`). */
  breakpoints(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ProcessDebugContextBreakpoint> {
    return this.session.stream(Tm1Request.list(contextPath(id).nav("Breakpoints"), query, ProcessDebugContextBreakpoint), options);
  }

  /** `POST …/Breakpoints`: adds a breakpoint, its kind given by the patch
   * (`ProcessDebugContextLineBreakpointPatch`, …Data…, …Lock…); returns it as created.
   *
   * The caller chooses the breakpoint's `ID`, 0 or more and unique in the context: TM1 (11.8) stores
   * one sent without an ID as -1, which it then refuses in a path, so it can't be changed or
   * removed, and a second one without an ID conflicts (409).
   * @throws RangeError if the patch has no `ID` of 0 or more */
  addBreakpoint(id: string, breakpoint: ProcessDebugContextBreakpointPatch, options?: Tm1CallOptions): Promise<ProcessDebugContextBreakpoint> {
    const value = required(breakpoint, "breakpoint")
      .values()
      .find(([k]) => k === "ID")?.[1];
    if (typeof value !== "number" || !(value >= 0)) {
      throw new RangeError("A breakpoint needs an ID of 0 or more: TM1 stores one without as -1, which can't be changed or removed");
    }
    return this.session.execute(Tm1Request.post(contextPath(id).nav("Breakpoints"), breakpoint, ProcessDebugContextBreakpoint), options);
  }

  /** `PATCH …/Breakpoints(n)`: changes a breakpoint, such as enabling or disabling it, with a patch of its kind. */
  updateBreakpoint(id: string, breakpoint: number, patch: ProcessDebugContextBreakpointPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(breakpointPath(id, breakpoint), required(patch, "patch")), options);
  }

  /** `DELETE …/Breakpoints(n)`. */
  removeBreakpoint(id: string, breakpoint: number, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(breakpointPath(id, breakpoint)), options);
  }

  /** `GET …/CurrentBreakpoint`: the breakpoint the run is paused at, or undefined if it isn't paused at one. */
  currentBreakpoint(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ProcessDebugContextBreakpoint | undefined> {
    return this.session.execute(Tm1Request.get(contextPath(id).nav("CurrentBreakpoint"), query, ProcessDebugContextBreakpoint), options);
  }

  /** `GET …/CallStack`, streamed: where the run is, innermost frame first; `query` can expand `Variables`. */
  callStack(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ProcessDebugContextStackFrame> {
    return this.session.stream(Tm1Request.list(contextPath(id).nav("CallStack"), query, ProcessDebugContextStackFrame), options);
  }

  /** `GET …/CallStack('frame')/Variables`, streamed: the frame's variables and their values. TM1
   * (11.8) lists TurboIntegrator's own variables (`NVALUE`, `DATASOURCE…`), not the process's local ones. */
  variables(id: string, frame: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ProcessDebugContextVariable> {
    return this.session.stream(Tm1Request.list(framePath(id, frame).nav("Variables"), query, ProcessDebugContextVariable), options);
  }

  /** `GET …/CallStack('frame')/Process`: the process the frame runs. */
  frameProcess(id: string, frame: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Process> {
    return this.session.execute(Tm1Request.get(framePath(id, frame).nav("Process"), query, Process), options);
  }

  /** `GET …/Process`: the process being debugged. */
  process(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Process> {
    return this.session.execute(Tm1Request.get(contextPath(id).nav("Process"), query, Process), options);
  }

  /** `GET …/Thread`: the server thread running the debug run. */
  thread(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Thread> {
    return this.session.execute(Tm1Request.get(contextPath(id).nav("Thread"), query, Thread), options);
  }
}

function step(operation: Tm1Operation, id: string, query: ODataQuery | null | undefined): Tm1Request<ProcessDebugContext> {
  return Tm1Request.post(contextPath(id).action(operation.name), {}, ProcessDebugContext).withQuery(query).requires(operation.capability());
}

function contextPath(id: string): ODataPath {
  return CONTEXTS.key(required(id, "id"));
}

function breakpointPath(id: string, breakpoint: number): ODataPath {
  if (!Number.isSafeInteger(breakpoint)) throw new RangeError("breakpoint is an integer ID: " + String(breakpoint));
  return contextPath(id).nav("Breakpoints").key(breakpoint);
}

function framePath(id: string, frame: string): ODataPath {
  return contextPath(id).nav("CallStack").key(required(frame, "frame"));
}
