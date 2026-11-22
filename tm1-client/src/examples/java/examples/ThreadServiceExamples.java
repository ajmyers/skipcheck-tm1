package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Thread;
import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.security.ThreadService}, quoted in
 * its Javadoc.
 */
final class ThreadServiceExamples {

	Flux<String> busy(Tm1Client tm1) {
		// @start region = busy
		// What the server is doing now: every thread that isn't idle
		return tm1.threads()
			.list(ODataQuery.none()
				.select(Thread.ID, Thread.NAME, Thread.FUNCTION, Thread.ELAPSED_TIME)
				.filter(Thread.STATE.ne("Idle")))
			.map(thread -> thread.name() + ": " + thread.function() + " for " + thread.elapsedTime());
		// @end
	}

	Mono<Void> cancelProcess(Tm1Client tm1) {
		// @start region = cancel-process
		// A process run through REST shows as its request, not by its name
		return tm1.threads()
			.list(ODataQuery.none()
				.select(Thread.ID)
				.filter(Filter.contains(Thread.FUNCTION, "Processes('Sales.Load')/tm1.ExecuteWithReturn")))
			.flatMap(thread -> tm1.threads().cancel(thread.id()))
			.then();
		// @end
	}

	private ThreadServiceExamples() {
	}

}
