package io.skipcheck.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Session;
import io.skipcheck.tm1.client.model.Thread;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * SessionService against a fake TM1. Every call is recorded to
 * {@code spec/conformance/calls/SessionServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class SessionServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsSessionsWithTheirUsers() {
		tm1().json("{\"value\":[{\"ID\":1,\"Context\":\"\"},{\"ID\":7,\"Context\":\"Nightly load\"}]}");
		List<Session> all = conformance.call("sessions", "list", ODataQuery.none());
		assertThat(all).extracting(Session::id).containsExactly(1L, 7L);
		assertThat(tm1().last().key()).isEqualTo("GET Sessions");

		tm1().json("{\"value\":[{\"ID\":7,\"Context\":\"Nightly load\",\"User\":{\"Name\":\"alice\"}}]}");
		List<Session> some = conformance.call("sessions", "list",
				ODataQuery.none()
					.select("ID", "Context")
					.expand("User", u -> u.select("Name"))
					.filter(Filter.raw("Context eq 'Nightly load'")));
		assertThat(some.get(0).user().name()).isEqualTo("alice");
		assertThat(tm1().last().target())
			.startsWith("Sessions?$select=ID,Context&$expand=User($select%3DName)&$filter=");
	}

	@Test
	void getsASessionById() {
		tm1().json("{\"ID\":7,\"Context\":\"Nightly load\",\"Active\":true}");
		Session session = conformance.call("sessions", "get", 7L, ODataQuery.none().expand("Threads"));
		assertThat(session.context()).isEqualTo("Nightly load");
		assertThat(tm1().last().key()).isEqualTo("GET Sessions(7)?$expand=Threads");
	}

	@Test
	void aClosedSessionIsNotFound() {
		tm1().error(404, "278", "'99' can not be found in collection of type 'Session'.");
		Throwable error = conformance.fail("sessions", "get", 99L, ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsTheActiveSession() {
		tm1().json("{\"ID\":1,\"Context\":\"\",\"User\":{\"Name\":\"Admin\"}}");
		Session session = conformance.call("sessions", "activeSession", ODataQuery.none().expand("User"));
		assertThat(session.id()).isEqualTo(1L);
		assertThat(tm1().last().key()).isEqualTo("GET ActiveSession?$expand=User");

		tm1().json("{\"ID\":1}");
		conformance.call("sessions", "activeSession", ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("GET ActiveSession");
	}

	@Test
	void readsASessionsUserAndThreads() {
		tm1().json("{\"Name\":\"alice\"}");
		User user = conformance.call("sessions", "user", 7L, ODataQuery.none().select("Name"));
		assertThat(user.name()).isEqualTo("alice");
		assertThat(tm1().last().key()).isEqualTo("GET Sessions(7)/User?$select=Name");

		tm1().json("{\"value\":[{\"ID\":31,\"State\":\"Run\",\"Function\":\"GET /api/v1/Cubes\"}]}");
		List<Thread> threads = conformance.call("sessions", "threads", 7L,
				ODataQuery.none().filter(Filter.raw("State ne 'Idle'")).orderBy("ID"));
		assertThat(threads).extracting(Thread::id).containsExactly(31L);
		assertThat(tm1().last().target()).startsWith("Sessions(7)/Threads?$filter=");

		tm1().json("{\"value\":[]}");
		List<Thread> none = conformance.call("sessions", "threads", 1L, ODataQuery.none());
		assertThat(none).isEmpty();
	}

	@Test
	void closesAnotherSession() {
		tm1().status(204);
		conformance.call("sessions", "close", 7L);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Sessions(7)/tm1.Close");
		assertThat(post.text()).isEqualTo("{}");

		tm1().error(404, "278", "'99' can not be found in collection of type 'Session'.");
		assertThat(conformance.fail("sessions", "close", 99L)).isInstanceOf(Tm1NotFoundException.class);
	}

}
