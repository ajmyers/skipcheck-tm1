// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.version.Tm1Dialect;

/**
 * {@link Tm1ConnectionDetails} from a server's properties, {@code tm1.*} or
 * {@code tm1.servers.<name>.*}.
 */
final class PropertiesTm1ConnectionDetails implements Tm1ConnectionDetails {

	private final Tm1Properties properties;

	private final @Nullable SslBundles bundles;

	/** Where the properties are, such as {@code tm1}, for the messages. */
	private final String prefix;

	PropertiesTm1ConnectionDetails(Tm1Properties properties, @Nullable SslBundles bundles, String prefix) {
		this.properties = properties;
		this.bundles = bundles;
		this.prefix = prefix;
	}

	private static @Nullable String nonBlank(@Nullable String value) {
		return value == null || value.isBlank() ? null : value;
	}

	@Override
	public Tm1Dialect getDialect() {
		String instance = nonBlank(properties.instance());
		String database = nonBlank(properties.database());
		if (instance == null && database == null) {
			return Tm1Dialect.V11;
		}
		if (instance == null || database == null) {
			throw new IllegalStateException(
					"Set both " + prefix + ".instance and " + prefix + ".database to connect with the TM1 12 dialect");
		}
		return Tm1Dialect.v12(instance, database);
	}

	@Override
	public String getServiceRoot() {
		String baseUrl = nonBlank(properties.baseUrl());
		if (baseUrl != null) {
			return baseUrl;
		}
		String address = nonBlank(properties.address());
		if (address != null) {
			return getDialect().serviceRoot(address);
		}
		throw new IllegalStateException(
				"Set " + prefix + ".address (such as http://tm1:8010) or " + prefix + ".base-url to connect to TM1");
	}

	@Override
	public Tm1Authenticator getAuthenticator() {
		String bearerToken = nonBlank(properties.bearerToken());
		String clientId = nonBlank(properties.clientId());
		String clientSecret = nonBlank(properties.clientSecret());
		String namespace = nonBlank(properties.namespace());
		String cognosUrl = nonBlank(properties.cognosUrl());
		String user = nonBlank(properties.user());
		boolean hasPassword = properties.password() != null && !properties.password().isEmpty();
		if (bearerToken != null) {
			if (clientId != null || clientSecret != null) {
				throw new IllegalStateException("Set either " + prefix + ".bearer-token or " + prefix + ".client-id / "
						+ prefix + ".client-secret, not both");
			}
			if (namespace != null || cognosUrl != null) {
				throw new IllegalStateException(
						"Set either " + prefix + ".bearer-token or " + prefix + ".namespace (CAM), not both");
			}
			if (user != null || hasPassword) {
				throw new IllegalStateException("Set either " + prefix + ".bearer-token or " + prefix + ".user / "
						+ prefix + ".password, not both");
			}
			return Tm1Authenticator.bearer(bearerToken);
		}
		if (clientId != null || clientSecret != null) {
			if (clientId == null || clientSecret == null) {
				throw new IllegalStateException("Set both " + prefix + ".client-id and " + prefix
						+ ".client-secret for TM1 12 application authentication");
			}
			if (namespace != null || cognosUrl != null) {
				throw new IllegalStateException("Set either " + prefix + ".client-id / " + prefix + ".client-secret or "
						+ prefix + ".namespace (CAM), not both");
			}
			if (hasPassword) {
				throw new IllegalStateException("Do not set " + prefix + ".password with TM1 12 application "
						+ "authentication (" + prefix + ".client-id / " + prefix + ".client-secret)");
			}
			if (nonBlank(properties.instance()) == null || nonBlank(properties.database()) == null) {
				throw new IllegalStateException("Set " + prefix + ".instance and " + prefix
						+ ".database when using TM1 12 application authentication (" + prefix + ".client-id)");
			}
			if (user == null) {
				throw new IllegalStateException("Set " + prefix + ".user to log in to TM1");
			}
			return Tm1Authenticator.application(clientId, clientSecret, user);
		}
		if (user == null) {
			throw new IllegalStateException("Set " + prefix + ".user to log in to TM1");
		}
		String password = properties.password() == null ? "" : properties.password();
		if (namespace != null && cognosUrl != null) {
			return Tm1Authenticator.cognosLogon(cognosUrl, namespace, user, password);
		}
		if (namespace != null) {
			return Tm1Authenticator.camNamespace(user, password, namespace);
		}
		return Tm1Authenticator.basic(user, properties.password());
	}

	@Override
	public @Nullable SslBundle getSslBundle() {
		String name = nonBlank(properties.ssl().bundle());
		if (name == null) {
			return null;
		}
		if (bundles == null) {
			throw new IllegalStateException(
					prefix + ".ssl.bundle is set to " + name + ", but there are no SSL bundles");
		}
		return bundles.getBundle(name);
	}

}
