import { Tm1Authenticator } from "../auth/index.js";
import { checkImpersonated, type Tm1SessionBuilder } from "./session.js";

/** The name as TM1 compares user names: ignoring case and spaces. */
function comparable(user: string): string {
  return user.replace(/ /g, "").toLowerCase();
}

/** Who a session is: the identity a {@link Tm1SessionPool} keys its sessions by, as the Java
 * client's `Tm1Identity`. In TM1 the identity is fixed when the session logs in, so one identity is
 * one session.
 *
 * - {@link serviceAccount}: the pool's own credentials, as they are.
 * - {@link impersonating}: the pool's credentials, acting as a TM1 user through `TM1-Impersonate`.
 * - {@link withCredentials}: a user's own credentials, such as their CAM passport.
 *
 * Two identities are equal ({@link equals}) when they're the same kind and their names match as TM1
 * matches user names, ignoring case and spaces, so `impersonating("Alice")` and
 * `impersonating("alice")` share a session. The authenticator isn't part of equality: an identity's
 * name says who it is. */
export class Tm1Identity {
  private static readonly SERVICE_ACCOUNT = new Tm1Identity("ServiceAccount", "", undefined);

  private readonly comparableName: string;

  private constructor(
    private readonly kindValue: Tm1Identity.Kind,
    private readonly nameValue: string,
    private readonly authenticatorValue: Tm1Authenticator | undefined,
  ) {
    this.comparableName = comparable(nameValue);
  }

  /** The pool's own credentials, with no impersonation. */
  static serviceAccount(): Tm1Identity {
    return Tm1Identity.SERVICE_ACCOUNT;
  }

  /** The pool's credentials, acting as TM1 user `user`. The pool's credentials must be an
   * administrator's or a data administrator's, and administrators can't be impersonated; see
   * {@link Tm1SessionBuilder.impersonate}.
   * @throws RangeError if `user` is blank or not printable ASCII, which TM1 can't impersonate correctly */
  static impersonating(user: string): Tm1Identity {
    return new Tm1Identity("Impersonating", checkImpersonated(user), undefined);
  }

  /** A user logging in with their own credentials.
   * @param name who the credentials belong to, for the pool's key and for logs, such as the user name
   * @param authenticator their credentials */
  static withCredentials(name: string, authenticator: Tm1Authenticator): Tm1Identity {
    if (typeof name !== "string") throw new TypeError("name is required");
    if (!name.trim()) throw new RangeError("An identity's name must not be blank");
    if (!(authenticator instanceof Tm1Authenticator)) throw new TypeError("authenticator is a Tm1Authenticator");
    return new Tm1Identity("OwnCredentials", name, authenticator);
  }

  /** What kind of identity this is. */
  kind(): Tm1Identity.Kind {
    return this.kindValue;
  }

  /** The user's name, as given; empty for the service account. */
  name(): string {
    return this.nameValue;
  }

  /** The user's own credentials, for `OwnCredentials`; undefined for the other kinds. */
  authenticator(): Tm1Authenticator | undefined {
    return this.authenticatorValue;
  }

  /** @internal What the pool keys its sessions by: the kind and the comparable name. */
  key(): string {
    return this.kindValue + "\u0000" + this.comparableName;
  }

  /** @internal Applies this identity to session settings copied from the pool's. */
  applyTo(settings: Tm1SessionBuilder): Tm1SessionBuilder {
    switch (this.kindValue) {
      case "ServiceAccount":
        return settings;
      case "Impersonating":
        return settings.impersonate(this.nameValue);
      case "OwnCredentials":
        return settings.withoutImpersonation().authenticator(this.authenticatorValue!);
    }
  }

  equals(other: unknown): boolean {
    return this === other || (other instanceof Tm1Identity && other.key() === this.key());
  }

  toString(): string {
    switch (this.kindValue) {
      case "ServiceAccount":
        return "Tm1Identity[service account]";
      case "Impersonating":
        return `Tm1Identity[impersonating ${this.nameValue}]`;
      case "OwnCredentials":
        return `Tm1Identity[${this.nameValue}]`;
    }
  }
}

export namespace Tm1Identity {
  /** The kinds of identity: the pool's own credentials, those credentials impersonating a user,
   * or a user's own credentials. */
  export type Kind = "ServiceAccount" | "Impersonating" | "OwnCredentials";

  // The value beside the type of the same name: used by callers, not in this file
  // eslint-disable-next-line no-useless-assignment
  export const Kind = {
    ServiceAccount: "ServiceAccount",
    Impersonating: "Impersonating",
    OwnCredentials: "OwnCredentials",
  } as const satisfies Record<Kind, Kind>;
}
