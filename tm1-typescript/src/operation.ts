import { Tm1Capability } from "./version/index.js";

function unqualified(name: string): string {
  return name.slice(name.lastIndexOf(".") + 1);
}

/** An action or function of the REST API, as `$metadata` declares it: what the generated
 * `Tm1Operations` catalog holds, as the Java client's `Tm1Operation`. */
export class Tm1Operation {
  constructor(
    readonly kind: "Action" | "Function",
    /** Its name in a path: `tm1.Activate`. */
    readonly name: string,
    readonly bindingType: string | null,
    readonly importName: string | null,
    readonly parameters: readonly Tm1Operation.Parameter[],
    readonly returnType: string | null,
    readonly addedIn: string | null,
    readonly deprecatedIn: string | null,
  ) {
    if ((bindingType === null) === (importName === null)) throw new TypeError(name + " needs exactly one of a binding type and an import name");
  }

  bound(): boolean {
    return this.bindingType !== null;
  }

  deprecated(): boolean {
    return this.deprecatedIn !== null;
  }

  /** Its coverage-gate ID: `Action Chore/Activate`, `ActionImport ExecuteMDX`. */
  endpoint(): string {
    return this.bindingType !== null ? `${this.kind} ${unqualified(this.bindingType)}/${unqualified(this.name)}` : `${this.kind}Import ${this.importName}`;
  }

  capability(): Tm1Capability {
    const capability = this.bindingType !== null ? Tm1Capability.boundOperation(this.bindingType, this.name) : Tm1Capability.unboundOperation(this.importName!);
    return this.addedIn === null ? capability : capability.since(this.addedIn);
  }

  toString(): string {
    return `Tm1Operation[${this.endpoint()}]`;
  }
}

export namespace Tm1Operation {
  export interface Parameter {
    readonly name: string;
    readonly type: string;
    readonly nullable: boolean;
  }
}
