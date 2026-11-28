// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * How to reach a kind of TM1 deployment: structural differences such as the REST API's
 * root path and how a session is closed. {@link #V11} is TM1 11 on-premises;
 * {@link #v12(String)} and {@link #v12(String, String)} are TM1 12 (Planning Analytics
 * Engine), where each database has its own service root under an instance and sessions
 * are closed at the instance level.
 */
public interface Tm1Dialect {

	/** TM1 11 on-premises: the REST API is at {@code http(s)://host:port/api/v1/}. */
	Tm1Dialect V11 = new Tm1Dialect() {

		@Override
		public String name() {
			return "TM1 11";
		}

		@Override
		public String serviceRoot(String address) {
			URI uri = URI.create(Objects.requireNonNull(address, "address").strip());
			if (!uri.isAbsolute() || uri.getRawAuthority() == null || uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException(
						"A TM1 11 address is the server's scheme, host and HTTP port, such as http://tm1:8010: "
								+ address);
			}
			String path = V12Dialect.stripTrailingSlashes(uri.getRawPath());
			if (!path.endsWith("/api/v1")) {
				path = path + "/api/v1";
			}
			return uri.getScheme() + "://" + uri.getRawAuthority() + path + "/";
		}

		@Override
		public String toString() {
			return "Tm1Dialect[" + name() + "]";
		}

	};

	/**
	 * TM1 12 (Planning Analytics Engine) for a database in the default {@code tm1}
	 * instance: {@code http(s)://host:port/tm1/api/v1/Databases('<database>')/}.
	 * @param database the database name
	 * @return the dialect
	 * @throws IllegalArgumentException if {@code database} is blank
	 */
	static Tm1Dialect v12(String database) {
		return v12(V12Dialect.DEFAULT_INSTANCE, database);
	}

	/**
	 * TM1 12 (Planning Analytics Engine) for a database in a named instance:
	 * {@code http(s)://host:port/<instance>/api/v1/Databases('<database>')/}.
	 * @param instance the instance name, a single URL path segment such as {@code tm1}
	 * @param database the database name
	 * @return the dialect
	 * @throws IllegalArgumentException if {@code instance} or {@code database} is blank,
	 * or if {@code instance} contains path separators, whitespace or URL delimiters
	 */
	static Tm1Dialect v12(String instance, String database) {
		return new V12Dialect(instance, database);
	}

	/**
	 * The deployment kind, for logs and messages.
	 * @return the name, such as {@code TM1 11} or {@code TM1 12}
	 */
	String name();

	/**
	 * The REST API's service root for a server's address.
	 * @param address where the server is, such as {@code http://tm1:8010}; a full service
	 * root is accepted too
	 * @return the service root, ending in {@code /}
	 * @throws IllegalArgumentException if {@code address} isn't an address of this kind
	 */
	String serviceRoot(String address);

	/**
	 * The URL a session posts to in order to close itself on TM1.
	 *
	 * <p>
	 * TM1 11 closes at {@code <serviceRoot>ActiveSession/tm1.Close}. TM1 12 closes at the
	 * instance level, {@code /<instance>/api/v1/ActiveSession/tm1s.Close}: calling the
	 * database-level {@code ActiveSession/tm1.Close} on TM1 (12.6.7) returns 204 but
	 * leaves the gateway session alive.
	 * @param serviceRoot the database REST API's service root
	 * @return the full URL to POST to when closing the session
	 * @throws IllegalArgumentException if {@code serviceRoot} isn't a valid service root
	 * for this dialect
	 */
	default String closeUrl(String serviceRoot) {
		URI uri = V12Dialect.parseAbsoluteUri(serviceRoot, "serviceRoot", "Not a service root: " + serviceRoot);
		String path = V12Dialect.stripTrailingSlashes(uri.getRawPath());
		return uri.getScheme() + "://" + uri.getRawAuthority() + path + "/ActiveSession/tm1.Close";
	}

	/**
	 * The URL a session posts to in order to log in with application (service-to-service)
	 * credentials, if this deployment supports it.
	 *
	 * <p>
	 * TM1 11 has no separate auth service and returns empty. TM1 12 (Planning Analytics
	 * Engine) exchanges application credentials at {@code /<instance>/auth/v1/session}.
	 * @param serviceRoot the database REST API's service root
	 * @return the full auth session URL, or empty if this dialect doesn't have one
	 * @throws IllegalArgumentException if {@code serviceRoot} isn't a valid service root
	 * for this dialect
	 */
	default Optional<String> authSessionUrl(String serviceRoot) {
		V12Dialect.parseAbsoluteUri(serviceRoot, "serviceRoot", "Not a service root: " + serviceRoot);
		return Optional.empty();
	}

}
