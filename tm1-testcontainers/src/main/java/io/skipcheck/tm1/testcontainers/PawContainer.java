package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startable;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

/**
 * IBM Planning Analytics Workspace (PAW) Local in Docker, for tests, started from the
 * user's own PAW Local zip against a {@link Tm1ServerContainer}.
 *
 * <pre>{@code
 * try (var tm1 = new Tm1ServerContainer(tm1Source);
 *      var paw = new PawContainer(pawSource).withTm1Server(tm1).acceptLicense()) {
 *     paw.start();                // starts tm1 first
 *     String url = paw.getUrl();  // http://localhost:54321/
 * }
 * }</pre>
 *
 * <p>
 * IBM ships PAW Local as about 30 Docker images, with compose files and a {@code paw.sh}
 * that starts them, in {@code ipa_workspace_local_<release>.<build>.zip}. This class uses
 * those as they are, never rebuilding or republishing them. It runs
 * {@code docker/paw/paw-local.sh}, which unpacks the zip, loads the images when any is
 * missing, writes PAW's configuration and runs IBM's {@code paw.sh}, inside a small Linux
 * "runner" container that talks to the Docker daemon. So it works the same on Windows,
 * macOS and Linux: the host needs only Docker. The unpacked zip and PAW's secrets live in
 * the Docker volume {@value #HOME_VOLUME}, and IBM's compose files mount files from it by
 * its path on the Docker host.
 *
 * <p>
 * Users log on to PAW as users of the TM1 server ({@code PAAuthMode=tm1}). PAW is
 * configured with the server's address on its network, and every PAW container is
 * connected to that network too, because PAW calls TM1 at the address the Admin Server
 * advertises.
 *
 * <p>
 * Things to know:
 * <ul>
 * <li><b>The license:</b> starting fails unless IBM's PAW license is accepted, with
 * {@link #acceptLicense()} or by setting {@value #ACCEPT_LICENSE}{@code =true}.
 * <li><b>One PAW per Docker daemon:</b> IBM's compose files give every container a fixed
 * name, under the compose project {@code paw}. Most tests share one, with
 * {@link RequiresPaw}.
 * <li><b>Heavy:</b> PAW needs about 15 GB of memory. The first start loads about 8 GB of
 * images, which takes a few minutes; later starts take about a minute.
 * <li><b>Cleanup:</b> {@link #stop()} removes the PAW containers and keeps PAW's data.
 * IBM's containers carry no Testcontainers labels, so if the JVM dies they keep running,
 * and the next start reuses them.
 * <li><b>linux/amd64 only:</b> on Apple Silicon, IBM's images run under Rosetta.
 * </ul>
 */
public class PawContainer implements Startable {

	/**
	 * Accepts IBM's license for PAW when {@code true}. Read as a system property, then an
	 * environment variable.
	 */
	public static final String ACCEPT_LICENSE = "PAW_ACCEPT_LICENSE";

	/**
	 * The Docker volume holding the unpacked PAW Local zip and PAW's secrets, shared by
	 * every PAW started this way.
	 */
	public static final String HOME_VOLUME = "tm1-paw-home";

	/** The network IBM's compose files put PAW on. */
	static final String PAW_NETWORK = "paw_default";

	/** The Admin Server's REST port in a {@link Tm1ServerContainer}. */
	static final int ADMIN_PORT = 5895;

	private static final String COMPOSE_PROJECT = "paw";

	private static final String SCRIPT = "io/skipcheck/tm1/testcontainers/paw-local.sh";

	private static final String RUNNER_BASE = "docker:cli";

	private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(20);

	private static final Duration READY_TIMEOUT = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(PawContainer.class);

	private static @Nullable PawContainer shared;

	private static @Nullable String sharedFailure;

	private final PawSource source;

	private final Map<String, String> settings = new LinkedHashMap<>();

	private @Nullable Tm1ServerContainer tm1;

	private @Nullable Tm1AdminServerContainer adminServer;

	private boolean licenseAccepted = licenseAcceptedBySetting();

	private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;

	private int httpPort;

	private boolean running;

	/**
	 * A PAW started from {@code source}'s zip.
	 * @param source the PAW Local zip
	 */
	public PawContainer(PawSource source) {
		this.source = Objects.requireNonNull(source, "source");
	}

	/**
	 * The PAW most tests share: one per test JVM, started on first use with the source
	 * {@link PawSource#discover()} finds, against {@link Tm1ServerContainer#shared()}.
	 * It's never stopped, so the next test JVM finds it running and starts in seconds.
	 * @return the started shared PAW
	 * <p>
	 * If starting it fails, later calls in the same JVM fail at once with the same error,
	 * rather than each waiting for PAW again.
	 * @throws IllegalStateException if no PAW or TM1 source is configured, or the license
	 * isn't accepted
	 */
	public static synchronized PawContainer shared() {
		if (sharedFailure != null) {
			throw new IllegalStateException("The shared PAW failed to start earlier in this JVM: " + sharedFailure);
		}
		if (shared == null) {
			try {
				PawSource source = PawSource.discover()
					.orElseThrow(() -> new IllegalStateException(RequiresPaw.NO_SOURCE_MESSAGE));
				PawContainer paw = new PawContainer(source).withTm1Server(Tm1ServerContainer.shared());
				paw.start();
				shared = paw;
			}
			catch (RuntimeException e) {
				sharedFailure = String.valueOf(e.getMessage());
				throw e;
			}
		}
		return shared;
	}

	/**
	 * Logs PAW users on to {@code tm1}, and lists servers from its Admin Server. Starts
	 * {@code tm1} first if it isn't running.
	 * @param tm1 the TM1 server
	 * @return this container
	 */
	public PawContainer withTm1Server(Tm1ServerContainer tm1) {
		this.tm1 = Objects.requireNonNull(tm1, "tm1");
		return this;
	}

	/**
	 * Lists servers from a separate Admin Server
	 * ({@link Tm1ServerContainer#withAdminServer}) instead of the
	 * {@linkplain #withTm1Server TM1 server}'s own. Users still log on through that TM1
	 * server, which must be registered with this Admin Server and on its network.
	 * @param admin the Admin Server PAW lists servers from
	 * @return this container
	 */
	public PawContainer withAdminServer(Tm1AdminServerContainer admin) {
		this.adminServer = Objects.requireNonNull(admin, "admin");
		return this;
	}

	/**
	 * Accepts IBM's license for Planning Analytics Workspace, which is in the PAW Local
	 * zip as {@code licenses/LA_en}. IBM's own setup asks for this in its admin tool,
	 * which this class skips.
	 * @return this container
	 */
	public PawContainer acceptLicense() {
		this.licenseAccepted = true;
		return this;
	}

	/**
	 * Sets one of {@code paw-local.sh}'s {@code PAW_*} settings, such as
	 * {@code PAW_JVM_ARGS} or {@code PAW_TM1WEB_URL}. The TM1 server, ports, network and
	 * paths are set by this class.
	 * @param name the setting, starting with {@code PAW_}
	 * @param value its value
	 * @return this container
	 */
	public PawContainer withSetting(String name, String value) {
		if (!name.startsWith("PAW_")) {
			throw new IllegalArgumentException("Not a paw-local.sh setting: " + name);
		}
		settings.put(name, Objects.requireNonNull(value, "value"));
		return this;
	}

	/**
	 * How long {@link #start()} may take to load IBM's images and start PAW. Default: 20
	 * minutes, since the first start loads about 8 GB of images.
	 * @param timeout the time allowed
	 * @return this container
	 */
	public PawContainer withStartupTimeout(Duration timeout) {
		this.startupTimeout = Objects.requireNonNull(timeout, "timeout");
		return this;
	}

	/**
	 * Where PAW comes from.
	 * @return the source
	 */
	public PawSource getSource() {
		return source;
	}

	/**
	 * The TM1 server set with {@link #withTm1Server}.
	 * @return the server, or {@code null} if none was set
	 */
	public @Nullable Tm1ServerContainer getTm1Server() {
		return tm1;
	}

	/**
	 * Whether PAW is started.
	 * @return {@code true} between {@link #start()} and {@link #stop()}
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * PAW's address as seen from the host, e.g. {@code http://localhost:54321/}.
	 * @return the URL, ending in {@code /}
	 * @throws IllegalStateException if PAW isn't started
	 */
	public String getUrl() {
		if (!running) {
			throw new IllegalStateException("PAW isn't started");
		}
		return "http://" + DockerClientFactory.instance().dockerHostIpAddress() + ":" + httpPort + "/";
	}

	/**
	 * Starts the TM1 server if needed, then PAW, and waits until a TM1 call through PAW
	 * works.
	 * @throws IllegalStateException if the license isn't accepted or no TM1 server is set
	 */
	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		if (!licenseAccepted) {
			throw new IllegalStateException(RequiresPaw.NO_LICENSE_MESSAGE + " Or call acceptLicense().");
		}
		Tm1ServerContainer server = this.tm1;
		if (server == null) {
			throw new IllegalStateException("No TM1 server: set one with withTm1Server");
		}
		if (!server.isRunning()) {
			server.start();
		}
		DockerClient docker = DockerClientFactory.instance().client();
		ContainerNetwork tm1Network = networkOf(server);
		String tm1Address = Objects.requireNonNull(tm1Network.getIpAddress(), "TM1's address");
		String adminAddress = tm1Address;
		Tm1AdminServerContainer admin = this.adminServer;
		if (admin != null) {
			if (!admin.isRunning()) {
				admin.start();
			}
			ContainerNetwork adminNetwork = networkOf(admin);
			if (!Objects.equals(adminNetwork.getNetworkID(), tm1Network.getNetworkID())) {
				throw new IllegalStateException("PAW needs the Admin Server and the TM1 server on one network");
			}
			adminAddress = Objects.requireNonNull(adminNetwork.getIpAddress(), "the Admin Server's address");
		}
		httpPort = freePort();
		Map<String, String> env = new LinkedHashMap<>(settings);
		env.put("PAW_TM1_ADMIN_URL", "http://" + adminAddress + ":" + ADMIN_PORT);
		env.put("PAW_TM1_LOGIN_URL", "http://" + tm1Address + ":" + Tm1ServerContainer.REST_PORT);
		env.put("PAW_AUTH_MODE", "tm1");
		env.put("PAW_HTTP_PORT", Integer.toString(httpPort));
		env.put("PAW_HTTPS_PORT", Integer.toString(freePort()));
		env.put("PAW_NETWORK", PAW_NETWORK);
		env.put("PAW_WAIT", "0");
		log.info("Starting PAW from {} against the TM1 server at {} (the first start loads IBM's images)",
				source.describe(), tm1Address);
		runScript(docker, "up", env, startupTimeout);
		connectToNetwork(docker, Objects.requireNonNull(tm1Network.getNetworkID(), "TM1's network"));
		running = true;
		awaitReady(server);
		log.info("PAW is up at {}, logging users on to the TM1 server at {}", getUrl(), tm1Address);
	}

	/** Removes the PAW containers, keeping PAW's data. */
	@Override
	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		try {
			runScript(DockerClientFactory.instance().client(), "down", Map.of(), Duration.ofMinutes(5));
		}
		catch (RuntimeException e) {
			log.warn("Couldn't stop PAW; its containers may still be running", e);
		}
	}

	/**
	 * Whether IBM's PAW license is accepted by the {@value #ACCEPT_LICENSE} setting.
	 * @return {@code true} if it's set to {@code true}
	 */
	static boolean licenseAcceptedBySetting() {
		String value = PawSource.setting(ACCEPT_LICENSE);
		return value != null && value.strip().equalsIgnoreCase("true");
	}

	/** Runs {@code paw-local.sh <command>} in the runner container, until it exits. */
	private void runScript(DockerClient docker, String command, Map<String, String> env, Duration timeout) {
		String home = homeMountpoint(docker);
		String zipName = Objects.requireNonNull(source.zip().getFileName(), "zip name").toString();
		String socket = DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
		try (GenericContainer<?> runner = new GenericContainer<>(runnerImage())) {
			runner.withCommand("paw-local.sh", command)
				.withEnv(env)
				.withEnv("PAW_ACCEPT_LICENSE", "true")
				.withEnv("PAW_ZIP", "/zip/" + zipName)
				.withEnv("PAW_HOME", home)
				.withFileSystemBind(source.zip().toString(), "/zip/" + zipName, BindMode.READ_ONLY)
				// The Docker socket, as Testcontainers' Ryuk mounts it, and PAW's home
				// volume at
				// its path on the Docker host: IBM's compose files bind-mount files from
				// it by
				// that path, which the daemon resolves
				.withCreateContainerCmdModifier(cmd -> {
					HostConfig host = Objects.requireNonNull(cmd.getHostConfig(), "hostConfig");
					List<Bind> binds = new ArrayList<>(
							host.getBinds() == null ? List.of() : Arrays.asList(host.getBinds()));
					binds.add(new Bind(socket, new Volume("/var/run/docker.sock")));
					binds.add(new Bind(HOME_VOLUME, new Volume(home)));
					host.withBinds(binds);
				})
				.withLogConsumer(new Slf4jLogConsumer(log).withPrefix("paw-local"))
				.withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(timeout));
			runner.start();
		}
	}

	/** The home volume's path on the Docker host, creating the volume if needed. */
	private static String homeMountpoint(DockerClient docker) {
		try {
			return docker.inspectVolumeCmd(HOME_VOLUME).exec().getMountpoint();
		}
		catch (NotFoundException e) {
			docker.createVolumeCmd().withName(HOME_VOLUME).exec();
			return docker.inspectVolumeCmd(HOME_VOLUME).exec().getMountpoint();
		}
	}

	/**
	 * Connects every PAW container to the TM1 server's network. PAW calls TM1 at the
	 * address the Admin Server advertises, which is on that network, but IBM's services
	 * find each other by container name, which only works on their own network.
	 */
	private static void connectToNetwork(DockerClient docker, String networkId) {
		List<Container> containers = docker.listContainersCmd()
			.withLabelFilter(Map.of("com.docker.compose.project", COMPOSE_PROJECT))
			.exec();
		for (Container container : containers) {
			var networks = container.getNetworkSettings() == null ? null : container.getNetworkSettings().getNetworks();
			boolean attached = networks != null
					&& networks.values().stream().anyMatch(network -> networkId.equals(network.getNetworkID()));
			if (!attached) {
				docker.connectToNetworkCmd().withNetworkId(networkId).withContainerId(container.getId()).exec();
			}
		}
	}

	/** Polls until a logon and a TM1 call through PAW work. */
	private void awaitReady(Tm1ServerContainer server) {
		Instant deadline = Instant.now().plus(READY_TIMEOUT);
		String last = "no answer yet";
		while (Instant.now().isBefore(deadline)) {
			try {
				PawSession session = new PawSession(getUrl()).login(server.getUsername(), server.getPassword());
				List<String> servers = session.servers();
				if (!servers.isEmpty()) {
					HttpResponse<String> version = session.tm1Get(servers.get(0),
							"Configuration/ProductVersion/$value");
					if (version.statusCode() == 200) {
						return;
					}
					last = "a TM1 call through PAW answered " + version.statusCode() + ": " + version.body();
				}
				else {
					last = "PAW lists no TM1 servers yet";
				}
			}
			catch (IOException e) {
				last = e.getMessage();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted waiting for PAW", e);
			}
			try {
				Thread.sleep(5_000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted waiting for PAW", e);
			}
		}
		throw new IllegalStateException("PAW wasn't ready within " + READY_TIMEOUT + ": " + last);
	}

	/**
	 * The TM1 server's network: its {@link Network}, or Docker's default bridge network.
	 */
	private static ContainerNetwork networkOf(GenericContainer<?> server) {
		Map<String, ContainerNetwork> networks = server.getContainerInfo().getNetworkSettings().getNetworks();
		Network network = server.getNetwork();
		ContainerNetwork found = network == null ? networks.get("bridge")
				: networks.values()
					.stream()
					.filter(candidate -> network.getId().equals(candidate.getNetworkID()))
					.findFirst()
					.orElse(null);
		if (found == null || found.getIpAddress() == null || found.getIpAddress().isBlank()) {
			throw new IllegalStateException("PAW can't reach the TM1 server: it's on " + networks.keySet()
					+ ". Give the TM1 server a Network (withNetwork), or leave it on Docker's default bridge network.");
		}
		return found;
	}

	/**
	 * The runner image: {@code docker:cli} with bash and {@code paw-local.sh}, tagged by
	 * the script's hash so a changed script builds a new one.
	 */
	private static ImageFromDockerfile runnerImage() {
		String hash = sha256(script()).substring(0, 12);
		return new ImageFromDockerfile("tm1-paw-runner:" + hash, false)
			.withDockerfileFromBuilder(dockerfile -> dockerfile.from(RUNNER_BASE)
				.run("apk add --no-cache bash curl")
				.copy("paw-local.sh", "/usr/local/bin/paw-local.sh")
				.run("chmod 755 /usr/local/bin/paw-local.sh")
				.build())
			.withFileFromClasspath("paw-local.sh", SCRIPT);
	}

	private static byte[] script() {
		try (InputStream in = PawContainer.class.getClassLoader().getResourceAsStream(SCRIPT)) {
			if (in == null) {
				throw new IllegalStateException("Missing " + SCRIPT + " in the tm1-testcontainers jar");
			}
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't read " + SCRIPT, e);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't find a free port for PAW", e);
		}
	}

	@Override
	public String toString() {
		return "PawContainer[" + source.describe() + (running ? ", " + getUrl() : "") + "]";
	}

}
