package net.ajmyers.tm1.client;

import java.time.Duration;
import java.util.Objects;

import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.client.error.Tm1ErrorClassifier;
import net.ajmyers.tm1.client.service.annotation.AnnotationService;
import net.ajmyers.tm1.client.service.attribute.AttributeService;
import net.ajmyers.tm1.client.service.attribute.LocalizedAttributeService;
import net.ajmyers.tm1.client.service.content.ContentService;
import net.ajmyers.tm1.client.service.cube.CubeService;
import net.ajmyers.tm1.client.service.cube.ViewService;
import net.ajmyers.tm1.client.service.data.CellService;
import net.ajmyers.tm1.client.service.data.CellsetService;
import net.ajmyers.tm1.client.service.data.ChangeSetService;
import net.ajmyers.tm1.client.service.data.DrillthroughService;
import net.ajmyers.tm1.client.service.data.MemberSetService;
import net.ajmyers.tm1.client.service.data.SandboxService;
import net.ajmyers.tm1.client.service.dimension.DimensionService;
import net.ajmyers.tm1.client.service.dimension.EdgeService;
import net.ajmyers.tm1.client.service.dimension.ElementService;
import net.ajmyers.tm1.client.service.dimension.HierarchyService;
import net.ajmyers.tm1.client.service.dimension.LevelService;
import net.ajmyers.tm1.client.service.dimension.MemberService;
import net.ajmyers.tm1.client.service.dimension.SubsetService;
import net.ajmyers.tm1.client.service.encryption.EncryptionService;
import net.ajmyers.tm1.client.service.git.GitService;
import net.ajmyers.tm1.client.service.process.ChoreService;
import net.ajmyers.tm1.client.service.process.ProcessDebugService;
import net.ajmyers.tm1.client.service.process.ProcessService;
import net.ajmyers.tm1.client.service.process.SqlDataSourceService;
import net.ajmyers.tm1.client.service.security.GroupService;
import net.ajmyers.tm1.client.service.security.OidcKeyService;
import net.ajmyers.tm1.client.service.security.SessionService;
import net.ajmyers.tm1.client.service.security.ThreadService;
import net.ajmyers.tm1.client.service.security.UserService;
import net.ajmyers.tm1.client.service.server.LogService;
import net.ajmyers.tm1.client.service.server.ServerService;
import net.ajmyers.tm1.client.session.Tm1Session;
import net.ajmyers.tm1.client.session.Tm1Timeouts;
import net.ajmyers.tm1.client.version.Tm1Dialect;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * The client for one TM1 server: one {@link Tm1Session}, and every service built on it
 * (plan §6), such as {@link #dimensions()}. Services arrive with the endpoint phases;
 * {@link #session()} sends any request, for what has no service yet.
 *
 * {@snippet class = examples.ClientExamples region = connect}
 *
 * <p>
 * Run a long process asynchronously, so no request stays open for its whole run:
 * {@snippet class = examples.ClientExamples region = async}
 */
public final class Tm1Client {

	private final Tm1Session session;

	private final DimensionService dimensions;

	private final HierarchyService hierarchies;

	private final ElementService elements;

	private final EdgeService edges;

	private final LevelService levels;

	private final MemberService members;

	private final SubsetService subsets;

	private final AttributeService attributes;

	private final LocalizedAttributeService localizedAttributes;

	private final CubeService cubes;

	private final ViewService views;

	private final CellsetService cellsets;

	private final CellService cells;

	private final MemberSetService memberSets;

	private final SandboxService sandboxes;

	private final ChangeSetService changeSets;

	private final DrillthroughService drillthrough;

	private final ProcessService processes;

	private final ProcessDebugService processDebugging;

	private final ChoreService chores;

	private final UserService users;

	private final GroupService groups;

	private final SessionService sessions;

	private final ThreadService threads;

	private final ServerService server;

	private final LogService logs;

	private final ContentService contents;

	private final AnnotationService annotations;

	private final GitService git;

	private final EncryptionService encryption;

	private final OidcKeyService oidcKeys;

	private final SqlDataSourceService sqlDataSources;

	private Tm1Client(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
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

	/**
	 * A builder for a client of one TM1 server.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * A client over an existing session, such as one from a session pool or a Spring
	 * bean.
	 * @param session the session to send requests on
	 * @return a client over {@code session}
	 */
	public static Tm1Client of(Tm1Session session) {
		return new Tm1Client(session);
	}

	/**
	 * The session every service uses.
	 * @return the session
	 */
	public Tm1Session session() {
		return session;
	}

	/**
	 * Dimensions.
	 * @return the dimension service, on this client's session
	 */
	public DimensionService dimensions() {
		return dimensions;
	}

	/**
	 * Hierarchies.
	 * @return the hierarchie service, on this client's session
	 */
	public HierarchyService hierarchies() {
		return hierarchies;
	}

	/**
	 * Elements.
	 * @return the element service, on this client's session
	 */
	public ElementService elements() {
		return elements;
	}

	/**
	 * Edges.
	 * @return the edge service, on this client's session
	 */
	public EdgeService edges() {
		return edges;
	}

	/**
	 * Levels.
	 * @return the level service, on this client's session
	 */
	public LevelService levels() {
		return levels;
	}

	/**
	 * Members.
	 * @return the member service, on this client's session
	 */
	public MemberService members() {
		return members;
	}

	/**
	 * Subsets.
	 * @return the subset service, on this client's session
	 */
	public SubsetService subsets() {
		return subsets;
	}

	/**
	 * Attribute definitions.
	 * @return the attribute service, on this client's session
	 */
	public AttributeService attributes() {
		return attributes;
	}

	/**
	 * Translated attribute values.
	 * @return the localized attribute service, on this client's session
	 */
	public LocalizedAttributeService localizedAttributes() {
		return localizedAttributes;
	}

	/**
	 * Cubes.
	 * @return the cube service, on this client's session
	 */
	public CubeService cubes() {
		return cubes;
	}

	/**
	 * Views.
	 * @return the view service, on this client's session
	 */
	public ViewService views() {
		return views;
	}

	/**
	 * Cellsets.
	 * @return the cellset service, on this client's session
	 */
	public CellsetService cellsets() {
		return cellsets;
	}

	/**
	 * Cell writes.
	 * @return the cell service, on this client's session
	 */
	public CellService cells() {
		return cells;
	}

	/**
	 * Member sets.
	 * @return the member set service, on this client's session
	 */
	public MemberSetService memberSets() {
		return memberSets;
	}

	/**
	 * Sandboxes.
	 * @return the sandboxe service, on this client's session
	 */
	public SandboxService sandboxes() {
		return sandboxes;
	}

	/**
	 * Change sets.
	 * @return the change set service, on this client's session
	 */
	public ChangeSetService changeSets() {
		return changeSets;
	}

	/**
	 * Drillthrough.
	 * @return the drillthrough service, on this client's session
	 */
	public DrillthroughService drillthrough() {
		return drillthrough;
	}

	/**
	 * Processes.
	 * @return the processe service, on this client's session
	 */
	public ProcessService processes() {
		return processes;
	}

	/**
	 * Process debugging.
	 * @return the process debug service, on this client's session
	 */
	public ProcessDebugService processDebugging() {
		return processDebugging;
	}

	/**
	 * Chores.
	 * @return the chore service, on this client's session
	 */
	public ChoreService chores() {
		return chores;
	}

	/**
	 * Users.
	 * @return the user service, on this client's session
	 */
	public UserService users() {
		return users;
	}

	/**
	 * Groups.
	 * @return the group service, on this client's session
	 */
	public GroupService groups() {
		return groups;
	}

	/**
	 * Server sessions.
	 * @return the session service, on this client's session
	 */
	public SessionService sessions() {
		return sessions;
	}

	/**
	 * Server threads.
	 * @return the thread service, on this client's session
	 */
	public ThreadService threads() {
		return threads;
	}

	/**
	 * The server.
	 * @return the server service, on this client's session
	 */
	public ServerService server() {
		return server;
	}

	/**
	 * The server's logs.
	 * @return the the server's log service, on this client's session
	 */
	public LogService logs() {
		return logs;
	}

	/**
	 * The contents tree.
	 * @return the the contents tree service, on this client's session
	 */
	public ContentService contents() {
		return contents;
	}

	/**
	 * Annotations on cube cells.
	 * @return the annotations on cube cell service, on this client's session
	 */
	public AnnotationService annotations() {
		return annotations;
	}

	/**
	 * TM1 Git.
	 * @return the tm1 git service, on this client's session
	 */
	public GitService git() {
		return git;
	}

	/**
	 * Encryption at rest.
	 * @return the encryption at rest service, on this client's session
	 */
	public EncryptionService encryption() {
		return encryption;
	}

	/**
	 * The cached OpenID Connect keys.
	 * @return the the cached openid connect key service, on this client's session
	 */
	public OidcKeyService oidcKeys() {
		return oidcKeys;
	}

	/**
	 * The ODBC data sources on TM1's host.
	 * @return the the odbc data sources on tm1's host service, on this client's session
	 */
	public SqlDataSourceService sqlDataSources() {
		return sqlDataSources;
	}

	/**
	 * A client on an async view of this client's session ({@link Tm1Session#withAsync}):
	 * every service's methods work as usual, but TM1 runs each request asynchronously and
	 * the client polls for the result, so a long process or chore holds no request open.
	 * Cancelling a subscription before its result stops the work on TM1.
	 * @param every how long to wait between polls
	 * @return the client
	 */
	public Tm1Client withAsync(Duration every) {
		return new Tm1Client(session.withAsync(every));
	}

	/**
	 * A client on a synchronous view of this client's session
	 * ({@link Tm1Session#withoutAsync}).
	 * @return the client
	 */
	public Tm1Client withoutAsync() {
		return new Tm1Client(session.withoutAsync());
	}

	/**
	 * A client whose services all run in sandbox {@code name}, sharing this client's
	 * session.
	 * @param name the sandbox's name
	 * @return a new client; this one is unchanged
	 */
	public Tm1Client withSandbox(String name) {
		return new Tm1Client(session.withSandbox(name));
	}

	/**
	 * A client on a view of this client's session whose writes are recorded in change set
	 * {@code id} ({@link Tm1Session#withChangeSet}); {@code ChangeSetService.within}
	 * makes one for the length of some work.
	 * @param id the change set's ID
	 * @return the client
	 */
	public Tm1Client withChangeSet(String id) {
		return new Tm1Client(session.withChangeSet(id));
	}

	/**
	 * Logs in now rather than on the first request, and completes with this client.
	 * @return a {@link Mono} emitting this client once logged in
	 */
	public Mono<Tm1Client> connect() {
		return session.connect().thenReturn(this);
	}

	/**
	 * Closes the session; see {@link Tm1Session#close()}.
	 * @return a {@link Mono} completing when the session is closed
	 */
	public Mono<Void> close() {
		return session.close();
	}

	@Override
	public String toString() {
		return "Tm1Client[" + session + "]";
	}

	/**
	 * Builds a {@link Tm1Client}: the same settings as {@link Tm1Session.Builder}, which
	 * it delegates to.
	 */
	public static final class Builder {

		private final Tm1Session.Builder session = Tm1Session.builder();

		private Builder() {
		}

		/**
		 * See {@link Tm1Session.Builder#baseUrl}.
		 * @param baseUrl the service root, such as {@code http://localhost:8010/api/v1/}
		 * @return this builder
		 */
		public Builder baseUrl(String baseUrl) {
			session.baseUrl(baseUrl);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#address}.
		 * @param address the server's address, such as {@code http://tm1:8010}
		 * @return this builder
		 */
		public Builder address(String address) {
			session.address(address);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#dialect}.
		 * @param dialect the kind of deployment
		 * @return this builder
		 */
		public Builder dialect(Tm1Dialect dialect) {
			session.dialect(dialect);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#basicAuth}.
		 * @param user the user name
		 * @param password the password
		 * @return this builder
		 */
		public Builder basicAuth(String user, String password) {
			session.basicAuth(user, password);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#authenticator}.
		 * @param authenticator supplies the credentials for each login
		 * @return this builder
		 */
		public Builder authenticator(Tm1Authenticator authenticator) {
			session.authenticator(authenticator);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#impersonate}.
		 * @param user the TM1 user to act as
		 * @return this builder
		 */
		public Builder impersonate(String user) {
			session.impersonate(user);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#sessionContext}.
		 * @param sessionContext the session context sent to TM1
		 * @return this builder
		 */
		public Builder sessionContext(String sessionContext) {
			session.sessionContext(sessionContext);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#timeouts}.
		 * @param timeouts the timeouts
		 * @return this builder
		 */
		public Builder timeouts(Tm1Timeouts timeouts) {
			session.timeouts(timeouts);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#errorClassifier}.
		 * @param classifier the classifier for error responses
		 * @return this builder
		 */
		public Builder errorClassifier(Tm1ErrorClassifier classifier) {
			session.errorClassifier(classifier);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#jsonMapper}.
		 * @param mapper the JSON mapper
		 * @return this builder
		 */
		public Builder jsonMapper(ObjectMapper mapper) {
			session.jsonMapper(mapper);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#observationRegistry}.
		 * @param observations the registry to record observations in
		 * @return this builder
		 */
		public Builder observationRegistry(ObservationRegistry observations) {
			session.observationRegistry(observations);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#webClient}.
		 * @param webClient the builder to build the {@link WebClient} from
		 * @return this builder
		 */
		public Builder webClient(WebClient.Builder webClient) {
			session.webClient(webClient);
			return this;
		}

		/**
		 * See {@link Tm1Session.Builder#connector}.
		 * @param connector the HTTP connector
		 * @return this builder
		 */
		public Builder connector(ClientHttpConnector connector) {
			session.connector(connector);
			return this;
		}

		/**
		 * The client. Nothing is sent to TM1 until the first request.
		 * @return the client
		 */
		public Tm1Client build() {
			return new Tm1Client(session.build());
		}

	}

}
