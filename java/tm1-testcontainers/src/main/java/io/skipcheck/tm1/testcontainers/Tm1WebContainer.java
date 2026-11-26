// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

/**
 * TM1 Web (IBM Planning Analytics Spreadsheet Services) in Docker, for tests, built on
 * the {@code tm1-web} image.
 *
 * <p>
 * TM1 Web lists and logs on to the TM1 servers registered with an Admin Server, so it's
 * started against a {@link Tm1ServerContainer} with {@link #withTm1Server}. That
 * container's embedded Admin Server is the one TM1 Web uses. Most tests use
 * {@link RequiresTm1Web}, which starts one {@linkplain #shared() shared} TM1 Web against
 * the shared TM1 server.
 *
 * {@snippet class = examples.Tm1WebContainerExamples region = start}
 *
 * <p>
 * The image contains no IBM software, so every container needs a {@link Tm1WebSource}.
 * TM1 Web's default heap here is 1 GB; set the image's {@code TM1_WEB_HEAP} with
 * {@link #withEnv} to change it.
 */
public class Tm1WebContainer extends GenericContainer<Tm1WebContainer> {

	/** The {@code tm1-web} image, without a tag. */
	public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("ghcr.io/ajmyers/tm1-web");

	/**
	 * Overrides the {@code tm1-web} image (not used with
	 * {@link Tm1WebSource.PrivateImage}), e.g. {@code tm1-web:local} for a local build.
	 * Read as a system property, then an environment variable.
	 */
	public static final String WEB_IMAGE = "TM1_WEB_IMAGE";

	/** TM1 Web's HTTP port inside the container. */
	public static final int HTTP_PORT = 9510;

	private static final String INSTALL_PATH = "/opt/ibm/cognos/tm1web";

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	private static @Nullable Tm1WebContainer shared;

	private final Tm1WebSource source;

	private final List<Bind> binds = new ArrayList<>();

	private @Nullable Tm1ServerContainer tm1;

	private @Nullable Tm1AdminServerContainer adminServer;

	/**
	 * A container on the default {@code tm1-web} image (or {@code TM1_WEB_IMAGE}),
	 * getting TM1 Web from {@code source}.
	 * @param source where TM1 Web comes from
	 */
	public Tm1WebContainer(Tm1WebSource source) {
		this(defaultImage(source), source);
	}

	/**
	 * A container on {@code image}, which must be {@code tm1-web} or built on it.
	 * @param image the image to run
	 * @param source where TM1 Web comes from
	 */
	@SuppressWarnings("this-escape") // configuring in the constructor is the
										// Testcontainers pattern
	public Tm1WebContainer(DockerImageName image, Tm1WebSource source) {
		super(image);
		this.source = Objects.requireNonNull(source, "source");
		withExposedPorts(HTTP_PORT);
		// TM1 Web's bundled Java only exists for x86-64. On Apple Silicon this runs under
		// Rosetta.
		withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
		withCreateContainerCmdModifier(cmd -> {
			HostConfig host = Objects.requireNonNull(cmd.getHostConfig(), "hostConfig");
			List<Bind> all = new ArrayList<>(host.getBinds() == null ? List.of() : Arrays.asList(host.getBinds()));
			all.addAll(binds);
			host.withBinds(all);
		});
		// The login page, as the image's healthcheck uses: 200 once both apps are up.
		waitingFor(new HttpOrExitWaitStrategy("/tm1web/", HTTP_PORT, code -> code == 200, STARTUP_TIMEOUT));
		applySource();
	}

	/**
	 * The TM1 Web most tests share: one per test JVM, started on first use with the
	 * source {@link Tm1WebSource#discover()} finds, against
	 * {@link Tm1ServerContainer#shared()}. Marked {@linkplain #withReuse reusable}, like
	 * the shared TM1 server.
	 * @return the started shared container
	 * @throws IllegalStateException if no TM1 or TM1 Web source is configured
	 */
	public static synchronized Tm1WebContainer shared() {
		if (shared == null) {
			Tm1WebSource source = Tm1WebSource.discover()
				.orElseThrow(() -> new IllegalStateException(RequiresTm1Web.NO_SOURCE_MESSAGE));
			Tm1WebContainer container = new Tm1WebContainer(source).withTm1Server(Tm1ServerContainer.shared())
				.withReuse(true);
			container.start();
			shared = container;
		}
		return shared;
	}

	/**
	 * Points TM1 Web at {@code tm1}'s Admin Server, and starts {@code tm1} first if it
	 * isn't running.
	 *
	 * <p>
	 * The two containers have to share a Docker network. A server that isn't started yet
	 * and has no network is given one of its own. A server that's already running (such
	 * as the shared one) is joined on the network it's on, including Docker's default
	 * bridge network.
	 * @param tm1 the TM1 server whose Admin Server TM1 Web lists servers from
	 * @return this container
	 */
	public Tm1WebContainer withTm1Server(Tm1ServerContainer tm1) {
		this.tm1 = Objects.requireNonNull(tm1, "tm1");
		if (!tm1.isRunning() && tm1.getNetwork() == null) {
			tm1.withNetwork(Network.newNetwork());
		}
		dependsOn(tm1);
		return self();
	}

	/**
	 * Lists and logs on to the TM1 servers registered with a separate Admin Server
	 * ({@link Tm1ServerContainer#withAdminServer}), instead of one server's own. Takes
	 * precedence over the Admin Server of a {@linkplain #withTm1Server TM1 server}. The
	 * two containers share a Docker network, and starting TM1 Web starts the Admin Server
	 * first; start the TM1 servers before TM1 Web too, so they're listed.
	 * @param admin the Admin Server to list servers from
	 * @return this container
	 */
	public Tm1WebContainer withAdminServer(Tm1AdminServerContainer admin) {
		this.adminServer = Objects.requireNonNull(admin, "admin");
		Tm1ServerContainer.shareNetwork(this, admin);
		dependsOn(admin);
		return self();
	}

	/**
	 * The Admin Server set with {@link #withAdminServer}.
	 * @return the Admin Server, or {@code null} if none was set
	 */
	public @Nullable Tm1AdminServerContainer getAdminServer() {
		return adminServer;
	}

	/**
	 * Where TM1 Web comes from.
	 * @return the source
	 */
	public Tm1WebSource getSource() {
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
	 * TM1 Web's address as seen from the host, e.g.
	 * {@code http://localhost:54321/tm1web/}.
	 * @return the URL, ending in {@code /}
	 */
	public String getUrl() {
		return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT) + "/tm1web/";
	}

	/**
	 * The Admin Server host TM1 Web is configured with: the TM1 server's address on the
	 * network the two share. This is the {@code AdminHost} a TM1 Web login, or a TM1 Web
	 * URL API link, names.
	 * @return the Admin Server host
	 * @throws IllegalStateException if no TM1 server was set or the container isn't
	 * started yet
	 */
	public String getAdminHost() {
		String host = getEnvMap().get("TM1_ADMIN_HOST");
		if (host == null) {
			throw new IllegalStateException("No Admin Server yet: set a TM1 server with withTm1Server and start");
		}
		return host;
	}

	@Override
	protected void configure() {
		if (adminServer != null) {
			withEnv("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS);
			return;
		}
		if (tm1 == null) {
			return;
		}
		if (!tm1.isRunning()) {
			throw new IllegalStateException("The TM1 server isn't running; it should have started first");
		}
		Network network = tm1.getNetwork();
		String address = addressOn(tm1, network);
		if (network != null) {
			withNetwork(network);
		}
		else {
			// TM1 registers with the Admin Server as http://<its hostname>:8010, and TM1
			// Web's evalservice (universal reports) calls that URL. Docker's DNS resolves
			// hostnames on networks it creates, but not on the default bridge, so name it
			// here.
			withExtraHost(tm1.getContainerInfo().getConfig().getHostName(), address);
		}
		withEnv("TM1_ADMIN_HOST", address);
	}

	@Override
	protected void containerIsStarted(InspectContainerResponse info) {
		logger().info("TM1 Web is up at {} ({}), listing servers from the Admin Server at {}", getUrl(),
				source.describe(), getEnvMap().getOrDefault("TM1_ADMIN_HOST", "(none)"));
	}

	/**
	 * The TM1 server's IP address on {@code network}, or on Docker's default bridge
	 * network when that's {@code null}. An address rather than a network alias, because
	 * the default bridge network has no names, and because TM1 registers with the Admin
	 * Server under its address too.
	 */
	private static String addressOn(Tm1ServerContainer tm1, @Nullable Network network) {
		Map<String, ContainerNetwork> networks = tm1.getContainerInfo().getNetworkSettings().getNetworks();
		ContainerNetwork found = null;
		if (network != null) {
			found = networks.values()
				.stream()
				.filter(candidate -> network.getId().equals(candidate.getNetworkID()))
				.findFirst()
				.orElse(null);
		}
		else {
			found = networks.get("bridge");
		}
		if (found == null || found.getIpAddress() == null || found.getIpAddress().isBlank()) {
			throw new IllegalStateException("TM1 Web can't reach the TM1 server: it's on " + networks.keySet()
					+ ". Give the TM1 server a Network (withNetwork), or leave it on Docker's default bridge network.");
		}
		return found.getIpAddress();
	}

	// An if-chain rather than a pattern switch: the libraries target Java 17 (see #8)
	private void applySource() {
		if (source instanceof Tm1WebSource.InstallDir dir) {
			binds.add(new Bind(dir.dir().toString(), new Volume(INSTALL_PATH), AccessMode.ro));
		}
		else if (source instanceof Tm1WebSource.InstallVolume volume) {
			binds.add(new Bind(volume.name(), new Volume(INSTALL_PATH)));
		}
		else if (source instanceof Tm1WebSource.Bundle bundle) {
			binds.add(new Bind(bundle.dir().toString(), new Volume("/ibm"), AccessMode.ro));
			if (bundle.file() != null) {
				withEnv("TM1_WEB_BUNDLE", bundle.file());
			}
			binds.add(new Bind(Tm1ServerContainer.CACHE_VOLUME, new Volume("/tm1-web/cache")));
		}
		else if (source instanceof Tm1WebSource.BundleUrl url) {
			withEnv("TM1_WEB_BUNDLE_URL", url.url().toString());
			if (url.sha256() != null) {
				withEnv("TM1_WEB_BUNDLE_SHA256", url.sha256());
			}
			binds.add(new Bind(Tm1ServerContainer.CACHE_VOLUME, new Volume("/tm1-web/cache")));
		}
		// Tm1WebSource.PrivateImage: TM1 Web is already in the image
	}

	private static DockerImageName defaultImage(Tm1WebSource source) {
		if (source instanceof Tm1WebSource.PrivateImage image) {
			return DockerImageName.parse(image.image()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		String override = System.getProperty(WEB_IMAGE, System.getenv(WEB_IMAGE));
		if (override != null && !override.isBlank()) {
			return DockerImageName.parse(override.strip()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		return DEFAULT_IMAGE.withTag(Tm1ServerContainer.imageTag(Tm1ServerContainer.libraryVersion()));
	}

}
