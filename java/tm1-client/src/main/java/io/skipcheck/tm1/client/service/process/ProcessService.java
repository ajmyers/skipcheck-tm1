package io.skipcheck.tm1.client.service.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.model.ProcessErrorLog;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessPatch;
import io.skipcheck.tm1.client.model.ProcessSyntaxError;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataLiterals;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Processes (TurboIntegrator): {@code /Processes}, compiling and running them, and their
 * error logs.
 *
 * <p>
 * Parameters go as a map of name to value, in order; a numeric parameter needs a number,
 * not text. Running a process returns when it ends, however long that takes: give the
 * request a timeout that fits, and know that cancelling the subscription doesn't stop the
 * process on the server.
 *
 * <p>
 * Create a process, compiling it first, since TM1 saves code that doesn't compile:
 * {@snippet class = examples.ProcessServiceExamples region = create}
 *
 * <p>
 * Run it with parameters, and fail if it wrote an error log:
 * {@snippet class = examples.ProcessServiceExamples region = run}
 *
 * <p>
 * Run code without saving it as a process:
 * {@snippet class = examples.ProcessServiceExamples region = run-unsaved}
 */
public final class ProcessService {

	private static final ODataPath PROCESSES = ODataPath.of("Processes");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ProcessService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Processes}, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the processes
	 */
	@Tm1Endpoint("EntitySet Processes")
	public Flux<Process> list(ODataQuery query) {
		return session.stream(Tm1Request.list(PROCESSES, query, Process.class));
	}

	/**
	 * {@code GET Processes('name')}. TM1 (11.8) leaves most of {@code DataSource}, and
	 * {@code UIData} and {@code VariablesUIData}, out of a {@code $select=*}: select them
	 * by name, or select nothing, to get them.
	 * @param name the process's name
	 * @param query what to select and expand
	 * @return the process, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Processes")
	public Mono<Process> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(process(name), query, Process.class));
	}

	/**
	 * {@code POST Processes}: creates a process. TM1 (11.8) saves code that doesn't
	 * compile: check it with {@link #compile(ProcessPatch)} first.
	 * @param process the new process; it needs a name
	 * @return the process as created
	 */
	@Tm1Endpoint("EntitySet Processes")
	public Mono<Process> create(ProcessPatch process) {
		return session.execute(Tm1Request.post(PROCESSES, process, Process.class));
	}

	/**
	 * {@code PATCH Processes('name')}: changes the properties the patch sets, such as a
	 * procedure. TM1 (11.8) saves code that doesn't compile.
	 * @param name the process's name
	 * @param patch the changes
	 * @return completes when the process is changed
	 */
	@Tm1Endpoint("EntitySet Processes")
	public Mono<Void> update(String name, ProcessPatch patch) {
		return session.execute(Tm1Request.patch(process(name), patch));
	}

	/**
	 * {@code PATCH Processes('name')} with {@code If-Match}.
	 * @param name the process's name
	 * @param patch the changes
	 * @param etag the ETag the process was read with ({@link Process#etag()})
	 * @return completes when the process is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 */
	@Tm1Endpoint("EntitySet Processes")
	public Mono<Void> update(String name, ProcessPatch patch, String etag) {
		return session.execute(Tm1Request.patch(process(name), patch).ifMatch(etag));
	}

	/**
	 * {@code DELETE Processes('name')}.
	 * @param name the process's name
	 * @return completes when the process is deleted
	 */
	@Tm1Endpoint("EntitySet Processes")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(process(name)));
	}

	/**
	 * {@code tm1.Compile}: compiles a saved process. Needs schema 11.0.0.
	 * @param name the process's name
	 * @return the syntax errors, by procedure and line; none if it compiles
	 */
	@Tm1Endpoint("Action Process/Compile")
	public Flux<ProcessSyntaxError> compile(String name) {
		Tm1Operation operation = Tm1Operations.PROCESS_COMPILE;
		return session
			.stream(Tm1Request.postList(process(name).action(operation.name()), Map.of(), ProcessSyntaxError.class)
				.requires(operation.capability()));
	}

	/**
	 * {@code CompileProcess}: compiles a process definition that needn't be saved, such
	 * as one about to be created. Needs schema 11.0.0.
	 * @param definition the process, with its procedures and parameters
	 * @return the syntax errors, by procedure and line; none if it compiles
	 */
	@Tm1Endpoint("ActionImport CompileProcess")
	public Flux<ProcessSyntaxError> compile(ProcessPatch definition) {
		Tm1Operation operation = Tm1Operations.COMPILE_PROCESS;
		return session.stream(
				Tm1Request
					.postList(root(operation), Map.of("Process", Objects.requireNonNull(definition, "definition")),
							ProcessSyntaxError.class)
					.requires(operation.capability()));
	}

	/**
	 * {@code tm1.ExecuteWithReturn}: runs a process and says how it ended. Needs schema
	 * 11.3.0. A {@code ProcessError}, or a runtime error, ends it {@code Aborted}, with
	 * an {@code ErrorLogFile} named {@code tm1processerror_<time>_<process>.log}. A run
	 * cancelled through its thread ({@code ThreadService.cancel}) fails instead, with a
	 * {@code Tm1ServerException} ({@code TM1UserException: Cancel}, code 174).
	 * @param name the process's name
	 * @param parameters its parameters, by name, in order; may be empty
	 * @param query what to expand of the result, such as {@code ErrorLogFile} when it
	 * ended with errors
	 * @return how it ended: {@code ProcessExecuteStatusCode}, and the error log's file if
	 * it wrote one
	 */
	@Tm1Endpoint("Action Process/ExecuteWithReturn")
	@Tm1Endpoint("Navigation ProcessExecuteResult/ErrorLogFile")
	public Mono<ProcessExecuteResult> executeWithReturn(String name, Map<String, ?> parameters, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.PROCESS_EXECUTE_WITH_RETURN;
		return session.execute(
				Tm1Request
					.post(process(name).action(operation.name()), withParameters(Map.of(), parameters),
							ProcessExecuteResult.class)
					.withQuery(query)
					.requires(operation.capability()));
	}

	/**
	 * {@code ExecuteProcessWithReturn}: runs a process definition that needn't be saved.
	 * Needs schema 11.3.0.
	 * @param definition the process, with its procedures and parameters
	 * @param parameters the values to run it with, by name, in order; may be empty
	 * @param query what to expand of the result, such as {@code ErrorLogFile}
	 * @return how it ended
	 */
	@Tm1Endpoint("ActionImport ExecuteProcessWithReturn")
	@Tm1Endpoint("Navigation ProcessExecuteResult/ErrorLogFile")
	public Mono<ProcessExecuteResult> executeWithReturn(ProcessPatch definition, Map<String, ?> parameters,
			ODataQuery query) {
		Tm1Operation operation = Tm1Operations.EXECUTE_PROCESS_WITH_RETURN;
		return session.execute(
				Tm1Request
					.post(root(operation),
							withParameters(Map.of("Process", Objects.requireNonNull(definition, "definition")),
									parameters),
							ProcessExecuteResult.class)
					.withQuery(query)
					.requires(operation.capability()));
	}

	/**
	 * {@code tm1.Execute}: runs a process, without saying how it ended.
	 * @param name the process's name
	 * @param parameters its parameters, by name, in order; may be empty
	 * @return completes when it's run
	 * @deprecated Deprecated in TM1 schema 11.3.0: use
	 * {@link #executeWithReturn(String, Map, ODataQuery)}.
	 */
	@Deprecated(since = "11.3.0")
	@Tm1Endpoint("Action Process/Execute")
	public Mono<Void> execute(String name, Map<String, ?> parameters) {
		return session.execute(Tm1Request.post(process(name).action(Tm1Operations.PROCESS_EXECUTE.name()),
				withParameters(Map.of(), parameters), Void.class));
	}

	/**
	 * {@code ExecuteProcess}: runs a process definition, without saying how it ended.
	 * Needs schema 11.0.0.
	 * @param definition the process, with its procedures and parameters
	 * @param parameters the values to run it with, by name, in order; may be empty
	 * @return completes when it's run
	 * @deprecated Deprecated in TM1 schema 11.3.0: use
	 * {@link #executeWithReturn(ProcessPatch, Map, ODataQuery)}.
	 */
	@Deprecated(since = "11.3.0")
	@Tm1Endpoint("ActionImport ExecuteProcess")
	public Mono<Void> execute(ProcessPatch definition, Map<String, ?> parameters) {
		return session.execute(Tm1Request.post(root(Tm1Operations.EXECUTE_PROCESS),
				withParameters(Map.of("Process", Objects.requireNonNull(definition, "definition")), parameters),
				Void.class));
	}

	/**
	 * {@code GET Processes('name')/ErrorLogs}, streamed: the logs its failed runs wrote,
	 * keyed by their timestamps.
	 * @param name the process's name
	 * @param query what to select, filter and order
	 * @return the error logs
	 * @deprecated Deprecated in TM1 schema 11.3.0, with {@code ProcessErrorLog}: a run's
	 * log is the {@code ErrorLogFile} that
	 * {@link #executeWithReturn(String, Map, ODataQuery)} returns.
	 */
	@Deprecated(since = "11.3.0")
	@Tm1Endpoint("Navigation Process/ErrorLogs")
	public Flux<ProcessErrorLog> errorLogs(String name, ODataQuery query) {
		return session.stream(Tm1Request.list(process(name).nav("ErrorLogs"), query, ProcessErrorLog.class));
	}

	/**
	 * {@code GET Processes('name')/ErrorLogs('timestamp')/Content}: an error log's text.
	 * TM1 keys error logs by their timestamp as a quoted string, as it lists it.
	 * @param name the process's name
	 * @param log the log, as {@link #errorLogs} lists it
	 * @return the log's text
	 * @deprecated Deprecated in TM1 schema 11.3.0, with {@code ProcessErrorLog}: a run's
	 * log is the {@code ErrorLogFile} that
	 * {@link #executeWithReturn(String, Map, ODataQuery)} returns.
	 */
	@Deprecated(since = "11.3.0")
	@Tm1Endpoint("Navigation Process/ErrorLogs")
	public Mono<String> errorLogContent(String name, ProcessErrorLog log) {
		String timestamp = Objects.requireNonNull(log.timestamp(), "the log's timestamp").toString();
		return session.execute(Tm1Request.text(
				process(name).nav("ErrorLogs").key(new ODataLiterals.Raw(ODataLiterals.of(timestamp))).nav("Content")));
	}

	private static Map<String, Object> withParameters(Map<String, Object> body, Map<String, ?> parameters) {
		Map<String, Object> result = new LinkedHashMap<>(body);
		if (!parameters.isEmpty()) {
			List<Map<String, @Nullable Object>> pairs = new ArrayList<>();
			parameters.forEach((key, value) -> {
				Map<String, @Nullable Object> pair = new LinkedHashMap<>();
				pair.put("Name", key);
				pair.put("Value", value);
				pairs.add(pair);
			});
			result.put("Parameters", pairs);
		}
		return result;
	}

	private static ODataPath root(Tm1Operation operation) {
		return ODataPath.of(Objects.requireNonNull(operation.importName()));
	}

	private static ODataPath process(String name) {
		return PROCESSES.key(Objects.requireNonNull(name, "name"));
	}

}
