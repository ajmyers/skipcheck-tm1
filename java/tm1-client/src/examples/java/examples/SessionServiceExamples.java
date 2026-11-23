package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Session;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.security.SessionService}, quoted in
 * its Javadoc.
 */
final class SessionServiceExamples {

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// Every session on the server, with its user's name and its label
		return tm1.sessions()
			.list(ODataQuery.none()
				.select(Session.ID, Session.CONTEXT)
				.expand(Session.USER, user -> user.select(User.NAME)))
			.map(session -> session.id() + " " + session.user().name() + " " + session.context());
		// @end
	}

	Mono<Void> closeLabelled(Tm1Client tm1) {
		// @start region = close
		// Close the sessions labelled "Nightly load", except this client's own
		return tm1.sessions()
			.activeSession(ODataQuery.none().select(Session.ID))
			.map(Session::id)
			.flatMapMany(own -> tm1.sessions()
				.list(ODataQuery.none().select(Session.ID).filter(Session.CONTEXT.eq("Nightly load")))
				.filter(session -> !own.equals(session.id())))
			.flatMap(session -> tm1.sessions().close(session.id()))
			.then();
		// @end
	}

	private SessionServiceExamples() {
	}

}
