const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** How TM1 signs in to the Git remote: nothing (a public or `git://` remote), a user name and
 * password or token (HTTPS), or an SSH key pair. TM1 doesn't keep them, so every call that reaches
 * the remote takes them again.
 *
 * The secrets stay out of what's logged: {@link toString}, `JSON.stringify` and Node's
 * `util.inspect` (`console.log`) show which credentials these are, not the password, keys or
 * passphrase. They're held in private fields, read through the getters. */
export class GitCredentials {
  readonly #username: string | null;
  readonly #password: string | null;
  readonly #publicKey: string | null;
  readonly #privateKey: string | null;
  readonly #passphrase: string | null;

  constructor(
    username: string | null | undefined,
    password: string | null | undefined,
    publicKey: string | null | undefined,
    privateKey: string | null | undefined,
    passphrase: string | null | undefined,
  ) {
    this.#username = username ?? null;
    this.#password = password ?? null;
    this.#publicKey = publicKey ?? null;
    this.#privateKey = privateKey ?? null;
    this.#passphrase = passphrase ?? null;
    Object.freeze(this);
  }

  /** No credentials. */
  static none(): GitCredentials {
    return new GitCredentials(null, null, null, null, null);
  }

  /** A user name and password, or access token, for an HTTPS remote. */
  static password(username: string, password: string): GitCredentials {
    return new GitCredentials(username, password, null, null, null);
  }

  /** An SSH key pair, for an SSH remote, and the private key's passphrase, if it has one. */
  static sshKey(publicKey: string, privateKey: string, passphrase?: string | null): GitCredentials {
    return new GitCredentials(null, null, publicKey, privateKey, passphrase);
  }

  /** The user name, for HTTPS. */
  get username(): string | null {
    return this.#username;
  }

  /** The password or access token, for HTTPS. */
  get password(): string | null {
    return this.#password;
  }

  /** The SSH public key. */
  get publicKey(): string | null {
    return this.#publicKey;
  }

  /** The SSH private key. */
  get privateKey(): string | null {
    return this.#privateKey;
  }

  /** The private key's passphrase. */
  get passphrase(): string | null {
    return this.#passphrase;
  }

  /** @internal The parameters TM1 takes them as, those set only. */
  addTo(parameters: Record<string, unknown>): void {
    putIfSet(parameters, "Username", this.#username);
    putIfSet(parameters, "Password", this.#password);
    putIfSet(parameters, "PublicKey", this.#publicKey);
    putIfSet(parameters, "PrivateKey", this.#privateKey);
    putIfSet(parameters, "Passphrase", this.#passphrase);
  }

  equals(other: unknown): boolean {
    return (
      other instanceof GitCredentials &&
      other.#username === this.#username &&
      other.#password === this.#password &&
      other.#publicKey === this.#publicKey &&
      other.#privateKey === this.#privateKey &&
      other.#passphrase === this.#passphrase
    );
  }

  /** Which credentials these are, without the secrets. */
  toString(): string {
    return `GitCredentials[username=${this.#username}, password=${this.#password === null ? "none" : "***"}, sshKey=${this.#privateKey === null ? "none" : "***"}]`;
  }

  /** What `JSON.stringify` writes: {@link toString}, without the secrets. */
  toJSON(): string {
    return this.toString();
  }

  /** What Node's `util.inspect` (and so `console.log`) shows: {@link toString}. */
  [INSPECT](): string {
    return this.toString();
  }
}

/** @internal Sets `name` only if there's a value. */
export function putIfSet(parameters: Record<string, unknown>, name: string, value: unknown): void {
  if (value !== null && value !== undefined) parameters[name] = value;
}
