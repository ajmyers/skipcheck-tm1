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

	@Override
	public String getServiceRoot() {
		if (properties.baseUrl() != null) {
			return properties.baseUrl();
		}
		if (properties.address() != null) {
			return Tm1Dialect.V11.serviceRoot(properties.address());
		}
		throw new IllegalStateException(
				"Set " + prefix + ".address (such as http://tm1:8010) or " + prefix + ".base-url to connect to TM1");
	}

	@Override
	public Tm1Authenticator getAuthenticator() {
		String user = properties.user();
		if (user == null) {
			throw new IllegalStateException("Set " + prefix + ".user to log in to TM1");
		}
		String namespace = properties.namespace();
		String password = properties.password() == null ? "" : properties.password();
		if (namespace != null && properties.cognosUrl() != null) {
			return Tm1Authenticator.cognosLogon(properties.cognosUrl(), namespace, user, password);
		}
		if (namespace != null) {
			return Tm1Authenticator.camNamespace(user, password, namespace);
		}
		return Tm1Authenticator.basic(user, properties.password());
	}

	@Override
	public @Nullable SslBundle getSslBundle() {
		String name = properties.ssl().bundle();
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
