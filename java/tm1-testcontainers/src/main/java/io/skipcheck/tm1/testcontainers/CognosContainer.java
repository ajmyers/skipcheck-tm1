package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

/**
 * IBM Cognos Analytics in Docker, for tests, built on the {@code cognos-server} image:
 * the security (CAM) a TM1 server in CAM mode takes its users from.
 *
 * <p>
 * Give it test users, and the container builds an LDAP directory with them and a Cognos
 * namespace ({@value #NAMESPACE}) for it. Then run a {@link Tm1ServerContainer} against
 * it with {@link Tm1ServerContainer#withCamSecurity}, and log in to TM1 as each test
 * user:
 *
 * {@snippet class = examples.CognosContainerExamples region = cam-security}
 *
 * <p>
 * Cognos is heavy: it takes about two minutes to start and uses about 8 GB of memory, so
 * Docker needs at least 12 GB. Every user is a Cognos administrator (a new content store
 * puts Everyone there); TM1's own security comes from the groups. The image contains no
 * IBM software, so every container needs a {@link CognosSource}.
 */
public class CognosContainer extends GenericContainer<CognosContainer> {

	/** The {@code cognos-server} image, without a tag. */
	public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("ghcr.io/ajmyers/cognos-server");

	/**
	 * Overrides the {@code cognos-server} image (not used with
	 * {@link CognosSource.PrivateImage}), e.g. {@code cognos-server:local} for a local
	 * build. Read as a system property, then an environment variable.
	 */
	public static final String SERVER_IMAGE = "COGNOS_SERVER_IMAGE";

	/** The Cognos dispatcher's port inside the container. */
	public static final int DISPATCHER_PORT = 9300;

	/**
	 * The container's host name and network alias, which Cognos's dispatcher URIs name.
	 * Other containers on its network reach it at {@code http://cognos:9300}.
	 */
	public static final String NETWORK_ALIAS = "cognos";

	/** The ID of the LDAP namespace the test users are in: the {@code CAMNamespace}. */
	public static final String NAMESPACE = "LDAP";

	/**
	 * A namespace the test users are in, and how {@code tm1-server} names its users and
	 * groups (CAMIDs) in it.
	 */
	public enum Namespace {

		/** The LDAP directory, always there with test users. */
		LDAP("LDAP", "ldap"),

		/** OpenID Connect through Keycloak, with {@link #withKeycloak}. */
		OIDC("OIDC", "oidc");

		private final String id;

		private final String type;

		Namespace(String id, String type) {
			this.id = id;
			this.type = type;
		}

		/**
		 * The namespace's ID: the {@code CAMNamespace} users log on with.
		 * @return the ID
		 */
		public String id() {
			return id;
		}

		/** The image's {@code TM1_CAM_NAMESPACE_TYPE}. */
		String type() {
			return type;
		}

	}

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

	private static final String INSTALL_PATH = "/opt/ibm/cognos/analytics";

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(15);

	private final CognosSource source;

	private final List<Bind> binds = new ArrayList<>();

	private final Map<String, User> users = new LinkedHashMap<>();

	private @Nullable KeycloakContainer keycloak;

	/**
	 * A test user.
	 *
	 * @param name the user name, which users log on with
	 * @param password the password
	 * @param groups the Cognos groups the user is in
	 */
	public record User(String name, String password, List<String> groups) {
		/** Copies the groups. */
		public User {
			groups = List.copyOf(groups);
		}
	}

	/**
	 * A container on the default {@code cognos-server} image (or
	 * {@code COGNOS_SERVER_IMAGE}), getting Cognos from {@code source}.
	 * @param source where Cognos comes from
	 */
	public CognosContainer(CognosSource source) {
		this(defaultImage(source), source);
	}

	/**
	 * A container on {@code image}, which must be {@code cognos-server} or built on it.
	 * @param image the image to run
	 * @param source where Cognos comes from
	 */
	@SuppressWarnings("this-escape") // configuring in the constructor is the
										// Testcontainers pattern
	public CognosContainer(DockerImageName image, CognosSource source) {
		super(image);
		this.source = Objects.requireNonNull(source, "source");
		withExposedPorts(DISPATCHER_PORT);
		withNetworkAliases(NETWORK_ALIAS);
		withEnv("COGNOS_HOST", NETWORK_ALIAS);
		// Cognos for Linux is x86-64 only. On Apple Silicon this runs under Rosetta.
		withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64").withHostName(NETWORK_ALIAS));
		withCreateContainerCmdModifier(cmd -> {
			HostConfig host = Objects.requireNonNull(cmd.getHostConfig(), "hostConfig");
			List<Bind> all = new ArrayList<>(host.getBinds() == null ? List.of() : Arrays.asList(host.getBinds()));
			all.addAll(binds);
			host.withBinds(all);
		});
		// The image's healthcheck: 200 (anonymous session) or 441 (logon prompt, with
		// users)
		waitingFor(Wait.forHttp("/bi/v1/login")
			.forPort(DISPATCHER_PORT)
			.forStatusCodeMatching(code -> code == 200 || code == 441)
			.withStartupTimeout(STARTUP_TIMEOUT));
		applySource();
	}

	/**
	 * Adds a test user. With at least one user, logons are required (anonymous access is
	 * off).
	 * @param name the user name: letters, digits, {@code .}, {@code _} and {@code -}
	 * @param password the password; no {@code :} or whitespace
	 * @param groups the Cognos groups the user is in, created as needed; same rules as
	 * names
	 * @return this container
	 * @throws IllegalArgumentException if a name or the password isn't allowed, or the
	 * user was already added
	 */
	public CognosContainer withUser(String name, String password, String... groups) {
		requireName(name, "user name");
		Objects.requireNonNull(password, "password");
		if (password.isEmpty() || password.contains(":") || password.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException(
					"The password for " + name + " must be non-empty, without ':' or spaces");
		}
		for (String group : groups) {
			requireName(group, "group name");
		}
		if (users.putIfAbsent(name, new User(name, password, List.of(groups))) != null) {
			throw new IllegalArgumentException("User " + name + " was already added");
		}
		return self();
	}

	/**
	 * Adds an OIDC namespace ({@link Namespace#OIDC}) through {@code keycloak}: when
	 * Cognos starts, it builds a realm there from the test users. The two containers
	 * share a Docker network, and Keycloak starts first.
	 * @param keycloak the Keycloak to use
	 * @return this container
	 */
	public CognosContainer withKeycloak(KeycloakContainer keycloak) {
		this.keycloak = Objects.requireNonNull(keycloak, "keycloak");
		Tm1ServerContainer.shareNetwork(this, keycloak);
		dependsOn(keycloak);
		withEnv("COGNOS_KEYCLOAK_URL", keycloak.getNetworkUrl());
		return self();
	}

	/**
	 * The Keycloak set with {@link #withKeycloak}.
	 * @return the Keycloak, or {@code null} if there's no OIDC namespace
	 */
	public @Nullable KeycloakContainer getKeycloak() {
		return keycloak;
	}

	/**
	 * The test users, in the order they were added.
	 * @return the users
	 */
	public List<User> getUsers() {
		return List.copyOf(users.values());
	}

	/**
	 * A test user by name.
	 * @param name the user name
	 * @return the user
	 * @throws IllegalArgumentException if there's no such user
	 */
	public User getUser(String name) {
		User user = users.get(name);
		if (user == null) {
			throw new IllegalArgumentException("No test user " + name + "; add it with withUser");
		}
		return user;
	}

	/**
	 * Every group of the test users, in order of first appearance.
	 * @return the group names
	 */
	public Set<String> getGroups() {
		return users.values()
			.stream()
			.flatMap(user -> user.groups().stream())
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * Where Cognos comes from.
	 * @return the source
	 */
	public CognosSource getSource() {
		return source;
	}

	/**
	 * Cognos as seen from the host, e.g. {@code http://localhost:54321/bi/}.
	 * @return the URL, ending in {@code /}
	 */
	public String getUrl() {
		return "http://" + getHost() + ":" + getMappedPort(DISPATCHER_PORT) + "/bi/";
	}

	/**
	 * The dispatcher's address on the container's Docker network,
	 * {@code http://cognos:9300}: what a TM1 server there is pointed at.
	 * @return the in-network address
	 */
	public String getNetworkUrl() {
		return "http://" + NETWORK_ALIAS + ":" + DISPATCHER_PORT;
	}

	/**
	 * The {@code Authorization} header for a test user's CAM logon to TM1 (or Cognos):
	 * {@code CAMNamespace} and the Base64 of {@code user:password:namespace}.
	 * @param user a test user's name
	 * @return the header value
	 * @throws IllegalArgumentException if there's no such user
	 */
	public String camAuthorization(String user) {
		return camAuthorization(user, Namespace.LDAP);
	}

	/**
	 * The {@code Authorization} header for a test user's CAM logon through a namespace.
	 * Through {@link Namespace#OIDC}, Cognos checks the password with Keycloak.
	 * @param user a test user's name
	 * @param namespace the namespace to log on through
	 * @return the header value
	 * @throws IllegalArgumentException if there's no such user
	 */
	public String camAuthorization(String user, Namespace namespace) {
		return camAuthorization(user, getUser(user).password(), namespace.id());
	}

	/**
	 * Logs a user on to Cognos with an ID token they already hold (the OIDC namespace's
	 * {@code CAMOIDCIdToken}), and returns their CAM passport. TM1 in CAM security takes
	 * it as {@code Authorization: CAMPassport <passport>}.
	 * @param idToken an ID token from the OIDC provider, e.g.
	 * {@link KeycloakContainer#idToken}
	 * @return the passport
	 * @throws IllegalStateException if Cognos refuses the token
	 */
	public String passport(String idToken) {
		String body = "{\"parameters\":[{\"name\":\"CAMNamespace\",\"value\":\"" + Namespace.OIDC.id()
				+ "\"},{\"name\":\"CAMOIDCIdToken\",\"value\":\"" + idToken + "\"}]}";
		HttpRequest request = HttpRequest
			.newBuilder(URI.create("http://" + getHost() + ":" + getMappedPort(DISPATCHER_PORT) + "/api/v1/session"))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(60))
			.PUT(HttpRequest.BodyPublishers.ofString(body))
			.build();
		try {
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			return response.headers()
				.allValues("Set-Cookie")
				.stream()
				.map(cookie -> cookie.split(";", 2)[0].strip())
				.filter(cookie -> cookie.startsWith("cam_passport="))
				.map(cookie -> cookie.substring("cam_passport=".length()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Cognos refused the ID token (HTTP " + response.statusCode() + "): " + response.body()));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The {@code Authorization} header for a CAM logon.
	 * @param user the user name
	 * @param password the password
	 * @param namespace the Cognos namespace ID
	 * @return {@code CAMNamespace} and the Base64 of {@code user:password:namespace}
	 */
	public static String camAuthorization(String user, String password, String namespace) {
		return "CAMNamespace " + Base64.getEncoder()
			.encodeToString((user + ":" + password + ":" + namespace).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The test users in the image's {@code COGNOS_USERS} format.
	 * @return {@code user:password[:group,group]} entries, separated by spaces
	 */
	String usersSpec() {
		return users.values()
			.stream()
			.map(user -> user.name() + ":" + user.password()
					+ (user.groups().isEmpty() ? "" : ":" + String.join(",", user.groups())))
			.collect(Collectors.joining(" "));
	}

	@Override
	protected void configure() {
		if (!users.isEmpty()) {
			withEnv("COGNOS_USERS", usersSpec());
		}
	}

	@Override
	protected void containerIsStarted(InspectContainerResponse info) {
		logger().info("Cognos is up at {} ({}), with {} test user(s) in namespace {}", getUrl(), source.describe(),
				users.size(), NAMESPACE);
	}

	// An if-chain rather than a pattern switch: the libraries target Java 17 (see #8)
	private void applySource() {
		if (source instanceof CognosSource.InstallVolume volume) {
			binds.add(new Bind(volume.name(), new Volume(INSTALL_PATH)));
		}
		else if (source instanceof CognosSource.Zip zip) {
			binds.add(new Bind(zip.dir().toString(), new Volume("/ibm"), AccessMode.ro));
			if (zip.file() != null) {
				withEnv("COGNOS_ZIP", zip.file());
			}
		}
		else if (source instanceof CognosSource.ZipUrl url) {
			withEnv("COGNOS_ZIP_URL", url.url().toString());
			if (url.sha256() != null) {
				withEnv("COGNOS_ZIP_SHA256", url.sha256());
			}
		}
		// CognosSource.PrivateImage: Cognos is already in the image
	}

	private static void requireName(String name, String what) {
		if (name == null || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException(
					"Bad " + what + " '" + name + "': use letters, digits, '.', '_' and '-'");
		}
	}

	private static DockerImageName defaultImage(CognosSource source) {
		if (source instanceof CognosSource.PrivateImage image) {
			return DockerImageName.parse(image.image()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		String override = System.getProperty(SERVER_IMAGE, System.getenv(SERVER_IMAGE));
		if (override != null && !override.isBlank()) {
			return DockerImageName.parse(override.strip()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		return DEFAULT_IMAGE.withTag(Tm1ServerContainer.imageTag(Tm1ServerContainer.libraryVersion()));
	}

}
