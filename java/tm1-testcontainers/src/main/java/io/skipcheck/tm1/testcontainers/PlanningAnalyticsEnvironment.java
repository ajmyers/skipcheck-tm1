// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import io.skipcheck.tm1.testcontainers.CognosContainer.Namespace;

/**
 * A whole Planning Analytics environment for tests, wired together on one Docker network:
 * any number of named TM1 servers, and optionally an Admin Server, TM1 Web, PAW, and
 * Cognos (CAM) security with LDAP and OIDC test users. Two servers and TM1 Web, with a
 * client for each server:
 *
 * {@snippet class = examples.PlanningAnalyticsEnvironmentExamples region = servers}
 *
 * <p>
 * Every part is optional, but TM1 Web, PAW and Cognos need at least one TM1 server. Each
 * part is the module's ordinary container class ({@link Tm1ServerContainer},
 * {@link Tm1AdminServerContainer}, {@link Tm1WebContainer}, {@link PawContainer},
 * {@link CognosContainer}, {@link KeycloakContainer}), and each builder method takes a
 * customizer that receives it, so every setting of those classes is available. The TM1,
 * Admin Server, TM1 Web, PAW and Keycloak customizers run after the environment's own
 * wiring, so they can also change it. The Cognos customizer runs first, because the TM1
 * servers are wired from its test users.
 *
 * <p>
 * The wiring:
 * <ul>
 * <li>TM1 servers are named ({@code TM1_SERVER_NAME}) and reachable by that name on the
 * network.
 * <li>With more than one TM1 server, or with {@link Builder#adminServer()}, they register
 * with one {@link Tm1AdminServerContainer}, and TM1 Web and PAW list every server from
 * it. With one server, its own Admin Server is used.
 * <li>With Cognos, every TM1 server runs in CAM security against it
 * ({@link Builder#camSecurity}); with {@link Builder#keycloak()}, Cognos also has an OIDC
 * namespace. PAW then logs users on through Cognos too, in the same namespace.
 * <li>{@link #start()} starts everything in dependency order, independent parts in
 * parallel. {@link #close()} stops it all.
 * </ul>
 *
 * <p>
 * Where IBM's software comes from is found from the environment, as for each container
 * ({@link Tm1Source#discover()} and the like), unless set on the builder.
 *
 * <p>
 * With Cognos, each test user logs in to every server as themselves:
 *
 * {@snippet class = examples.PlanningAnalyticsEnvironmentExamples region = cam-security}
 *
 * <p>
 * Cognos takes minutes to start, and PAW longer. With {@link Builder#reuse}, the
 * environment keeps its containers running between test runs, so the next run starts in
 * seconds:
 *
 * {@snippet class = examples.PlanningAnalyticsEnvironmentExamples region = reuse}
 *
 * <p>
 * Reuse also needs your own switch, as for any Testcontainers container:
 * {@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties}, or
 * {@code TESTCONTAINERS_REUSE_ENABLE=true}. Without it, {@code reuse} has no effect. With
 * both:
 * <ul>
 * <li>The containers and the network are named after the environment's configuration (its
 * identity, a hash): the label {@code io.skipcheck.tm1.environment} on each container,
 * and the network {@code tm1-environment-<identity>}. A run with the same configuration
 * finds them; a different configuration gets its own, and never another's.
 * <li>{@link #stop()} leaves them running, PAW too. Testcontainers doesn't remove
 * reusable containers at the end of a run either.
 * <li>What tests leave behind stays: objects they created in TM1, sessions, content saved
 * in Cognos. Tests should create uniquely named objects and delete them, as with
 * {@link Tm1ServerContainer#shared()}.
 * <li>A configuration you no longer use keeps its containers running, and Cognos holds
 * about 8 GB. To start clean, remove them all:
 * {@code docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)}, then
 * {@code docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)}.
 * <li>The identity covers each part's image, source, environment variables, command,
 * network aliases, labels, exposed ports and copied files, the Cognos users and the CAM
 * settings. A change made with {@code withCreateContainerCmdModifier} isn't seen: remove
 * the containers after changing one.
 * <li>PAW isn't a Testcontainers container: IBM's compose files give it fixed names, so
 * there's one per Docker host whatever the identity. Each start runs IBM's compose again,
 * which takes about a minute.
 * </ul>
 */
public final class PlanningAnalyticsEnvironment implements Startable {

	/**
	 * The label on a reused environment's containers and network. Its value is the
	 * environment's identity, a hash of its configuration.
	 */
	static final String REUSE_LABEL = "io.skipcheck.tm1.environment";

	/** The start of a reused environment's network name; the identity follows. */
	static final String NETWORK_PREFIX = "tm1-environment-";

	/** The network alias Testcontainers gives every container, different each run. */
	private static final Pattern RANDOM_ALIAS = Pattern.compile("tc-[1-9A-HJ-NP-Za-km-z]{8}");

	private static final Pattern IMAGE_NAME = Pattern.compile("imageName=([^,)]+)");

	private final Network network;

	private final boolean reused;

	private @Nullable String identity;

	private final boolean ownsNetwork;

	private final Map<String, Tm1ServerContainer> tm1Servers;

	private final @Nullable Tm1AdminServerContainer adminServer;

	private final @Nullable Tm1WebContainer tm1Web;

	private final @Nullable PawContainer paw;

	private final @Nullable CognosContainer cognos;

	private final @Nullable KeycloakContainer keycloak;

	private PlanningAnalyticsEnvironment(Builder b, boolean reuseEnabled) {
		this.reused = b.reuse && reuseEnabled;
		Network given = b.network;
		this.network = given != null ? given
				: reused ? new ReusableNetwork(() -> NETWORK_PREFIX + identity()) : Network.newNetwork();
		this.ownsNetwork = given == null;
		this.keycloak = b.keycloak == null ? null : (b.keycloakImage == null ? new KeycloakContainer()
				: new KeycloakContainer(DockerImageName.parse(b.keycloakImage)))
			.withNetwork(network);
		CognosContainer cognos = null;
		if (b.cognos != null) {
			CognosSource source = b.get(b.cognosSource, ibm -> new CognosSource.Zip(ibm, null), CognosSource::discover,
					CognosSource.PrivateImage::new, b.cognosImage, RequiresCognos.NO_SOURCE_MESSAGE);
			cognos = (runs(b.cognosImage, source instanceof CognosSource.PrivateImage)
					? new CognosContainer(image(b.cognosImage, CognosContainer.DEFAULT_IMAGE), source)
					: new CognosContainer(source))
				.withNetwork(network);
			if (keycloak != null) {
				cognos.withKeycloak(keycloak);
			}
			// First: the TM1 servers' CAM security is wired from the test users
			b.cognos.accept(cognos);
		}
		this.cognos = cognos;
		Tm1Source tm1Source = b.get(b.tm1Source, ibm -> new Tm1Source.Bundle(ibm, null), Tm1Source::discover,
				Tm1Source.PrivateImage::new, b.tm1Image, RequiresTm1.NO_SOURCE_MESSAGE);
		boolean tm1Image = runs(b.tm1Image, tm1Source instanceof Tm1Source.PrivateImage);
		this.adminServer = b.adminServer != null || b.tm1.size() > 1 ? (tm1Image
				? new Tm1AdminServerContainer(image(b.tm1Image, Tm1ServerContainer.DEFAULT_IMAGE), tm1Source)
				: new Tm1AdminServerContainer(tm1Source))
			.withNetwork(network) : null;
		Map<String, Tm1ServerContainer> servers = new LinkedHashMap<>();
		for (String name : b.tm1.keySet()) {
			Tm1ServerContainer server = (tm1Image
					? new Tm1ServerContainer(image(b.tm1Image, Tm1ServerContainer.DEFAULT_IMAGE), tm1Source)
					: new Tm1ServerContainer(tm1Source))
				.withNetwork(network)
				.withNetworkAliases(name)
				.withEnv("TM1_SERVER_NAME", name);
			if (adminServer != null) {
				server.withAdminServer(adminServer);
			}
			if (cognos != null) {
				server.withCamSecurity(cognos, b.camNamespace, b.camAdmins(cognos));
			}
			servers.put(name, server);
		}
		this.tm1Servers = Collections.unmodifiableMap(servers);
		Tm1ServerContainer first = servers.values().iterator().next();
		Tm1WebContainer web = null;
		if (b.tm1Web != null) {
			Tm1WebSource source = b.get(b.tm1WebSource, ibm -> new Tm1WebSource.Bundle(ibm, null),
					Tm1WebSource::discover, Tm1WebSource.PrivateImage::new, b.tm1WebImage,
					RequiresTm1Web.NO_SOURCE_MESSAGE);
			web = (runs(b.tm1WebImage, source instanceof Tm1WebSource.PrivateImage)
					? new Tm1WebContainer(image(b.tm1WebImage, Tm1WebContainer.DEFAULT_IMAGE), source)
					: new Tm1WebContainer(source))
				.withTm1Server(first);
			if (adminServer != null) {
				web.withAdminServer(adminServer);
			}
			// List every server: start them all before TM1 Web
			for (Tm1ServerContainer server : servers.values()) {
				web.dependsOn(server);
			}
		}
		this.tm1Web = web;
		PawContainer paw = null;
		if (b.paw != null) {
			paw = new PawContainer(
					b.get(b.pawSource, b::pawFromIbm, PawSource::discover, null, null, RequiresPaw.NO_SOURCE_MESSAGE))
				.withTm1Server(first);
			if (adminServer != null) {
				paw.withAdminServer(adminServer);
			}
		}
		this.paw = paw;

		// The callers' own settings last, so they can change anything set above
		apply(b.adminServer, adminServer);
		b.tm1.forEach((name, customizer) -> customizer.accept(servers.get(name)));
		apply(b.tm1Web, tm1Web);
		apply(b.paw, paw);
		apply(b.keycloak, keycloak);
	}

	/**
	 * Starts building an environment.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Starts building the environment a {@code pa-environment.yaml} describes, the file
	 * {@code pa-setup} reads ({@code docker/pa-setup}), so tests run what developers run.
	 * The file is checked as {@code pa-setup} checks it, with its defaults. The builder
	 * comes back configured; change it further, or call {@link Builder#build()}:
	 *
	 * {@snippet class = examples.PlanningAnalyticsEnvironmentExamples region = file}
	 *
	 * <p>
	 * How each setting maps:
	 * <ul>
	 * <li>{@code tm1.servers.<name>}: {@link Builder#tm1(String, Consumer)}, with
	 * {@code seed} as {@link Tm1ServerContainer#withSeed} and each {@code cfg} parameter
	 * as {@code TM1_CFG_<name>}, written as {@code pa-setup} writes it ({@code true} is
	 * {@code True});
	 * <li>{@code tm1.adminServer}: {@link Builder#adminServer()}; with more than one
	 * server there's one anyway, and {@code false} then fails, as in {@code pa-setup};
	 * <li>{@code tm1Web}: {@link Builder#tm1Web()}, with {@code heap} as
	 * {@code TM1_WEB_HEAP} ({@code 1g});
	 * <li>{@code cognos}: {@link Builder#cognos}, with {@code users} as
	 * {@link CognosContainer#withUser} and {@code memory} as {@code COGNOS_MAX_MEMORY}
	 * ({@code 6144}); {@code oidc} adds {@link Builder#keycloak()}; {@code cam} is
	 * {@link Builder#camSecurity}, by default LDAP with the first user as administrator;
	 * <li>{@code paw}: {@link Builder#paw} with {@link PawContainer#acceptLicense()},
	 * which the file must say ({@code acceptLicense: true}); the zip is {@code zip} in
	 * {@code ibm}, or the newest there;
	 * <li>{@code ibm}: each part's source, the folder as {@link Tm1Source.Bundle},
	 * {@link Tm1WebSource.Bundle} and {@link CognosSource.Zip}. A relative path is from
	 * the file's folder; {@code ~} is the user's home;
	 * <li>{@code images}: the image each part runs, as a container's image argument. A
	 * part with no other source takes its image as the private image with IBM's software
	 * built in ({@link Tm1Source.PrivateImage} and the like).
	 * </ul>
	 *
	 * <p>
	 * A part's source is the one set on the builder, else {@code ibm}, else what
	 * {@code discover()} finds (the variables pa-setup's {@code .env} sets, such as
	 * {@code TM1_BUNDLE_URL}), else its image as a private image.
	 *
	 * <p>
	 * Ignored, because they place things on the host that Testcontainers manages itself:
	 * {@code name} (the compose project; the environment has its own network), every
	 * {@code port}, {@code adminPort} and {@code keycloakPort} (Testcontainers maps
	 * random host ports), and {@code files} (the host folder pa-setup keeps databases in;
	 * each test environment starts from a new database, or one copied in with
	 * {@link Tm1ServerContainer#withData}). They're still checked as {@code pa-setup}
	 * checks them.
	 *
	 * <p>
	 * Refused, because the environment can't run them yet: {@code cognos} without
	 * {@code tm1.servers}, and {@code seed: 24retail}.
	 * @param path the file, usually {@code pa-environment.yaml}
	 * @return a builder configured from the file
	 * @throws IllegalArgumentException if the file can't be read, isn't valid, or asks
	 * for what the environment can't run; the message names the setting
	 */
	public static Builder fromConfig(Path path) {
		return PaEnvironmentFile.read(Objects.requireNonNull(path, "path"));
	}

	/**
	 * Starts every part, in dependency order: Keycloak and Cognos, the Admin Server, the
	 * TM1 servers, then TM1 Web and PAW. Parts that don't depend on each other start in
	 * parallel.
	 */
	@Override
	public void start() {
		if (reused) {
			prepareForReuse();
		}
		List<Startable> containers = new ArrayList<>(containers());
		containers.remove(paw);
		Startables.deepStart(containers).join();
		if (paw != null) {
			paw.start();
		}
	}

	/**
	 * Stops every part, the dependents first, and removes the network if it made it. A
	 * {@linkplain Builder#reuse reused} environment is left running for the next run.
	 */
	@Override
	public void stop() {
		if (reused) {
			return;
		}
		List<Startable> all = new ArrayList<>(containers());
		Collections.reverse(all);
		for (Startable part : all) {
			part.stop();
		}
		if (ownsNetwork) {
			network.close();
		}
	}

	/**
	 * A TM1 server by the name it was added with.
	 * @param name the server's name
	 * @return the server
	 * @throws IllegalArgumentException if there's no such server
	 */
	public Tm1ServerContainer tm1(String name) {
		Tm1ServerContainer server = tm1Servers.get(name);
		if (server == null) {
			throw new IllegalArgumentException("No TM1 server " + name + "; there's " + tm1Servers.keySet());
		}
		return server;
	}

	/**
	 * The TM1 servers, by name, in the order they were added.
	 * @return the servers
	 */
	public Map<String, Tm1ServerContainer> tm1Servers() {
		return tm1Servers;
	}

	/**
	 * The shared Admin Server, present with more than one TM1 server or when asked for.
	 * @return the Admin Server, if there is one
	 */
	public Optional<Tm1AdminServerContainer> adminServer() {
		return Optional.ofNullable(adminServer);
	}

	/**
	 * TM1 Web.
	 * @return TM1 Web
	 * @throws IllegalStateException if it wasn't added
	 */
	public Tm1WebContainer tm1Web() {
		return require(tm1Web, "TM1 Web", "tm1Web()");
	}

	/**
	 * PAW.
	 * @return PAW
	 * @throws IllegalStateException if it wasn't added
	 */
	public PawContainer paw() {
		return require(paw, "PAW", "paw(...)");
	}

	/**
	 * Cognos, with the test users.
	 * @return Cognos
	 * @throws IllegalStateException if it wasn't added
	 */
	public CognosContainer cognos() {
		return require(cognos, "Cognos", "cognos(...)");
	}

	/**
	 * Keycloak, the OIDC provider.
	 * @return Keycloak
	 * @throws IllegalStateException if it wasn't added
	 */
	public KeycloakContainer keycloak() {
		return require(keycloak, "Keycloak", "keycloak()");
	}

	/**
	 * The Docker network every part is on.
	 * @return the network
	 */
	public Network network() {
		return network;
	}

	/**
	 * Whether the containers are kept for later runs: {@link Builder#reuse} and the
	 * developer's Testcontainers reuse switch.
	 */
	boolean reused() {
		return reused;
	}

	/**
	 * The environment's identity: a hash of every part's configuration, the same for the
	 * same configuration in every run. Fixed the first time it's asked for, which
	 * {@link #start()} does before anything starts.
	 */
	synchronized String identity() {
		if (identity == null) {
			StringBuilder all = new StringBuilder();
			for (Startable part : containers()) {
				all.append(describe(part)).append('\n');
			}
			identity = sha256(all.toString()).substring(0, 16);
		}
		return identity;
	}

	/**
	 * Marks every container reusable, with the identity as a label, and takes away the
	 * random network alias Testcontainers adds: Testcontainers finds a container to reuse
	 * by a hash of everything it would create it with, and a new alias each run would
	 * never match.
	 */
	void prepareForReuse() {
		String id = identity();
		for (Startable part : containers()) {
			if (part instanceof GenericContainer<?> container) {
				container.withReuse(true).withLabel(REUSE_LABEL, id);
				container.setNetworkAliases(container.getNetworkAliases()
					.stream()
					.filter(a -> !RANDOM_ALIAS.matcher(a).matches())
					.toList());
			}
		}
	}

	/** One part's configuration, as far as it can be read before it starts. */
	// getCopyToFileContainerPathMap is deprecated, but nothing else reads what withData
	// copies in, and a changed database has to change the identity
	@SuppressWarnings("deprecation")
	private String describe(Startable part) {
		if (part instanceof PawContainer paw) {
			return "PawContainer source=" + paw.getSource() + " settings=" + new TreeMap<>(paw.settings()) + " tm1="
					+ name(paw.getTm1Server());
		}
		GenericContainer<?> c = (GenericContainer<?>) part;
		StringBuilder d = new StringBuilder(c.getClass().getSimpleName());
		d.append(" image=").append(imageName(c));
		d.append(" aliases=")
			.append(new TreeSet<>(
					c.getNetworkAliases().stream().filter(a -> !RANDOM_ALIAS.matcher(a).matches()).toList()));
		d.append(" command=").append(Arrays.toString(c.getCommandParts()));
		d.append(" ports=").append(new TreeSet<>(c.getExposedPorts()));
		d.append(" env=").append(new TreeMap<>(c.getEnvMap()));
		Map<String, String> labels = new TreeMap<>(c.getLabels());
		labels.remove(REUSE_LABEL);
		d.append(" labels=").append(labels);
		Map<String, String> files = new TreeMap<>();
		c.getCopyToFileContainerPathMap().forEach((from, to) -> files.put(to, from.getResolvedPath()));
		d.append(" files=").append(files);
		if (c instanceof Tm1ServerContainer tm1) {
			d.append(" source=").append(tm1.getSource());
			d.append(" cam=").append(tm1.getCamNamespace()).append(tm1.getCamAdmins());
		}
		else if (c instanceof Tm1AdminServerContainer admin) {
			d.append(" source=").append(admin.getSource());
		}
		else if (c instanceof Tm1WebContainer web) {
			d.append(" source=").append(web.getSource()).append(" tm1=").append(name(web.getTm1Server()));
		}
		else if (c instanceof CognosContainer cognos) {
			d.append(" source=").append(cognos.getSource()).append(" users=").append(cognos.usersSpec());
		}
		return d.toString();
	}

	/** A TM1 server's name in this environment, its {@code TM1_SERVER_NAME}. */
	private static @Nullable String name(@Nullable Tm1ServerContainer server) {
		return server == null ? null : server.getEnvMap().get("TM1_SERVER_NAME");
	}

	/**
	 * The image a container runs, without pulling it as {@code getDockerImageName} does.
	 * RemoteDockerImage's toString names it; the rest of that string differs each run.
	 * Should that change, the image is left out of the identity, and Testcontainers' own
	 * hash of each container still tells images apart.
	 */
	private static String imageName(GenericContainer<?> container) {
		Matcher name = IMAGE_NAME.matcher(container.getImage().toString());
		return name.find() ? name.group(1) : "";
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Every part, in start order. */
	List<Startable> containers() {
		List<Startable> all = new ArrayList<>();
		if (keycloak != null) {
			all.add(keycloak);
		}
		if (cognos != null) {
			all.add(cognos);
		}
		if (adminServer != null) {
			all.add(adminServer);
		}
		all.addAll(tm1Servers.values());
		if (tm1Web != null) {
			all.add(tm1Web);
		}
		if (paw != null) {
			all.add(paw);
		}
		return all;
	}

	/**
	 * Whether a part runs pa-setup's image setting: not when its source is a private
	 * image, which is the image.
	 */
	private static boolean runs(@Nullable String image, boolean privateImage) {
		return image != null && !privateImage;
	}

	private static DockerImageName image(@Nullable String image, DockerImageName compatibleWith) {
		return DockerImageName.parse(Objects.requireNonNull(image, "image")).asCompatibleSubstituteFor(compatibleWith);
	}

	private static <T> void apply(@Nullable Consumer<T> customizer, @Nullable T part) {
		if (customizer != null && part != null) {
			customizer.accept(part);
		}
	}

	private static <T> T require(@Nullable T part, String what, String how) {
		if (part == null) {
			throw new IllegalStateException("This environment has no " + what + "; add it with " + how);
		}
		return part;
	}

	/** Builds a {@link PlanningAnalyticsEnvironment}. Every part is optional. */
	public static final class Builder {

		private final Map<String, Consumer<Tm1ServerContainer>> tm1 = new LinkedHashMap<>();

		private @Nullable Consumer<Tm1AdminServerContainer> adminServer;

		private @Nullable Consumer<Tm1WebContainer> tm1Web;

		private @Nullable Consumer<PawContainer> paw;

		private @Nullable Consumer<CognosContainer> cognos;

		private @Nullable Consumer<KeycloakContainer> keycloak;

		private Namespace camNamespace = Namespace.LDAP;

		private List<String> camAdmins = List.of();

		private @Nullable Tm1Source tm1Source;

		private @Nullable Tm1WebSource tm1WebSource;

		private @Nullable PawSource pawSource;

		private @Nullable CognosSource cognosSource;

		private @Nullable Network network;

		private boolean reuse;

		// From pa-environment.yaml (fromConfig): the IBM downloads folder, PAW's zip in
		// it,
		// and each part's image
		@Nullable Path ibm;

		@Nullable String pawZip;

		@Nullable String tm1Image;

		@Nullable String tm1WebImage;

		@Nullable String cognosImage;

		@Nullable String keycloakImage;

		private Builder() {
		}

		/**
		 * Adds a TM1 server with an empty database.
		 * @param name its name ({@code ServerName}), also its name on the network
		 * @return this builder
		 */
		public Builder tm1(String name) {
			return tm1(name, server -> {
			});
		}

		/**
		 * Adds a TM1 server.
		 * @param name its name ({@code ServerName}), also its name on the network:
		 * letters, digits, {@code _} and {@code -}
		 * @param customizer configures the container, e.g. {@code withSeed} or
		 * {@code withData}
		 * @return this builder
		 * @throws IllegalArgumentException if the name isn't allowed or is already used
		 */
		public Builder tm1(String name, Consumer<Tm1ServerContainer> customizer) {
			if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
				throw new IllegalArgumentException("Bad TM1 server name '" + name + "': use letters, digits, _ and -");
			}
			if (tm1.putIfAbsent(name, Objects.requireNonNull(customizer, "customizer")) != null) {
				throw new IllegalArgumentException("TM1 server " + name + " was already added");
			}
			return this;
		}

		/**
		 * Adds a shared Admin Server, even for one TM1 server. With more than one, it's
		 * added anyway.
		 * @return this builder
		 */
		public Builder adminServer() {
			return adminServer(admin -> {
			});
		}

		/**
		 * Adds a shared Admin Server.
		 * @param customizer configures the container
		 * @return this builder
		 */
		public Builder adminServer(Consumer<Tm1AdminServerContainer> customizer) {
			this.adminServer = Objects.requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * Adds TM1 Web, listing every TM1 server.
		 * @return this builder
		 */
		public Builder tm1Web() {
			return tm1Web(web -> {
			});
		}

		/**
		 * Adds TM1 Web.
		 * @param customizer configures the container, e.g. {@code TM1_WEB_HEAP}
		 * @return this builder
		 */
		public Builder tm1Web(Consumer<Tm1WebContainer> customizer) {
			this.tm1Web = Objects.requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * Adds PAW, logging users on to the first TM1 server and listing every one. IBM's
		 * license must be accepted ({@link PawContainer#acceptLicense()} in the
		 * customizer, or {@code PAW_ACCEPT_LICENSE=true}). With {@link #cognos}, users
		 * log on to PAW through Cognos, in the {@linkplain #camSecurity CAM namespace}.
		 * @param customizer configures the container
		 * @return this builder
		 */
		public Builder paw(Consumer<PawContainer> customizer) {
			this.paw = Objects.requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * Adds Cognos, and runs every TM1 server in CAM security against it
		 * ({@link #camSecurity}).
		 * @param customizer configures the container, and adds its test users with
		 * {@link CognosContainer#withUser}
		 * @return this builder
		 */
		public Builder cognos(Consumer<CognosContainer> customizer) {
			this.cognos = Objects.requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * Adds Keycloak, giving Cognos an OIDC namespace with the same test users. Needs
		 * {@link #cognos}.
		 * @return this builder
		 */
		public Builder keycloak() {
			return keycloak(kc -> {
			});
		}

		/**
		 * Adds Keycloak.
		 * @param customizer configures the container
		 * @return this builder
		 */
		public Builder keycloak(Consumer<KeycloakContainer> customizer) {
			this.keycloak = Objects.requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * How the TM1 servers use Cognos: the namespace their administrators and groups
		 * are in, and which test users are TM1 administrators. Default:
		 * {@link Namespace#LDAP}, with the first test user as the administrator.
		 * @param namespace the namespace ({@link Namespace#OIDC} needs
		 * {@link #keycloak()})
		 * @param admins test users to make TM1 administrators; none for the first test
		 * user
		 * @return this builder
		 */
		public Builder camSecurity(Namespace namespace, String... admins) {
			this.camNamespace = Objects.requireNonNull(namespace, "namespace");
			this.camAdmins = List.of(admins);
			return this;
		}

		/**
		 * Where TM1 comes from; {@link Tm1Source#discover()} by default.
		 * @param source the source
		 * @return this builder
		 */
		public Builder tm1Source(Tm1Source source) {
			this.tm1Source = Objects.requireNonNull(source, "source");
			return this;
		}

		/**
		 * Where TM1 Web comes from; {@link Tm1WebSource#discover()} by default.
		 * @param source the source
		 * @return this builder
		 */
		public Builder tm1WebSource(Tm1WebSource source) {
			this.tm1WebSource = Objects.requireNonNull(source, "source");
			return this;
		}

		/**
		 * Where PAW comes from; {@link PawSource#discover()} by default.
		 * @param source the source
		 * @return this builder
		 */
		public Builder pawSource(PawSource source) {
			this.pawSource = Objects.requireNonNull(source, "source");
			return this;
		}

		/**
		 * Where Cognos comes from; {@link CognosSource#discover()} by default.
		 * @param source the source
		 * @return this builder
		 */
		public Builder cognosSource(CognosSource source) {
			this.cognosSource = Objects.requireNonNull(source, "source");
			return this;
		}

		/**
		 * The Docker network to put everything on; a new one, removed by
		 * {@link PlanningAnalyticsEnvironment#close()}, by default.
		 * @param network the network
		 * @return this builder
		 */
		public Builder network(Network network) {
			this.network = Objects.requireNonNull(network, "network");
			return this;
		}

		/**
		 * Keeps the containers running between test runs, when you've also turned on
		 * Testcontainers reuse ({@code testcontainers.reuse.enable=true} in
		 * {@code ~/.testcontainers.properties}, or
		 * {@code TESTCONTAINERS_REUSE_ENABLE=true}). A later run with the same
		 * configuration finds them, and objects tests left behind are still there.
		 * Without the switch it has no effect. See {@link PlanningAnalyticsEnvironment}
		 * for what's reused and how to start clean.
		 *
		 * <p>
		 * With your own {@link #network}, reuse only works if it's the same network each
		 * run: Testcontainers finds a container by everything it's created with,
		 * including its network's ID.
		 * @param reuse whether to keep the containers
		 * @return this builder
		 */
		public Builder reuse(boolean reuse) {
			this.reuse = reuse;
			return this;
		}

		/**
		 * Creates the containers and wires them together. Nothing starts until
		 * {@link PlanningAnalyticsEnvironment#start()}.
		 * @return the environment
		 * @throws IllegalStateException if a part needs one that's missing, or a source
		 * can't be found
		 */
		public PlanningAnalyticsEnvironment build() {
			return build(TestcontainersConfiguration.getInstance().environmentSupportsReuse());
		}

		/** Builds, with the developer's reuse switch given, so tests needn't set it. */
		PlanningAnalyticsEnvironment build(boolean reuseEnabled) {
			if (tm1.isEmpty()) {
				throw new IllegalStateException("Add at least one TM1 server with tm1(name)");
			}
			if (keycloak != null && cognos == null) {
				throw new IllegalStateException("Keycloak gives Cognos its OIDC namespace: add cognos(...) too");
			}
			if (camNamespace == Namespace.OIDC && keycloak == null) {
				throw new IllegalStateException("CAM security through OIDC needs keycloak()");
			}
			return new PlanningAnalyticsEnvironment(this, reuseEnabled);
		}

		private String[] camAdmins(CognosContainer cognos) {
			if (!camAdmins.isEmpty()) {
				return camAdmins.toArray(new String[0]);
			}
			List<CognosContainer.User> users = cognos.getUsers();
			if (users.isEmpty()) {
				throw new IllegalStateException("Cognos needs test users for CAM security: add them with withUser");
			}
			return new String[] { users.get(0).name() };
		}

		/**
		 * A part's source: the one set on the builder, else the file's {@code ibm}
		 * folder, else what {@code discover} finds, else the file's image as a private
		 * image.
		 */
		private <T> T get(@Nullable T configured, Function<Path, T> fromIbm, Supplier<Optional<T>> discover,
				@Nullable Function<String, T> privateImage, @Nullable String image, String message) {
			if (configured != null) {
				return configured;
			}
			if (ibm != null) {
				if (!Files.isDirectory(ibm)) {
					throw new IllegalStateException("ibm in pa-environment.yaml isn't a folder: " + ibm);
				}
				return fromIbm.apply(ibm);
			}
			Optional<T> found = discover.get();
			if (found.isPresent()) {
				return found.get();
			}
			if (privateImage != null && image != null) {
				return privateImage.apply(image);
			}
			throw new IllegalStateException(message);
		}

		private PawSource pawFromIbm(Path ibm) {
			if (pawZip != null) {
				return new PawSource(ibm.resolve(pawZip));
			}
			return PawSource.discover(name -> PawSource.BUNDLE_DIR.equals(name) ? ibm.toString() : null)
				.orElseThrow(() -> new IllegalStateException("No PAW Local zip in " + ibm));
		}

	}

	/**
	 * A reused environment's network: named after its identity, found if it's there and
	 * created if not. It carries none of Testcontainers' session labels, so
	 * Testcontainers doesn't remove it after the run, and {@link #close()} keeps it for
	 * the next.
	 */
	static final class ReusableNetwork implements Network {

		private final Supplier<String> name;

		private @Nullable String id;

		ReusableNetwork(Supplier<String> name) {
			this.name = name;
		}

		/**
		 * The network's name.
		 * @return {@code tm1-environment-<identity>}
		 */
		String getName() {
			return name.get();
		}

		@Override
		public synchronized String getId() {
			if (id == null) {
				id = findOrCreate(DockerClientFactory.instance().client(), getName());
			}
			return id;
		}

		/** Keeps the network for the next run. */
		@Override
		public void close() {
		}

		private static String findOrCreate(DockerClient docker, String name) {
			Optional<String> found = find(docker, name);
			if (found.isPresent()) {
				return found.get();
			}
			try {
				return docker.createNetworkCmd()
					.withName(name)
					.withCheckDuplicate(true)
					.withLabels(Map.of(REUSE_LABEL, name.substring(NETWORK_PREFIX.length())))
					.exec()
					.getId();
			}
			catch (ConflictException e) {
				// Another test run created it first
				return find(docker, name).orElseThrow(() -> e);
			}
		}

		private static Optional<String> find(DockerClient docker, String name) {
			// The name filter matches part of a name, so check the whole
			return docker.listNetworksCmd()
				.withNameFilter(name)
				.exec()
				.stream()
				.filter(network -> name.equals(network.getName()))
				.map(network -> network.getId())
				.findFirst();
		}

	}

}
