// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import io.skipcheck.tm1.client.version.Tm1Version;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A TM1 v12 engine (Planning Analytics 3.x) in Docker, for tests: the {@code tm1-engine}
 * image with TM1 from a {@link Tm1EngineSource}, and the MongoDB it keeps its metadata
 * in, on a network of their own. For TM1 11, use {@link Tm1ServerContainer}.
 *
 * <p>
 * One engine holds <b>instances</b>, each holding <b>databases</b>. Most tests use the
 * one instance, {@code tm1}, and a database of their own: {@link RequiresTm1Engine} gives
 * each test class one. A container you make yourself has the databases you name with
 * {@link #withDatabase}, or one called {@code tm1}:
 *
 * {@snippet class = examples.Tm1EngineExamples region = container}
 *
 * <p>
 * TM1 v12 has no passwords of its own. A test logs in through the instance's application
 * ({@link Tm1Login}), which this container creates and keeps the credentials of.
 *
 * <p>
 * Starting takes about 40 seconds, and a database about 10 more to create
 * ({@link #createDatabase}). The image exists for x86-64 only, as TM1 does; on Apple
 * Silicon it runs under Rosetta.
 */
public class Tm1EngineContainer extends GenericContainer<Tm1EngineContainer> {

	/** The {@code tm1-engine} image, without a tag. */
	public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("ghcr.io/ajmyers/tm1-engine");

	/**
	 * Overrides the {@code tm1-engine} image (not used with
	 * {@link Tm1EngineSource.PrivateImage}), e.g. {@code tm1-engine:local} for a local
	 * build. Read as a system property, then an environment variable.
	 */
	public static final String ENGINE_IMAGE = "TM1_ENGINE_IMAGE";

	/** The MongoDB image the engine's datastore runs. */
	public static final DockerImageName DATASTORE_IMAGE = DockerImageName.parse("mongo:8.0");

	/**
	 * The port of TM1's gateway inside the container: the REST API, logins and
	 * management.
	 */
	public static final int PORT = 4444;

	/** The instance databases are in unless one is named. */
	public static final String DEFAULT_INSTANCE = "tm1";

	/** The database a container has when none is named. */
	public static final String DEFAULT_DATABASE = "tm1";

	/** The datastore's name on the engine's network. */
	public static final String DATASTORE_ALIAS = "tm1-datastore";

	/**
	 * The volume package installs are cached in, shared by every engine container on this
	 * Docker host. Only the first container to see a package installs it. Remove the
	 * volume to clear it.
	 */
	public static final String CACHE_VOLUME = "tm1-engine-testcontainers-cache";

	static final String INSTALL_PATH = "/opt/ibm/tm1-engine";

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	// The image's own rule: at least three characters, and nothing the commands or a URL
	// would need quoting for
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{2,}");

	private static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]+");

	// The datastore is this engine's alone and goes with it: nothing else is given the
	// password
	private static final String DATASTORE_USER = "root";

	private static final String DATASTORE_PASSWORD = "tm1datastore";

	private static @Nullable Tm1EngineContainer shared;

	private final Tm1EngineSource source;

	private final List<Bind> binds = new ArrayList<>();

	private final GenericContainer<?> datastore;

	// instance/database, in the order they were added
	private final Set<String> databases = new LinkedHashSet<>();

	private final Map<String, List<String>> users = new LinkedHashMap<>();

	private @Nullable JsonNode credentials;

	private @Nullable Tm1Version version;

	/**
	 * A container that gets TM1 from {@code source}, with the default image for it.
	 * @param source where TM1 comes from
	 */
	public Tm1EngineContainer(Tm1EngineSource source) {
		this(defaultImage(source), source);
	}

	/**
	 * A container from an image of your own, such as a locally built one.
	 * @param image the image to run
	 * @param source where TM1 comes from
	 */
	@SuppressWarnings("this-escape") // configuring in the constructor is the
										// Testcontainers pattern
	public Tm1EngineContainer(DockerImageName image, Tm1EngineSource source) {
		super(image);
		this.source = Objects.requireNonNull(source, "source");
		withLabel(Tm1ServerContainer.LIBRARY_LABEL, Tm1ServerContainer.LIBRARY);
		withExposedPorts(PORT);
		// TM1 only exists for x86-64. On Apple Silicon this runs under Rosetta.
		withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
		withCreateContainerCmdModifier(cmd -> {
			HostConfig host = Objects.requireNonNull(cmd.getHostConfig(), "hostConfig");
			List<Bind> all = new ArrayList<>(host.getBinds() == null ? List.of() : Arrays.asList(host.getBinds()));
			all.addAll(binds);
			host.withBinds(all);
		});
		// The gateway refuses a request without a login (401) once it's up. That isn't
		// ready
		// yet: containerIsStarted then waits for the image to finish setting TM1 up.
		// It stops waiting if the container exits, as it does when TM1 can't start.
		waitingFor(new HttpOrExitWaitStrategy("/", PORT, code -> code == 401 || code == 200, STARTUP_TIMEOUT));
		this.datastore = new GenericContainer<>(DATASTORE_IMAGE)
			.withLabel(Tm1ServerContainer.LIBRARY_LABEL, Tm1ServerContainer.LIBRARY)
			.withNetworkAliases(DATASTORE_ALIAS)
			.withEnv("MONGO_INITDB_ROOT_USERNAME", DATASTORE_USER)
			.withEnv("MONGO_INITDB_ROOT_PASSWORD", DATASTORE_PASSWORD)
			// Enough for TM1's metadata; MongoDB would otherwise take half the machine's
			// memory
			.withCommand("--wiredTigerCacheSizeGB", "0.5");
		withEnv("TM1_DATASTORE_URL",
				"mongodb://" + DATASTORE_USER + ":" + DATASTORE_PASSWORD + "@" + DATASTORE_ALIAS + ":27017");
		dependsOn(datastore);
		mountSource();
	}

	/**
	 * The engine most tests share: one per test JVM, started on first use with the source
	 * {@link Tm1EngineSource#discover()} finds, and stopped by Testcontainers when the
	 * JVM exits. It has one database, {@code tm1}; {@link RequiresTm1Engine} creates one
	 * more for each test class.
	 *
	 * <p>
	 * It isn't marked reusable: an engine and its datastore belong together, and a kept
	 * engine would keep every database tests left in it.
	 * @return the started shared engine
	 * @throws IllegalStateException if no source is configured
	 */
	public static synchronized Tm1EngineContainer shared() {
		if (shared == null) {
			Tm1EngineSource found = Tm1EngineSource.discover()
				.orElseThrow(() -> new IllegalStateException(RequiresTm1Engine.NO_SOURCE_MESSAGE));
			Tm1EngineContainer container = new Tm1EngineContainer(found);
			container.start();
			shared = container;
		}
		return shared;
	}

	/**
	 * Adds a database, in the instance {@code tm1}. Without any, the container has one
	 * called {@code tm1}.
	 * @param name the database's name: at least three letters, digits, {@code _} or
	 * {@code -}. {@code Sales} and {@code sales} are two databases
	 * @return this container
	 */
	public Tm1EngineContainer withDatabase(String name) {
		return withDatabase(DEFAULT_INSTANCE, name);
	}

	/**
	 * Adds a database in a named instance, which is created with an application of its
	 * own. An instance's users, sessions and applications are separate from another's.
	 * @param instance the instance's name
	 * @param name the database's name
	 * @return this container
	 */
	public Tm1EngineContainer withDatabase(String instance, String name) {
		databases.add(requireName(instance, "instance") + "/" + requireName(name, "database"));
		return self();
	}

	/**
	 * Adds a user to every database, in exactly these groups (created if missing). A new
	 * database has one user already: {@code admin}, in the {@code ADMIN} group.
	 * @param name the user's name
	 * @param groups the groups the user is in
	 * @return this container
	 */
	public Tm1EngineContainer withUser(String name, String... groups) {
		if (name == null || !USER.matcher(name).matches()) {
			throw new IllegalArgumentException("A user's name is letters, digits, '.', '_' and '-': " + name);
		}
		for (String group : groups) {
			if (group == null || !USER.matcher(group).matches()) {
				throw new IllegalArgumentException("A group's name is letters, digits, '.', '_' and '-': " + group);
			}
		}
		if (users.putIfAbsent(name, List.of(groups)) != null) {
			throw new IllegalArgumentException("The user " + name + " is already added");
		}
		return self();
	}

	/**
	 * Where TM1 comes from.
	 * @return the source
	 */
	public Tm1EngineSource getSource() {
		return source;
	}

	/**
	 * The MongoDB the engine keeps its metadata in, started and stopped with it.
	 * @return the datastore's container
	 */
	public GenericContainer<?> getDatastore() {
		return datastore;
	}

	/**
	 * The gateway as seen from the host, e.g. {@code http://localhost:54321/}.
	 * @return the gateway's root, ending in {@code /}
	 */
	public String getUrl() {
		return "http://" + getHost() + ":" + getMappedPort(PORT) + "/";
	}

	/**
	 * The management API, which lists and creates instances, applications and databases,
	 * e.g. {@code http://localhost:54321/manage/v1/}. It takes the root credentials as
	 * Basic authentication ({@link #getRootClientId()}, {@link #getRootClientSecret()}).
	 * @return the management API's root, ending in {@code /}
	 */
	public String getManagementUrl() {
		return getUrl() + "manage/v1/";
	}

	/**
	 * The client ID of the engine's root credentials, for the management API.
	 * @return the client ID
	 */
	public String getRootClientId() {
		return credentials().path("root").path("clientId").asString();
	}

	/**
	 * The client secret of the engine's root credentials.
	 * @return the client secret
	 */
	public String getRootClientSecret() {
		return credentials().path("root").path("clientSecret").asString();
	}

	/**
	 * The version of TM1 the engine runs. Only available once started.
	 * @return the version
	 * @throws IllegalStateException if the container isn't started yet
	 */
	public Tm1Version getTm1Version() {
		if (version == null) {
			throw new IllegalStateException("The container isn't started yet");
		}
		return version;
	}

	/**
	 * The databases the engine has now, in every instance.
	 * @return the databases
	 */
	public List<Tm1Database> databases() {
		// Read again: createDatabase and dropDatabase change them
		credentials = null;
		List<Tm1Database> found = new ArrayList<>();
		for (JsonNode instance : credentials().path("instances")) {
			for (JsonNode database : instance.path("databases")) {
				found.add(new Tm1Database(this, instance.path("name").asString(), database.path("name").asString()));
			}
		}
		return found;
	}

	/**
	 * A database of the instance {@code tm1}.
	 * @param name the database's name
	 * @return the database
	 * @throws IllegalArgumentException if there's no such database
	 */
	public Tm1Database database(String name) {
		return database(DEFAULT_INSTANCE, name);
	}

	/**
	 * A database of a named instance.
	 * @param instance the instance's name
	 * @param name the database's name
	 * @return the database
	 * @throws IllegalArgumentException if there's no such database
	 */
	public Tm1Database database(String instance, String name) {
		Tm1Database wanted = new Tm1Database(this, instance, name);
		if (isRunning() && !databases().contains(wanted)) {
			throw new IllegalArgumentException(
					"No database " + instance + "/" + name + " in this engine. It has " + databases());
		}
		if (!isRunning() && !configured().contains(instance + "/" + name)) {
			throw new IllegalArgumentException(
					"No database " + instance + "/" + name + " was added (withDatabase). Added: " + configured());
		}
		return wanted;
	}

	/**
	 * Creates a database in the instance {@code tm1} of the running engine, with the
	 * users of {@link #withUser}. Takes about 10 seconds.
	 * @param name the new database's name
	 * @return the database
	 */
	public Tm1Database createDatabase(String name) {
		return createDatabase(DEFAULT_INSTANCE, name);
	}

	/**
	 * Creates a database in a named instance of the running engine, which must exist.
	 * @param instance the instance's name
	 * @param name the new database's name
	 * @return the database
	 */
	public Tm1Database createDatabase(String instance, String name) {
		command("database", "create", requireName(instance, "instance") + "/" + requireName(name, "database"));
		credentials = null;
		return new Tm1Database(this, instance, name);
	}

	/**
	 * Deletes a database of the running engine, with everything in it. Takes about 5
	 * seconds.
	 * @param database the database
	 */
	public void dropDatabase(Tm1Database database) {
		command("database", "drop", database.getInstance() + "/" + database.getName());
		credentials = null;
	}

	/** A login to an instance, through its first application. */
	Tm1Login login(String instance, String user) {
		for (JsonNode found : credentials().path("instances")) {
			if (found.path("name").asString().equals(instance) && !found.path("applications").isEmpty()) {
				JsonNode application = found.path("applications").get(0);
				return new Tm1Login(getUrl() + instance + "/auth/v1/", instance,
						application.path("clientId").asString(), application.path("clientSecret").asString(), user);
			}
		}
		throw new IllegalStateException("The instance " + instance + " has no application to log in through");
	}

	/** What's passed to the image as TM1_DATABASES: what was added, or the default. */
	List<String> configured() {
		return databases.isEmpty() ? List.of(DEFAULT_INSTANCE + "/" + DEFAULT_DATABASE) : List.copyOf(databases);
	}

	@Override
	public void start() {
		// Before anything starts: Testcontainers starts the datastore, which this depends
		// on, ahead of configure()
		joinNetwork();
		try {
			super.start();
		}
		catch (RuntimeException e) {
			// Testcontainers stops the engine it couldn't start, not what it depends on
			datastore.stop();
			throw e;
		}
	}

	/** Puts the engine and its datastore on one network: the one given, or a new one. */
	private void joinNetwork() {
		if (getNetwork() == null) {
			withNetwork(Network.newNetwork());
		}
		datastore.withNetwork(getNetwork());
	}

	@Override
	protected void configure() {
		joinNetwork();
		withEnv("TM1_DATABASES", String.join(",", configured()));
		if (!users.isEmpty()) {
			List<String> listed = new ArrayList<>();
			users
				.forEach((name, groups) -> listed.add(groups.isEmpty() ? name : name + ":" + String.join(":", groups)));
			withEnv("TM1_USERS", String.join(",", listed));
		}
	}

	@Override
	protected void containerIsStarted(InspectContainerResponse info) {
		awaitSetup();
		version = readVersion();
		logger().info("TM1 {} is up at {} ({}), with {}", version, getUrl(), source.describe(), databases());
	}

	@Override
	public void stop() {
		super.stop();
		// Not a dependent: Testcontainers starts what this depends on, and leaves
		// stopping to us
		datastore.stop();
		credentials = null;
		version = null;
	}

	/**
	 * Waits until the image has created every instance, application, database and user,
	 * and every database is ready: what its health check reports.
	 */
	private void awaitSetup() {
		long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
		while (true) {
			InspectContainerResponse.ContainerState state = getCurrentContainerInfo().getState();
			if (!Boolean.TRUE.equals(state.getRunning())) {
				List<String> lines = getLogs().lines().toList();
				throw new ContainerLaunchException("The container exited while starting, with code "
						+ state.getExitCodeLong() + ". The end of its log:\n"
						+ String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size())));
			}
			try {
				if (execInContainer("tm1-engine", "health").getExitCode() == 0) {
					return;
				}
				if (System.nanoTime() - deadline > 0) {
					throw new ContainerLaunchException(
							"Timed out after " + STARTUP_TIMEOUT + " waiting for TM1 to be set up. " + getLogs());
				}
				Thread.sleep(1000);
			}
			catch (IOException e) {
				throw new UncheckedIOException("Couldn't ask the container whether TM1 is set up", e);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ContainerLaunchException("Interrupted while waiting for TM1 to be set up", e);
			}
		}
	}

	/** Runs the image's tm1-engine command, and returns what it printed. */
	private String command(String... arguments) {
		if (!isRunning()) {
			throw new IllegalStateException("The container isn't started yet");
		}
		String[] all = new String[arguments.length + 3];
		all[0] = "timeout";
		all[1] = String.valueOf(COMMAND_TIMEOUT.toSeconds());
		all[2] = "tm1-engine";
		System.arraycopy(arguments, 0, all, 3, arguments.length);
		try {
			ExecResult result = execInContainer(all);
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("tm1-engine " + String.join(" ", arguments) + " failed (exit "
						+ result.getExitCode() + "): " + (result.getStderr() + result.getStdout()).strip());
			}
			return result.getStdout();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't run tm1-engine " + arguments[0] + " in the container", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted running tm1-engine " + arguments[0], e);
		}
	}

	/** The engine's addresses and credentials, as the image reports them. */
	private synchronized JsonNode credentials() {
		if (credentials == null) {
			credentials = JsonMapper.shared().readTree(command("credentials", "--json"));
		}
		return credentials;
	}

	/**
	 * Reads the version from the first database, logged in as its administrator. Plain
	 * HTTP rather than tm1-client, so a client bug can't break the harness that tests the
	 * client.
	 */
	private Tm1Version readVersion() {
		Tm1Database first = databases().stream()
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("The engine has no database to read its version from"));
		try {
			HttpResponse<String> response = HttpClient.newBuilder()
				.connectTimeout(REQUEST_TIMEOUT)
				.build()
				.send(first.login()
					.authorize(HttpRequest
						.newBuilder(URI.create(first.getRestUrl() + "Configuration/ProductVersion/$value")))
					.timeout(REQUEST_TIMEOUT)
					.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException(
						"TM1 didn't give its version (HTTP " + response.statusCode() + "): " + response.body());
			}
			return Tm1Version.parse(response.body());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't read the TM1 version from " + first.getRestUrl(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted reading the TM1 version", e);
		}
	}

	private void mountSource() {
		// An if-chain rather than a pattern switch: the libraries target Java 17 (see #8)
		if (source instanceof Tm1EngineSource.InstallDir dir) {
			binds.add(new Bind(dir.dir().toString(), new Volume(INSTALL_PATH), AccessMode.ro));
		}
		else if (source instanceof Tm1EngineSource.InstallVolume volume) {
			binds.add(new Bind(volume.name(), new Volume(INSTALL_PATH)));
		}
		else if (source instanceof Tm1EngineSource.Bundle bundle) {
			binds.add(new Bind(bundle.dir().toString(), new Volume("/ibm"), AccessMode.ro));
			if (bundle.file() != null) {
				withEnv("TM1_ENGINE_BUNDLE", bundle.file());
			}
			binds.add(new Bind(CACHE_VOLUME, new Volume("/tm1/cache")));
		}
		else if (source instanceof Tm1EngineSource.BundleUrl url) {
			withEnv("TM1_ENGINE_BUNDLE_URL", url.url().toString());
			if (url.sha256() != null) {
				withEnv("TM1_ENGINE_BUNDLE_SHA256", url.sha256());
			}
			binds.add(new Bind(CACHE_VOLUME, new Volume("/tm1/cache")));
		}
		// Tm1EngineSource.PrivateImage: TM1 is already in the image
	}

	/** The binds the source asks for, for the wiring tests. */
	List<Bind> sourceBinds() {
		return List.copyOf(binds);
	}

	static DockerImageName defaultImage(Tm1EngineSource source) {
		if (source instanceof Tm1EngineSource.PrivateImage image) {
			return DockerImageName.parse(image.image()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		String override = System.getProperty(ENGINE_IMAGE, System.getenv(ENGINE_IMAGE));
		if (override != null && !override.isBlank()) {
			return DockerImageName.parse(override.strip()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		return DEFAULT_IMAGE.withTag(Tm1ServerContainer.imageTag(Tm1ServerContainer.libraryVersion()));
	}

	private static String requireName(String name, String what) {
		if (name == null || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("A TM1 " + what
					+ "'s name is at least three letters, digits, '_' and '-', starting with a letter or digit: "
					+ name);
		}
		return name;
	}

}
