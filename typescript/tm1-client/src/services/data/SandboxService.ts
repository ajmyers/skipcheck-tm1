import { Sandbox, Tm1Operations, type ConflictResolution, type SandboxPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const SANDBOXES = ODataPath.of("Sandboxes");

/** Sandboxes: private copies of the data a user writes to before publishing. They belong to the
 * user the session is for.
 *
 * Reading and writing in a sandbox go through a session view: `Tm1Session.withSandbox` or
 * `Tm1Client.withSandbox`, which add TM1's `!sandbox` option to every request. This service manages
 * the sandboxes themselves.
 *
 * Create a sandbox, write to it, and publish the changes to base data:
 * ```ts
 * await tm1.sandboxes.create(SandboxPatch.builder().name("What if").build());
 * await tm1.withSandbox("What if").cells.update("Sales", { Region: "North", Measure: "Amount" }, 42);
 * await tm1.sandboxes.publish("What if");
 * ```
 */
export class SandboxService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: ["EntitySet Sandboxes"],
    get: ["EntitySet Sandboxes"],
    create: ["EntitySet Sandboxes"],
    update: ["EntitySet Sandboxes"],
    delete: ["EntitySet Sandboxes"],
    load: ["Action Sandbox/Load"],
    unload: ["Action Sandbox/Unload"],
    discardChanges: ["Action Sandbox/DiscardChanges"],
    publish: ["Action Sandbox/Publish"],
    merge: ["Action Sandbox/Merge"],
  } as const satisfies Tm1Endpoints<SandboxService>;

  /** `GET Sandboxes`, streamed: the session user's sandboxes. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Sandbox> {
    return this.session.stream(Tm1Request.list(SANDBOXES, query, Sandbox), options);
  }

  /** `GET Sandboxes('name')`: the sandbox, or a `Tm1NotFoundException` if there's none. */
  get(name: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Sandbox> {
    return this.session.execute(Tm1Request.get(sandbox(name), query, Sandbox), options);
  }

  /** `POST Sandboxes`: creates an empty sandbox; it needs a name. Returns the sandbox as created. */
  create(sandbox: SandboxPatch, options?: Tm1CallOptions): Promise<Sandbox> {
    return this.session.execute(Tm1Request.post(SANDBOXES, required(sandbox, "sandbox"), Sandbox), options);
  }

  /** `PATCH Sandboxes('name')`: changes the properties the patch sets, such as `IncludeInSandboxDimension`. */
  update(name: string, patch: SandboxPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(sandbox(name), required(patch, "patch")), options);
  }

  /** `DELETE Sandboxes('name')`, with its unpublished changes. */
  delete(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(sandbox(name)), options);
  }

  /** `tm1.Load`: loads the sandbox into memory. */
  load(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.SandboxLoad, name, {}), options);
  }

  /** `tm1.Unload`: frees the sandbox's memory, keeping its changes. */
  unload(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.SandboxUnload, name, {}), options);
  }

  /** `tm1.DiscardChanges`: throws away the sandbox's changes, keeping the sandbox. */
  discardChanges(name: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(action(Tm1Operations.SandboxDiscardChanges, name, {}), options);
  }

  /** `tm1.Publish`: writes the sandbox's changes to base data; with `conflicts`, what to do with
   * cells changed in base data since, whether to wait for a lock rather than fail
   * (`allowQueueing`), and whether to empty the sandbox once published (`cleanAfter`). */
  publish(name: string, options?: Tm1CallOptions): Promise<void>;
  /** `tm1.Publish`, saying what to do with cells changed in base data since (`conflicts`), whether
   * to wait for a lock rather than fail (`allowQueueing`), and whether to empty the sandbox once
   * published (`cleanAfter`). */
  publish(name: string, conflicts: ConflictResolution, allowQueueing: boolean, cleanAfter: boolean, options?: Tm1CallOptions): Promise<void>;
  publish(
    name: string,
    conflicts?: ConflictResolution | Tm1CallOptions,
    allowQueueing?: boolean,
    cleanAfter?: boolean,
    options?: Tm1CallOptions,
  ): Promise<void> {
    if (typeof conflicts !== "string") return this.session.execute(action(Tm1Operations.SandboxPublish, name, {}), conflicts);
    return this.session.execute(action(Tm1Operations.SandboxPublish, name, publishOptions(conflicts, allowQueueing, cleanAfter)), options);
  }

  /** `tm1.Merge`: copies the sandbox's changes into the sandbox `target`; with `conflicts`, what to
   * do with cells the target changed too, whether to wait for a lock rather than fail
   * (`allowQueueing`), and whether to empty this sandbox once merged (`cleanAfter`). */
  merge(name: string, target: string, options?: Tm1CallOptions): Promise<void>;
  /** `tm1.Merge`, saying what to do with cells the target changed too (`conflicts`), whether to
   * wait for a lock rather than fail (`allowQueueing`), and whether to empty this sandbox once
   * merged (`cleanAfter`). */
  merge(name: string, target: string, conflicts: ConflictResolution, allowQueueing: boolean, cleanAfter: boolean, options?: Tm1CallOptions): Promise<void>;
  merge(
    name: string,
    target: string,
    conflicts?: ConflictResolution | Tm1CallOptions,
    allowQueueing?: boolean,
    cleanAfter?: boolean,
    options?: Tm1CallOptions,
  ): Promise<void> {
    const body: Record<string, unknown> = { "Target@odata.bind": sandbox(target).toString() };
    if (typeof conflicts !== "string") return this.session.execute(action(Tm1Operations.SandboxMerge, name, body), conflicts);
    return this.session.execute(action(Tm1Operations.SandboxMerge, name, { ...body, ...publishOptions(conflicts, allowQueueing, cleanAfter) }), options);
  }
}

function publishOptions(conflicts: ConflictResolution, allowQueueing: boolean | undefined, cleanAfter: boolean | undefined): Record<string, unknown> {
  // "Unknown" stands for a value this client doesn't know, and isn't sent
  if (conflicts === "Unknown") throw new TypeError("conflicts is required: Unknown isn't sent");
  if (typeof allowQueueing !== "boolean" || typeof cleanAfter !== "boolean") throw new TypeError("allowQueueing and cleanAfter are required");
  return { ConflictResolution: conflicts, AllowQueueing: allowQueueing, CleanAfter: cleanAfter };
}

function sandbox(name: string): ODataPath {
  return SANDBOXES.key(required(name, "name"));
}

function action(operation: Tm1Operation, name: string, body: Record<string, unknown>): Tm1Request<void> {
  return Tm1Request.post(sandbox(name).action(operation.name), body).requires(operation.capability());
}
