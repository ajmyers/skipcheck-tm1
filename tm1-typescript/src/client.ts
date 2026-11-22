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
  readonly dimensions: DimensionService;
  readonly hierarchies: HierarchyService;
  readonly elements: ElementService;
  readonly edges: EdgeService;
  readonly levels: LevelService;
  readonly members: MemberService;
  readonly subsets: SubsetService;
  readonly attributes: AttributeService;
  readonly localizedAttributes: LocalizedAttributeService;
  readonly cubes: CubeService;
  readonly views: ViewService;
  readonly cellsets: CellsetService;
  readonly cells: CellService;
  readonly memberSets: MemberSetService;
  readonly sandboxes: SandboxService;
  readonly changeSets: ChangeSetService;
  readonly drillthrough: DrillthroughService;
  readonly processes: ProcessService;
  readonly processDebugging: ProcessDebugService;
  readonly chores: ChoreService;
  readonly users: UserService;
  readonly groups: GroupService;
  readonly sessions: SessionService;
  readonly threads: ThreadService;
  readonly server: ServerService;
  readonly logs: LogService;
  readonly contents: ContentService;
  readonly annotations: AnnotationService;
  readonly git: GitService;
  readonly encryption: EncryptionService;
  readonly oidcKeys: OidcKeyService;
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

  [Symbol.asyncDispose](): Promise<void> {
    return this.close();
  }

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

  size(): number {
    return this.batch.size();
  }

  run(perRequest?: number, options?: Tm1CallOptions): Promise<void> {
    return this.batch.run(perRequest, options);
  }
}

/** The session's settings, as the Java client's `Tm1Client.Builder`; {@link build} makes the client. */
export class Tm1ClientBuilder {
  private readonly sessionBuilder = new Tm1SessionBuilder();

  baseUrl(baseUrl: string): this {
    this.sessionBuilder.baseUrl(baseUrl);
    return this;
  }

  address(address: string): this {
    this.sessionBuilder.address(address);
    return this;
  }

  dialect(dialect: Tm1Dialect): this {
    this.sessionBuilder.dialect(dialect);
    return this;
  }

  basicAuth(user: string, password?: string | null): this {
    this.sessionBuilder.basicAuth(user, password);
    return this;
  }

  authenticator(authenticator: Tm1Authenticator): this {
    this.sessionBuilder.authenticator(authenticator);
    return this;
  }

  impersonate(user: string): this {
    this.sessionBuilder.impersonate(user);
    return this;
  }

  sessionContext(sessionContext: string): this {
    this.sessionBuilder.sessionContext(sessionContext);
    return this;
  }

  timeouts(timeouts: Tm1Timeouts): this {
    this.sessionBuilder.timeouts(timeouts);
    return this;
  }

  errorClassifier(classifier: Tm1ErrorClassifier): this {
    this.sessionBuilder.errorClassifier(classifier);
    return this;
  }

  /** What sends the requests (Java's `webClient` and `connector`): `fetchTransport()` by default. */
  transport(transport: Tm1Transport): this {
    this.sessionBuilder.transport(transport);
    return this;
  }

  checkCapabilities(check: boolean): this {
    this.sessionBuilder.checkCapabilities(check);
    return this;
  }

  build(): Tm1Client {
    return Tm1Client.of(this.sessionBuilder.build());
  }
}
