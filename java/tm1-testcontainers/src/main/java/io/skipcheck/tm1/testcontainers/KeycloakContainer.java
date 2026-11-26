// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Keycloak in Docker, as the OpenID Connect provider for a {@link CognosContainer}'s OIDC
 * namespace ({@link CognosContainer#withKeycloak}). Cognos builds a realm in it from its
 * test users when it starts.
 *
 * <p>
 * Its issuer is its address on the shared Docker network, {@code http://keycloak:8080},
 * whichever way it's reached, so Cognos accepts tokens that tests get through the
 * host-side port ({@link #idToken}). The interactive (browser) logon would need a browser
 * that reaches that network address, so it isn't wrapped here.
 */
public class KeycloakContainer extends GenericContainer<KeycloakContainer> {

	/** The Keycloak image. */
	public static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("quay.io/keycloak/keycloak:26.4");

	/**
	 * Overrides the Keycloak image, e.g. another version. Read as a system property, then
	 * an environment variable.
	 */
	public static final String IMAGE = "KEYCLOAK_IMAGE";

	/** Keycloak's HTTP port inside the container. */
	public static final int HTTP_PORT = 8080;

	/** The container's name on its Docker network. */
	public static final String NETWORK_ALIAS = "keycloak";

	/** The realm {@code cognos-server} builds from its test users. */
	public static final String REALM = "cognos";

	/**
	 * The realm's client for applications: public, and its tokens include Cognos's client
	 * in their audience, which Cognos requires.
	 */
	public static final String APP_CLIENT = "tm1-app";

	private static final Pattern ID_TOKEN = Pattern.compile("\"id_token\"\\s*:\\s*\"([^\"]+)\"");

	private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	// Realms to import at start, by name: a TM1 v12 engine's, for OAuth 2.0 logins
	private final Map<String, Supplier<String>> realms = new LinkedHashMap<>();

	// Who gave each realm: an engine's is its own, and a second engine's would replace it
	private final Map<String, Object> owners = new LinkedHashMap<>();

	/** Keycloak on the default image (or {@code KEYCLOAK_IMAGE}), in development mode. */
	public KeycloakContainer() {
		this(defaultImage());
	}

	/**
	 * Keycloak on {@code image}.
	 * @param image a Keycloak image, version 25 or later
	 */
	@SuppressWarnings("this-escape") // configuring in the constructor is the
										// Testcontainers pattern
	public KeycloakContainer(DockerImageName image) {
		super(image);
		withLabel(Tm1ServerContainer.LIBRARY_LABEL, Tm1ServerContainer.LIBRARY);
		withCommand("start-dev");
		withExposedPorts(HTTP_PORT);
		withNetworkAliases(NETWORK_ALIAS);
		withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
		withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin");
		withEnv("KC_HTTP_ENABLED", "true");
		// One issuer for Cognos and the tests, whichever address they use
		withEnv("KC_HOSTNAME", getNetworkUrl());
		waitingFor(new HttpOrExitWaitStrategy("/realms/master", HTTP_PORT, code -> code == 200, Duration.ofMinutes(3)));
	}

	/**
	 * A realm for this Keycloak to import when it starts, as JSON. The JSON is asked for
	 * when Keycloak starts, so whoever gives it can still be changed until then.
	 */
	@SuppressWarnings("ReferenceEquality") // the very engine, not one configured like it
	KeycloakContainer withRealm(String name, Object owner, Supplier<String> json) {
		Object has = owners.get(name);
		if (has != null && has != owner) {
			throw new IllegalStateException("This Keycloak already gives another TM1 v12 engine its realm (" + name
					+ "): each engine needs a Keycloak of its own");
		}
		owners.put(name, owner);
		realms.put(name, json);
		return this;
	}

	/**
	 * The realm files copied in before Keycloak starts, by their path in the container.
	 */
	Map<String, String> realmFiles() {
		Map<String, String> files = new LinkedHashMap<>();
		realms.forEach((name, json) -> files.put("/opt/keycloak/data/import/" + name + "-realm.json", json.get()));
		return files;
	}

	@Override
	protected void configure() {
		if (!realms.isEmpty()) {
			// Added to the command it has: a caller's own options stay
			List<String> command = new ArrayList<>(Arrays.asList(getCommandParts()));
			if (!command.contains("--import-realm")) {
				command.add("--import-realm");
				withCommand(command.toArray(new String[0]));
			}
			realmFiles().forEach((path, json) -> withCopyToContainer(Transferable.of(json), path));
		}
	}

	/**
	 * An access token for a user of a realm, by the password grant, as a program holding
	 * the user's password gets one.
	 */
	String accessToken(String realm, String client, String user, String password) {
		String form = "grant_type=password&client_id=" + encode(client) + "&username=" + encode(user) + "&password="
				+ encode(password);
		HttpRequest request = HttpRequest
			.newBuilder(URI.create(getUrl() + "/realms/" + realm + "/protocol/openid-connect/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.timeout(TIMEOUT)
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		try {
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			Matcher m = ACCESS_TOKEN.matcher(response.body());
			if (response.statusCode() != 200 || !m.find()) {
				throw new IllegalStateException(
						"Keycloak refused " + user + " (HTTP " + response.statusCode() + "): " + response.body());
			}
			return m.group(1);
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
	 * Keycloak as other containers on its network reach it: {@code http://keycloak:8080}.
	 * Also its issuer's base.
	 * @return the in-network URL, without a trailing slash
	 */
	public String getNetworkUrl() {
		return "http://" + NETWORK_ALIAS + ":" + HTTP_PORT;
	}

	/**
	 * Keycloak as seen from the host, e.g. {@code http://localhost:54321}. Its admin
	 * console logs on as {@code admin} / {@code admin}.
	 * @return the URL, without a trailing slash
	 */
	public String getUrl() {
		return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
	}

	/**
	 * A user's ID token from the realm's {@value #APP_CLIENT} client, by the password
	 * grant: what an application that has logged a user on would hold. Cognos logs that
	 * user on with it ({@link CognosContainer#passport}).
	 * @param user a test user's name
	 * @param password the password
	 * @return the ID token (a JWT)
	 * @throws IllegalStateException if Keycloak refuses the logon
	 */
	public String idToken(String user, String password) {
		String form = "grant_type=password&client_id=" + APP_CLIENT + "&scope=openid&username=" + encode(user)
				+ "&password=" + encode(password);
		HttpRequest request = HttpRequest
			.newBuilder(URI.create(getUrl() + "/realms/" + REALM + "/protocol/openid-connect/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.timeout(TIMEOUT)
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		try {
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			Matcher m = ID_TOKEN.matcher(response.body());
			if (response.statusCode() != 200 || !m.find()) {
				throw new IllegalStateException(
						"Keycloak refused " + user + " (HTTP " + response.statusCode() + "): " + response.body());
			}
			return m.group(1);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static DockerImageName defaultImage() {
		String override = System.getProperty(IMAGE, System.getenv(IMAGE));
		if (override != null && !override.isBlank()) {
			return DockerImageName.parse(override.strip()).asCompatibleSubstituteFor(DEFAULT_IMAGE);
		}
		return DEFAULT_IMAGE;
	}

}
