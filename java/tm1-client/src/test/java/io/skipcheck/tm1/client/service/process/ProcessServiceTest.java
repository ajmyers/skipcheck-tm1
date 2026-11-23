package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.model.ProcessErrorLog;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessExecuteStatusCode;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.model.ProcessPatch;
import io.skipcheck.tm1.client.model.ProcessSyntaxError;
import io.skipcheck.tm1.client.model.ProcessVariableType;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * ProcessService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/ProcessServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
@SuppressWarnings("deprecation")
class ProcessServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	private static Map<String, Object> parameters() {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("pYear", 2026);
		parameters.put("pVersion", "Actual");
		return parameters;
	}

	private static ProcessPatch adhoc() {
		return ProcessPatch.builder()
			.name("adhoc")
			.parameters(List
				.of(ProcessParameterPatch.builder().name("p").value("v").type(ProcessVariableType.STRING).build()))
			.prologProcedure("LogOutput('INFO', p);\n")
			.build();
	}

	@Test
	void listsProcesses() {
		tm1().json("{\"value\":[{\"Name\":\"Load\"},{\"Name\":\"}bedrock.cube.clone\"}]}");
		List<Process> all = conformance.call("processes", "list", ODataQuery.none());
		assertThat(all).extracting(Process::name).containsExactly("Load", "}bedrock.cube.clone");
		assertThat(tm1().last().key()).isEqualTo("GET Processes");

		tm1().json("{\"value\":[{\"Name\":\"Load\"}]}");
		conformance.call("processes", "list",
				ODataQuery.none()
					.select("Name")
					.filter(Filter.raw("not startswith(Name,'}')"))
					.orderBy("Name")
					.top(10));
		assertThat(tm1().last().target())
			.isEqualTo("Processes?$select=Name&$filter=not%20startswith(Name,'%7D')&$orderby=Name&$top=10");
	}

	@Test
	void followsNextLinks() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"Processes?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Process> all = conformance.call("processes", "list", ODataQuery.none().select("Name"));
		assertThat(all).extracting(Process::name).containsExactly("a", "b");
	}

	@Test
	void getsAProcessWithItsEtag() {
		tm1().json("{\"@odata.etag\":\"W/\\\"1\\\"\",\"Name\":\"Load\",\"PrologProcedure\":\"x=1;\","
				+ "\"Parameters\":[{\"Name\":\"pYear\",\"Value\":2026,\"Type\":\"Numeric\"}]}");
		Process process = conformance.call("processes", "get", "Load",
				ODataQuery.none().select("Name", "PrologProcedure", "Parameters"));
		assertThat(process.etag()).isEqualTo("W/\"1\"");
		assertThat(process.prologProcedure()).isEqualTo("x=1;");
		assertThat(tm1().last().target()).isEqualTo("Processes('Load')?$select=Name,PrologProcedure,Parameters");
	}

	@Test
	void quotesAndEncodesAName() {
		tm1().json("{\"Name\":\"Joe's load/#1 & 50%\"}");
		conformance.call("processes", "get", "Joe's load/#1 & 50%", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Processes('Joe''s%20load%2F%231%20%26%2050%25')");

		tm1().json("{\"Name\":\"}bedrock.server.wait\"}");
		conformance.call("processes", "get", "}bedrock.server.wait", ODataQuery.none().expand("DataSource"));
		assertThat(tm1().last().target()).isEqualTo("Processes('%7Dbedrock.server.wait')?$expand=DataSource");
	}

	@Test
	void aMissingProcessIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Process'.");
		Throwable error = conformance.fail("processes", "get", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAProcess() {
		tm1().json(201, "{\"Name\":\"adhoc\"}");
		Process created = conformance.call("processes", "create", adhoc());
		assertThat(created.name()).isEqualTo("adhoc");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Processes");
		assertThat(post.text()).isEqualTo("{\"Name\":\"adhoc\",\"Parameters\":[{\"Name\":\"p\",\"Value\":\"v\","
				+ "\"Type\":\"String\"}],\"PrologProcedure\":\"LogOutput('INFO', p);\\n\"}");
	}

	@Test
	void anExistingProcessConflicts() {
		tm1().error(409, "278", "A process with the name 'adhoc' already exists.");
		Throwable error = conformance.fail("processes", "create", ProcessPatch.builder().name("adhoc").build());
		assertThat(error).hasMessageContaining("already exists");
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		conformance.call("processes", "update", "Load", ProcessPatch.builder().prologProcedure("x=2;").build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Processes('Load')");
		assertThat(tm1().last().header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("processes", "update", "Load", ProcessPatch.builder().epilogProcedure(null).build(),
				"W/\"1\"");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"1\"");
		assertThat(tm1().last().text()).isEqualTo("{\"EpilogProcedure\":null}");
	}

	@Test
	void aStaleEtagIsRefused() {
		tm1().error(412, "278", "stale");
		Throwable error = conformance.fail("processes", "update", "Load",
				ProcessPatch.builder().epilogProcedure("").build(), "W/\"0\"");
		assertThat(error).isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void deletesAProcess() {
		tm1().status(204);
		conformance.call("processes", "delete", "Load");
		assertThat(tm1().last().key()).isEqualTo("DELETE Processes('Load')");

		tm1().error(404, "278", "'Nope' can not be found");
		conformance.fail("processes", "delete", "Nope");
	}

	@Test
	void compilesASavedProcess() {
		tm1().json("{\"value\":[{\"Procedure\":\"Prolog\",\"LineNumber\":3,\"Message\":\"Syntax error\"}]}");
		List<ProcessSyntaxError> errors = conformance.call("processes", "compile", "Load");
		assertThat(errors).extracting(ProcessSyntaxError::lineNumber).containsExactly(3);
		assertThat(tm1().last().key()).isEqualTo("POST Processes('Load')/tm1.Compile");
		assertThat(tm1().last().text()).isEqualTo("{}");
	}

	@Test
	void compilesADefinition() {
		tm1().json("{\"value\":[]}");
		List<ProcessSyntaxError> errors = conformance.call("processes", "compile", adhoc());
		assertThat(errors).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("POST CompileProcess");
		assertThat(tm1().last().json().get("Process").get("Name").asString()).isEqualTo("adhoc");
	}

	@Test
	void executesWithReturnAndParametersInOrder() {
		tm1().json("{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\",\"ErrorLogFile\":null}");
		ProcessExecuteResult result = conformance.call("processes", "executeWithReturn", "Load", parameters(),
				ODataQuery.none().expand("ErrorLogFile"));
		assertThat(result.processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
		assertThat(tm1().last().target()).isEqualTo("Processes('Load')/tm1.ExecuteWithReturn?$expand=ErrorLogFile");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Parameters\":[{\"Name\":\"pYear\",\"Value\":2026},{\"Name\":\"pVersion\",\"Value\":\"Actual\"}]}");

		tm1().json("{\"ProcessExecuteStatusCode\":\"CompletedSuccessfully\"}");
		conformance.call("processes", "executeWithReturn", "Load", Map.of(), ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Processes('Load')/tm1.ExecuteWithReturn");
		assertThat(tm1().last().text()).isEqualTo("{}");
	}

	@Test
	void executesADefinitionWithReturn() {
		tm1().json("{\"ProcessExecuteStatusCode\":\"Aborted\","
				+ "\"ErrorLogFile\":{\"Filename\":\"tm1processerror_20260102030405_adhoc.log\"}}");
		ProcessExecuteResult result = conformance.call("processes", "executeWithReturn", adhoc(), Map.of("p", "x"),
				ODataQuery.none().expand("ErrorLogFile"));
		assertThat(result.processExecuteStatusCode()).isEqualTo(ProcessExecuteStatusCode.ABORTED);
		assertThat(result.errorLogFile().filename()).isEqualTo("tm1processerror_20260102030405_adhoc.log");
		assertThat(tm1().last().target()).isEqualTo("ExecuteProcessWithReturn?$expand=ErrorLogFile");
		assertThat(tm1().last().json().get("Parameters").get(0).get("Value").asString()).isEqualTo("x");
	}

	@Test
	void readsAStatusItDoesntKnow() {
		tm1().json("{\"ProcessExecuteStatusCode\":\"SomethingNew\"}");
		ProcessExecuteResult result = conformance.call("processes", "executeWithReturn", "Load", Map.of(),
				ODataQuery.none());
		assertThat(result.processExecuteStatusCode()).isNotEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
	}

	@Test
	void aCancelledRunFails() {
		tm1().error(500, "174", "TM1UserException: Cancel");
		conformance.fail("processes", "executeWithReturn", "Load", Map.of(), ODataQuery.none());
	}

	@Test
	void runsTheDeprecatedExecute() {
		tm1().status(204);
		conformance.call("processes", "execute", "Load", Map.of("p", 1));
		assertThat(tm1().last().key()).isEqualTo("POST Processes('Load')/tm1.Execute");
		assertThat(tm1().last().text()).isEqualTo("{\"Parameters\":[{\"Name\":\"p\",\"Value\":1}]}");

		tm1().status(204);
		conformance.call("processes", "execute", "Load", Map.of());
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().status(204);
		Map<String, Object> nulls = new LinkedHashMap<>();
		nulls.put("p", null);
		nulls.put("q", 2.5);
		conformance.call("processes", "execute", ProcessPatch.builder().name("adhoc").build(), nulls);
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteProcess");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Process\":{\"Name\":\"adhoc\"},\"Parameters\":[{\"Name\":\"p\",\"Value\":null},{\"Name\":\"q\",\"Value\":2.5}]}");
	}

	@Test
	void readsTheDeprecatedErrorLogs() {
		tm1().json("{\"value\":[{\"Timestamp\":\"2026-01-02T03:04:05Z\"}]}");
		List<ProcessErrorLog> logs = conformance.call("processes", "errorLogs", "Load", ODataQuery.none().top(1));
		assertThat(logs).extracting(ProcessErrorLog::timestamp)
			.containsExactly(OffsetDateTime.parse("2026-01-02T03:04:05Z"));
		assertThat(tm1().last().key()).isEqualTo("GET Processes('Load')/ErrorLogs?$top=1");

		tm1().text("line 1\nline 2");
		String content = conformance.call("processes", "errorLogContent", "Load", logs.get(0));
		assertThat(content).isEqualTo("line 1\nline 2");
		assertThat(tm1().last().target()).isEqualTo("Processes('Load')/ErrorLogs('2026-01-02T03%3A04%3A05Z')/Content");
	}

	@Test
	void keysAnErrorLogByItsTimestampAsJavaWritesIt() {
		tm1().text("x");
		conformance.call("processes", "errorLogContent", "Load",
				new ProcessErrorLog(null, OffsetDateTime.parse("2026-01-02T03:04:00.000+00:00")));
		assertThat(tm1().last().target()).isEqualTo("Processes('Load')/ErrorLogs('2026-01-02T03%3A04Z')/Content");

		tm1().text("y");
		conformance.call("processes", "errorLogContent", "Load",
				new ProcessErrorLog(null, OffsetDateTime.parse("2026-01-02T03:04:05.120+01:00")));
		assertThat(tm1().last().target())
			.isEqualTo("Processes('Load')/ErrorLogs('2026-01-02T03%3A04%3A05.120%2B01%3A00')/Content");
	}

	@Test
	void refusesALogWithoutATimestamp() {
		Throwable error = conformance.fail("processes", "errorLogContent", "Load", new ProcessErrorLog(null, null));
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void refusesAMissingName() {
		Throwable error = conformance.fail("processes", "delete", (Object) null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
