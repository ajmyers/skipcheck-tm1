// SPDX-License-Identifier: Apache-2.0

/** What went wrong, independent of the status TM1 chose to report it with. A
 * {@link Tm1ErrorClassifier} picks the kind from the status, TM1's error code and its message,
 * since TM1 reports the same failure with different statuses across versions. */
export type Tm1ErrorKind =
  /** The object doesn't exist. */
  | "NotFound"
  /** An object of that name already exists. */
  | "AlreadyExists"
  /** The object is locked (a reserved cube, a locked element). */
  | "Locked"
  /** The object is in use, so TM1 refused to change or delete it. */
  | "InUse"
  /** Some other conflict with the object's state. */
  | "Conflict"
  /** An `If-Match` ETag didn't match: someone else changed the object. */
  | "PreconditionFailed"
  /** The session isn't logged in, or no longer is. */
  | "Unauthenticated"
  /** The login's credentials were refused (or the impersonated user is disabled). */
  | "CredentialRejected"
  /** The user may not do this. */
  | "Forbidden"
  /** The user can't read the object. */
  | "NoReadRights"
  /** The user may not impersonate the one asked for. */
  | "ImpersonationRefused"
  /** The user to impersonate doesn't exist. */
  | "UnknownUser"
  /** A data spread couldn't be applied. */
  | "DataSpreadFailed"
  /** A member's unique name doesn't resolve. */
  | "InvalidUniqueName"
  /** The operation was cancelled on TM1. */
  | "Cancelled"
  /** TM1 doesn't implement the operation. */
  | "NotImplemented"
  /** None of the above. */
  | "Other";

/** What a TM1 error means, as values: `Tm1ErrorKind.NotFound`. More specific than the HTTP status,
 * since TM1 reports many different failures with the same status and code. */
export const Tm1ErrorKind = {
  NotFound: "NotFound",
  AlreadyExists: "AlreadyExists",
  Locked: "Locked",
  InUse: "InUse",
  Conflict: "Conflict",
  PreconditionFailed: "PreconditionFailed",
  Unauthenticated: "Unauthenticated",
  CredentialRejected: "CredentialRejected",
  Forbidden: "Forbidden",
  NoReadRights: "NoReadRights",
  ImpersonationRefused: "ImpersonationRefused",
  UnknownUser: "UnknownUser",
  DataSpreadFailed: "DataSpreadFailed",
  InvalidUniqueName: "InvalidUniqueName",
  Cancelled: "Cancelled",
  NotImplemented: "NotImplemented",
  Other: "Other",
} as const satisfies Record<Tm1ErrorKind, Tm1ErrorKind>;

const MAX_TEXT = 1000;

function text(node: Record<string, unknown>, field: string): string | undefined {
  const v = node[field];
  if (v === undefined || v === null) return undefined;
  return typeof v === "string" ? v : JSON.stringify(v);
}

/** The `error` object of an OData error response: a code and message, and optionally a target,
 * details and an inner error. TM1 sends some errors as plain text instead, read by {@link fromText}. */
export class ODataError {
  /** TM1's error code, or empty. */
  readonly code: string;
  /** TM1's message, or empty. */
  readonly message: string;
  /** What the error applies to, or undefined. */
  readonly target: string | undefined;
  /** Further errors, or empty. */
  readonly details: readonly ODataError[];
  /** Server-specific details as TM1 sent them, or undefined. */
  readonly innerError: unknown;

  /** An OData error response body: `{"error":{"code":"278","message":"..."}}`. TM1 uses code `278`
   * for most errors (not found, bad request, precondition failed and not implemented alike), so
   * the code alone rarely identifies an error: see {@link Tm1ErrorClassifier}. A null code or
   * message reads as empty. */
  constructor(code: string | null | undefined, message: string | null | undefined, target?: string, details?: readonly ODataError[], innerError?: unknown) {
    this.code = code ?? "";
    this.message = message ?? "";
    this.target = target;
    this.details = [...(details ?? [])];
    this.innerError = innerError;
  }

  /** The error in a parsed JSON body, wrapped in `error` or not; undefined if it isn't one. */
  static from(body: unknown): ODataError | undefined {
    if (typeof body !== "object" || body === null || Array.isArray(body)) return undefined;
    const b = body as Record<string, unknown>;
    const error = (typeof b["error"] === "object" && b["error"] !== null ? b["error"] : b) as Record<string, unknown>;
    if (Array.isArray(error) || !("code" in error || "message" in error)) return undefined;
    const details: ODataError[] = [];
    if (Array.isArray(error["details"])) {
      for (const item of error["details"]) {
        const d = ODataError.from(item);
        if (d) details.push(d);
      }
    }
    return new ODataError(text(error, "code"), text(error, "message"), text(error, "target"), details, error["innererror"]);
  }

  /** The error in a plain-text body, as TM1 sends some (its `Error:` lines dropped, the rest joined
   * on one line and cut at 1000 characters); undefined if there's no text. */
  static fromText(body: string): ODataError | undefined {
    const lines = body
      .split(/\r\n|\n|\r/)
      .map((l) => l.trim())
      .filter((l) => l.length > 0 && !l.startsWith("Error:"));
    if (lines.length === 0) return undefined;
    const message = lines.join(" ");
    return new ODataError("", message.length > MAX_TEXT ? message.slice(0, MAX_TEXT) + "…" : message);
  }

  /** The error in a response body, whatever its form: JSON, then plain text; undefined if empty. */
  static parse(body: Uint8Array | string | null | undefined): ODataError | undefined {
    if (body === null || body === undefined || body.length === 0) return undefined;
    const s = (typeof body === "string" ? body : new TextDecoder().decode(body)).replace(/^\uFEFF/, "");
    try {
      const e = ODataError.from(JSON.parse(s));
      if (e) return e;
    } catch {
      // Not JSON: TM1 sends some errors as plain text, read below
    }
    return ODataError.fromText(s);
  }

  /** Whether `other` is the same error: code, message, target, details and inner error alike. */
  equals(other: unknown): boolean {
    return (
      other instanceof ODataError &&
      other.code === this.code &&
      other.message === this.message &&
      other.target === this.target &&
      other.details.length === this.details.length &&
      other.details.every((d, i) => d.equals(this.details[i])) &&
      JSON.stringify(other.innerError) === JSON.stringify(this.innerError)
    );
  }

  /** The error's code and message, for logs. */
  toString(): string {
    return `ODataError[code=${this.code}, message=${this.message}]`;
  }
}

function describe(method: string | undefined, url: string | undefined): string {
  return method === undefined ? "" : ` (${method} ${url})`;
}

/** Any failure talking to TM1: TM1 refused the request ({@link Tm1ApiException}), it got no complete
 * response ({@link Tm1TransportException}), the response couldn't be read
 * ({@link Tm1ProtocolException}), or the server lacks what the request needs
 * ({@link Tm1UnsupportedOperationException}). Each says whether retrying could help and whether the
 * request may have taken effect. */
export abstract class Tm1Exception extends Error {
  /** The request's HTTP method, when the failure is a request's. */
  readonly method: string | undefined;
  /** The request's URL, when the failure is a request's. */
  readonly url: string | undefined;

  protected constructor(message: string, method: string | undefined, url: string | undefined, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = new.target.name;
    this.method = method;
    this.url = url;
  }

  /** Whether sending the request again could succeed. */
  abstract isRetryable(): boolean;

  /** Whether the request may have taken effect on TM1 despite the failure. */
  abstract mayHaveApplied(): boolean;
}

function apiMessage(status: number, error: ODataError | undefined, method: string, url: string): string {
  const detail = !error || error.message.length === 0 ? "" : ": " + error.message;
  const code = !error || error.code.length === 0 ? "" : ` [code ${error.code}]`;
  return `TM1 returned ${status}${detail}${code}${describe(method, url)}`;
}

/** TM1 refused a request with an error status. The class follows the {@link kind} (a missing object
 * is a {@link Tm1NotFoundException} whatever status TM1 used), then the status. */
export class Tm1ApiException extends Tm1Exception {
  /** @internal Made by {@link Tm1ApiException.of}. */
  constructor(
    readonly status: number,
    readonly kind: Tm1ErrorKind,
    /** TM1's error, when the response had one. */
    readonly error: ODataError | undefined,
    method: string,
    url: string,
  ) {
    super(apiMessage(status, error, method, url), method, url);
  }

  /** The exception for a refused request: its kind given, or from `classifier`; of the class the
   * kind calls for. */
  static of(status: number, kindOrClassifier: Tm1ErrorKind | Tm1ErrorClassifier, error: ODataError | undefined, method: string, url: string): Tm1ApiException {
    const kind = typeof kindOrClassifier === "string" ? kindOrClassifier : kindOrClassifier.classify(status, error);
    switch (kind) {
      case "NotFound":
        return new Tm1NotFoundException(status, kind, error, method, url);
      case "AlreadyExists":
      case "Locked":
      case "InUse":
      case "Conflict":
        return new Tm1ConflictException(status, kind, error, method, url);
      case "PreconditionFailed":
        return new Tm1PreconditionFailedException(status, kind, error, method, url);
      case "Unauthenticated":
      case "CredentialRejected":
        return new Tm1AuthenticationException(status, kind, error, method, url);
      case "Forbidden":
      case "NoReadRights":
      case "ImpersonationRefused":
        return new Tm1ForbiddenException(status, kind, error, method, url);
      case "NotImplemented":
        return new Tm1NotImplementedException(status, kind, error, method, url);
    }
    if (status >= 500) return new Tm1ServerException(status, kind, error, method, url);
    if (status >= 400) return new Tm1BadRequestException(status, kind, error, method, url);
    return new Tm1ApiException(status, kind, error, method, url);
  }

  /** @internal The exception for an error status and its body (JSON or TM1's plain text). */
  static fromBody(status: number, body: Uint8Array | string | undefined, method: string, url: string, classifier: Tm1ErrorClassifier): Tm1ApiException {
    return Tm1ApiException.of(status, classifier, ODataError.parse(body), method, url);
  }

  /** 429, 502, 503 and 504: TM1 (or a proxy before it) is busy or restarting. */
  isRetryable(): boolean {
    return [429, 502, 503, 504].includes(this.status);
  }

  /** A 5xx other than 501: TM1 may have failed after applying the change. */
  mayHaveApplied(): boolean {
    return this.status >= 500 && this.status !== 501;
  }
}

/** A 4xx TM1 rejected as invalid. */
export class Tm1BadRequestException extends Tm1ApiException {}

/** Not logged in, or the credentials were refused. */
export class Tm1AuthenticationException extends Tm1ApiException {}

/** The user may not do this, can't read the object, or may not impersonate. */
export class Tm1ForbiddenException extends Tm1ApiException {}

/** The object doesn't exist, whatever status TM1 said so with. */
export class Tm1NotFoundException extends Tm1ApiException {}

/** The object exists already, is locked, in use or otherwise in the way. */
export class Tm1ConflictException extends Tm1ApiException {}

/** An ETag didn't match: someone else changed the object. */
export class Tm1PreconditionFailedException extends Tm1ApiException {}

/** TM1 doesn't implement the operation. */
export class Tm1NotImplementedException extends Tm1ApiException {}

/** TM1 failed with a 5xx. */
export class Tm1ServerException extends Tm1ApiException {}

/** The request got no complete response: it couldn't be sent (`Connect`: refused, unknown host, TLS
 * handshake, connect timeout), so it didn't apply; or the response didn't arrive whole (`Response`:
 * a timeout, a dropped connection), so it may have. */
export class Tm1TransportException extends Tm1Exception {
  /** An exception for a request that got no complete response. @param phase how far the request got
   * @param idempotent whether the request is safe to repeat (GET, PUT, DELETE), which makes a
   * response-phase failure retryable @param method the request's HTTP method @param url the
   * request's URL, without credentials @param cause the underlying failure, if any */
  constructor(
    /** Where the request failed (Java's `phase()`). */
    readonly phase: Tm1TransportException.Phase,
    private readonly idempotent: boolean,
    method: string,
    url: string,
    cause?: unknown,
  ) {
    super(
      (phase === "Connect" ? "Couldn't send the request to TM1" : "No complete response from TM1") +
        describe(method, url) +
        (cause instanceof Error && cause.message ? ": " + cause.message : ""),
      method,
      url,
      cause,
    );
  }

  /** Unsent requests, and idempotent ones (GET, PUT, DELETE, HEAD), may be sent again. */
  isRetryable(): boolean {
    return this.phase === "Connect" || this.idempotent;
  }

  /** False only if the request was never sent. */
  mayHaveApplied(): boolean {
    return this.phase === "Response";
  }
}

/** The phase type of {@link Tm1TransportException}, nested as in Java. */
export namespace Tm1TransportException {
  /** How far the request got: `Connect`, before it was sent (connecting, TLS, or waiting for a
   * pooled connection); `Response`, after it was sent, before the response was complete. */
  export type Phase = "Connect" | "Response";
}

/** TM1 answered with something this client can't read: not JSON, a login without a cookie, an async
 * result that isn't an HTTP message. The request may have applied. */
export class Tm1ProtocolException extends Tm1Exception {
  /** The error TM1 reported in the response, if it reported one. */
  readonly error: ODataError | undefined;

  /** An exception for a response that couldn't be read. @param message what was wrong with the
   * response; the method and URL are appended @param error the error TM1 reported in the response,
   * if any @param method the request's HTTP method, if known @param url the request's URL, without
   * credentials, if known @param cause the underlying failure, such as a parse error */
  constructor(message: string, error?: ODataError, method?: string, url?: string, cause?: unknown) {
    super(message + describe(method, url), method, url, cause);
    this.error = error;
  }

  /** False: the same request would get the same answer. */
  isRetryable(): boolean {
    return false;
  }

  /** True: the server got the request and started answering. */
  mayHaveApplied(): boolean {
    return true;
  }
}

/** A request this client refuses to send because the server's `$metadata` lacks what it needs (a
 * function, an entity set, a property) or its version is too old; nothing was sent. */
export class Tm1UnsupportedOperationException extends Tm1Exception {
  /** Creates the exception. @param message the full message @param missing what's missing, such as
   * `Cube.Reserve` */
  constructor(
    message: string,
    /** What's missing, such as `Cube.Reserve`. */
    readonly missing: string,
  ) {
    super(message, undefined, undefined);
  }

  /** False: the server won't gain the operation by trying again. */
  isRetryable(): boolean {
    return false;
  }

  /** False: nothing was sent. */
  mayHaveApplied(): boolean {
    return false;
  }
}

/** Using a session, or a pool, after it was closed. */
export class Tm1SessionClosedException extends Error {
  /** An exception for a closed session or pool at `serviceRoot`. */
  constructor(readonly serviceRoot: string) {
    super(`The TM1 session at ${serviceRoot} is closed`);
    this.name = "Tm1SessionClosedException";
  }
}

/** What a refusal means, from TM1's status, code and message, as the Java client's
 * `Tm1ErrorClassifier`: the first rule that matches, else by status. */
export class Tm1ErrorClassifier {
  private static defaultRules: Tm1ErrorClassifier | undefined;

  private static makeDefaults(): Tm1ErrorClassifier {
    return new Tm1ErrorClassifier([
      Tm1ErrorClassifier.Rule.message("insufficient rights to impersonate", "ImpersonationRefused"),
      Tm1ErrorClassifier.Rule.message("administrator to be able to impersonate", "ImpersonationRefused"),
      Tm1ErrorClassifier.Rule.message("user specified in the impersonation request does not exist", "UnknownUser"),
      Tm1ErrorClassifier.Rule.message("tm1userexception: cancel", "Cancelled"),
      Tm1ErrorClassifier.Rule.message("DataSpreadFailed", "DataSpreadFailed"),
      Tm1ErrorClassifier.Rule.code("248", "InvalidUniqueName"),
      Tm1ErrorClassifier.Rule.code("65", "NoReadRights"),
      Tm1ErrorClassifier.Rule.message("ObjectSecurityNoReadRights", "NoReadRights"),
      Tm1ErrorClassifier.Rule.message("can ?not be found|not found|does not exist|doesn't exist|ObjectNotFound", "NotFound"),
      Tm1ErrorClassifier.Rule.message("already exists", "AlreadyExists"),
      Tm1ErrorClassifier.Rule.message("\\blocked\\b", "Locked"),
      Tm1ErrorClassifier.Rule.message("\\bin use\\b|is used by|isbeingusedby|cannot be deleted", "InUse"),
    ]);
  }

  private constructor(private readonly rules: readonly Tm1ErrorClassifier.Rule[]) {}

  /** The rules this client knows about. */
  static defaults(): Tm1ErrorClassifier {
    return (Tm1ErrorClassifier.defaultRules ??= Tm1ErrorClassifier.makeDefaults());
  }

  /** This classifier with `rule` tried first. */
  withRule(rule: Tm1ErrorClassifier.Rule): Tm1ErrorClassifier {
    return new Tm1ErrorClassifier([rule, ...this.rules]);
  }

  /** Classifies an error response; `error` may be undefined if the body wasn't an OData error. The
   * first matching rule's kind, or else the kind for the status (`Other` if it has none). */
  classify(status: number, error: ODataError | undefined): Tm1ErrorKind {
    for (const rule of this.rules) if (rule.matches(status, error)) return rule.kind;
    switch (status) {
      case 401:
        return "Unauthenticated";
      case 403:
        return "Forbidden";
      case 404:
        return "NotFound";
      case 409:
        return "Conflict";
      case 412:
      case 428:
        return "PreconditionFailed";
      case 501:
        return "NotImplemented";
      default:
        return "Other";
    }
  }
}

/** The rule type and builder of {@link Tm1ErrorClassifier}, nested as in Java. */
export namespace Tm1ErrorClassifier {
  /** A rule: the status, code and message to match (any undefined matches anything; at least one is
   * needed), and the kind it means. */
  export class Rule {
    constructor(
      readonly status: number | undefined,
      readonly code: string | undefined,
      readonly messagePattern: RegExp | undefined,
      readonly kind: Tm1ErrorKind,
    ) {
      if (status === undefined && code === undefined && messagePattern === undefined) throw new TypeError("A rule needs at least one condition");
    }

    /** Matches TM1's message, case-insensitively. */
    static message(regex: string, kind: Tm1ErrorKind): Rule {
      return new Rule(undefined, undefined, new RegExp(regex, "i"), kind);
    }

    /** Matches TM1's error code. */
    static code(code: string, kind: Tm1ErrorKind): Rule {
      return new Rule(undefined, code, undefined, kind);
    }

    /** @internal */
    matches(status: number, error: ODataError | undefined): boolean {
      return (
        (this.status === undefined || this.status === status) &&
        (this.code === undefined || (error !== undefined && error.code === this.code)) &&
        (this.messagePattern === undefined || (error !== undefined && this.messagePattern.test(error.message)))
      );
    }
  }
}
