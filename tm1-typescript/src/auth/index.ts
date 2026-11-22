function base64(text: string): string {
  let binary = "";
  for (const b of new TextEncoder().encode(text)) binary += String.fromCharCode(b);
  return btoa(binary);
}

/** A function that supplies a secret at each login (a passport, an ID token), maybe asynchronously. */
export type Tm1Supplier = (signal?: AbortSignal) => string | Promise<string>;

/** How a session logs in: the `Authorization` header of its first request, as the Java client's
 * `Tm1Authenticator`. TM1 answers with a `TM1SessionId` cookie, which later requests send instead. */
export abstract class Tm1Authenticator {
  /** The header to log in with; asked for at each login. */
  abstract authorization(signal?: AbortSignal): Promise<string>;

  /** Who logs in, without secrets, for logs and errors. */
  abstract describe(): string;

  toString(): string {
    return `Tm1Authenticator[${this.describe()}]`;
  }

  /** TM1's own security (modes 1 and 2). */
  static basic(user: string, password?: string | null): Tm1Authenticator {
    if (typeof user !== "string") throw new TypeError("user is required");
    return new Fixed("Basic " + base64(`${user}:${password ?? ""}`), "Basic " + user);
  }

  /** CAM security (modes 4 and 5), with a user in a namespace. */
  static camNamespace(user: string, password: string, namespace: string): Tm1Authenticator {
    for (const [n, v] of [
      ["user", user],
      ["password", password],
      ["namespace", namespace],
    ] as const) {
      if (typeof v !== "string") throw new TypeError(n + " is required");
    }
    return new Fixed("CAMNamespace " + base64(`${user}:${password}:${namespace}`), `CAMNamespace ${namespace}/${user}`);
  }

  /** CAM security with a passport, fetched at each login (a passport expires). */
  static camPassport(passport: Tm1Supplier): Tm1Authenticator {
    if (typeof passport !== "function") throw new TypeError("passport is required");
    return new Passport(passport, "CAMPassport");
  }

  /** CAM security with a passport the client gets from Cognos itself, logging on with a user name
   * and password: a fresh passport for each login. Unlike {@link camNamespace}, TM1 never sees the
   * password, only Cognos does. */
  static cognosLogon(dispatcher: string, namespace: string, user: string, password: string, fetch?: typeof globalThis.fetch): Tm1Authenticator {
    return new Passport(CognosPassports.password(dispatcher, namespace, user, password, fetch), `CAMPassport from Cognos ${namespace}/${user}`);
  }

  /** CAM security with a passport the client gets from Cognos for an OpenID Connect ID token it
   * already holds: a fresh passport for each login. */
  static cognosIdToken(dispatcher: string, namespace: string, idToken: Tm1Supplier, fetch?: typeof globalThis.fetch): Tm1Authenticator {
    return new Passport(CognosPassports.idToken(dispatcher, namespace, idToken, fetch), `CAMPassport from Cognos ${namespace} (ID token)`);
  }
}

class Fixed extends Tm1Authenticator {
  constructor(
    private readonly value: string,
    private readonly description: string,
  ) {
    super();
  }

  authorization(): Promise<string> {
    return Promise.resolve(this.value);
  }

  describe(): string {
    return this.description;
  }
}

class Passport extends Tm1Authenticator {
  constructor(
    private readonly passport: Tm1Supplier,
    private readonly description: string,
  ) {
    super();
  }

  async authorization(signal?: AbortSignal): Promise<string> {
    return "CAMPassport " + (await this.passport(signal));
  }

  describe(): string {
    return this.description;
  }
}

/** Passports from Cognos, for {@link Tm1Authenticator.camPassport}, as the Java client's
 * `CognosPassports`. */
export const CognosPassports = {
  /** A passport for a user name and password, from Cognos's `bi/v1/login`. */
  password(dispatcher: string, namespace: string, user: string, password: string, fetch?: typeof globalThis.fetch): Tm1Supplier {
    void [dispatcher, namespace, user, password, fetch];
    return () => Promise.reject(new Error("CognosPassports isn't ported to TypeScript yet"));
  },

  /** A passport for an OpenID Connect ID token. */
  idToken(dispatcher: string, namespace: string, idToken: Tm1Supplier, fetch?: typeof globalThis.fetch): Tm1Supplier {
    void [dispatcher, namespace, idToken, fetch];
    return () => Promise.reject(new Error("CognosPassports isn't ported to TypeScript yet"));
  },
} as const;
