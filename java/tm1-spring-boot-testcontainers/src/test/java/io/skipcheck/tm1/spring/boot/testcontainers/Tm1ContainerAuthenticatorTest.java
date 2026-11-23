package io.skipcheck.tm1.spring.boot.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * The authenticator {@code @ServiceConnection} gives a container's TM1: the one its own
 * checks log in with. Nothing is started.
 */
class Tm1ContainerAuthenticatorTest {

	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	@SuppressWarnings("resource") // never started
	void logsInWithBasicToNativeSecurity() {
		Tm1ServerContainer tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"))
			.withCredentials("admin", "apple");

		assertThat(Tm1ContainerConnectionDetailsFactory.authenticator(tm1).authorization().block(WAIT))
			.isEqualTo(tm1.getAuthorization());
	}

	@Test
	@SuppressWarnings("resource") // never started
	void logsInThroughTheNamespaceToCamSecurity() {
		CognosContainer cognos = new CognosContainer(new CognosSource.PrivateImage("cognos-server:test"))
			.withUser("admin", "adminpw", "Admins");
		// The container sets its CAM administrator's credentials when it starts; set here
		Tm1ServerContainer tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"))
			.withCamSecurity(cognos, CognosContainer.Namespace.OIDC, "admin")
			.withCredentials("admin", "adminpw");

		assertThat(tm1.getCamNamespace()).isEqualTo(CognosContainer.Namespace.OIDC);
		assertThat(Tm1ContainerConnectionDetailsFactory.authenticator(tm1).authorization().block(WAIT))
			.isEqualTo(CognosContainer.camAuthorization("admin", "adminpw", "OIDC"))
			.isEqualTo(tm1.getAuthorization());
	}

}
