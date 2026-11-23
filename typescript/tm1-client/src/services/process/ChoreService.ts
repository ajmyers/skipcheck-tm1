import { Chore, ChoreTask, Process, Tm1Operations, type ChorePatch, type ChoreTaskPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { javaLocalDate, javaLocalTime } from "./formats.js";

const CHORES = ODataPath.of("Chores");

/** Chores: scheduled runs of processes, as ordered tasks. `/Chores`, their tasks, and running them
 * now.
 *
 * A task binds a process (`ChoreTaskPatch.builder().bindProcess(…)`) and gives each of its
 * parameters a value; its `Step`, its place in the chore, is TM1's to number.
 *
 * Create a daily chore with one task:
 * ```ts
 * const task = ChoreTaskPatch.builder().bindProcess(ODataPath.of("Processes").key("Load")).build();
 * await tm1.chores.create(ChorePatch.builder().name("Nightly").startTime("2026-01-01T02:00:00Z").frequency("P1DT00H00M00S").executionMode("SingleCommit").tasks([task]).build());
 * ```
 * Add a task at the end, and get the step TM1 gave it:
 * ```ts
 * const { Step } = await tm1.chores.addTask("Nightly", ChoreTaskPatch.builder().bindProcess(ODataPath.of("Processes").key("Report")).build());
 * ```
 * Schedule the chore, by patching `Active`:
 * ```ts
 * await tm1.chores.update("Nightly", ChorePatch.builder().active(true).build());
 * ```
 */
export class ChoreService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Chores"],
    get: ["EntitySet Chores"],
    create: ["EntitySet Chores"],
    update: ["EntitySet Chores"],
    delete: ["EntitySet Chores"],
    tasks: ["Navigation Chore/Tasks"],
    addTask: ["Navigation Chore/Tasks"],
    updateTask: ["Navigation Chore/Tasks"],
    deleteTask: ["Navigation Chore/Tasks"],
    taskProcess: ["Navigation ChoreTask/Process"],
    taskChore: ["Navigation ChoreTask/Chore"],
    execute: ["Action Chore/Execute"],
    activate: ["Action Chore/Activate"],
    deactivate: ["Action Chore/Deactivate"],
    setServerLocalStartTime: ["Action Chore/SetServerLocalStartTime"],
  } as const satisfies Tm1Endpoints<ChoreService>;

  /** `GET Chores`, streamed. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Chore> {
    return this.session.stream(Tm1Request.list(CHORES, query, Chore), options);
  }

  /** `GET Chores('name')`, with what `query` expands, such as `Tasks($expand=Process)`; a
   * `Tm1NotFoundException` if there's none. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Chore> {
    return this.session.execute(Tm1Request.get(chorePath(name), query, Chore), options);
  }

  /** `POST Chores`: creates a chore, with its tasks if the patch has them. It needs a name, start
   * time, frequency and execution mode; returns the chore as created. */
  create(chore: ChorePatch, options?: Tm1CallOptions): Promise<Chore> {
    return this.session.execute(Tm1Request.post(CHORES, required(chore, "chore"), Chore), options);
  }

  /** `PATCH Chores('name')`: changes the properties the patch sets, `Active` included, which IBM
   * recommends over the deprecated {@link activate}. Adding, changing or removing a task changes the
   * chore's ETag too. With `etag` (`chore["@odata.etag"]`), sent as `If-Match`: only if the chore
   * hasn't changed since, else a `Tm1PreconditionFailedException`. */
  async update(name: string, patch: ChorePatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(chorePath(name), required(patch, "patch"));
    await this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `DELETE Chores('name')`; its processes stay. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(chorePath(name)), options);
  }

  /** `GET Chores('name')/Tasks`, streamed, in step order. */
  tasks(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ChoreTask> {
    return this.session.stream(Tm1Request.list(chorePath(name).nav("Tasks"), query, ChoreTask), options);
  }

  /** `POST Chores('name')/Tasks`: adds a task at the end (its bound process and a value for each of
   * the process's parameters); returns it as created, with the `Step` TM1 gave it. */
  addTask(name: string, task: ChoreTaskPatch, options?: Tm1CallOptions): Promise<ChoreTask> {
    return this.session.execute(Tm1Request.post(chorePath(name).nav("Tasks"), required(task, "task"), ChoreTask), options);
  }

  /** `PATCH Chores('name')/Tasks(step)`: changes a task, such as its parameter values. */
  updateTask(name: string, step: number, patch: ChoreTaskPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(taskPath(name, step), required(patch, "patch")), options);
  }

  /** `DELETE Chores('name')/Tasks(step)`. */
  deleteTask(name: string, step: number, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(taskPath(name, step)), options);
  }

  /** `GET Chores('name')/Tasks(step)/Process`: the process the task runs. */
  taskProcess(name: string, step: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Process> {
    return this.session.execute(Tm1Request.get(taskPath(name, step).nav("Process"), query, Process), options);
  }

  /** `GET Chores('name')/Tasks(step)/Chore`: the task's chore. */
  taskChore(name: string, step: number, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Chore> {
    return this.session.execute(Tm1Request.get(taskPath(name, step).nav("Chore"), query, Chore), options);
  }

  /** `tm1.Execute`: runs the chore now, whether or not it's active, and settles when its tasks have
   * run. Needs schema 10.2.2.2. */
  execute(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ChoreExecute, name, {}), options);
  }

  /** `tm1.Activate`: schedules the chore.
   * @deprecated Deprecated in TM1 schema 11.8.3: patch `Active` with {@link update}. */
  activate(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ChoreActivate, name, {}), options);
  }

  /** `tm1.Deactivate`: unschedules the chore.
   * @deprecated Deprecated in TM1 schema 11.8.3: patch `Active` with {@link update}. */
  deactivate(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.ChoreDeactivate, name, {}), options);
  }

  /** `tm1.SetServerLocalStartTime`: sets the chore's start in the server's time zone: `date` as
   * `2026-01-02`, `time` as `02:00` or `02:00:30` (Java's `LocalDate` and `LocalTime`, sent as
   * their `toString` writes them). TM1 (11.8) refuses it while the chore is active ("Can't duplicate
   * active chore", code 7): deactivate it first.
   * @deprecated Deprecated in TM1 schema 11.8.3: patch `StartTime` with {@link update}. */
  setServerLocalStartTime(name: string, date: string, time: string, options?: Tm1CallOptions): Promise<void> {
    const body = { StartDate: javaLocalDate(required(date, "date"), "date"), StartTime: javaLocalTime(required(time, "time"), "time") };
    return this.session.execute(action(Tm1Operations.ChoreSetServerLocalStartTime, name, body), options);
  }
}

function chorePath(name: string): ODataPath {
  return CHORES.key(required(name, "name"));
}

function taskPath(name: string, step: number): ODataPath {
  if (!Number.isSafeInteger(step)) throw new RangeError("step is an integer: " + String(step));
  return chorePath(name).nav("Tasks").key(step);
}

function action(operation: Tm1Operation, name: string, body: Record<string, unknown>): Tm1Request<void> {
  return Tm1Request.post(chorePath(name).action(operation.name), body).requires(operation.capability());
}
