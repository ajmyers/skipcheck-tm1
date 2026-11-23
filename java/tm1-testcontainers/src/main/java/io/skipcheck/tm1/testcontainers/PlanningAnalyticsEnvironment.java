// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;

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
 * namespace.
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
 */
public final class PlanningAnalyticsEnvironment implements Startable {

	private final Network network;

	private final boolean ownsNetwork;

	private final Map<String, Tm1ServerContainer> tm1Servers;

	private final @Nullable Tm1AdminServerContainer adminServer;

	private final @Nullable Tm1WebContainer tm1Web;

	private final @Nullable PawContainer paw;

	private final @Nullable CognosContainer cognos;

	private final @Nullable KeycloakContainer keycloak;

	private PlanningAnalyticsEnvironment(Builder b, Network network) {
		this.network = network;
		this.ownsNetwork = b.network == null;
		this.keycloak = b.keycloak == null ? null : new KeycloakContainer().withNetwork(network);
		CognosContainer cognos = null;
		if (b.cognos != null) {
			cognos = new CognosContainer(
					b.get(b.cognosSource, CognosSource::discover, RequiresCognos.NO_SOURCE_MESSAGE))
				.withNetwork(network);
			if (keycloak != null) {
				cognos.withKeycloak(keycloak);
			}
			// First: the TM1 servers' CAM security is wired from the test users
			b.cognos.accept(cognos);
		}
		this.cognos = cognos;
		Tm1Source tm1Source = b.get(b.tm1Source, Tm1Source::discover, RequiresTm1.NO_SOURCE_MESSAGE);
		this.adminServer = b.adminServer != null || b.tm1.size() > 1
				? new Tm1AdminServerContainer(tm1Source).withNetwork(network) : null;
		Map<String, Tm1ServerContainer> servers = new LinkedHashMap<>();
		for (String name : b.tm1.keySet()) {
			Tm1ServerContainer server = new Tm1ServerContainer(tm1Source).withNetwork(network)
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
			web = new Tm1WebContainer(b.get(b.tm1WebSource, Tm1WebSource::discover, RequiresTm1Web.NO_SOURCE_MESSAGE))
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
			paw = new PawContainer(b.get(b.pawSource, PawSource::discover, RequiresPaw.NO_SOURCE_MESSAGE))
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
	 * Starts every part, in dependency order: Keycloak and Cognos, the Admin Server, the
	 * TM1 servers, then TM1 Web and PAW. Parts that don't depend on each other start in
	 * parallel.
	 */
	@Override
	public void start() {
		List<Startable> containers = new ArrayList<>(containers());
		containers.remove(paw);
		Startables.deepStart(containers).join();
		if (paw != null) {
			paw.start();
		}
	}

	/** Stops every part, the dependents first, and removes the network if it made it. */
	@Override
	public void stop() {
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
		 * customizer, or {@code PAW_ACCEPT_LICENSE=true}). Not available with Cognos yet:
		 * PAW logs users on with TM1's own security.
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
		 * Creates the containers and wires them together. Nothing starts until
		 * {@link PlanningAnalyticsEnvironment#start()}.
		 * @return the environment
		 * @throws IllegalStateException if a part needs one that's missing, or a source
		 * can't be found
		 */
		public PlanningAnalyticsEnvironment build() {
			if (tm1.isEmpty()) {
				throw new IllegalStateException("Add at least one TM1 server with tm1(name)");
			}
			if (keycloak != null && cognos == null) {
				throw new IllegalStateException("Keycloak gives Cognos its OIDC namespace: add cognos(...) too");
			}
			if (camNamespace == Namespace.OIDC && keycloak == null) {
				throw new IllegalStateException("CAM security through OIDC needs keycloak()");
			}
			if (paw != null && cognos != null) {
				throw new IllegalStateException(
						"PAW with Cognos (CAM) security isn't supported yet: PAW logs users on with TM1's own security");
			}
			return new PlanningAnalyticsEnvironment(this, network != null ? network : Network.newNetwork());
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

		private <T> T get(@Nullable T configured, Supplier<Optional<T>> discover, String message) {
			return configured != null ? configured
					: discover.get().orElseThrow(() -> new IllegalStateException(message));
		}

	}

}
