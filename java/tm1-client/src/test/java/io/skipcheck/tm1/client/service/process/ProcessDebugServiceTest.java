// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.model.ProcessDebugContext;
import io.skipcheck.tm1.client.model.ProcessDebugContextBreakpoint;
import io.skipcheck.tm1.client.model.ProcessDebugContextBreakpointCreate;
import io.skipcheck.tm1.client.model.ProcessDebugContextBreakpointPatch;
import io.skipcheck.tm1.client.model.ProcessDebugContextLineBreakpoint;
import io.skipcheck.tm1.client.model.ProcessDebugContextLineBreakpointCreate;
import io.skipcheck.tm1.client.model.ProcessDebugContextStackFrame;
import io.skipcheck.tm1.client.model.ProcessDebugContextStatus;
import io.skipcheck.tm1.client.model.ProcessDebugContextVariable;
import io.skipcheck.tm1.client.model.ProcessProcedure;
import io.skipcheck.tm1.client.model.Thread;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ProcessDebugService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call but {@code debugging}'s, whose argument is a function, is recorded
 * to {@code spec/conformance/calls/ProcessDebugServiceTest.json}, which the Python, .NET
 * and TypeScript clients replay; {@code debugging} is tested here alone, and each client
 * ports those tests by hand.
 */
class ProcessDebugServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String PAUSED = "{\"ID\":\"d1\",\"Status\":\"Paused\"}";

	private static final String COMPLETE = "{\"ID\":\"d1\",\"Status\":\"Complete\","
			+ "\"Result\":{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}}";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void debugsWithParametersInOrder() {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("pYear", 2026);
		parameters.put("pVersion", "Actual");
		tm1().json(PAUSED);
		ProcessDebugContext context = conformance.call("processDebugging", "debug", "Load", parameters,
				ODataQuery.none().expand("Breakpoints"));
		assertThat(context.id()).isEqualTo("d1");
		assertThat(context.status()).isEqualTo(ProcessDebugContextStatus.PAUSED);
		assertThat(tm1().last().key()).isEqualTo("POST Processes('Load')/tm1.Debug?$expand=Breakpoints");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Parameters\":[{\"Name\":\"pYear\",\"Value\":2026},{\"Name\":\"pVersion\",\"Value\":\"Actual\"}]}");

		tm1().json(PAUSED);
		conformance.call("processDebugging", "debug", "Joe's load", Map.of(), ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("POST Processes('Joe''s%20load')/tm1.Debug");
		assertThat(tm1().last().text()).isEqualTo("{}");
	}

	@Test
	void aMissingProcessIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Process'.");
		Throwable error = conformance.fail("processDebugging", "debug", "Nope", Map.of(), ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void listsAndGetsContexts() {
		tm1().json("{\"value\":[]}");
		List<ProcessDebugContext> contexts = conformance.call("processDebugging", "list", ODataQuery.none());
		assertThat(contexts).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts");

		tm1().json(PAUSED);
		ProcessDebugContext context = conformance.call("processDebugging", "get", "d1",
				ODataQuery.none().select("Status"));
		assertThat(context.status()).isEqualTo(ProcessDebugContextStatus.PAUSED);
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')?$select=Status");

		tm1().error(404, "278", "'x' can not be found");
		conformance.fail("processDebugging", "get", "x", ODataQuery.none());
	}

	@Test
	void steps() {
		for (String step : List.of("resume", "stepIn", "stepOut", "stepOver")) {
			tm1().json(PAUSED);
			ProcessDebugContext context = conformance.call("processDebugging", step, "d1", ODataQuery.none());
			assertThat(context.id()).isEqualTo("d1");
			String action = step.equals("resume") ? "Continue"
					: Character.toUpperCase(step.charAt(0)) + step.substring(1);
			assertThat(tm1().last().key()).isEqualTo("POST ProcessDebugContexts('d1')/tm1." + action);
			assertThat(tm1().last().text()).isEqualTo("{}");
		}
		tm1().json(PAUSED);
		conformance.call("processDebugging", "stepOver", "d1", ODataQuery.none().expand("CurrentBreakpoint"));
		assertThat(tm1().last().target())
			.isEqualTo("ProcessDebugContexts('d1')/tm1.StepOver?$expand=CurrentBreakpoint");
	}

	@Test
	void readsBreakpointsAsTheirKinds() {
		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint\",\"ID\":1,"
				+ "\"Enabled\":true,\"ProcessName\":\"Load\",\"Procedure\":\"Prolog\",\"LineNumber\":3},"
				+ "{\"@odata.type\":\"#ibm.tm1.api.v1.ProcessDebugContextDataBreakpoint\",\"ID\":2,\"Enabled\":false}]}");
		List<ProcessDebugContextBreakpoint> breakpoints = conformance.call("processDebugging", "breakpoints", "d1",
				ODataQuery.none());
		assertThat(breakpoints).extracting(ProcessDebugContextBreakpoint::id).containsExactly(1, 2);
		assertThat(breakpoints.get(0)).isInstanceOf(ProcessDebugContextLineBreakpoint.class);
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/Breakpoints");
	}

	@Test
	void addsUpdatesAndRemovesBreakpoints() {
		tm1().json(201, "{\"@odata.type\":\"#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint\",\"ID\":1,"
				+ "\"Enabled\":true,\"LineNumber\":3}");
		ProcessDebugContextBreakpoint added = conformance.call("processDebugging", "addBreakpoint", "d1",
				ProcessDebugContextLineBreakpointCreate.builder()
					.id(1)
					.enabled(true)
					.processName("Load")
					.procedure(ProcessProcedure.PROLOG)
					.lineNumber(3)
					.build());
		assertThat(added.id()).isEqualTo(1);
		assertThat(tm1().last().key()).isEqualTo("POST ProcessDebugContexts('d1')/Breakpoints");
		assertThat(tm1().last().json().get("@odata.type").asString())
			.isEqualTo("#ibm.tm1.api.v1.ProcessDebugContextLineBreakpoint");

		tm1().json(201, "{\"ID\":0}");
		conformance.call("processDebugging", "addBreakpoint", "d1",
				ProcessDebugContextBreakpointCreate.builder().id(0).build());
		assertThat(tm1().last().text()).isEqualTo("{\"ID\":0}");

		tm1().status(204);
		conformance.call("processDebugging", "updateBreakpoint", "d1", 1,
				ProcessDebugContextBreakpointPatch.builder().enabled(false).build());
		assertThat(tm1().last().key()).isEqualTo("PATCH ProcessDebugContexts('d1')/Breakpoints(1)");
		assertThat(tm1().last().text()).isEqualTo("{\"Enabled\":false}");

		tm1().status(204);
		conformance.call("processDebugging", "removeBreakpoint", "d1", 1);
		assertThat(tm1().last().key()).isEqualTo("DELETE ProcessDebugContexts('d1')/Breakpoints(1)");
	}

	@Test
	void aSecondBreakpointWithTheSameIdConflicts() {
		tm1().error(409, "278", "A breakpoint with ID 1 already exists");
		conformance.fail("processDebugging", "addBreakpoint", "d1",
				ProcessDebugContextBreakpointCreate.builder().id(1).build());
	}

	@Test
	void refusesABreakpointWithoutAnIdOfZeroOrMore() {
		Throwable none = conformance.fail("processDebugging", "addBreakpoint", "d1",
				ProcessDebugContextLineBreakpointCreate.builder().lineNumber(3).build());
		assertThat(none).isInstanceOf(IllegalArgumentException.class);
		conformance.fail("processDebugging", "addBreakpoint", "d1",
				ProcessDebugContextBreakpointCreate.builder().id(-1).build());
		conformance.fail("processDebugging", "addBreakpoint", "d1",
				ProcessDebugContextBreakpointCreate.builder().id(null).build());
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void saysWhereTheRunIs() {
		tm1().json("{\"ID\":2,\"Enabled\":true}");
		ProcessDebugContextBreakpoint current = conformance.call("processDebugging", "currentBreakpoint", "d1",
				ODataQuery.none());
		assertThat(current.id()).isEqualTo(2);
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/CurrentBreakpoint");

		tm1().status(204);
		Object none = conformance.call("processDebugging", "currentBreakpoint", "d1", ODataQuery.none().select("ID"));
		assertThat(none).isNull();
		assertThat(tm1().last().target()).isEqualTo("ProcessDebugContexts('d1')/CurrentBreakpoint?$select=ID");

		tm1().json("{\"value\":[{\"ID\":\"f0\",\"Procedure\":\"Prolog\",\"LineNumber\":3}]}");
		List<ProcessDebugContextStackFrame> frames = conformance.call("processDebugging", "callStack", "d1",
				ODataQuery.none().expand("Variables"));
		assertThat(frames).extracting(ProcessDebugContextStackFrame::lineNumber).containsExactly(3);
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/CallStack?$expand=Variables");

		tm1().json("{\"value\":[{\"Name\":\"NVALUE\",\"Value\":\"1\"}]}");
		List<ProcessDebugContextVariable> variables = conformance.call("processDebugging", "variables", "d1", "f0",
				ODataQuery.none().top(1));
		assertThat(variables).extracting(ProcessDebugContextVariable::name).containsExactly("NVALUE");
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/CallStack('f0')/Variables?$top=1");

		tm1().json("{\"Name\":\"Load\"}");
		Process frameProcess = conformance.call("processDebugging", "frameProcess", "d1", "f0", ODataQuery.none());
		assertThat(frameProcess.name()).isEqualTo("Load");
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/CallStack('f0')/Process");

		tm1().json("{\"Name\":\"Load\"}");
		conformance.call("processDebugging", "process", "d1", ODataQuery.none().select("Name"));
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/Process?$select=Name");

		tm1().json("{\"ID\":42,\"ObjectName\":\"Load\"}");
		Thread thread = conformance.call("processDebugging", "thread", "d1", ODataQuery.none());
		assertThat(thread.id()).isEqualTo(42);
		assertThat(tm1().last().key()).isEqualTo("GET ProcessDebugContexts('d1')/Thread");
	}

	@Test
	void finishesByRemovingTheBreakpointsItCanAndResumingToTheEnd() {
		tm1().json("{\"value\":[{\"ID\":1},{\"ID\":-1},{},{\"ID\":4}]}");
		tm1().status(204);
		tm1().status(204);
		tm1().json(PAUSED);
		tm1().json(PAUSED);
		tm1().json(COMPLETE);
		ProcessDebugContext done = conformance.call("processDebugging", "finish", "d1");
		assertThat(done.status()).isEqualTo(ProcessDebugContextStatus.COMPLETE);
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("GET ProcessDebugContexts('d1')/Breakpoints",
					"DELETE ProcessDebugContexts('d1')/Breakpoints(1)",
					"DELETE ProcessDebugContexts('d1')/Breakpoints(4)", "POST ProcessDebugContexts('d1')/tm1.Continue",
					"POST ProcessDebugContexts('d1')/tm1.Continue", "POST ProcessDebugContexts('d1')/tm1.Continue");
	}

	@Test
	void finishesARunThatIsAlreadyComplete() {
		tm1().json("{\"value\":[]}");
		tm1().json(COMPLETE);
		ProcessDebugContext done = conformance.call("processDebugging", "finish", "d1");
		assertThat(done.status()).isEqualTo(ProcessDebugContextStatus.COMPLETE);
	}

	@Test
	void givesUpFinishingAfterAHundredResumes() {
		tm1().json("{\"value\":[]}");
		for (int i = 0; i < 100; i++) {
			tm1().json(PAUSED);
		}
		ProcessDebugContext last = conformance.call("processDebugging", "finish", "d1");
		assertThat(last.status()).isEqualTo(ProcessDebugContextStatus.PAUSED);
		assertThat(tm1().requests()).filteredOn(r -> r.target().endsWith("tm1.Continue")).hasSize(100);
	}

	@Test
	void refusesAMissingId() {
		Throwable error = conformance.fail("processDebugging", "get", null, ODataQuery.none());
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	// debugging takes a function, so it isn't recorded: each client ports these by hand

	private static void scriptDebugging(ConformanceTm1 tm1) {
		tm1.json(PAUSED);
		tm1.json("{\"value\":[]}");
		tm1.json(COMPLETE);
	}

	@Test
	void debuggingFinishesTheRunAfterTheWork() {
		ConformanceTm1 tm1 = tm1();
		scriptDebugging(tm1);
		Flux<String> debugging = conformance.client()
			.processDebugging()
			.debugging("Load", Map.of("p", 1), context -> Mono.just(context.id()));
		assertThat(tm1.requests()).isEmpty();
		assertThat(debugging.collectList().block()).containsExactly("d1");
		assertThat(tm1.requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("POST Processes('Load')/tm1.Debug", "GET ProcessDebugContexts('d1')/Breakpoints",
					"POST ProcessDebugContexts('d1')/tm1.Continue");
		assertThat(tm1.requests().get(0).text()).isEqualTo("{\"Parameters\":[{\"Name\":\"p\",\"Value\":1}]}");
	}

	@Test
	void debuggingFinishesTheRunWhenTheWorkFails() {
		ConformanceTm1 tm1 = tm1();
		scriptDebugging(tm1);
		Flux<String> debugging = conformance.client()
			.processDebugging()
			.debugging("Load", Map.of(), context -> Mono.error(new IllegalStateException("boom")));
		assertThatThrownBy(debugging::blockLast).hasMessage("boom");
		assertThat(tm1.last().key()).isEqualTo("POST ProcessDebugContexts('d1')/tm1.Continue");
	}

	@Test
	void debuggingFinishesTheRunWhenItsCancelled() {
		ConformanceTm1 tm1 = tm1();
		scriptDebugging(tm1);
		List<Long> taken = new ArrayList<>();
		conformance.client()
			.processDebugging()
			.debugging("Load", Map.of(), context -> Flux.interval(Duration.ZERO, Duration.ofMillis(1)))
			.take(1)
			.doOnNext(taken::add)
			.blockLast();
		assertThat(taken).containsExactly(0L);
		assertThat(tm1.last().key()).isEqualTo("POST ProcessDebugContexts('d1')/tm1.Continue");
	}

}
