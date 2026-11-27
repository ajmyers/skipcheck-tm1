// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import io.skipcheck.tm1.testcontainers.CognosContainer.Namespace;

/**
 * Reads pa-setup's {@code pa-environment.yaml} onto a
 * {@link PlanningAnalyticsEnvironment.Builder}. The checks and defaults are
 * {@code docker/pa-setup/pa_setup.py}'s ({@code normalize}), in its order and with its
 * messages, so a file one accepts the other does too, except for what the environment
 * can't run, which fails here with a message naming the setting.
 */
final class PaEnvironmentFile {

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

	private static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]+");

	private static final List<String> SEEDS = List.of("empty", "sdata", "24retail");

	// The tm1-engine image's rule for a database's and an instance's name
	private static final Pattern ENGINE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

	private static final Pattern PASSWORD_BREAK = Pattern.compile("[:\\s]");

	private final String file;

	private PaEnvironmentFile(String file) {
		this.file = file;
	}

	/** Reads and checks the file, and configures a new builder from it. */
	static PlanningAnalyticsEnvironment.Builder read(Path path) {
		PaEnvironmentFile reader = new PaEnvironmentFile(String.valueOf(path.getFileName()));
		Object cfg;
		try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			cfg = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
		}
		catch (NoSuchFileException e) {
			throw reader.error("not found: " + path.toAbsolutePath());
		}
		catch (IOException e) {
			throw reader.error("can't be read: " + e.getMessage());
		}
		catch (YAMLException e) {
			throw reader.error("isn't valid YAML: " + e.getMessage());
		}
		Path dir = Objects.requireNonNull(path.toAbsolutePath().getParent(), "folder");
		return reader.apply(cfg == null ? Map.of() : cfg, dir);
	}

	private PlanningAnalyticsEnvironment.Builder apply(Object root, Path dir) {
		// pa_setup.normalize: the same checks, in the same order
		Map<?, ?> cfg = keys(root,
				List.of("version", "name", "ibm", "files", "images", "tm1", "tm1Web", "paw", "cognos", "tm1Engine"),
				"the file");
		if (!Integer.valueOf(1).equals(cfg.get("version"))) {
			throw error("version: must be 1");
		}
		Path ibm = cfg.get("ibm") != null && truthy(cfg.get("ibm")) ? hostPath(cfg.get("ibm"), "ibm", dir) : null;
		hostPath(or(cfg.get("files"), "pa-files"), "files", dir);
		Map<?, ?> images = keys(or(cfg.get("images"), Map.of()),
				List.of("tm1Server", "tm1Web", "cognosServer", "keycloak", "tm1Engine", "mongo"), "images");

		Map<?, ?> tm1 = section(cfg, "tm1");
		Map<String, Server> servers = new LinkedHashMap<>();
		Integer admin = null;
		if (tm1 != null) {
			keys(tm1, List.of("servers", "adminServer", "adminPort"), "tm1");
			Object raw = tm1.get("servers");
			if (!(raw instanceof Map<?, ?> named) || named.isEmpty()) {
				throw error("tm1.servers: name at least one server, e.g. tm1: {servers: {tm1: {}}}");
			}
			Set<Integer> usedPorts = new LinkedHashSet<>();
			Set<String> lower = new LinkedHashSet<>();
			int i = 0;
			for (Map.Entry<?, ?> entry : named.entrySet()) {
				String where = "tm1.servers." + entry.getKey();
				if (!(entry.getKey() instanceof String name) || !NAME.matcher(name).matches()) {
					throw error(where + ": server names are letters, digits, _ and -");
				}
				if (!lower.add(name.toLowerCase(Locale.ROOT))) {
					throw error(where + ": two servers differ only in case");
				}
				Map<?, ?> s = keys(or(entry.getValue(), Map.of()), List.of("port", "seed", "cfg"), where);
				int p = port(s.get("port"), where, 8010 + i);
				if (!usedPorts.add(p)) {
					throw error(where + ": port " + p + " is used twice");
				}
				String seed = s.get("seed") != null && truthy(s.get("seed")) ? String.valueOf(s.get("seed")) : "empty";
				if (!SEEDS.contains(seed)) {
					throw error(where + ".seed: one of " + String.join(", ", SEEDS));
				}
				Map<?, ?> extra = mapping(or(s.get("cfg"), Map.of()), where + ".cfg");
				Map<String, String> settings = new LinkedHashMap<>();
				for (Map.Entry<?, ?> setting : extra.entrySet()) {
					String key = String.valueOf(setting.getKey());
					if (!NAME.matcher(key).matches()) {
						throw error(where + ".cfg: '" + key + "' isn't a tm1s.cfg parameter name");
					}
					settings.put(key, cfgValue(setting.getValue(), where + ".cfg." + key));
				}
				servers.put(name, new Server(p, seed, settings));
				i++;
			}
			Object wantsAdmin = tm1.get("adminServer");
			if (Boolean.TRUE.equals(wantsAdmin) || (wantsAdmin == null && servers.size() > 1)) {
				admin = port(tm1.get("adminPort"), "tm1.adminPort", 5895);
			}
			else if (Boolean.FALSE.equals(wantsAdmin) && servers.size() > 1) {
				throw error("tm1.adminServer: several servers need a shared Admin Server");
			}
		}

		Map<?, ?> web = section(cfg, "tm1Web");
		Integer webPort = null;
		String heap = null;
		if (web != null) {
			keys(web, List.of("port", "heap"), "tm1Web");
			if (servers.isEmpty()) {
				throw error("tm1Web: needs a TM1 server (tm1.servers)");
			}
			webPort = port(web.get("port"), "tm1Web", 9510);
			heap = web.get("heap") != null && truthy(web.get("heap")) ? String.valueOf(web.get("heap")) : "1g";
		}

		Map<?, ?> cognos = section(cfg, "cognos");
		Cognos cam = null;
		if (cognos != null) {
			keys(cognos, List.of("port", "users", "oidc", "cam", "memory"), "cognos");
			List<CognosContainer.User> users = new ArrayList<>();
			Object rawUsers = or(cognos.get("users"), List.of());
			if (!(rawUsers instanceof List<?> listed)) {
				throw error("cognos.users: a list of users, each user:password[:group,group]");
			}
			for (int u = 0; u < listed.size(); u++) {
				users.add(user(listed.get(u), "cognos.users[" + u + "]"));
			}
			List<String> names = users.stream().map(CognosContainer.User::name).toList();
			if (new LinkedHashSet<>(names).size() != names.size()) {
				throw error("cognos.users: a user is listed twice");
			}
			Object oidc = cognos.get("oidc");
			if (Boolean.TRUE.equals(oidc)) {
				oidc = Map.of();
			}
			Integer keycloakPort = null;
			// On with true or a mapping, an empty one too: only absent or false is off
			if (oidc != null && !Boolean.FALSE.equals(oidc)) {
				Map<?, ?> o = keys(oidc, List.of("keycloakPort"), "cognos.oidc");
				if (users.isEmpty()) {
					throw error("cognos.oidc: needs cognos.users");
				}
				keycloakPort = port(o.get("keycloakPort"), "cognos.oidc", 8180);
			}
			Map<?, ?> camSection = keys(or(cognos.get("cam"), Map.of()), List.of("namespace", "admins"), "cognos.cam");
			String namespace = (camSection.get("namespace") != null && truthy(camSection.get("namespace"))
					? String.valueOf(camSection.get("namespace")) : "ldap")
				.toLowerCase(Locale.ROOT);
			if (!namespace.equals("ldap") && !namespace.equals("oidc")) {
				throw error("cognos.cam.namespace: ldap or oidc");
			}
			if (namespace.equals("oidc") && keycloakPort == null) {
				throw error("cognos.cam.namespace: oidc needs cognos.oidc");
			}
			List<String> admins = new ArrayList<>();
			if (truthy(camSection.get("admins"))) {
				if (!(camSection.get("admins") instanceof List<?> listedAdmins)) {
					throw error("cognos.cam.admins: a list of cognos.users");
				}
				listedAdmins.forEach(a -> admins.add(String.valueOf(a)));
			}
			else if (!names.isEmpty()) {
				admins.add(names.get(0));
			}
			if (!servers.isEmpty() && users.isEmpty()) {
				throw error("cognos.users: TM1 in CAM security needs test users");
			}
			for (String a : admins) {
				if (!names.contains(a)) {
					throw error("cognos.cam.admins: " + a + " isn't one of cognos.users");
				}
			}
			Object memory = or(cognos.get("memory"), 6144);
			if (!(memory instanceof Integer mb) || mb < 2048) {
				throw error("cognos.memory: the dispatcher heap in MB, at least 2048 (6144 recommended)");
			}
			int cognosPort = port(cognos.get("port"), "cognos", 9300);
			cam = new Cognos(cognosPort, users, keycloakPort,
					namespace.equals("oidc") ? Namespace.OIDC : Namespace.LDAP, admins, mb);
		}

		Map<?, ?> paw = section(cfg, "paw");
		Integer pawPort = null;
		String pawZip = null;
		if (paw != null) {
			keys(paw, List.of("port", "acceptLicense", "zip"), "paw");
			if (servers.isEmpty()) {
				throw error("paw: needs a TM1 server (tm1.servers)");
			}
			if (!Boolean.TRUE.equals(paw.get("acceptLicense"))) {
				throw error("paw.acceptLicense: PAW starts only once you accept IBM's license (true); "
						+ "read it with: unzip -p ipa_workspace_local_*.zip licenses/LA_en");
			}
			if (ibm == null) {
				throw error("paw: needs ibm, the folder with the PAW Local zip");
			}
			pawPort = port(paw.get("port"), "paw", 8800);
			pawZip = paw.get("zip") != null && truthy(paw.get("zip")) ? String.valueOf(paw.get("zip")) : null;
		}

		Map<?, ?> engineSection = section(cfg, "tm1Engine");
		Engine engine = null;
		Integer enginePort = null;
		Integer datastorePort = null;
		Integer engineKeycloakPort = null;
		String enginePassThrough = null;
		if (engineSection != null) {
			keys(engineSection, List.of("port", "databases", "users", "datastore", "oauth2", "passthrough"),
					"tm1Engine");
			// name -> instance, in the file's order
			Map<String, String> databases = new LinkedHashMap<>();
			Object raw = engineSection.get("databases");
			if (raw == null) {
				databases.put(Tm1EngineContainer.DEFAULT_DATABASE, Tm1EngineContainer.DEFAULT_INSTANCE);
			}
			else if (!(raw instanceof Map<?, ?> named) || named.isEmpty()) {
				throw error("tm1Engine.databases: name at least one database, "
						+ "e.g. tm1Engine: {databases: {planning: {}}}");
			}
			else {
				for (Map.Entry<?, ?> entry : named.entrySet()) {
					String where = "tm1Engine.databases." + entry.getKey();
					// No check for names that differ only in case, as tm1.servers has:
					// Sales
					// and sales are two databases to TM1 v12
					if (!(entry.getKey() instanceof String name)) {
						throw error(where + ": YAML reads this name as a number or true/false: put it in quotes");
					}
					if (!ENGINE_NAME.matcher(name).matches()) {
						throw error(where
								+ ": database names are letters, digits, _ and -, starting with a letter or digit");
					}
					if (name.length() < 3) {
						throw error(where + ": TM1 needs database names of at least 3 characters");
					}
					Map<?, ?> d = keys(or(entry.getValue(), Map.of()), List.of("instance"), where);
					Object instance = d.get("instance") == null ? Tm1EngineContainer.DEFAULT_INSTANCE
							: d.get("instance");
					if (!(instance instanceof String in) || !ENGINE_NAME.matcher(in).matches()) {
						throw error(where + ".instance: instance names are letters, digits, _ and -, "
								+ "starting with a letter or digit");
					}
					databases.put(name, in);
				}
			}
			Object listed = engineSection.get("users") != null && truthy(engineSection.get("users"))
					? engineSection.get("users") : List.of();
			if (!(listed instanceof List<?> list)) {
				throw error("tm1Engine.users must be a list, e.g. users: [analyst::Analysts]");
			}
			// Every user first, then the names, as pa_setup: a bad entry is reported
			// before a
			// name listed twice
			// With oauth2 in the section a password is meant, so user:password says what
			// it
			// means: on, or false to keep the passwords with OAuth 2.0 off
			boolean passwordUsed = engineSection.get("oauth2") != null;
			// With pass-through a password is the TM1 server's to know, not this file's
			boolean passwordElsewhere = engineSection.get("passthrough") != null
					&& !Boolean.FALSE.equals(engineSection.get("passthrough"));
			List<EngineUser> engineUsers = new ArrayList<>();
			for (int i = 0; i < list.size(); i++) {
				engineUsers.add(engineUser(list.get(i), "tm1Engine.users[" + i + "]", passwordUsed, passwordElsewhere));
			}
			Set<String> engineNames = new LinkedHashSet<>();
			for (EngineUser user : engineUsers) {
				if (!engineNames.add(user.name().toLowerCase(Locale.ROOT))) {
					throw error("tm1Engine.users: a user is listed twice");
				}
			}
			Object datastore = engineSection.get("datastore");
			Map<?, ?> ds = datastore == null || datastore instanceof Boolean ? Map.of()
					: keys(datastore, List.of("port"), "tm1Engine.datastore");
			// Before the ports, as pa_setup: a file wrong in both says the same in each
			// reader
			Object oauth2 = engineSection.get("oauth2");
			// On with true or a mapping, an empty one too: only absent or false is off
			if (oauth2 != null && !Boolean.FALSE.equals(oauth2)) {
				Map<?, ?> o = keys(Boolean.TRUE.equals(oauth2) ? Map.of() : oauth2, List.of("keycloakPort"),
						"tm1Engine.oauth2");
				if (engineUsers.stream().allMatch(u -> u.password().isEmpty())) {
					throw error("tm1Engine.oauth2: needs a user with a password to log in as, "
							+ "e.g. users: [analyst:analystpw:Analysts]");
				}
				engineKeycloakPort = port(o.get("keycloakPort"), "tm1Engine.oauth2", 8181);
			}
			Object passthrough = engineSection.get("passthrough");
			// Off when absent or false; on, it names the TM1 server that checks logins
			if (passthrough != null && !Boolean.FALSE.equals(passthrough)) {
				if (Boolean.TRUE.equals(passthrough)) {
					throw error("tm1Engine.passthrough: name the TM1 server that checks names and passwords, "
							+ "e.g. passthrough: {server: legacy}");
				}
				Map<?, ?> p = keys(passthrough, List.of("server"), "tm1Engine.passthrough");
				if (!(p.get("server") instanceof String wanted) || !servers.containsKey(wanted)) {
					throw error("tm1Engine.passthrough.server: name one of tm1.servers, whose users then log in "
							+ "to TM1 v12 with their name and password there"
							+ (servers.isEmpty() ? "" : " (" + String.join(", ", servers.keySet()) + ")"));
				}
				if (engineKeycloakPort != null) {
					throw error("tm1Engine: oauth2 and passthrough can't both be on: TM1 would take tokens and "
							+ "refuse a name and password");
				}
				Map<String, String> serverCfg = servers.get(wanted).cfg();
				// Whatever its case: the server's image matches a tm1s.cfg parameter's
				// name in any
				String useSsl = cfgParameter(serverCfg, "UseSSL", "F");
				if (!useSsl.strip().equalsIgnoreCase("f") && !useSsl.strip().equalsIgnoreCase("false")) {
					throw error("tm1Engine.passthrough.server: " + wanted + " has cfg.UseSSL on, and the engine "
							+ "asks it over plain HTTP: take UseSSL out of that server's cfg");
				}
				// With cognos a TM1 server is in CAM security, unless its cfg says
				// another
				// mode: the image applies cfg last
				String mode = cfgParameter(serverCfg, "IntegratedSecurityMode", "5").strip();
				if (cam != null && (mode.equals("4") || mode.equals("5"))) {
					// Only Cognos's users log in to a TM1 server in CAM security
					List<String> strangers = new ArrayList<>();
					for (EngineUser user : engineUsers) {
						if (cam.users().stream().noneMatch(known -> known.name().equalsIgnoreCase(user.name()))) {
							strangers.add(user.name());
						}
					}
					if (!strangers.isEmpty()) {
						throw error("tm1Engine.users: " + String.join(", ", strangers) + " isn't in cognos.users. "
								+ "With cognos, " + wanted + " is in CAM security, and passthrough logs in "
								+ "Cognos's users only: add the name to cognos.users, or take it out of "
								+ "tm1Engine.users");
					}
				}
				enginePassThrough = wanted;
			}
			enginePort = port(engineSection.get("port"), "tm1Engine", 4444);
			// Published only when a port is given
			datastorePort = ds.get("port") == null ? null : port(ds.get("port"), "tm1Engine.datastore", 27017);
			for (String name : servers.keySet()) {
				// The engine's two compose services in pa-setup: a server of that name
				// would
				// be replaced by them
				String lowered = name.toLowerCase(Locale.ROOT);
				if (lowered.equals("tm1-engine") || lowered.equals("tm1-engine-mongo")
						|| (engineKeycloakPort != null && lowered.equals("tm1-engine-keycloak"))) {
					throw error("tm1.servers." + name
							+ ": that name is the TM1 v12 engine's here; give the server another");
				}
			}
			engine = new Engine(databases, engineUsers, engineKeycloakPort != null, enginePassThrough);
		}

		if (servers.isEmpty() && cam == null && engine == null) {
			throw error("nothing to run: add tm1.servers, tm1Engine and/or cognos");
		}
		List<Integer> ports = new ArrayList<>();
		addIfSet(ports, enginePort);
		addIfSet(ports, datastorePort);
		addIfSet(ports, engineKeycloakPort);
		servers.values().forEach(s -> ports.add(s.port()));
		addIfSet(ports, admin);
		addIfSet(ports, webPort);
		if (cam != null) {
			ports.add(cam.port());
			addIfSet(ports, cam.keycloakPort());
		}
		addIfSet(ports, pawPort);
		Set<Integer> dupes = new TreeSet<>();
		for (Integer p : ports) {
			if (ports.indexOf(p) != ports.lastIndexOf(p)) {
				dupes.add(p);
			}
		}
		if (!dupes.isEmpty()) {
			throw error("port(s) " + String.join(", ", dupes.stream().map(String::valueOf).toList())
					+ " used by more than one service");
		}

		// What pa-setup runs and this environment can't
		if (cam != null && servers.isEmpty()) {
			throw error("cognos: PlanningAnalyticsEnvironment needs a TM1 server too (tm1.servers); "
					+ "for Cognos alone, use CognosContainer");
		}
		servers.forEach((name, s) -> {
			if (s.seed().equals("24retail")) {
				throw error("tm1.servers." + name + ".seed: 24retail isn't available in tm1-testcontainers "
						+ "(Tm1Seed has empty and sdata)");
			}
		});

		PlanningAnalyticsEnvironment.Builder b = configure(servers, admin != null, heap, cam, pawPort != null, ibm,
				pawZip, images);
		if (engine != null) {
			Engine wanted = engine;
			b.tm1Engine();
			// Kept apart from the caller's customizer, which a later tm1Engine(...) sets
			b.fileTm1Engine = container -> {
				wanted.databases().forEach((name, instance) -> container.withDatabase(instance, name));
				// TM1 v12 keeps no passwords: a user's is checked and not used
				wanted.users().forEach(u -> container.withUser(u.name(), u.groups().toArray(new String[0])));
				if (wanted.oauth2()) {
					// With OAuth 2.0 a password is the user's at Keycloak
					wanted.users()
						.stream()
						.filter(u -> !u.password().isEmpty())
						.forEach(u -> container.withLogin(u.name(), u.password()));
				}
			};
			if (engine.oauth2()) {
				b.tm1EngineOAuth2();
			}
			if (engine.passThrough() != null) {
				b.tm1EnginePassThrough(engine.passThrough());
			}
		}
		// Whatever the file's sections: an engine added in code runs the file's image too
		b.tm1EngineImage = image(images, "tm1Engine");
		return b;
	}

	private static PlanningAnalyticsEnvironment.Builder configure(Map<String, Server> servers, boolean admin,
			@Nullable String heap, @Nullable Cognos cam, boolean paw, @Nullable Path ibm, @Nullable String pawZip,
			Map<?, ?> images) {
		PlanningAnalyticsEnvironment.Builder b = PlanningAnalyticsEnvironment.builder();
		servers.forEach((name, s) -> b.tm1(name, server -> {
			server.withSeed(s.seed().equals("sdata") ? Tm1Seed.SDATA : Tm1Seed.EMPTY);
			s.cfg().forEach((key, value) -> server.withEnv("TM1_CFG_" + key, value));
		}));
		if (admin) {
			b.adminServer();
		}
		if (heap != null) {
			b.tm1Web(web -> web.withEnv("TM1_WEB_HEAP", heap));
		}
		if (cam != null) {
			b.cognos(cognos -> {
				cam.users().forEach(u -> cognos.withUser(u.name(), u.password(), u.groups().toArray(new String[0])));
				cognos.withEnv("COGNOS_MAX_MEMORY", String.valueOf(cam.memory()));
			});
			if (cam.keycloakPort() != null) {
				b.keycloak();
			}
			b.camSecurity(cam.namespace(), cam.admins().toArray(new String[0]));
		}
		if (paw) {
			b.paw(PawContainer::acceptLicense);
		}
		b.ibm = ibm;
		b.pawZip = pawZip;
		b.tm1Image = image(images, "tm1Server");
		b.tm1WebImage = image(images, "tm1Web");
		b.cognosImage = image(images, "cognosServer");
		b.keycloakImage = image(images, "keycloak");
		return b;
	}

	private static @Nullable String image(Map<?, ?> images, String key) {
		return images.get(key) != null && truthy(images.get(key)) ? String.valueOf(images.get(key)).strip() : null;
	}

	private static void addIfSet(List<Integer> ports, @Nullable Integer port) {
		if (port != null) {
			ports.add(port);
		}
	}

	/** pa_setup.check_keys: a mapping with only the allowed settings. */
	private Map<?, ?> keys(Object section, List<String> allowed, String where) {
		Map<?, ?> map = mapping(section, where);
		Set<String> unknown = new TreeSet<>();
		for (Object key : map.keySet()) {
			if (!allowed.contains(String.valueOf(key))) {
				unknown.add(String.valueOf(key));
			}
		}
		if (!unknown.isEmpty()) {
			throw error(where + ": unknown setting(s) " + String.join(", ", unknown) + " (allowed: "
					+ String.join(", ", allowed) + ")");
		}
		return map;
	}

	private Map<?, ?> mapping(Object section, String where) {
		if (!(section instanceof Map<?, ?> map)) {
			throw error(where + " must be a mapping");
		}
		return map;
	}

	/** pa_setup.section: absent or false is off; true is on with defaults. */
	private @Nullable Map<?, ?> section(Map<?, ?> cfg, String key) {
		Object value = cfg.get(key);
		if (value == null || Boolean.FALSE.equals(value)) {
			return null;
		}
		if (Boolean.TRUE.equals(value)) {
			return Map.of();
		}
		return mapping(value, key);
	}

	private int port(@Nullable Object value, String where, int fallback) {
		Object p = value == null ? fallback : value;
		if (!(p instanceof Integer port) || port < 1 || port > 65535) {
			throw error(where + ": port must be a number from 1 to 65535, not " + p);
		}
		return port;
	}

	private Path hostPath(@Nullable Object value, String what, Path dir) {
		if (!(value instanceof String text) || text.isBlank()) {
			throw error(what + " must be a path");
		}
		String path = text.strip();
		if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
			Path home = Path.of(System.getProperty("user.home"));
			return (path.length() > 1 ? home.resolve(path.substring(2)) : home).normalize();
		}
		return dir.resolve(path).normalize();
	}

	private CognosContainer.User user(@Nullable Object entry, String where) {
		Object name;
		Object password;
		List<Object> groups = new ArrayList<>();
		if (entry instanceof String text) {
			String[] parts = text.split(":", -1);
			if (parts.length != 2 && parts.length != 3) {
				throw error(where + ": expected user:password[:group,group], not '" + text + "'");
			}
			name = parts[0];
			password = parts[1];
			if (parts.length == 3) {
				for (String g : parts[2].split(",", -1)) {
					if (!g.isEmpty()) {
						groups.add(g);
					}
				}
			}
		}
		else if (entry instanceof Map<?, ?>) {
			Map<?, ?> map = keys(entry, List.of("name", "password", "groups"), where);
			name = map.get("name");
			password = map.get("password");
			Object listed = or(map.get("groups"), List.of());
			if (!(listed instanceof List<?> list)) {
				throw error(where + ": groups is a list");
			}
			groups.addAll(list);
		}
		else {
			throw error(where + ": a user is user:password[:groups] or a mapping");
		}
		// Strings only, as in pa_setup: a missing name or a number isn't a user name
		if (!(name instanceof String userName) || !USER.matcher(userName).matches()) {
			throw error(where + ": user name '" + name + "' must be letters, digits, '.', '_' and '-'");
		}
		if (!(password instanceof String pw) || pw.isEmpty() || Pattern.compile("[:\\s]").matcher(pw).find()) {
			throw error(where + ": " + userName + "'s password must be non-empty, without ':' or spaces");
		}
		List<String> groupNames = new ArrayList<>();
		for (Object g : groups) {
			if (!(g instanceof String group) || !USER.matcher(group).matches()) {
				throw error(where + ": group '" + g + "' must be letters, digits, '.', '_' and '-'");
			}
			groupNames.add(group);
		}
		return new CognosContainer.User(userName, pw, List.copyOf(groupNames));
	}

	/** A tm1s.cfg parameter of a server's cfg, whatever the case of its name. */
	private static String cfgParameter(Map<String, String> cfg, String parameter, String otherwise) {
		for (Map.Entry<String, String> setting : cfg.entrySet()) {
			if (setting.getKey().equalsIgnoreCase(parameter)) {
				return setting.getValue();
			}
		}
		return otherwise;
	}

	/**
	 * pa_setup.parse_user with password_optional: an engine's user, whose password may be
	 * left out or empty, since TM1 v12 keeps none.
	 */
	private EngineUser engineUser(@Nullable Object entry, String where, boolean passwordUsed,
			boolean passwordElsewhere) {
		Object name;
		Object password;
		List<Object> groups = new ArrayList<>();
		if (entry instanceof String text) {
			String[] parts = text.split(":", -1);
			if (parts.length == 2 && !parts[1].isEmpty() && !passwordUsed) {
				// The image's own form has groups there; here the second part is a
				// password
				String hint = passwordElsewhere
						? "With passthrough the password is the one the user has on the TM1 server, and isn't "
								+ "written here: write " + parts[0] + "."
						: "A password kept for OAuth 2.0 logins needs oauth2 in the section: oauth2: false keeps "
								+ "it, off.";
				throw error(where + ": '" + text + "' reads as a password, which TM1 v12 doesn't keep: "
						+ "for a group, write " + parts[0] + "::" + parts[1] + ". (" + hint + ")");
			}
			if (parts.length > 3) {
				throw error(
						where + ": expected user, user::group,group or user:password:group,group, not '" + text + "'");
			}
			name = parts[0];
			password = parts.length > 1 ? parts[1] : "";
			if (parts.length == 3) {
				for (String g : parts[2].split(",", -1)) {
					if (!g.isEmpty()) {
						groups.add(g);
					}
				}
			}
		}
		else if (entry instanceof Map<?, ?>) {
			Map<?, ?> map = keys(entry, List.of("name", "password", "groups"), where);
			name = map.get("name");
			password = map.get("password") == null ? "" : map.get("password");
			Object listed = map.get("groups") != null && truthy(map.get("groups")) ? map.get("groups") : List.of();
			if (!(listed instanceof List<?> list)) {
				throw error(where + ": groups must be a list, e.g. groups: [Analysts]");
			}
			groups.addAll(list);
		}
		else {
			throw error(where + ": a user is user:password[:groups] or a mapping");
		}
		if (!(name instanceof String userName) || !USER.matcher(userName).matches()) {
			throw error(where + ": user name '" + name + "' must be letters, digits, '.', '_' and '-'");
		}
		if (!(password instanceof String pw) || PASSWORD_BREAK.matcher(pw).find()) {
			throw error(where + ": " + userName + "'s password must be text without ':' or spaces (quote a number)");
		}
		List<String> groupNames = new ArrayList<>();
		for (Object g : groups) {
			if (!(g instanceof String group) || !USER.matcher(group).matches()) {
				throw error(where + ": group '" + g + "' must be letters, digits, '.', '_' and '-'");
			}
			groupNames.add(group);
		}
		return new EngineUser(userName, pw, List.copyOf(groupNames));
	}

	/** A tm1s.cfg value as pa_setup writes it, with Python's str(): true is True. */
	private String cfgValue(@Nullable Object value, String where) {
		if (value instanceof Boolean flag) {
			return flag ? "True" : "False";
		}
		if (value instanceof String || value instanceof Number) {
			return String.valueOf(value);
		}
		throw error(where + ": a string, number or boolean");
	}

	/** pa_setup's {@code value or fallback}. */
	private static Object or(@Nullable Object value, Object fallback) {
		return value != null && truthy(value) ? value : fallback;
	}

	/** Python's truth: null, false, 0, "" and empty collections are false. */
	private static boolean truthy(@Nullable Object value) {
		if (value == null || Boolean.FALSE.equals(value)) {
			return false;
		}
		if (value instanceof Number n) {
			return n.doubleValue() != 0;
		}
		if (value instanceof String s) {
			return !s.isEmpty();
		}
		if (value instanceof Map<?, ?> m) {
			return !m.isEmpty();
		}
		if (value instanceof List<?> l) {
			return !l.isEmpty();
		}
		return true;
	}

	private IllegalArgumentException error(String message) {
		return new IllegalArgumentException(file + ": " + message);
	}

	private record Engine(Map<String, String> databases, List<EngineUser> users, boolean oauth2,
			@Nullable String passThrough) {
	}

	private record EngineUser(String name, String password, List<String> groups) {
	}

	private record Server(int port, String seed, Map<String, String> cfg) {
	}

	private record Cognos(int port, List<CognosContainer.User> users, @Nullable Integer keycloakPort,
			Namespace namespace, List<String> admins, int memory) {
	}

}
