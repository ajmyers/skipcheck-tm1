// SPDX-License-Identifier: Apache-2.0

import type { Tm1Authenticator } from "./auth/index.js";
import type { Tm1ErrorClassifier } from "./errors/index.js";
import { AnnotationService } from "./services/annotation/AnnotationService.js";
import { AttributeService } from "./services/attribute/AttributeService.js";
import { CellService } from "./services/data/CellService.js";
import { CellsetService } from "./services/data/CellsetService.js";
import { ChangeSetService } from "./services/data/ChangeSetService.js";
import { ChoreService } from "./services/process/ChoreService.js";
import { ContentService } from "./services/content/ContentService.js";
import { CubeService } from "./services/cube/CubeService.js";
import { DimensionService } from "./services/dimension/DimensionService.js";
import { DrillthroughService } from "./services/data/DrillthroughService.js";
import { EdgeService } from "./services/dimension/EdgeService.js";
import { ElementService } from "./services/dimension/ElementService.js";
import { EncryptionService } from "./services/encryption/EncryptionService.js";
import { GitService } from "./services/git/GitService.js";
import { GroupService } from "./services/security/GroupService.js";
import { HierarchyService } from "./services/dimension/HierarchyService.js";
import { LevelService } from "./services/dimension/LevelService.js";
import { LocalizedAttributeService } from "./services/attribute/LocalizedAttributeService.js";
import { LogService } from "./services/server/LogService.js";
import { MemberService } from "./services/dimension/MemberService.js";
import { MemberSetService } from "./services/data/MemberSetService.js";
import { OidcKeyService } from "./services/security/OidcKeyService.js";
import { ProcessDebugService } from "./services/process/ProcessDebugService.js";
import { ProcessService } from "./services/process/ProcessService.js";
import { SandboxService } from "./services/data/SandboxService.js";
import { ServerService } from "./services/server/ServerService.js";
import { SessionService } from "./services/security/SessionService.js";
import { SqlDataSourceService } from "./services/process/SqlDataSourceService.js";
import { SubsetService } from "./services/dimension/SubsetService.js";
import { ThreadService } from "./services/security/ThreadService.js";
import { UserService } from "./services/security/UserService.js";
import { ViewService } from "./services/cube/ViewService.js";
import { Tm1Session, Tm1SessionBuilder, type Tm1CallOptions, type Tm1SessionBatch } from "./session/session.js";
import type { Tm1Timeouts } from "./session/timeouts.js";
import type { Tm1Transport } from "./session/transport.js";
import type { Tm1Dialect } from "./version/index.js";

/** The client for one TM1 server: one session, and every service built on it, as the Java client's
 * `Tm1Client`:
 * ```ts
 * await using tm1 = Tm1Client.builder().baseUrl("http://localhost:8010/api/v1/").basicAuth("admin", "apple").build();
 * for await (const cube of tm1.cubes.modelCubes(ODataQuery.none().select(Cube.Fields.Name))) {
 *   console.log(cube.Name);
 * }
 * ```
 * Run a long process asynchronously, so no request stays open for its whole run; aborting the
 * signal stops the process on TM1:
 * ```ts
 * await tm1.withAsync(2000).processes.executeWithReturn("Rebuild Forecast", undefined, undefined, { signal });
 * ```
 */
export class Tm1Client implements AsyncDisposable {
  /** The session, which sends any request (`Tm1Request`). */
  readonly session: Tm1Session;
  /** Dimensions. The service, on this client's session. */
  readonly dimensions: DimensionService;
  /** Hierarchies. The service, on this client's session. */
  readonly hierarchies: HierarchyService;
  /** Elements. The service, on this client's session. */
  readonly elements: ElementService;
  /** Edges. The service, on this client's session. */
  readonly edges: EdgeService;
  /** Levels. The service, on this client's session. */
  readonly levels: LevelService;
  /** Members. The service, on this client's session. */
  readonly members: MemberService;
  /** Subsets. The service, on this client's session. */
  readonly subsets: SubsetService;
  /** Attribute definitions. The service, on this client's session. */
  readonly attributes: AttributeService;
  /** Translated attribute values. The service, on this client's session. */
  readonly localizedAttributes: LocalizedAttributeService;
  /** Cubes. The service, on this client's session. */
  readonly cubes: CubeService;
  /** Views. The service, on this client's session. */
  readonly views: ViewService;
  /** Cellsets. The service, on this client's session. */
  readonly cellsets: CellsetService;
  /** Cell writes. The service, on this client's session. */
  readonly cells: CellService;
  /** Member sets. The service, on this client's session. */
  readonly memberSets: MemberSetService;
  /** Sandboxes. The service, on this client's session. */
  readonly sandboxes: SandboxService;
  /** Change sets. The service, on this client's session. */
  readonly changeSets: ChangeSetService;
  /** Drillthrough. The service, on this client's session. */
  readonly drillthrough: DrillthroughService;
  /** Processes. The service, on this client's session. */
  readonly processes: ProcessService;
  /** Process debugging. The service, on this client's session. */
  readonly processDebugging: ProcessDebugService;
  /** Chores. The service, on this client's session. */
  readonly chores: ChoreService;
  /** Users. The service, on this client's session. */
  readonly users: UserService;
  /** Groups. The service, on this client's session. */
  readonly groups: GroupService;
  /** Server sessions. The service, on this client's session. */
  readonly sessions: SessionService;
  /** Server threads. The service, on this client's session. */
  readonly threads: ThreadService;
  /** The server. The service, on this client's session. */
  readonly server: ServerService;
  /** The server's logs. The service, on this client's session. */
  readonly logs: LogService;
  /** The contents tree. The service, on this client's session. */
  readonly contents: ContentService;
  /** Annotations on cube cells. The service, on this client's session. */
  readonly annotations: AnnotationService;
  /** TM1 Git. The service, on this client's session. */
  readonly git: GitService;
  /** Encryption at rest. The service, on this client's session. */
  readonly encryption: EncryptionService;
  /** The cached OpenID Connect keys. The service, on this client's session. */
  readonly oidcKeys: OidcKeyService;
  /** The ODBC data sources on TM1's host. The service, on this client's session. */
  readonly sqlDataSources: SqlDataSourceService;

  private constructor(session: Tm1Session) {
    if (!(session instanceof Tm1Session)) throw new TypeError("session is a Tm1Session");
    this.session = session;
    this.dimensions = new DimensionService(session);
    this.hierarchies = new HierarchyService(session);
    this.elements = new ElementService(session);
    this.edges = new EdgeService(session);
    this.levels = new LevelService(session);
    this.members = new MemberService(session);
    this.subsets = new SubsetService(session);
    this.attributes = new AttributeService(session);
    this.localizedAttributes = new LocalizedAttributeService(session);
    this.cubes = new CubeService(session);
    this.views = new ViewService(session);
    this.cellsets = new CellsetService(session);
    this.cells = new CellService(session);
    this.memberSets = new MemberSetService(session);
    this.sandboxes = new SandboxService(session);
    this.changeSets = new ChangeSetService(session);
    this.drillthrough = new DrillthroughService(session);
    this.processes = new ProcessService(session);
    this.processDebugging = new ProcessDebugService(session);
    this.chores = new ChoreService(session);
    this.users = new UserService(session);
    this.groups = new GroupService(session);
    this.sessions = new SessionService(session);
    this.threads = new ThreadService(session);
    this.server = new ServerService(session);
    this.logs = new LogService(session);
    this.contents = new ContentService(session);
    this.annotations = new AnnotationService(session);
    this.git = new GitService(session);
    this.encryption = new EncryptionService(session);
    this.oidcKeys = new OidcKeyService(session);
    this.sqlDataSources = new SqlDataSourceService(session);
  }

  /** A builder for a client of one TM1 server. */
  static builder(): Tm1ClientBuilder {
    return new Tm1ClientBuilder();
  }

  /** A client over `session`, which it shares. */
  static of(session: Tm1Session): Tm1Client {
    return new Tm1Client(session);
  }

  /** A JSON `$batch`: calls through {@link Tm1Batch.client} are queued, and `run()` sends them together. */
  batch(): Tm1Batch {
    return new Tm1Batch(this.session.batch());
  }

  /** A client whose requests TM1 runs asynchronously, polled every `every` milliseconds. */
  withAsync(every: number): Tm1Client {
    return new Tm1Client(this.session.withAsync(every));
  }

  /** A client on a synchronous view of this client's session ({@link Tm1Session.withoutAsync}). */
  withoutAsync(): Tm1Client {
    return new Tm1Client(this.session.withoutAsync());
  }

  /** A client whose requests run in sandbox `name`. */
  withSandbox(name: string): Tm1Client {
    return new Tm1Client(this.session.withSandbox(name));
  }

  /** A client whose writes are recorded in change set `id`. */
  withChangeSet(id: string): Tm1Client {
    return new Tm1Client(this.session.withChangeSet(id));
  }

  /** Logs in now, rather than on the first request. */
  async connect(options?: Tm1CallOptions): Promise<Tm1Client> {
    await this.session.connect(options);
    return this;
  }

  /** Ends the session on the server. */
  close(): Promise<void> {
    return this.session.close();
  }

  /** Ends the session on the server, for `await using`: the same as {@link close}. */
  [Symbol.asyncDispose](): Promise<void> {
    return this.close();
  }

  /** The client and its service root, for logs. */
  toString(): string {
    return `Tm1Client[${this.session.serviceRoot()}]`;
  }
}

/** A JSON `$batch` of client calls, as the Java client's `Tm1Batch`: each call's promise settles
 * with its own result or error once the batch has run. */
export class Tm1Batch {
  /** The client whose calls join this batch. */
  readonly client: Tm1Client;

  /** @internal */
  constructor(private readonly batch: Tm1SessionBatch) {
    this.client = Tm1Client.of(batch.session);
  }

  /** How many requests have joined. */
  size(): number {
    return this.batch.size();
  }

  /** Sends the queued requests ({@link Tm1SessionBatch.run}), at most `perRequest` to a `$batch`
   * request. Resolves when every call has its result. */
  run(perRequest?: number, options?: Tm1CallOptions): Promise<void> {
    return this.batch.run(perRequest, options);
  }
}

/** The session's settings, as the Java client's `Tm1Client.Builder`; {@link build} makes the client. */
export class Tm1ClientBuilder {
  private readonly sessionBuilder = new Tm1SessionBuilder();

  /** The service root, such as `http://localhost:8010/api/v1/`. See {@link
   * Tm1SessionBuilder.baseUrl}. */
  baseUrl(baseUrl: string): this {
    this.sessionBuilder.baseUrl(baseUrl);
    return this;
  }

  /** The server's address, such as `http://tm1:8010`. See {@link Tm1SessionBuilder.address}. */
  address(address: string): this {
    this.sessionBuilder.address(address);
    return this;
  }

  /** The kind of deployment. See {@link Tm1SessionBuilder.dialect}. */
  dialect(dialect: Tm1Dialect): this {
    this.sessionBuilder.dialect(dialect);
    return this;
  }

  /** Logs in with TM1's own security, as `user` with `password`. See {@link
   * Tm1SessionBuilder.basicAuth}. */
  basicAuth(user: string, password?: string | null): this {
    this.sessionBuilder.basicAuth(user, password);
    return this;
  }

  /** Supplies the credentials for each login. See {@link Tm1SessionBuilder.authenticator}. */
  authenticator(authenticator: Tm1Authenticator): this {
    this.sessionBuilder.authenticator(authenticator);
    return this;
  }

  /** Logs in as the service account but acts as TM1 user `user`. See {@link
   * Tm1SessionBuilder.impersonate}. */
  impersonate(user: string): this {
    this.sessionBuilder.impersonate(user);
    return this;
  }

  /** The session context sent to TM1: a label it shows for the session. See {@link
   * Tm1SessionBuilder.sessionContext}. */
  sessionContext(sessionContext: string): this {
    this.sessionBuilder.sessionContext(sessionContext);
    return this;
  }

  /** The timeouts. See {@link Tm1SessionBuilder.timeouts}. */
  timeouts(timeouts: Tm1Timeouts): this {
    this.sessionBuilder.timeouts(timeouts);
    return this;
  }

  /** The classifier for error responses. See {@link Tm1SessionBuilder.errorClassifier}. */
  errorClassifier(classifier: Tm1ErrorClassifier): this {
    this.sessionBuilder.errorClassifier(classifier);
    return this;
  }

  /** What sends the requests (Java's `webClient` and `connector`): `fetchTransport()` by default. */
  transport(transport: Tm1Transport): this {
    this.sessionBuilder.transport(transport);
    return this;
  }

  /** Whether a request the server doesn't define is refused before it's sent. See {@link
   * Tm1SessionBuilder.checkCapabilities}. */
  checkCapabilities(check: boolean): this {
    this.sessionBuilder.checkCapabilities(check);
    return this;
  }

  /** The client. Nothing is sent to TM1 until the first request. */
  build(): Tm1Client {
    return Tm1Client.of(this.sessionBuilder.build());
  }
}
