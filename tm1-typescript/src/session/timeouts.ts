function positive(value: number, name: string): void {
  if (!(value > 0) || !Number.isFinite(value)) throw new RangeError(`${name} must be positive: ${value}`);
}

/** How long a session waits, in milliseconds, as the Java client's `Tm1Timeouts`: to connect, for a
 * login, for a request's answer, for the close at the end, and how long a refused login is
 * remembered (requests meanwhile fail at once rather than trying the same credentials again; zero
 * to try every time). `fetch` has no connect timeout of its own: `connect` goes to the transport
 * (`Tm1HttpRequest.connectTimeout`) for one that can use it. */
export class Tm1Timeouts {
  static readonly DEFAULTS = new Tm1Timeouts(10_000, 30_000, 600_000, 5_000, 30_000);

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

  static defaults(): Tm1Timeouts {
    return Tm1Timeouts.DEFAULTS;
  }

  static builder(): Tm1Timeouts.Builder {
    return new Tm1Timeouts.Builder(Tm1Timeouts.DEFAULTS);
  }

  toBuilder(): Tm1Timeouts.Builder {
    return new Tm1Timeouts.Builder(this);
  }

  toString(): string {
    return `Tm1Timeouts[connect=${this.connect}ms, login=${this.login}ms, request=${this.request}ms, close=${this.close}ms, refusedLogin=${this.refusedLogin}ms]`;
  }
}

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
