import { ODataError, Tm1ApiException, Tm1ProtocolException } from "../errors/index.js";

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

/** Credentials that don't change: Basic and CAMNamespace. The value is a private field, so neither
 * `JSON.stringify` nor `console.log` shows it. */
class Fixed extends Tm1Authenticator {
  readonly #value: string;

  constructor(
    value: string,
    private readonly description: string,
  ) {
    super();
    this.#value = value;
  }

  authorization(): Promise<string> {
    return Promise.resolve(this.#value);
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

/** One logon parameter, as Cognos takes it: `{"name":…,"value":…}`. The value only ever goes to
 * Cognos: never into logs, errors or `toString()`. */
interface Parameter {
  readonly name: string;
  readonly value: string;
}

/** @internal Cognos's session resource, on the dispatcher, with or without a `/bi` path. */
export function cognosSession(dispatcher: string): string {
  return dispatcher.replace(/\/+$/, "").replace(/\/bi$/, "") + "/api/v1/session";
}

function cookieNamed(response: Response, name: string): string | undefined {
  // getSetCookie() keeps each Set-Cookie apart; a fetch without it joins them with commas, split
  // here before each name=
  const all =
    typeof response.headers.getSetCookie === "function"
      ? response.headers.getSetCookie()
      : (response.headers.get("set-cookie") ?? "").split(/,(?=\s*[^;,=\s]+=)/);
  for (const header of all) {
    const first = header.split(";")[0] ?? "";
    const eq = first.indexOf("=");
    if (eq > 0 && first.slice(0, eq).trim() === name) {
      return first
        .slice(eq + 1)
        .trim()
        .replace(/^"|"$/g, "");
    }
  }
  return undefined;
}

function requireString(value: unknown, name: string): void {
  if (typeof value !== "string") throw new TypeError(name + " is required");
}

function logon(
  dispatcher: string,
  namespace: string,
  credentials: (signal?: AbortSignal) => Promise<Parameter[]>,
  fetchFn: typeof globalThis.fetch | undefined,
): (signal?: AbortSignal) => Promise<string> {
  requireString(dispatcher, "dispatcher");
  requireString(namespace, "namespace");
  const session = cognosSession(dispatcher);
  return async (signal?: AbortSignal) => {
    const parameters: Parameter[] = [{ name: "CAMNamespace", value: namespace }, ...(await credentials(signal))];
    const send = fetchFn ?? globalThis.fetch;
    const init: RequestInit = {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ parameters: parameters.map((p) => ({ name: p.name, value: p.value })) }),
      redirect: "manual",
      credentials: "omit",
    };
    if (signal) init.signal = signal;
    const response = await send(session, init);
    const status = response.status;
    // Read to the end, so the connection can be reused
    await response.arrayBuffer().catch(() => undefined);
    if (status === 401 || status === 403) {
      throw Tm1ApiException.of(status, "CredentialRejected", ODataError.fromText("Cognos refused the logon"), "PUT", session);
    }
    const passport = status < 300 ? cookieNamed(response, "cam_passport") : undefined;
    if (passport === undefined || !passport.trim()) {
      throw new Tm1ProtocolException(`Cognos answered the logon with ${status} and no cam_passport cookie`, undefined, "PUT", session);
    }
    return passport;
  };
}

/** CAM passports from Cognos, for {@link Tm1Authenticator.camPassport}, as the Java client's
 * `CognosPassports`: the client logs on to Cognos itself, rather than being handed a passport. Each
 * supplier logs on again every time it's asked, so every TM1 login (including the one after TM1
 * drops a session) gets a fresh passport, and an expired one is never reused.
 *
 * Both log on with Cognos's REST API: `PUT <dispatcher>/api/v1/session` with the logon parameters,
 * answered with the passport in a `cam_passport` cookie. The dispatcher is the address TM1 itself
 * is pointed at (`ServerCAMURI`, such as `http://cognos:9300`), reachable from the client; TM1 names
 * it when it refuses a login (`WWW-Authenticate: CAMPassport http://cognos:9300/bi/v1/disp`).
 *
 * A logon Cognos refuses (401, 403) fails with a `Tm1AuthenticationException`
 * (`CredentialRejected`), which the session doesn't retry. They send with `fetch`: the argument, or
 * the global one. A browser never lets a page read `Set-Cookie`, so in a browser the `fetch` given
 * must be one that can (through a proxy of the application's own, say); otherwise the logon fails
 * with a `Tm1ProtocolException`. */
export const CognosPassports = {
  /** Passports for a user name and password in a namespace, such as an LDAP one (`CAMNamespace`,
   * `CAMUsername`, `CAMPassword`): a supplier that logs on each time it's asked.
   * @param dispatcher the Cognos dispatcher's address, such as `http://cognos:9300`
   * @param namespace the namespace's ID, such as `LDAP`
   * @param fetch the `fetch` to send with; the global one by default */
  password(dispatcher: string, namespace: string, user: string, password: string, fetch?: typeof globalThis.fetch): (signal?: AbortSignal) => Promise<string> {
    requireString(user, "user");
    requireString(password, "password");
    const parameters: Parameter[] = [
      { name: "CAMUsername", value: user },
      { name: "CAMPassword", value: password },
    ];
    return logon(dispatcher, namespace, () => Promise.resolve(parameters), fetch);
  },

  /** Passports for an OpenID Connect ID token the application already holds, through Cognos's OIDC
   * namespace (`CAMNamespace`, `CAMOIDCIdToken`): no password ever reaches the client.
   * @param dispatcher the Cognos dispatcher's address, such as `http://cognos:9300`
   * @param namespace the OIDC namespace's ID, such as `OIDC`
   * @param idToken supplies a current ID token; asked at each logon
   * @param fetch the `fetch` to send with; the global one by default */
  idToken(dispatcher: string, namespace: string, idToken: Tm1Supplier, fetch?: typeof globalThis.fetch): (signal?: AbortSignal) => Promise<string> {
    if (typeof idToken !== "function") throw new TypeError("idToken is required");
    return logon(dispatcher, namespace, async (signal) => [{ name: "CAMOIDCIdToken", value: await idToken(signal) }], fetch);
  },
} as const;
