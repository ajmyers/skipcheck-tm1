// SPDX-License-Identifier: Apache-2.0

function positive(value: number, name: string): void {
  if (!(value > 0) || !Number.isFinite(value)) throw new RangeError(`${name} timeout must be positive: ${value}`);
}

/** How long a session waits, in milliseconds, as the Java client's `Tm1Timeouts`: to connect, for a
 * login, for a request's answer, for the close at the end, and how long a refused login is
 * remembered (requests meanwhile fail at once rather than trying the same credentials again; zero
 * to try every time). The authenticator gets as long as a login again to supply the credentials
 * first, since it may ask another server, such as Cognos for a passport; if it doesn't, the request
 * fails with a `Tm1TransportException` in the connect phase, which is safe to retry. `fetch` has no
 * connect timeout of its own: `connect` goes to the transport (`Tm1HttpRequest.connectTimeout`) for
 * one that can use it. */
export class Tm1Timeouts {
  /** The defaults: 10 s to connect, 30 s for a login, 10 minutes for a request, 5 s for the close,
   * and a refused login remembered for 30 s. */
  static readonly DEFAULTS = new Tm1Timeouts(10_000, 30_000, 600_000, 5_000, 30_000);

  /** Timeouts in milliseconds. `connect`: for a connection, including TLS. `login`: for a login
   * response; short, because a login does no work. The authenticator gets as long again to supply
   * the credentials first. `request`: how long a request may go without receiving any data; long,
   * because TM1 answers a process or a big view only when it's done. It's an idle timeout, not a
   * limit on the whole response, so a large collection that keeps arriving isn't cut off (with a
   * transport that calls `Tm1HttpRequest.received`, as `fetchTransport` does). `close`: for the logout at the end. `refusedLogin`: how long
   * a refused login is remembered; zero to try every time.
   * @throws RangeError if one isn't positive, or `refusedLogin` is negative */
  constructor(
    readonly connect: number,
    readonly login: number,
    readonly request: number,
    readonly close: number,
    readonly refusedLogin: number,
  ) {
    positive(connect, "connect");
    positive(login, "login");
    positive(request, "request");
    positive(close, "close");
    if (!(refusedLogin >= 0)) throw new RangeError("refusedLogin must not be negative: " + refusedLogin);
  }

  /** The defaults ({@link DEFAULTS}). */
  static defaults(): Tm1Timeouts {
    return Tm1Timeouts.DEFAULTS;
  }

  /** A builder starting from the defaults. */
  static builder(): Tm1Timeouts.Builder {
    return new Tm1Timeouts.Builder(Tm1Timeouts.DEFAULTS);
  }

  /** A builder starting from these timeouts, to change some of them. */
  toBuilder(): Tm1Timeouts.Builder {
    return new Tm1Timeouts.Builder(this);
  }

  /** Whether `other` has the same timeouts (Java's record equality). */
  equals(other: unknown): boolean {
    return (
      other instanceof Tm1Timeouts &&
      other.connect === this.connect &&
      other.login === this.login &&
      other.request === this.request &&
      other.close === this.close &&
      other.refusedLogin === this.refusedLogin
    );
  }

  /** The timeouts, for logs. */
  toString(): string {
    return `Tm1Timeouts[connect=${this.connect}ms, login=${this.login}ms, request=${this.request}ms, close=${this.close}ms, refusedLogin=${this.refusedLogin}ms]`;
  }
}

/** The builder of {@link Tm1Timeouts}, nested as in Java. */
export namespace Tm1Timeouts {
  /** Java's `Tm1Timeouts.Builder`. */
  export class Builder {
    private connectMs: number;
    private loginMs: number;
    private requestMs: number;
    private closeMs: number;
    private refusedLoginMs: number;

    /** @internal */
    constructor(start: Tm1Timeouts) {
      this.connectMs = start.connect;
      this.loginMs = start.login;
      this.requestMs = start.request;
      this.closeMs = start.close;
      this.refusedLoginMs = start.refusedLogin;
    }

    connect(ms: number): this {
      this.connectMs = ms;
      return this;
    }

    login(ms: number): this {
      this.loginMs = ms;
      return this;
    }

    request(ms: number): this {
      this.requestMs = ms;
      return this;
    }

    close(ms: number): this {
      this.closeMs = ms;
      return this;
    }

    refusedLogin(ms: number): this {
      this.refusedLoginMs = ms;
      return this;
    }

    build(): Tm1Timeouts {
      return new Tm1Timeouts(this.connectMs, this.loginMs, this.requestMs, this.closeMs, this.refusedLoginMs);
    }
  }
}
