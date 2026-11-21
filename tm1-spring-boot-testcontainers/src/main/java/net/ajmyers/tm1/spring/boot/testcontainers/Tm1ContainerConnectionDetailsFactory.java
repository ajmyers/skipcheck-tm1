package net.ajmyers.tm1.spring.boot.testcontainers;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;

import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.spring.boot.autoconfigure.Tm1ConnectionDetails;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * {@link Tm1ConnectionDetails} from a {@link Tm1ServerContainer} annotated
 * {@code @ServiceConnection}: its REST URL and its login. Registered in
 * {@code META-INF/spring.factories}, so Spring Boot finds it; applications don't use it
 * directly.
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
			Tm1ServerContainer container = getContainer();
			return Tm1Authenticator.basic(container.getUsername(), container.getPassword());
		}

		@Override
		public @Nullable SslBundle getSslBundle() {
			return super.getSslBundle();
		}

	}

}
