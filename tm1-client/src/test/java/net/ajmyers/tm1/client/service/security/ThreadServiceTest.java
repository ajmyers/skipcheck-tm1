package net.ajmyers.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Session;
import net.ajmyers.tm1.client.model.Thread;
import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * ThreadService against a fake TM1. Every call is recorded to
 * {@code spec/conformance/calls/ThreadServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class ThreadServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsThreads() {
		tm1().json("{\"value\":[{\"ID\":31,\"Type\":\"User\",\"Name\":\"Admin\",\"State\":\"Run\","
				+ "\"Function\":\"POST /api/v1/Processes('Load')/tm1.ExecuteWithReturn\",\"ObjectType\":\"\","
				+ "\"ObjectName\":\"\",\"ElapsedTime\":\"P0DT00H00M03S\"},"
				+ "{\"ID\":32,\"State\":\"Idle\",\"ElapsedTime\":\"P1DT02H00M03.5S\",\"WaitTime\":\"P0DT00H00M00S\"}]}");
		List<Thread> threads = conformance.call("threads", "list", ODataQuery.none());
		assertThat(threads).extracting(Thread::id).containsExactly(31L, 32L);
		assertThat(threads.get(1).elapsedTime()).isEqualTo(Duration.ofHours(26).plusMillis(3500));
		assertThat(threads.get(0).objectName()).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET Threads");

		tm1().json("{\"value\":[{\"ID\":31}]}");
		conformance.call("threads", "list",
				ODataQuery.none()
					.select("ID")
					.filter(Filter.raw("contains(Function,'Processes(''Load'')/tm1.ExecuteWithReturn')"))
					.orderByDescending("ID")
					.top(1));
		assertThat(tm1().last().target()).startsWith("Threads?$select=ID&$filter=");
	}

	@Test
	void getsAThread() {
		tm1().json("{\"ID\":31,\"State\":\"Idle\"}");
		Thread thread = conformance.call("threads", "get", 31L, ODataQuery.none().select("ID", "State"));
		assertThat(thread.id()).isEqualTo(31L);
		assertThat(tm1().last().key()).isEqualTo("GET Threads(31)?$select=ID,State");
	}

	@Test
	void aFinishedThreadIsNotFound() {
		tm1().error(404, "278", "'31' can not be found in collection of type 'Thread'.");
		assertThat(conformance.fail("threads", "get", 31L, ODataQuery.none())).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsAThreadsSession() {
		tm1().json("{\"ID\":7,\"Context\":\"Nightly load\"}");
		Session session = conformance.call("threads", "session", 31L, ODataQuery.none().select("ID", "Context"));
		assertThat(session.id()).isEqualTo(7L);
		assertThat(tm1().last().key()).isEqualTo("GET Threads(31)/Session?$select=ID,Context");
	}

	@Test
	void cancelsAThreadsOperation() {
		tm1().status(204);
		conformance.call("threads", "cancel", 31L);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Threads(31)/tm1.CancelOperation");
		assertThat(post.text()).isEqualTo("{}");

		tm1().error(404, "278", "'32' can not be found in collection of type 'Thread'.");
		assertThat(conformance.fail("threads", "cancel", 32L)).isInstanceOf(Tm1NotFoundException.class);
	}

}
