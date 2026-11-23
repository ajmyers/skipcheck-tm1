package io.skipcheck.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * GroupService against a fake TM1. Every call is recorded to
 * {@code spec/conformance/calls/GroupServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class GroupServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsGroups() {
		tm1().json("{\"value\":[{\"Name\":\"ADMIN\"},{\"Name\":\"Planners\"}]}");
		List<Group> groups = conformance.call("groups", "list", ODataQuery.none());
		assertThat(groups).extracting(Group::name).containsExactly("ADMIN", "Planners");
		assertThat(tm1().last().key()).isEqualTo("GET Groups");

		tm1().json("{\"value\":[{\"Name\":\"Planners\",\"Users\":[{\"Name\":\"alice\"}]}]}");
		List<Group> expanded = conformance.call("groups", "list",
				ODataQuery.none().select("Name").expand("Users", u -> u.select("Name")).orderBy("Name").skip(1));
		assertThat(expanded.get(0).users()).extracting(User::name).containsExactly("alice");
		assertThat(tm1().last().target())
			.isEqualTo("Groups?$select=Name&$expand=Users($select%3DName)&$orderby=Name&$skip=1");
	}

	@Test
	void getsAGroup() {
		tm1().json("{\"Name\":\"Planners\"}");
		Group group = conformance.call("groups", "get", "Planners", ODataQuery.none().select("Name"));
		assertThat(group.name()).isEqualTo("Planners");
		assertThat(tm1().last().key()).isEqualTo("GET Groups('Planners')?$select=Name");

		tm1().json("{\"Name\":\"}Sales & O'Neil/1\"}");
		conformance.call("groups", "get", "}Sales & O'Neil/1", ODataQuery.none());
		assertThat(tm1().last().target()).startsWith("Groups('%7DSales%20");
	}

	@Test
	void aMissingGroupIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Group'.");
		Throwable error = conformance.fail("groups", "get", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAGroup() {
		tm1().json(201, "{\"Name\":\"Planners\"}");
		Group created = conformance.call("groups", "create", "Planners");
		assertThat(created.name()).isEqualTo("Planners");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Groups");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo("{\"Name\":\"Planners\"}");
	}

	@Test
	void anExistingGroupIsRefused() {
		tm1().error(400, "278", "Group 'Planners' already exists.");
		Throwable error = conformance.fail("groups", "create", "Planners");
		assertThat(error).isInstanceOfSatisfying(Tm1ApiException.class,
				e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.ALREADY_EXISTS));
	}

	@Test
	void deletesAGroup() {
		tm1().status(204);
		conformance.call("groups", "delete", "O'Neil");
		assertThat(tm1().last().key()).isEqualTo("DELETE Groups('O''Neil')");
	}

	@Test
	void listsAGroupsUsers() {
		tm1().json("{\"value\":[{\"Name\":\"alice\"},{\"Name\":\"bob\"}]}");
		List<User> users = conformance.call("groups", "users", "Planners", ODataQuery.none().select("Name"));
		assertThat(users).extracting(User::name).containsExactly("alice", "bob");
		assertThat(tm1().last().target()).isEqualTo("Groups('Planners')/Users?$select=Name");

		tm1().json("{\"value\":[]}");
		List<User> none = conformance.call("groups", "users", "Empty", ODataQuery.none());
		assertThat(none).isEmpty();
	}

	@Test
	void refusesAMissingName() {
		assertThat(conformance.fail("groups", "create", (Object) null)).isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("groups", "get", null, ODataQuery.none())).isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("groups", "users", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
