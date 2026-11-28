// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.ProcessCreate;
import io.skipcheck.tm1.client.model.ProcessExecuteStatusCode;
import io.skipcheck.tm1.client.model.Thread;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;

/** The async view against TM1: results through the monitor, and cancelling a run. */
@LiveTm1
class Tm1SessionAsyncTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1Client tm1;

	private static Tm1Client async;

	@BeforeAll
	static void connect(LiveServer server) {
		tm1 = server.clientBuilder().build();
		async = tm1.withAsync(Duration.ofMillis(200));
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void runsAProcessAndReadsACollectionThroughTheMonitor() {
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes().create(ProcessCreate.builder().name(process).prologProcedure("x = 1;\n").build()).block(WAIT);
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
	void waitsBeforeGoingAsyncWhenRequested() {
		String fast = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		String slow = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		try {
			tm1.processes().create(ProcessCreate.builder().name(fast).prologProcedure("x = 1;\n").build()).block(WAIT);
			tm1.processes()
				.create(ProcessCreate.builder().name(slow).prologProcedure("Sleep(2000);\n").build())
				.block(WAIT);
			Tm1Client longWait = tm1.withAsync(Duration.ofMillis(200), Duration.ofSeconds(10));
			assertThat(longWait.processes()
				.executeWithReturn(fast, Map.of(), ODataQuery.none())
				.block(WAIT)
				.processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);

			Tm1Client shortWait = tm1.withAsync(Duration.ofMillis(200), Duration.ofSeconds(1));
			assertThat(shortWait.processes()
				.executeWithReturn(slow, Map.of(), ODataQuery.none())
				.block(WAIT)
				.processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
		}
		finally {
			tm1.processes().delete(fast).onErrorComplete().block(WAIT);
			tm1.processes().delete(slow).onErrorComplete().block(WAIT);
		}
	}

	@Test
	void stopsTheWorkOnTm1WhenCancelled() throws InterruptedException {
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes()
			.create(ProcessCreate.builder()
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

	/**
	 * Waits up to 30 s for a thread (v11) or job (v12) running {@code process} to be
	 * there, or gone.
	 */
	private static boolean waitFor(String process, boolean running) throws InterruptedException {
		boolean v12 = ServerAssumptions.isV12(tm1);
		for (int i = 0; i < 150; i++) {
			boolean found = v12
					? tm1.session()
						.stream(Tm1Request.list(ODataPath.of("Jobs"), ODataQuery.none(), JsonNode.class))
						.map(job -> job.path("Description").asString(""))
						.any(description -> description.contains("Processes('" + process + "')"))
						.block(WAIT)
					: tm1.threads()
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
