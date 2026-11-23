package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.version.Tm1Dialect;

/** {@link Tm1ConnectionDetails} from the {@code tm1.*} properties. */
final class PropertiesTm1ConnectionDetails implements Tm1ConnectionDetails {

	private final Tm1Properties properties;

	private final @Nullable SslBundles bundles;

	PropertiesTm1ConnectionDetails(Tm1Properties properties, @Nullable SslBundles bundles) {
		this.properties = properties;
		this.bundles = bundles;
	}

	@Override
	public String getServiceRoot() {
		if (properties.baseUrl() != null) {
			return properties.baseUrl();
		}
		if (properties.address() != null) {
			return Tm1Dialect.V11.serviceRoot(properties.address());
		}
		throw new IllegalStateException("Set tm1.address (such as http://tm1:8010) or tm1.base-url to connect to TM1");
	}

	@Override
	public Tm1Authenticator getAuthenticator() {
		String user = properties.user();
		if (user == null) {
			throw new IllegalStateException("Set tm1.user to log in to TM1");
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
			throw new IllegalStateException("tm1.ssl.bundle is set to " + name + ", but there are no SSL bundles");
		}
		return bundles.getBundle(name);
	}

}
