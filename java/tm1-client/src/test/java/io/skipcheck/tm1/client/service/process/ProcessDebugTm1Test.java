// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.model.ProcessCreate;
import io.skipcheck.tm1.client.model.ProcessDebugContext;
import io.skipcheck.tm1.client.model.ProcessDebugContextLineBreakpoint;
import io.skipcheck.tm1.client.model.ProcessDebugContextLineBreakpointPatch;
import io.skipcheck.tm1.client.model.ProcessDebugContextStackFrame;
import io.skipcheck.tm1.client.model.ProcessDebugContextStatus;
import io.skipcheck.tm1.client.model.ProcessDebugContextVariable;
import io.skipcheck.tm1.client.model.ProcessExecuteStatusCode;
import io.skipcheck.tm1.client.model.ProcessProcedure;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Mono;

/** Debugging a process of three lines: nA = 1; nB = nA + 1; nC = nB + 1. */
@RequiresTm1
class ProcessDebugTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1Client tm1;

	private static ProcessDebugService debug;

	private static String process;

	@BeforeAll
	static void createProcess(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		debug = tm1.processDebugging();
		process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes()
			.create(ProcessCreate.builder()
				.name(process)
				.prologProcedure("nA = 1;\nnB = nA + 1;\nnC = nB + 1;\n")
				.build())
			.block(WAIT);
	}

	@AfterAll
	static void deleteProcess() {
		tm1.processes().delete(process).block(WAIT);
		tm1.close().block(WAIT);
	}

	@Test
	void stopsAtABreakpointReadsTheStateAndRunsToTheEnd() {
		String id = debug.debug(process, Map.of(), ODataQuery.none()).block(WAIT).id();
		try {
			stopsAndRunsToTheEnd(id);
		}
		finally {
			// A run left paused holds the process, and its delete would wait for it
			debug.finish(id).onErrorComplete().block(WAIT);
		}
	}

	/**
	 * The context once the run has stopped moving. TM1 answers a start, a resume or a
	 * step with the status of that moment, RUNNING or already PAUSED.
	 */
	private static ProcessDebugContext settled(String id) {
		return debug.get(id, ODataQuery.none())
			.repeatWhen(polls -> polls.delayElements(Duration.ofMillis(100)))
			.filter(context -> context.status() != ProcessDebugContextStatus.RUNNING)
			.blockFirst(WAIT);
	}

	private static void stopsAndRunsToTheEnd(String id) {
		assertThat(settled(id).status()).as("paused before the first line").isEqualTo(ProcessDebugContextStatus.PAUSED);
		debug
			.addBreakpoint(id,
					ProcessDebugContextLineBreakpointPatch.builder()
						.id(1)
						.processName(process)
						.procedure(ProcessProcedure.PROLOG)
						.lineNumber(3)
						.enabled(true)
						.build())
			.block(WAIT);
		assertThat(debug.breakpoints(id, ODataQuery.none()).collectList().block(WAIT)).singleElement()
			.isInstanceOfSatisfying(ProcessDebugContextLineBreakpoint.class,
					b -> assertThat(b.lineNumber()).isEqualTo(3));

		assertThat(debug.resume(id, ODataQuery.none()).block(WAIT).status()).isIn(ProcessDebugContextStatus.RUNNING,
				ProcessDebugContextStatus.PAUSED);
		assertThat(settled(id).status()).as("at the breakpoint").isEqualTo(ProcessDebugContextStatus.PAUSED);
		assertThat(debug.currentBreakpoint(id, ODataQuery.none()).block(WAIT)).isInstanceOfSatisfying(
				ProcessDebugContextLineBreakpoint.class, b -> assertThat(b.lineNumber()).isEqualTo(3));
		List<ProcessDebugContextStackFrame> stack = debug.callStack(id, ODataQuery.none()).collectList().block(WAIT);
		assertThat(stack).singleElement().satisfies(f -> {
			assertThat(f.procedure()).isEqualTo(ProcessProcedure.PROLOG);
			assertThat(f.lineNumber()).isEqualTo(3);
		});
		String frame = stack.get(0).id();
		assertThat(debug.variables(id, frame, ODataQuery.none())
			.map(ProcessDebugContextVariable::name)
			.collectList()
			.block(WAIT)).as("TI's own variables, not the process's")
			.contains("NVALUE", "DATASOURCETYPE")
			.doesNotContain("NA", "NB");
		assertThat(debug.frameProcess(id, frame, ODataQuery.none()).block(WAIT).name()).isEqualTo(process);
		assertThat(debug.process(id, ODataQuery.none()).block(WAIT).name()).isEqualTo(process);
		assertThat(debug.thread(id, ODataQuery.none()).block(WAIT).objectName()).isEqualTo(process);
		assertThat(debug.list(ODataQuery.none()).collectList().block(WAIT)).as("lists none").isEmpty();

		assertThat(debug.stepOver(id, ODataQuery.none()).block(WAIT).status())
			.as("past the last line: still running, paused with no line to be on, or already complete")
			.isIn(ProcessDebugContextStatus.RUNNING, ProcessDebugContextStatus.PAUSED,
					ProcessDebugContextStatus.COMPLETE);
		ProcessDebugContext done = debug.finish(id).block(WAIT);
		assertThat(done.status()).isEqualTo(ProcessDebugContextStatus.COMPLETE);
		assertThat(done.result().processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
		assertThat(debug.get(id, ODataQuery.none()).block(WAIT).status()).as("still readable, complete")
			.isEqualTo(ProcessDebugContextStatus.COMPLETE);
		assertThatThrownBy(() -> tm1.session()
			.execute(Tm1Request.delete(ODataPath.of("ProcessDebugContexts").key(id)))
			.block(WAIT)).as("TM1 refuses to delete one").isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void needsAChosenBreakpointIdAndUpdatesAndRemovesByIt() {
		debug.debugging(process, Map.of(), context -> {
			String id = context.id();
			assertThatIllegalArgumentException().isThrownBy(() -> debug.addBreakpoint(id,
					ProcessDebugContextLineBreakpointPatch.builder().processName(process).lineNumber(2).build()));
			return debug.addBreakpoint(id, line(1, 2))
				.then(debug.addBreakpoint(id, line(2, 3)))
				.then(debug.removeBreakpoint(id, 1))
				.then(debug.updateBreakpoint(id, 2,
						ProcessDebugContextLineBreakpointPatch.builder().enabled(false).build()))
				.thenMany(debug.breakpoints(id, ODataQuery.none()))
				.doOnNext(b -> {
					assertThat(b.id()).isEqualTo(2);
					assertThat(b.enabled()).isFalse();
				});
		}).blockLast(WAIT);
	}

	private static ProcessDebugContextLineBreakpointPatch line(int id, int line) {
		return ProcessDebugContextLineBreakpointPatch.builder()
			.id(id)
			.processName(process)
			.procedure(ProcessProcedure.PROLOG)
			.lineNumber(line)
			.enabled(true)
			.build();
	}

	@Test
	void finishesTheRunAfterDebuggingEvenWithBreakpointsLeft() {
		AtomicReference<String> id = new AtomicReference<>();
		debug.debugging(process, Map.of(), context -> {
			id.set(context.id());
			return debug
				.addBreakpoint(context.id(),
						ProcessDebugContextLineBreakpointPatch.builder()
							.id(1)
							.processName(process)
							.procedure(ProcessProcedure.PROLOG)
							.lineNumber(2)
							.enabled(true)
							.build())
				.then(debug.stepIn(context.id(), ODataQuery.none()))
				.then(Mono.just(1));
		}).blockLast(WAIT);
		assertThat(debug.get(id.get(), ODataQuery.none()).block(WAIT).status())
			.isEqualTo(ProcessDebugContextStatus.COMPLETE);
	}

}
