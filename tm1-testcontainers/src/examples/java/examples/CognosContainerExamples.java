package examples;

import java.time.Duration;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.auth.Tm1Authenticator;
import net.ajmyers.tm1.client.model.User;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.CognosContainer;
import net.ajmyers.tm1.testcontainers.CognosSource;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import net.ajmyers.tm1.testcontainers.Tm1Source;

/**
 * Examples for {@link CognosContainer}, quoted in its Javadoc and
 * {@link Tm1ServerContainer}'s. Test code, so it may block.
 */
final class CognosContainerExamples {

	private static final Duration WAIT = Duration.ofSeconds(30);

	String camSecurity(CognosSource cognosSource, Tm1Source tm1Source) {
		// @start region = cam-security
		try (var cognos = new CognosContainer(cognosSource).withUser("admin", "adminpw", "Admins")
			.withUser("viewer", "viewerpw", "Readers");
				var server = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, "admin")) {
			server.start(); // starts Cognos first

			// Each test user logs in with their own identity, through the LDAP namespace
			Tm1Client viewer = Tm1Client.builder()
				.baseUrl(server.getRestUrl())
				.authenticator(Tm1Authenticator.camNamespace("viewer", "viewerpw", CognosContainer.NAMESPACE))
				.build();
			try {
				// TM1 knows the user by their CAMID
				return viewer.users().activeUser(ODataQuery.none().select(User.NAME)).map(User::name).block(WAIT);
			}
			finally {
				viewer.close().block(WAIT);
			}
		}
		// @end
	}

	private CognosContainerExamples() {
	}

}
