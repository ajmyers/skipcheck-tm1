package net.ajmyers.tm1.testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import net.ajmyers.tm1.client.version.Tm1Version;

/**
 * A TM1 server in Docker, for tests, built on the {@code tm1-server} image.
 *
 * <p>
 * The image contains no IBM software, so every container needs a {@link Tm1Source}. Most
 * tests don't create containers themselves: they use {@link RequiresTm1}, which finds a
 * source from the environment, starts one {@linkplain #shared() shared} container, and
 * skips the tests when there's no source or no Docker.
 *
 * <pre>{@code
 * try (var tm1 = new Tm1ServerContainer(new Tm1Source.InstallDir(Path.of("/opt/ibm/tm1_64")))) {
 *     tm1.start();
 *     String url = tm1.getRestUrl();   // http://localhost:54321/api/v1/
 * }
 * }</pre>
 *
 * <p>
 * By default the server starts with an empty database and the login {@code admin} with no
 * password. Once started, the container reads the server's version, available from
 * {@link #getTm1Version()}. If {@link #withExpectedVersion} was set and the version
 * differs, it fails to start, before any test runs against the wrong TM1.
 */
public class Tm1ServerContainer extends GenericContainer<Tm1ServerContainer> {

	/** The {@code tm1-server} image, without a tag. */
	public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("ghcr.io/ajmyers/tm1-server");

	/**
	 * Overrides the {@code tm1-server} image (not used with
	 * {@link Tm1Source.PrivateImage}), e.g. {@code tm1-server:local} for a local build.
	 * Read as a system property, then an environment variable, like {@link Tm1Source}'s
	 * settings.
	 */
	public static final String SERVER_IMAGE = "TM1_SERVER_IMAGE";

	/** The REST API's port inside the container. */
	public static final int REST_PORT = 8010;

	/**
	 * The volume bundle installs are cached in, shared by every container on this Docker
	 * host. Only the first container to see a bundle installs it; the rest reuse it.
	 * Remove the volume to clear it.
	 */
	public static final String CACHE_VOLUME = "tm1-testcontainers-cache";

	private static final String INSTALL_PATH = "/opt/ibm/cognos/tm1_64";

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static Tm1ServerContainer shared;

	private final Tm1Source source;

	private final List<Bind> binds = new ArrayList<>();

	private String username = "admin";

	private String password = "";

	private Tm1Version expectedVersion;

	private Tm1Version version;

	/**
	 * A container on the default {@code tm1-server} image (or {@code TM1_SERVER_IMAGE}),
	 * getting TM1 from {@code source}.
	 */
	public Tm1ServerContainer(Tm1Source source) {
		this(defaultImage(source), source);
	}

	/** A container on {@code image}, which must be {@code tm1-server} or built on it. */
	@SuppressWarnings("this-escape") // configuring in the constructor is the
										// Testcontainers pattern
	public Tm1ServerContainer(DockerImageName image, Tm1Source source) {
		super(image);
		this.source = Objects.requireNonNull(source, "source");
		withExposedPorts(REST_PORT);
		withEnv("TM1_SEED", Tm1Seed.EMPTY.value());
		// TM1 Server only exists for x86-64. On Apple Silicon this runs under Rosetta.
		withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
		withCreateContainerCmdModifier(cmd -> {
			HostConfig host = cmd.getHostConfig();
			List<Bind> all = new ArrayList<>(host.getBinds() == null ? List.of() : Arrays.asList(host.getBinds()));
			all.addAll(binds);
			host.withBinds(all);
		});
		// TM1 serves $metadata without a login, so a 200 means the REST API is really up.
		// This is also the image's healthcheck.
		waitingFor(Wait.forHttp("/api/v1/$metadata")
			.forPort(REST_PORT)
			.forStatusCode(200)
			.withStartupTimeout(STARTUP_TIMEOUT));
		applySource();
	}

	/**
	 * The container most tests share: one per test JVM, started on first use with the
	 * source {@link Tm1Source#discover()} finds. Testcontainers stops it when the JVM
	 * exits.
	 *
	 * <p>
	 * It's marked {@linkplain #withReuse reusable}. If you've turned on Testcontainers
	 * reuse ({@code testcontainers.reuse.enable=true} in
	 * {@code ~/.testcontainers.properties}), it keeps running between test runs, so tests
	 * start instantly, and objects tests leave behind stay too. Otherwise reuse has no
	 * effect.
	 * @throws IllegalStateException if no source is configured
	 */
	public static synchronized Tm1ServerContainer shared() {
		if (shared == null) {
			Tm1Source source = Tm1Source.discover()
				.orElseThrow(() -> new IllegalStateException(RequiresTm1.NO_SOURCE_MESSAGE));
			Tm1ServerContainer container = new Tm1ServerContainer(source).withReuse(true);
			container.start();
			shared = container;
		}
		return shared;
	}

	/**
	 * Seeds an empty database with objects. The default is {@link Tm1Seed#EMPTY}, which
	 * seeds nothing.
	 */
	public Tm1ServerContainer withSeed(Tm1Seed seed) {
		withEnv("TM1_SEED", seed.value());
		return withCredentials(seed.username(), seed.password());
	}

	/**
	 * Copies a TM1 database (a folder of {@code .cub}, {@code .dim}, {@code .pro} and so
	 * on) into the container before it starts. Any seed is ignored, since the database
	 * isn't empty. Set {@link #withCredentials} to a login the database has.
	 */
	public Tm1ServerContainer withData(Path dataDir) {
		if (!Files.isDirectory(dataDir)) {
			throw new IllegalArgumentException("Not a folder: " + dataDir);
		}
		return withCopyFileToContainer(MountableFile.forHostPath(dataDir), "/tm1/data/");
	}

	/** The login tests should use. The default is {@code admin} with no password. */
	public Tm1ServerContainer withCredentials(String username, String password) {
		this.username = Objects.requireNonNull(username, "username");
		this.password = Objects.requireNonNull(password, "password");
		return self();
	}

	/** Fails startup unless the server reports exactly this version. */
	public Tm1ServerContainer withExpectedVersion(Tm1Version version) {
		this.expectedVersion = Objects.requireNonNull(version, "version");
		return self();
	}

	/** Where TM1 comes from. */
	public Tm1Source getSource() {
		return source;
	}

	/**
	 * The REST API's service root as seen from the host, e.g.
	 * {@code http://localhost:54321/api/v1/}.
	 */
	public String getRestUrl() {
		return "http://" + getHost() + ":" + getMappedPort(REST_PORT) + "/api/v1/";
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	/** The version the running server reports. Only available once started. */
	public Tm1Version getTm1Version() {
		if (version == null) {
			throw new IllegalStateException("The container isn't started yet");
		}
		return version;
	}

	@Override
	protected void containerIsStarted(InspectContainerResponse info) {
		version = readVersion();
		if (expectedVersion != null && !expectedVersion.equals(version)) {
			throw new IllegalStateException("Expected TM1 " + expectedVersion + " but the server from "
					+ source.describe() + " reports " + version);
		}
		logger().info("TM1 {} is up at {} ({})", version, getRestUrl(), source.describe());
	}

	// An if-chain rather than a pattern switch: the libraries target Java 17 (see #8)
	private void applySource() {
		if (source instanceof Tm1Source.InstallDir dir) {
			binds.add(new Bind(dir.dir().toString(), new Volume(INSTALL_PATH), AccessMode.ro));
		}
		else if (source instanceof Tm1Source.InstallVolume volume) {
			binds.add(new Bind(volume.name(), new Volume(INSTALL_PATH)));
		}
		else if (source instanceof Tm1Source.Bundle bundle) {
			binds.add(new Bind(bundle.dir().toString(), new Volume("/ibm"), AccessMode.ro));
			if (bundle.file() != null) {
				withEnv("TM1_BUNDLE", bundle.file());
			}
			binds.add(new Bind(CACHE_VOLUME, new Volume("/tm1/cache")));
		}
		else if (source instanceof Tm1Source.BundleUrl url) {
			withEnv("TM1_BUNDLE_URL", url.url().toString());
			if (url.sha256() != null) {
				withEnv("TM1_BUNDLE_SHA256", url.sha256());
			}
			binds.add(new Bind(CACHE_VOLUME, new Volume("/tm1/cache")));
		}
		// Tm1Source.PrivateImage: TM1 is already in the image
	}

	/**
	 * Reads the version with a throwaway login, then closes the session it opened, so the
	 * container doesn't start with a session held open. Plain HTTP rather than
	 * tm1-client, so a client bug can't break the harness that tests the client.
	 */
	private Tm1Version readVersion() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
		String auth = "Basic "
				+ Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
		try {
			HttpResponse<String> response = http
				.send(HttpRequest.newBuilder(URI.create(getRestUrl() + "Configuration/ProductVersion/$value"))
					.header("Authorization", auth)
					.timeout(REQUEST_TIMEOUT)
					.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException(
						"TM1 refused the login '" + username + "' (HTTP " + response.statusCode() + "): "
								+ response.body() + ". Set the database's login with withCredentials.");
			}
			response.headers()
				.allValues("Set-Cookie")
				.stream()
				.map(cookie -> cookie.split(";", 2)[0].strip())
				.filter(cookie -> cookie.startsWith("TM1SessionId="))
				.findFirst()
				.ifPresent(cookie -> closeSession(http, cookie));
			return Tm1Version.parse(response.body());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't read the TM1 version from " + getRestUrl(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted reading the TM1 version", e);
		}
	}

	private void closeSession(HttpClient http, String cookie) {
		try {
			// TM1 rejects actions without a JSON content type, even with no parameters
			http.send(HttpRequest.newBuilder(URI.create(getRestUrl() + "ActiveSession/tm1.Close"))
				.header("Cookie", cookie)
				.header("Content-Type", "application/json")
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build(), HttpResponse.BodyHandlers.discarding());
		}
		catch (IOException e) {
			logger().debug("Couldn't close the version-check session; TM1 will time it out", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static DockerImageName defaultImage(Tm1Source source) {
		if (source instanceof Tm1Source.PrivateImage image) {
			return DockerImageName.parse(image.image()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		String override = System.getProperty(SERVER_IMAGE, System.getenv(SERVER_IMAGE));
		if (override != null && !override.isBlank()) {
			return DockerImageName.parse(override.strip()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		return DEFAULT_IMAGE.withTag(imageTag(libraryVersion()));
	}

	/**
	 * Released libraries use the image of the same version; snapshots use {@code edge},
	 * built from master.
	 */
	static String imageTag(String libraryVersion) {
		return libraryVersion.endsWith("-SNAPSHOT") ? "edge" : libraryVersion;
	}

	private static String libraryVersion() {
		try (InputStream in = Tm1ServerContainer.class.getResourceAsStream("tm1-testcontainers.properties")) {
			Properties properties = new Properties();
			properties.load(Objects.requireNonNull(in, "tm1-testcontainers.properties is missing from the jar"));
			return properties.getProperty("version");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
