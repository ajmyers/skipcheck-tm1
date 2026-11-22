package net.ajmyers.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Configuration only: nothing here starts a container. */
class CognosContainerTest {

	private final CognosContainer cognos = new CognosContainer(new CognosSource.PrivateImage("cognos-server:test"))
		.withUser("admin", "adminpw", "Admins")
		.withUser("analyst", "analyst#pw", "Analysts", "Readers")
		.withUser("viewer", "viewerpw", "Readers");

	@Test
	void passesTheUsersToTheImage() {
		assertThat(cognos.usersSpec())
			.isEqualTo("admin:adminpw:Admins analyst:analyst#pw:Analysts,Readers viewer:viewerpw:Readers");
		assertThat(cognos.getGroups()).containsExactly("Admins", "Analysts", "Readers");
	}

	@Test
	void buildsCamLogons() {
		String auth = cognos.camAuthorization("analyst");
		assertThat(auth).startsWith("CAMNamespace ");
		assertThat(new String(Base64.getDecoder().decode(auth.substring("CAMNamespace ".length())),
				StandardCharsets.UTF_8))
			.isEqualTo("analyst:analyst#pw:LDAP");
		assertThatIllegalArgumentException().isThrownBy(() -> cognos.camAuthorization("nobody"))
			.withMessageContaining("nobody");
	}

	@Test
	void refusesUsersTheImageCantTakeApart() {
		assertThatIllegalArgumentException().isThrownBy(() -> cognos.withUser("two words", "pw"));
		assertThatIllegalArgumentException().isThrownBy(() -> cognos.withUser("x", "pa:ss"));
		assertThatIllegalArgumentException().isThrownBy(() -> cognos.withUser("x", "pa ss"));
		assertThatIllegalArgumentException().isThrownBy(() -> cognos.withUser("x", "pw", "Bad Group"));
		assertThatIllegalArgumentException().isThrownBy(() -> cognos.withUser("viewer", "again"))
			.withMessageContaining("already");
	}

	@Test
	void tm1InCamSecurityLogsOnAsTheFirstAdmin() {
		var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test")).withCamSecurity(cognos,
				"admin");
		tm1.configure();
		assertThat(tm1.getUsername()).isEqualTo("admin");
		assertThat(tm1.getAuthorization()).isEqualTo(cognos.camAuthorization("admin"));
		assertThat(tm1.getEnvMap()).containsEntry("TM1_CAM_URI", "http://cognos:9300")
			.containsEntry("TM1_CAM_ADMINS", "admin")
			.containsEntry("TM1_CAM_GROUPS", "Admins Analysts Readers");
		assertThat(tm1.getNetwork()).isSameAs(cognos.getNetwork());
		assertThat(tm1.getCognos()).isSameAs(cognos);
	}

	@Test
	void needsAnAdminWhoIsATestUser() {
		var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"));
		assertThatIllegalArgumentException().isThrownBy(() -> tm1.withCamSecurity(cognos));
		tm1.withCamSecurity(cognos, "nobody");
		assertThatIllegalArgumentException().isThrownBy(tm1::configure).withMessageContaining("nobody");
	}

	@Test
	void nativeSecurityKeepsBasicLogons() {
		var tm1 = new Tm1ServerContainer(new Tm1Source.PrivateImage("tm1-server:test"));
		assertThat(tm1.getAuthorization())
			.isEqualTo("Basic " + Base64.getEncoder().encodeToString("admin:".getBytes(StandardCharsets.UTF_8)));
	}

}
