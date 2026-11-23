// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;

import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * {@link Tm1ConnectionDetails} from a {@link Tm1ServerContainer} annotated
 * {@code @ServiceConnection}: its REST URL and its login, Basic or, for a container in
 * Cognos security ({@code withCamSecurity}), {@code CAMNamespace} through its namespace.
 * It connects the starter's default server, the one a plain {@code Tm1Client} gets.
 * Registered in {@code META-INF/spring.factories}, so Spring Boot finds it; applications
 * don't use it directly.
 *
 * <p>
 * A Spring Boot test with its TM1 from the shared container:
 *
 * {@snippet class = examples.ServiceConnectionExamples region = test}
 */
public final class Tm1ContainerConnectionDetailsFactory
		extends ContainerConnectionDetailsFactory<Tm1ServerContainer, Tm1ConnectionDetails> {

	/** Created by Spring Boot, through {@code META-INF/spring.factories}. */
	public Tm1ContainerConnectionDetailsFactory() {
	}

	@Override
	protected Tm1ConnectionDetails getContainerConnectionDetails(ContainerConnectionSource<Tm1ServerContainer> source) {
		return new Tm1ContainerConnectionDetails(source);
	}

	/**
	 * How to log in to {@code container}, as its own checks do: {@code CAMNamespace} for
	 * its CAM administrator when it runs in Cognos security ({@code withCamSecurity}),
	 * and Basic otherwise.
	 */
	static Tm1Authenticator authenticator(Tm1ServerContainer container) {
		CognosContainer.Namespace namespace = container.getCamNamespace();
		if (namespace != null) {
			return Tm1Authenticator.camNamespace(container.getUsername(), container.getPassword(), namespace.id());
		}
		return Tm1Authenticator.basic(container.getUsername(), container.getPassword());
	}

	/** The connection a started container offers. */
	static final class Tm1ContainerConnectionDetails extends ContainerConnectionDetails<Tm1ServerContainer>
			implements Tm1ConnectionDetails {

		Tm1ContainerConnectionDetails(ContainerConnectionSource<Tm1ServerContainer> source) {
			super(source);
		}

		@Override
		public String getServiceRoot() {
			return getContainer().getRestUrl();
		}

		@Override
		public Tm1Authenticator getAuthenticator() {
			return authenticator(getContainer());
		}

		@Override
		public @Nullable SslBundle getSslBundle() {
			return super.getSslBundle();
		}

	}

}
