// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.model.ProcessErrorLog;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessExecuteStatusCode;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.model.ProcessPatch;
import io.skipcheck.tm1.client.model.ProcessProcedure;
import io.skipcheck.tm1.client.model.ProcessSyntaxError;
import io.skipcheck.tm1.client.model.ProcessVariableType;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** ProcessService, on a process whose numeric parameter picks how it ends. */
@RequiresTm1
class ProcessServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final String PROLOG = """
			If(pCount = 2);
			  ProcessQuit;
			EndIf;
			If(pCount = 3);
			  ProcessError;
			EndIf;
			If(pCount = 4);
			  CellPutN(1, 'NoSuchCube', 'x');
			EndIf;
			""";

	private static Tm1Client tm1;

	private static ProcessService processes;

	private String name;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		processes = tm1.processes();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createProcess() {
		name = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		processes.create(definition(name, PROLOG)).block(WAIT);
	}

	@AfterEach
	void deleteProcess() {
		processes.delete(name).onErrorComplete().block(WAIT);
	}

	@Test
	void createsReadsUpdatesAndDeletesAProcess() {
		Process read = processes.get(name, ODataQuery.none()).block(WAIT);
		assertThat(read.prologProcedure()).contains("ProcessQuit");
		assertThat(read.parameters()).extracting(p -> p.name()).containsExactly("pName", "pCount");
		assertThat(processes.list(ODataQuery.none().select(Process.NAME).filter(Filter.startsWith(Process.NAME, name)))
			.map(Process::name)
			.collectList()
			.block(WAIT)).containsExactly(name);

		processes.update(name, ProcessPatch.builder().epilogProcedure("nX = 1;\n").build(), read.etag()).block(WAIT);
		assertThat(processes.get(name, ODataQuery.none()).block(WAIT).epilogProcedure()).contains("nX = 1;");
		assertThatThrownBy(() -> processes.update(name, ProcessPatch.builder().epilogProcedure("").build(), read.etag())
			.block(WAIT)).isInstanceOf(Tm1PreconditionFailedException.class);

		processes.delete(name).block(WAIT);
		assertThatThrownBy(() -> processes.get(name, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void compilesSavedAndUnsavedCode() {
		assertThat(processes.compile(name).collectList().block(WAIT)).isEmpty();
		List<ProcessSyntaxError> errors = processes.compile(definition("unsaved", "nX = ;\n"))
			.collectList()
			.block(WAIT);
		assertThat(errors).singleElement().satisfies(e -> {
			assertThat(e.procedure()).isEqualTo(ProcessProcedure.PROLOG);
			assertThat(e.lineNumber()).isEqualTo(1);
		});
		processes.update(name, ProcessPatch.builder().prologProcedure("nX = ;\n").build()).block(WAIT);
		assertThat(processes.compile(name).collectList().block(WAIT)).as("saved, though it doesn't compile").hasSize(1);
	}

	@Test
	void runsAndSaysHowItEnded() {
		for (int count : new int[] { 3, 4 }) {
			ProcessExecuteResult result = processes
				.executeWithReturn(name, parameters(count), ODataQuery.none().expand("ErrorLogFile"))
				.block(WAIT);
			assertThat(result.processExecuteStatusCode()).as("ProcessError, then a runtime error")
				.isEqualTo(ProcessExecuteStatusCode.ABORTED);
			assertThat(result.errorLogFile().filename()).startsWith("tm1processerror_").endsWith("_" + name + ".log");
		}
		assertThat(status(1)).isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
		assertThat(status(2)).isEqualTo(ProcessExecuteStatusCode.QUIT_CALLED);
		assertThat(status(3)).isEqualTo(ProcessExecuteStatusCode.ABORTED);
	}

	@Test
	void runsUnsavedCodeAndTheDeprecatedWays() {
		assertThat(processes.executeWithReturn(definition("unsaved", PROLOG), parameters(2), ODataQuery.none())
			.block(WAIT)
			.processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.QUIT_CALLED);
		deprecatedRuns();
	}

	@SuppressWarnings("deprecation")
	private void deprecatedRuns() {
		processes.executeWithReturn(name, parameters(3), ODataQuery.none()).block(WAIT);
		processes.execute(name, parameters(1)).block(WAIT);
		processes.execute(definition("unsaved", PROLOG), parameters(1)).block(WAIT);
		List<ProcessErrorLog> logs = processes.errorLogs(name, ODataQuery.none()).collectList().block(WAIT);
		assertThat(logs).as("the aborted run's").hasSize(1);
		assertThat(processes.errorLogContent(name, logs.get(0)).block(WAIT)).as("keyed by its quoted timestamp")
			.contains("ProcessError");
	}

	private ProcessExecuteStatusCode status(int count) {
		return processes.executeWithReturn(name, parameters(count), ODataQuery.none())
			.block(WAIT)
			.processExecuteStatusCode();
	}

	private static Map<String, Object> parameters(int count) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("pName", "x");
		parameters.put("pCount", count);
		return parameters;
	}

	private static ProcessPatch definition(String name, String prolog) {
		return ProcessPatch.builder()
			.name(name)
			.parameters(List.of(
					ProcessParameterPatch.builder().name("pName").value("").type(ProcessVariableType.STRING).build(),
					ProcessParameterPatch.builder().name("pCount").value(0).type(ProcessVariableType.NUMERIC).build()))
			.prologProcedure(prolog)
			.build();
	}

}
