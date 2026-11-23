package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.ProcessExecuteStatusCode;
import io.skipcheck.tm1.client.model.ProcessPatch;
import io.skipcheck.tm1.client.model.Thread;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.Disposable;

/** The async view against TM1: results through the monitor, and cancelling a run. */
@RequiresTm1
class Tm1SessionAsyncTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1Client tm1;

	private static Tm1Client async;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		async = tm1.withAsync(Duration.ofMillis(200));
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void runsAProcessAndReadsACollectionThroughTheMonitor() {
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes().create(ProcessPatch.builder().name(process).prologProcedure("x = 1;\n").build()).block(WAIT);
		try {
			assertThat(async.processes()
				.executeWithReturn(process, Map.of(), ODataQuery.none())
				.block(WAIT)
				.processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
			assertThat(async.cubes().list(ODataQuery.none().select("Name")).collectList().block(WAIT)).isNotEmpty();
		}
		finally {
			tm1.processes().delete(process).block(WAIT);
		}
	}

	@Test
	void stopsTheWorkOnTm1WhenCancelled() throws InterruptedException {
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes()
			.create(ProcessPatch.builder()
				.name(process)
				.prologProcedure("i = 0;\nWhile(i < 300000000);\n  i = i + 1;\nEnd;\n")
				.build())
			.block(WAIT);
		try {
			Disposable run = async.processes().executeWithReturn(process, Map.of(), ODataQuery.none()).subscribe();
			assertThat(waitFor(process, true)).as("the process runs").isTrue();
			run.dispose();
			assertThat(waitFor(process, false)).as("cancelling deleted the monitor, which stopped it").isTrue();
		}
		finally {
			waitFor(process, false);
			tm1.processes().delete(process).block(WAIT);
		}
	}

	/** Waits up to 30 s for a thread running {@code process} to be there, or gone. */
	private static boolean waitFor(String process, boolean running) throws InterruptedException {
		for (int i = 0; i < 150; i++) {
			boolean found = tm1.threads()
				.list(ODataQuery.none())
				.map(Thread::function)
				.any(function -> function != null && function.contains("Processes('" + process + "')"))
				.block(WAIT);
			if (found == running) {
				return true;
			}
			java.lang.Thread.sleep(200);
		}
		return false;
	}

}
