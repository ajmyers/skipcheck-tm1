// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.model.Session;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.model.UserCreate;
import io.skipcheck.tm1.client.model.UserPatch;
import io.skipcheck.tm1.client.model.UserType;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * UserService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/UserServiceTest.json}, which
 * the Python, .NET and TypeScript clients replay.
 */
class UserServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsUsers() {
		tm1().json("{\"value\":[{\"Name\":\"Admin\",\"Type\":\"Admin\"},{\"Name\":\"alice\",\"Type\":\"User\"}]}");
		List<User> all = conformance.call("users", "list", ODataQuery.none());
		assertThat(all).extracting(User::name).containsExactly("Admin", "alice");
		assertThat(all.get(1).type()).isEqualTo(UserType.USER);
		assertThat(tm1().last().key()).isEqualTo("GET Users");

		tm1().json("{\"value\":[{\"Name\":\"alice\",\"Groups\":[{\"Name\":\"Planners\"}]}]}");
		List<User> some = conformance.call("users", "list",
				ODataQuery.none()
					.select("Name")
					.expand("Groups", g -> g.select("Name"))
					.filter(Filter.raw("Enabled eq true"))
					.orderBy("Name")
					.top(10));
		assertThat(some.get(0).groups()).extracting(Group::name).containsExactly("Planners");
		assertThat(tm1().last().target()).startsWith("Users?$select=Name&$expand=Groups($select%3DName)&$filter=");
	}

	@Test
	void followsNextLinks() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Users?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<User> users = conformance.call("users", "list", ODataQuery.none().select("Name"));
		assertThat(users).extracting(User::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly("Users?$select=Name", "Users?$skiptoken=1");
	}

	@Test
	void getsAUser() {
		tm1().json("{\"Name\":\"alice\",\"FriendlyName\":\"Alice\",\"Type\":\"User\",\"IsActive\":false,"
				+ "\"Enabled\":true}");
		User user = conformance.call("users", "get", "alice", ODataQuery.none().select("Name", "FriendlyName"));
		assertThat(user.friendlyName()).isEqualTo("Alice");
		assertThat(user.enabled()).isTrue();
		assertThat(tm1().last().key()).isEqualTo("GET Users('alice')?$select=Name,FriendlyName");
		assertThat(tm1().last().header("Accept")).isEqualTo("application/json");
	}

	@Test
	void escapesAUsersName() {
		tm1().json("{\"Name\":\"O'Neil / Ünï #1\"}");
		conformance.call("users", "get", "O'Neil / Ünï #1", ODataQuery.none().expand("Groups"));
		assertThat(tm1().last().target()).startsWith("Users('O''Neil%20%2F%20");
	}

	@Test
	void aMissingUserIsNotFound() {
		tm1().error(404, "278", "'nobody' can not be found in collection of type 'User'.");
		Throwable error = conformance.fail("users", "get", "nobody", ODataQuery.none());
		assertThat(error).isInstanceOfSatisfying(Tm1NotFoundException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.NOT_FOUND));
	}

	@Test
	void readsTheActiveUser() {
		tm1().json("{\"Name\":\"Admin\",\"Type\":\"Admin\",\"Groups\":[{\"Name\":\"ADMIN\"}]}");
		User user = conformance.call("users", "activeUser", ODataQuery.none().expand("Groups"));
		assertThat(user.type()).isEqualTo(UserType.ADMIN);
		assertThat(tm1().last().key()).isEqualTo("GET ActiveUser?$expand=Groups");

		tm1().json("{\"Name\":\"Admin\"}");
		conformance.call("users", "activeUser", ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("GET ActiveUser");
	}

	@Test
	void createsAUserInGroups() {
		ODataPath groups = ODataPath.of("Groups");
		tm1().json(201, "{\"Name\":\"alice\",\"Type\":\"User\"}");
		User created = conformance.call("users", "create",
				UserCreate.builder()
					.name("alice")
					.friendlyName("Alice")
					.password("initial-password")
					.bindGroups(groups.key("Planners"), groups.key("}Sales & Co"))
					.build());
		assertThat(created.name()).isEqualTo("alice");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Users");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.json().get("Groups@odata.bind").get(0).asString()).isEqualTo("Groups('Planners')");
	}

	@Test
	void anExistingUserIsRefused() {
		tm1().error(400, "278", "User 'alice' already exists.");
		Throwable error = conformance.fail("users", "create", UserCreate.builder().name("alice").build());
		assertThat(error).isInstanceOfSatisfying(Tm1ConflictException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.ALREADY_EXISTS));
	}

	@Test
	void updatesAUser() {
		tm1().status(204);
		Object none = conformance.call("users", "update", "alice",
				UserPatch.builder().friendlyName(null).enabled(false).build());
		assertThat(none).isNull();
		ConformanceTm1.Recorded patch = tm1().last();
		assertThat(patch.key()).isEqualTo("PATCH Users('alice')");
		assertThat(patch.text()).isEqualTo("{\"FriendlyName\":null,\"Enabled\":false}");
		assertThat(patch.header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("users", "update", "O'Neil", UserPatch.builder().password("new").build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Users('O''Neil')");
	}

	@Test
	void refusesAnUpdateThatSetsGroups() {
		// Refused at compile time: UserPatch offers no Groups, as a PATCH with them
		// replaces them; setGroups sets them (#361)
		assertThat(Arrays.stream(UserPatch.Builder.class.getMethods()).map(Method::getName)).doesNotContain("groups",
				"bindGroups");
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void setsGroupsWhole() {
		tm1().status(204);
		conformance.call("users", "setGroups", "alice", List.of("Planners", "O'Neil's / }Team"));
		ConformanceTm1.Recorded patch = tm1().last();
		assertThat(patch.key()).isEqualTo("PATCH Users('alice')");
		assertThat(patch.json().get("Groups@odata.bind").get(1).asString()).startsWith("Groups('O''Neil''s%20");

		tm1().status(204);
		conformance.call("users", "setGroups", "alice", List.of());
		assertThat(tm1().last().text()).isEqualTo("{\"Groups@odata.bind\":[]}");
	}

	@Test
	void refusesAMissingGroup() {
		Throwable error = conformance.fail("users", "setGroups", "alice", Arrays.asList("Planners", null));
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void deletesAUser() {
		tm1().status(204);
		conformance.call("users", "delete", "alice");
		assertThat(tm1().last().key()).isEqualTo("DELETE Users('alice')");

		tm1().error(404, "278", "'nobody' can not be found in collection of type 'User'.");
		Throwable error = conformance.fail("users", "delete", "nobody");
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsAUsersGroupsAndSessions() {
		tm1().json("{\"value\":[{\"Name\":\"ADMIN\"},{\"Name\":\"Planners\"}]}");
		List<Group> groups = conformance.call("users", "groups", "alice", ODataQuery.none().select("Name"));
		assertThat(groups).extracting(Group::name).containsExactly("ADMIN", "Planners");
		assertThat(tm1().last().target()).isEqualTo("Users('alice')/Groups?$select=Name");

		tm1().json("{\"value\":[{\"ID\":7,\"Context\":\"Nightly load\"}]}");
		List<Session> sessions = conformance.call("users", "sessions", "alice",
				ODataQuery.none().expand("Threads", t -> t.select("ID", "State")));
		assertThat(sessions).extracting(Session::id).containsExactly(7L);
		assertThat(tm1().last().target()).isEqualTo("Users('alice')/Sessions?$expand=Threads($select%3DID,State)");

		tm1().json("{\"value\":[]}");
		List<Session> none = conformance.call("users", "sessions", "O'Neil", ODataQuery.none());
		assertThat(none).isEmpty();
		assertThat(tm1().last().target()).isEqualTo("Users('O''Neil')/Sessions");
	}

	@Test
	void disconnectsAUser() {
		tm1().status(204);
		conformance.call("users", "disconnect", "alice");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Users('alice')/tm1.Disconnect");
		assertThat(post.text()).isEqualTo("{}");
	}

	@Test
	void refusesAMissingName() {
		assertThat(conformance.fail("users", "get", null, ODataQuery.none())).isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("users", "delete", (Object) null)).isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("users", "disconnect", (Object) null)).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
