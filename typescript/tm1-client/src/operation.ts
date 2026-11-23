import { Tm1Capability } from "./version/index.js";

function unqualified(name: string): string {
  return name.slice(name.lastIndexOf(".") + 1);
}

/** An action or function of the REST API, as `$metadata` declares it: what the generated
 * `Tm1Operations` catalog holds, as the Java client's `Tm1Operation`. */
export class Tm1Operation {
  /** An operation as `$metadata` declares it. @param kind whether it's an action or a function
   * @param name the operation's name, qualified as in `$metadata`, such as `tm1.Activate` @param
   * bindingType the qualified type it's bound to, or null for an import @param importName the name
   * it's imported under at the service root, or null if it's bound @param parameters its
   * parameters, the binding parameter apart @param returnType what it returns, or null for nothing
   * @param addedIn the TM1 schema version that added it, or null @param deprecatedIn the version
   * that deprecated it, or null */
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

  /** Whether it's bound to a type, rather than invoked from the service root. */
  bound(): boolean {
    return this.bindingType !== null;
  }

  /** Whether `$metadata` marks it deprecated. */
  deprecated(): boolean {
    return this.deprecatedIn !== null;
  }

  /** Its coverage-gate ID: `Action Chore/Activate`, `ActionImport ExecuteMDX`. */
  endpoint(): string {
    return this.bindingType !== null ? `${this.kind} ${unqualified(this.bindingType)}/${unqualified(this.name)}` : `${this.kind}Import ${this.importName}`;
  }

  /** What a request using it needs the server to have, with the version that added it, for {@link
   * Tm1Request.requires}. */
  capability(): Tm1Capability {
    const capability = this.bindingType !== null ? Tm1Capability.boundOperation(this.bindingType, this.name) : Tm1Capability.unboundOperation(this.importName!);
    return this.addedIn === null ? capability : capability.since(this.addedIn);
  }

  /** The operation's endpoint ID, for logs. */
  toString(): string {
    return `Tm1Operation[${this.endpoint()}]`;
  }
}

/** The parameter type of {@link Tm1Operation}, nested as in Java. */
export namespace Tm1Operation {
  /** A parameter of an operation, the binding parameter apart. */
  export interface Parameter {
    /** The parameter's name. */
    readonly name: string;
    /** The parameter's type, as `$metadata` names it. */
    readonly type: string;
    /** Whether the parameter may be null. */
    readonly nullable: boolean;
  }
}
