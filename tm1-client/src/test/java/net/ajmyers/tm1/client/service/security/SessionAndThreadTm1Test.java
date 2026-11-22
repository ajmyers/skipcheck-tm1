package net.ajmyers.tm1.client.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.error.Tm1ServerException;
import net.ajmyers.tm1.client.model.ProcessExecuteResult;
import net.ajmyers.tm1.client.model.ProcessPatch;
import net.ajmyers.tm1.client.model.Session;
import net.ajmyers.tm1.client.model.Thread;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * SessionService and ThreadService, with a second client to look at, close and interrupt.
 */
@RequiresTm1
class SessionAndThreadTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1ServerContainer server;

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer container) {
		server = container;
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void readsSessionsTheirUsersAndThreadsAndClosesAnother() {
		String label = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		Tm1Client other = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.sessionContext(label)
			.build();
		try {
			other.connect().block(WAIT);
			long id = other.sessions().activeSession(ODataQuery.none()).block(WAIT).id();
			assertThat(tm1.sessions().activeSession(ODataQuery.none()).block(WAIT).id()).as("each client its own")
				.isNotEqualTo(id);
			assertThat(tm1.sessions().list(ODataQuery.none()).map(Session::id).collectList().block(WAIT)).contains(id);
			assertThat(tm1.sessions().get(id, ODataQuery.none()).block(WAIT).context()).isEqualTo(label);
			assertThat(tm1.sessions().user(id, ODataQuery.none()).block(WAIT).name())
				.isEqualToIgnoringCase(server.getUsername());
			assertThat(tm1.sessions().threads(id, ODataQuery.none()).collectList().block(WAIT)).isNotNull();
			assertThat(tm1.users()
				.sessions(server.getUsername(), ODataQuery.none())
				.map(Session::id)
				.collectList()
				.block(WAIT)).contains(id);

			tm1.sessions().close(id).block(WAIT);
			assertThat(other.sessions().activeSession(ODataQuery.none()).block(WAIT).id())
				.as("the other client logs in again, as a new session")
				.isNotEqualTo(id);
		}
		finally {
			other.close().block(WAIT);
		}
	}

	@Test
	void cancelsARunningProcessThroughItsThread() throws Exception {
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes()
			.create(ProcessPatch.builder()
				.name(process)
				.prologProcedure("i = 0;\nWhile(i < 100000000);\n  i = i + 1;\nEnd;\n")
				.build())
			.block(WAIT);
		Tm1Client runner = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		CompletableFuture<ProcessExecuteResult> run = runner.processes()
			.executeWithReturn(process, Map.of(), ODataQuery.none())
			.toFuture();
		try {
			Thread running = null;
			for (int i = 0; i < 100 && running == null; i++) {
				List<Thread> threads = tm1.threads().list(ODataQuery.none()).collectList().block(WAIT);
				running = threads.stream()
					.filter(t -> t.function() != null
							&& t.function().contains("Processes('" + process + "')/tm1.ExecuteWithReturn"))
					.findFirst()
					.orElse(null);
				if (running == null) {
					java.lang.Thread.sleep(100);
				}
			}
			assertThat(running).as("the process's thread").isNotNull();
			assertThat(running.objectName()).as("a process run through REST shows as its request").isEmpty();
			assertThat(tm1.threads().get(running.id(), ODataQuery.none()).block(WAIT).function()).contains(process);
			assertThat(tm1.threads().session(running.id(), ODataQuery.none()).block(WAIT).id()).isNotNull();
			tm1.threads().cancel(running.id()).block(WAIT);
			assertThatThrownBy(() -> run.get(60, TimeUnit.SECONDS))
				.as("the run's request fails, rather than reporting Aborted")
				.hasCauseInstanceOf(Tm1ServerException.class)
				.hasMessageContaining("Cancel");
		}
		finally {
			run.handle((r, e) -> r).get(120, TimeUnit.SECONDS);
			runner.close().onErrorComplete().block(WAIT);
			tm1.processes().delete(process).block(WAIT);
		}
	}

}
