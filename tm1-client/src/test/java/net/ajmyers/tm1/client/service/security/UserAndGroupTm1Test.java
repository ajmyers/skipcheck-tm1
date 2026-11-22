package net.ajmyers.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Group;
import net.ajmyers.tm1.client.model.User;
import net.ajmyers.tm1.client.model.UserPatch;
import net.ajmyers.tm1.client.model.UserType;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** UserService and GroupService, on a user and two groups of their own. */
@RequiresTm1
class UserAndGroupTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private Tm1ServerContainer server;

	private Tm1Client tm1;

	private String user;

	private String g1;

	private String g2;

	@BeforeEach
	void createUserAndGroups(Tm1ServerContainer server) {
		this.server = server;
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		String id = UUID.randomUUID().toString().substring(0, 8);
		user = "tm1-client-" + id;
		g1 = "tm1-client-g1-" + id;
		g2 = "tm1-client-g2-" + id;
		tm1.groups().create(g1).then(tm1.groups().create(g2)).block(WAIT);
		tm1.users()
			.create(UserPatch.builder().name(user).password("pw").bindGroups(ODataPath.of("Groups").key(g1)).build())
			.block(WAIT);
	}

	@AfterEach
	void deleteUserAndGroups() {
		tm1.users().delete(user).onErrorComplete().block(WAIT);
		tm1.groups().delete(g1).onErrorComplete().then(tm1.groups().delete(g2).onErrorComplete()).block(WAIT);
		tm1.close().block(WAIT);
	}

	@Test
	void createsReadsUpdatesAndDeletesAUser() {
		User read = tm1.users().get(user, ODataQuery.none()).block(WAIT);
		assertThat(read.type()).isEqualTo(UserType.USER);
		assertThat(tm1.users()
			.get(user.toUpperCase(Locale.ROOT).replace("-", " - "), ODataQuery.none())
			.blockOptional(WAIT)).as("names match ignoring case and spaces").isPresent();
		assertThat(read.etag()).as("TM1 sends none for a user").isNull();
		tm1.users().update(user, UserPatch.builder().friendlyName("Friend").build()).block(WAIT);
		assertThat(tm1.users().get(user, ODataQuery.none()).block(WAIT).friendlyName()).isEqualTo("Friend");
		assertThat(tm1.users().activeUser(ODataQuery.none()).block(WAIT).name())
			.isEqualToIgnoringCase(server.getUsername());

		tm1.users().delete(user).block(WAIT);
		assertThatThrownBy(() -> tm1.users().get(user, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void setsGroupsWholeAndListsMembersFromBothSides() {
		assertThat(groups()).containsExactly(g1);
		tm1.users().setGroups(user, List.of(g1, g2)).block(WAIT);
		assertThat(groups()).containsExactlyInAnyOrder(g1, g2);
		tm1.users().setGroups(user, List.of(g2)).block(WAIT);
		assertThat(groups()).as("replaced").containsExactly(g2);
		assertThat(tm1.groups().users(g2, ODataQuery.none()).map(User::name).collectList().block(WAIT))
			.containsExactly(user);
		assertThat(tm1.groups().get(g1, ODataQuery.none()).block(WAIT).name()).as("left, not deleted").isEqualTo(g1);

		assertThatIllegalArgumentException().isThrownBy(
				() -> tm1.users().update(user, UserPatch.builder().bindGroups(ODataPath.of("Groups").key(g1)).build()))
			.withMessageContaining("setGroups");
	}

	@Test
	void deletesTheGroupItselfOnAReferenceDelete() {
		// The call UserService never makes: OData's "remove the link" deletes the group
		tm1.session()
			.execute(Tm1Request.delete(ODataPath.of("Users").key(user).nav("Groups").key(g1).ref()))
			.block(WAIT);
		assertThatThrownBy(() -> tm1.groups().get(g1, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void disconnectsAUser() {
		Tm1Client asUser = Tm1Client.builder().baseUrl(server.getRestUrl()).basicAuth(user, "pw").build();
		try {
			asUser.connect().block(WAIT);
			assertThat(tm1.users().get(user, ODataQuery.none()).block(WAIT).isActive()).isTrue();
			tm1.users().disconnect(user).block(WAIT);
			assertThat(tm1.users().get(user, ODataQuery.none()).block(WAIT).isActive()).isFalse();
		}
		finally {
			asUser.close().onErrorComplete().block(WAIT);
		}
	}

	private List<String> groups() {
		return tm1.users().groups(user, ODataQuery.none()).map(Group::name).collectList().block(WAIT);
	}

}
