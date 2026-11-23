// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;
import org.springframework.boot.ssl.SslBundle;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;

/**
 * How to connect to a TM1 server: the source of truth for the server's auto-configured
 * session and pool. By default it comes from the server's properties, {@code tm1.*} or
 * {@code tm1.servers.<name>.*}. A bean of yours named {@code <name>Tm1ConnectionDetails}
 * takes its place, for any server. For the default server, so does a bean of yours under
 * any other name, or {@code @ServiceConnection} on a {@code Tm1ServerContainer} (with
 * {@code tm1-spring-boot-testcontainers}); see {@link Tm1AutoConfiguration}.
 *
 * <p>
 * A bean of your own for the default server, for credentials the properties can't
 * describe:
 *
 * {@snippet class = examples.Tm1ConnectionDetailsExamples region = own-bean}
 */
public interface Tm1ConnectionDetails extends ConnectionDetails {

	/**
	 * The REST API's service root, such as {@code http://tm1:8010/api/v1/}.
	 * @return the service root
	 */
	String getServiceRoot();

	/**
	 * The credentials to log in with.
	 * @return the authenticator
	 */
	Tm1Authenticator getAuthenticator();

	/**
	 * The SSL bundle to connect with, for a server on HTTPS with certificates the JVM
	 * doesn't trust by default.
	 * @return the bundle, or {@code null} for the JVM's defaults
	 */
	default @Nullable SslBundle getSslBundle() {
		return null;
	}

}
