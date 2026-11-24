// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.ErrorLogFile;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.model.ProcessCreate;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.model.ProcessVariableType;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.process.ProcessService}, quoted in
 * its Javadoc.
 */
final class ProcessServiceExamples {

	Mono<Process> create(Tm1Client tm1) {
		// @start region = create
		// TM1 saves code that doesn't compile, so compile the definition first
		ProcessCreate process = ProcessCreate.builder()
			.name("Load Sales")
			.parameters(List.of(
					ProcessParameterPatch.builder()
						.name("pVersion")
						.value("Actual")
						.type(ProcessVariableType.STRING)
						.build(),
					ProcessParameterPatch.builder()
						.name("pYear")
						.value(2026)
						.type(ProcessVariableType.NUMERIC)
						.build()))
			.prologProcedure("LogOutput('INFO', 'Loading ' | pVersion | ' for ' | NumberToString(pYear));\n")
			.build();
		return tm1.processes()
			.compile(process)
			.collectList()
			.flatMap(errors -> errors.isEmpty() ? tm1.processes().create(process)
					: Mono.error(new IllegalArgumentException("The process doesn't compile: " + errors)));
		// @end
	}

	Mono<ProcessExecuteResult> run(Tm1Client tm1) {
		// @start region = run
		// Parameters by name, in order; a numeric one takes a number, not text
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("pVersion", "Forecast");
		parameters.put("pYear", 2026);
		return tm1.processes()
			.executeWithReturn("Load Sales", parameters, ODataQuery.none().expand(ProcessExecuteResult.ERROR_LOG_FILE))
			.flatMap(result -> {
				// A run that ended with errors names the log it wrote
				ErrorLogFile log = result.errorLogFile();
				return log == null ? Mono.just(result) : Mono
					.error(new IllegalStateException(result.processExecuteStatusCode() + ": see " + log.filename()));
			});
		// @end
	}

	Mono<ProcessExecuteResult> runUnsaved(Tm1Client tm1) {
		// @start region = run-unsaved
		// A one-off run of code that isn't saved as a process
		ProcessCreate cleanup = ProcessCreate.builder()
			.name("Clear Sales Staging")
			.prologProcedure("CubeClearData('Sales Staging');\n")
			.build();
		return tm1.processes().executeWithReturn(cleanup, Map.of(), ODataQuery.none());
		// @end
	}

	private ProcessServiceExamples() {
	}

}
