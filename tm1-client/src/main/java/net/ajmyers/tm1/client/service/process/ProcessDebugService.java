package net.ajmyers.tm1.client.service.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Process;
import net.ajmyers.tm1.client.model.ProcessDebugContext;
import net.ajmyers.tm1.client.model.ProcessDebugContextBreakpoint;
import net.ajmyers.tm1.client.model.ProcessDebugContextBreakpointPatch;
import net.ajmyers.tm1.client.model.ProcessDebugContextStackFrame;
import net.ajmyers.tm1.client.model.ProcessDebugContextStatus;
import net.ajmyers.tm1.client.model.ProcessDebugContextVariable;
import net.ajmyers.tm1.client.model.Thread;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Process debugging: a run of a process that stops at breakpoints and steps line by line,
 * held on the server as a {@code ProcessDebugContext} until deleted.
 *
 * <p>
 * {@link #debug} starts one, paused before the first line; {@link #resume} runs to the
 * next breakpoint (or the end), and the {@code step…} methods move a line at a time. Each
 * returns the context as it then is.
 *
 * <p>
 * TM1 (11.8) refuses to delete a debug context: a run ends by running to completion,
 * which {@link #finish} does, and {@link #debugging} does when its work is done. A
 * finished context stays readable until the session ends. {@code ProcessDebugContexts}
 * lists none, though each reads by ID.
 *
 * <p>
 * Stop at a breakpoint and see where the run is, with {@link #debugging} finishing the
 * run afterwards:
 * {@snippet class = examples.ProcessDebugServiceExamples region = breakpoint}
 *
 * <p>
 * Step through a run started with {@link #debug}, and finish it:
 * {@snippet class = examples.ProcessDebugServiceExamples region = step}
 */
public final class ProcessDebugService {

	private static final ODataPath CONTEXTS = ODataPath.of("ProcessDebugContexts");

	/**
	 * How many times {@link #finish} resumes a run that keeps pausing before giving up.
	 */
	private static final int MAX_RESUMES = 100;

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ProcessDebugService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code tm1.Debug}: starts debugging a process.
	 * @param process the process's name
	 * @param parameters its parameters, by name, in order; may be empty
	 * @param query what to expand of the context
	 * @return the debug context, with its {@code ID}
	 */
	@Tm1Endpoint("Action Process/Debug")
	public Mono<ProcessDebugContext> debug(String process, Map<String, ?> parameters, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.PROCESS_DEBUG;
		Map<String, Object> body = new LinkedHashMap<>();
		if (!parameters.isEmpty()) {
			List<Map<String, @Nullable Object>> pairs = new ArrayList<>();
			parameters.forEach((key, value) -> {
				Map<String, @Nullable Object> pair = new LinkedHashMap<>();
				pair.put("Name", key);
				pair.put("Value", value);
				pairs.add(pair);
			});
			body.put("Parameters", pairs);
		}
		return session.execute(Tm1Request
			.post(ODataPath.of("Processes").key(Objects.requireNonNull(process, "process")).action(operation.name()),
					body, ProcessDebugContext.class)
			.withQuery(query)
			.requires(operation.capability()));
	}

	/**
	 * Debugs a process for the length of {@code work}, then finishes the run: when the
	 * work completes, fails or is cancelled. TM1 (11.8) refuses to delete a debug
	 * context, so finishing it is how the run ends.
	 * @param <R> what the work gives
	 * @param process the process's name
	 * @param parameters its parameters, by name, in order; may be empty
	 * @param work what to do with the context: set breakpoints, step, read variables
	 * @return what the work gives, with the run finished at the end
	 */
	@Tm1Endpoint("Action Process/Debug")
	@Tm1Endpoint("EntitySet ProcessDebugContexts")
	public <R> Flux<R> debugging(String process, Map<String, ?> parameters,
			Function<ProcessDebugContext, Publisher<R>> work) {
		return Flux.usingWhen(debug(process, parameters, ODataQuery.none()), work, this::finishContext,
				(context, error) -> finishContext(context), this::finishContext);
	}

	/**
	 * {@code GET ProcessDebugContexts}, streamed. TM1 (11.8) lists none, though each
	 * reads by ID with {@link #get}.
	 * @param query what to select and expand
	 * @return the contexts
	 */
	@Tm1Endpoint("EntitySet ProcessDebugContexts")
	public Flux<ProcessDebugContext> list(ODataQuery query) {
		return session.stream(Tm1Request.list(CONTEXTS, query, ProcessDebugContext.class));
	}

	/**
	 * {@code GET ProcessDebugContexts('id')}.
	 * @param id the context's ID
	 * @param query what to expand
	 * @return the context, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet ProcessDebugContexts")
	public Mono<ProcessDebugContext> get(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(context(id), query, ProcessDebugContext.class));
	}

	/**
	 * Runs a debug run to its end: removes its breakpoints, then resumes until it's
	 * {@code Complete}. TM1 (11.8) refuses to delete a debug context, so finishing the
	 * run is how to end one; the context stays readable, complete, until the session
	 * ends.
	 * @param id the context's ID
	 * @return the context, complete, with the run's {@code Result}
	 */
	@Tm1Endpoint("Action ProcessDebugContext/Continue")
	@Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")
	public Mono<ProcessDebugContext> finish(String id) {
		return breakpoints(id, ODataQuery.none())
			.concatMap(breakpoint -> breakpoint.id() == null || breakpoint.id() < 0 ? Mono.empty()
					: removeBreakpoint(id, breakpoint.id()))
			.then(resume(id, ODataQuery.none()))
			.expand(context -> context.status() == ProcessDebugContextStatus.COMPLETE ? Mono.empty()
					: resume(id, ODataQuery.none()))
			.take(MAX_RESUMES)
			.last();
	}

	/**
	 * {@code tm1.Continue}: runs to the next breakpoint, or to the end.
	 * @param id the context's ID
	 * @param query what to expand of the context
	 * @return the context, paused or complete
	 */
	@Tm1Endpoint("Action ProcessDebugContext/Continue")
	public Mono<ProcessDebugContext> resume(String id, ODataQuery query) {
		return session.execute(step(Tm1Operations.PROCESS_DEBUG_CONTEXT_CONTINUE, id, query));
	}

	/**
	 * {@code tm1.StepIn}: runs one line, into a process it calls.
	 * @param id the context's ID
	 * @param query what to expand of the context
	 * @return the context after the step
	 */
	@Tm1Endpoint("Action ProcessDebugContext/StepIn")
	public Mono<ProcessDebugContext> stepIn(String id, ODataQuery query) {
		return session.execute(step(Tm1Operations.PROCESS_DEBUG_CONTEXT_STEP_IN, id, query));
	}

	/**
	 * {@code tm1.StepOut}: runs to the end of the current procedure or called process.
	 * @param id the context's ID
	 * @param query what to expand of the context
	 * @return the context after the step
	 */
	@Tm1Endpoint("Action ProcessDebugContext/StepOut")
	public Mono<ProcessDebugContext> stepOut(String id, ODataQuery query) {
		return session.execute(step(Tm1Operations.PROCESS_DEBUG_CONTEXT_STEP_OUT, id, query));
	}

	/**
	 * {@code tm1.StepOver}: runs one line, over any process it calls.
	 * @param id the context's ID
	 * @param query what to expand of the context
	 * @return the context after the step
	 */
	@Tm1Endpoint("Action ProcessDebugContext/StepOver")
	public Mono<ProcessDebugContext> stepOver(String id, ODataQuery query) {
		return session.execute(step(Tm1Operations.PROCESS_DEBUG_CONTEXT_STEP_OVER, id, query));
	}

	/**
	 * {@code GET …/Breakpoints}, streamed, each as its kind: line, data or lock.
	 * @param id the context's ID
	 * @param query what to select
	 * @return the breakpoints
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")
	public Flux<ProcessDebugContextBreakpoint> breakpoints(String id, ODataQuery query) {
		return session
			.stream(Tm1Request.list(context(id).nav("Breakpoints"), query, ProcessDebugContextBreakpoint.class));
	}

	/**
	 * {@code POST …/Breakpoints}: adds a breakpoint, its kind given by the patch
	 * ({@code ProcessDebugContextLineBreakpointPatch}, …Data…, …Lock…).
	 *
	 * <p>
	 * The caller chooses the breakpoint's {@code ID}: TM1 (11.8) stores one sent without
	 * an ID as -1, which it then refuses in a path, so it can't be changed or removed,
	 * and a second one without an ID conflicts (409).
	 * @param id the context's ID
	 * @param breakpoint the breakpoint, with an {@code ID} of 0 or more, unique in the
	 * context
	 * @return the breakpoint as created
	 * @throws IllegalArgumentException if the patch has no {@code ID} of 0 or more
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")
	public Mono<ProcessDebugContextBreakpoint> addBreakpoint(String id, ProcessDebugContextBreakpointPatch breakpoint) {
		if (!(breakpoint.json().get("ID") instanceof Number number) || number.longValue() < 0) {
			throw new IllegalArgumentException(
					"A breakpoint needs an ID of 0 or more: TM1 stores one without as -1, which can't be changed or removed");
		}
		return session
			.execute(Tm1Request.post(context(id).nav("Breakpoints"), breakpoint, ProcessDebugContextBreakpoint.class));
	}

	/**
	 * {@code PATCH …/Breakpoints(n)}: changes a breakpoint, such as enabling or disabling
	 * it.
	 * @param id the context's ID
	 * @param breakpoint the breakpoint's ID
	 * @param patch the changes, of the breakpoint's kind
	 * @return completes when it's changed
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")
	public Mono<Void> updateBreakpoint(String id, int breakpoint, ProcessDebugContextBreakpointPatch patch) {
		return session.execute(Tm1Request.patch(context(id).nav("Breakpoints").key(breakpoint), patch));
	}

	/**
	 * {@code DELETE …/Breakpoints(n)}.
	 * @param id the context's ID
	 * @param breakpoint the breakpoint's ID
	 * @return completes when it's removed
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")
	public Mono<Void> removeBreakpoint(String id, int breakpoint) {
		return session.execute(Tm1Request.delete(context(id).nav("Breakpoints").key(breakpoint)));
	}

	/**
	 * {@code GET …/CurrentBreakpoint}: the breakpoint the run is paused at.
	 * @param id the context's ID
	 * @param query what to select
	 * @return the breakpoint, or empty if it isn't paused at one
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/CurrentBreakpoint")
	public Mono<ProcessDebugContextBreakpoint> currentBreakpoint(String id, ODataQuery query) {
		return session
			.execute(Tm1Request.get(context(id).nav("CurrentBreakpoint"), query, ProcessDebugContextBreakpoint.class));
	}

	/**
	 * {@code GET …/CallStack}, streamed: where the run is, innermost frame first.
	 * @param id the context's ID
	 * @param query what to select and expand, such as {@code Variables}
	 * @return the stack frames
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/CallStack")
	public Flux<ProcessDebugContextStackFrame> callStack(String id, ODataQuery query) {
		return session
			.stream(Tm1Request.list(context(id).nav("CallStack"), query, ProcessDebugContextStackFrame.class));
	}

	/**
	 * {@code GET …/CallStack('frame')/Variables}, streamed: the frame's variables and
	 * their values. TM1 (11.8) lists TurboIntegrator's own variables ({@code NVALUE},
	 * {@code DATASOURCE…}), not the process's local ones.
	 * @param id the context's ID
	 * @param frame the frame's ID
	 * @param query what to select and filter
	 * @return the variables
	 */
	@Tm1Endpoint("Navigation ProcessDebugContextStackFrame/Variables")
	public Flux<ProcessDebugContextVariable> variables(String id, String frame, ODataQuery query) {
		return session
			.stream(Tm1Request.list(frame(id, frame).nav("Variables"), query, ProcessDebugContextVariable.class));
	}

	/**
	 * {@code GET …/CallStack('frame')/Process}: the process the frame runs.
	 * @param id the context's ID
	 * @param frame the frame's ID
	 * @param query what to select
	 * @return the process
	 */
	@Tm1Endpoint("Navigation ProcessDebugContextStackFrame/Process")
	public Mono<Process> frameProcess(String id, String frame, ODataQuery query) {
		return session.execute(Tm1Request.get(frame(id, frame).nav("Process"), query, Process.class));
	}

	/**
	 * {@code GET …/Process}: the process being debugged.
	 * @param id the context's ID
	 * @param query what to select
	 * @return the process
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/Process")
	public Mono<Process> process(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(context(id).nav("Process"), query, Process.class));
	}

	/**
	 * {@code GET …/Thread}: the server thread running the debug run.
	 * @param id the context's ID
	 * @param query what to select
	 * @return the thread
	 */
	@Tm1Endpoint("Navigation ProcessDebugContext/Thread")
	public Mono<Thread> thread(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(context(id).nav("Thread"), query, Thread.class));
	}

	private Mono<Void> finishContext(ProcessDebugContext context) {
		String id = context.id();
		return id == null ? Mono.empty() : finish(id).then();
	}

	private static Tm1Request<ProcessDebugContext> step(Tm1Operation operation, String id, ODataQuery query) {
		return Tm1Request.post(context(id).action(operation.name()), Map.of(), ProcessDebugContext.class)
			.withQuery(query)
			.requires(operation.capability());
	}

	private static ODataPath context(String id) {
		return CONTEXTS.key(Objects.requireNonNull(id, "id"));
	}

	private static ODataPath frame(String id, String frame) {
		return context(id).nav("CallStack").key(Objects.requireNonNull(frame, "frame"));
	}

}
