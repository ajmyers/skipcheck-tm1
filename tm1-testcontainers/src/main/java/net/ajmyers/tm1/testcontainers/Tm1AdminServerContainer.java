package net.ajmyers.tm1.testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;

/**
 * A TM1 Admin Server on its own, for tests with several TM1 servers: the
 * {@code tm1-server} image in {@code admin} mode. TM1 servers register with it through
 * {@link Tm1ServerContainer#withAdminServer}, and {@link Tm1WebContainer} and
 * {@link PawContainer} list them all from it.
 *
 * <pre>{@code
 * try (var admin = new Tm1AdminServerContainer(tm1Source);
 *      var planning = new Tm1ServerContainer(tm1Source).withEnv("TM1_SERVER_NAME", "planning").withAdminServer(admin);
 *      var reporting = new Tm1ServerContainer(tm1Source).withEnv("TM1_SERVER_NAME", "reporting").withAdminServer(admin)) {
 *     planning.start();
 *     reporting.start();
 *     String servers = admin.getRestUrl() + "Servers";   // lists planning and reporting
 * }
 * }</pre>
 *
 * <p>
 * The Admin Server is part of the TM1 install, so it takes the same {@link Tm1Source}.
 * Other containers reach it on their shared network as {@value #NETWORK_ALIAS}.
 */
public class Tm1AdminServerContainer extends GenericContainer<Tm1AdminServerContainer> {

	/** The Admin Server's REST port inside the container. */
	public static final int REST_PORT = 5895;

	/** The container's name on its Docker network, which TM1 servers register with. */
	public static final String NETWORK_ALIAS = "tm1-admin";

	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	private final Tm1Source source;

	private final List<Bind> binds = new ArrayList<>();

	/**
	 * An Admin Server on the default {@code tm1-server} image (or
	 * {@code TM1_SERVER_IMAGE}), from {@code source}'s TM1 install.
	 * @param source where TM1 comes from
	 */
	public Tm1AdminServerContainer(Tm1Source source) {
		this(Tm1ServerContainer.defaultImage(source), source);
	}

	/**
	 * An Admin Server on {@code image}, which must be {@code tm1-server} or built on it.
	 * @param image the image to run
	 * @param source where TM1 comes from
	 */
	@SuppressWarnings("this-escape") // configuring in the constructor is the
										// Testcontainers pattern
	public Tm1AdminServerContainer(DockerImageName image, Tm1Source source) {
		super(image);
		this.source = Objects.requireNonNull(source, "source");
		withCommand("admin");
		withExposedPorts(REST_PORT);
		withNetworkAliases(NETWORK_ALIAS);
		// TM1 only exists for x86-64. On Apple Silicon this runs under Rosetta.
		withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
		withCreateContainerCmdModifier(cmd -> {
			HostConfig host = Objects.requireNonNull(cmd.getHostConfig(), "hostConfig");
			List<Bind> all = new ArrayList<>(host.getBinds() == null ? List.of() : Arrays.asList(host.getBinds()));
			all.addAll(binds);
			host.withBinds(all);
		});
		// The image's healthcheck in admin mode: the server list answers without a login
		waitingFor(Wait.forHttp("/api/v1/Servers")
			.forPort(REST_PORT)
			.forStatusCode(200)
			.withStartupTimeout(STARTUP_TIMEOUT));
		Tm1ServerContainer.mountSource(source, binds, this::withEnv);
	}

	/**
	 * Where TM1 comes from.
	 * @return the source
	 */
	public Tm1Source getSource() {
		return source;
	}

	/**
	 * The Admin Server's REST API as seen from the host, e.g.
	 * {@code http://localhost:54321/api/v1/}. {@code Servers} lists the registered TM1
	 * servers.
	 * @return the service root, ending in {@code /}
	 */
	public String getRestUrl() {
		return "http://" + getHost() + ":" + getMappedPort(REST_PORT) + "/api/v1/";
	}

	@Override
	protected void containerIsStarted(InspectContainerResponse info) {
		logger().info("TM1 Admin Server is up at {} ({})", getRestUrl(), source.describe());
	}

}
