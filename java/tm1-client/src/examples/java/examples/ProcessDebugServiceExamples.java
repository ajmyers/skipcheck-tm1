package examples;

import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.ProcessDebugContext;
import io.skipcheck.tm1.client.model.ProcessDebugContextLineBreakpointPatch;
import io.skipcheck.tm1.client.model.ProcessProcedure;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.process.ProcessDebugService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.process.ProcessDebugService},
 * quoted in its Javadoc.
 */
final class ProcessDebugServiceExamples {

	Flux<String> breakpoint(Tm1Client tm1) {
		// @start region = breakpoint
		// Where the run stops at a breakpoint on line 3 of the prolog. debugging()
		// runs it to the end afterwards.
		ProcessDebugService debug = tm1.processDebugging();
		return debug.debugging("Load Sales", Map.of("pVersion", "Forecast"), context -> {
			String id = Objects.requireNonNull(context.id());
			ProcessDebugContextLineBreakpointPatch line3 = ProcessDebugContextLineBreakpointPatch.builder()
				.id(1) // TM1 needs an ID chosen by the caller
				.processName("Load Sales")
				.procedure(ProcessProcedure.PROLOG)
				.lineNumber(3)
				.enabled(true)
				.build();
			return debug.addBreakpoint(id, line3)
				.then(debug.resume(id, ODataQuery.none()))
				.thenMany(debug.callStack(id, ODataQuery.none()))
				.map(frame -> frame.procedure() + ", line " + frame.lineNumber());
		});
		// @end
	}

	Mono<ProcessDebugContext> step(Tm1Client tm1) {
		// @start region = step
		// Without debugging(), end the run with finish(): TM1 can't delete it
		ProcessDebugService debug = tm1.processDebugging();
		return debug.debug("Load Sales", Map.of(), ODataQuery.none())
			.map(context -> Objects.requireNonNull(context.id()))
			.flatMap(id -> debug.stepOver(id, ODataQuery.none())
				.then(debug.stepOver(id, ODataQuery.none()))
				.then(debug.finish(id)));
		// @end
	}

	private ProcessDebugServiceExamples() {
	}

}
