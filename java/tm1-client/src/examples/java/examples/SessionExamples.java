// SPDX-License-Identifier: Apache-2.0

package examples;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.model.MessageLogEntry;
import io.skipcheck.tm1.client.model.SandboxPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.data.CellService;
import io.skipcheck.tm1.client.service.data.SandboxService;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link Tm1Session}, quoted in its Javadoc.
 */
final class SessionExamples {

	Tm1Session build(String password) {
		// @start region = build
		// CAM security, with a label that shows in Sessions and Threads
		return Tm1Session.builder()
			.address("https://tm1.example.com:8010")
			.authenticator(Tm1Authenticator.camNamespace("alice", password, "LDAP"))
			.sessionContext("Sales planning")
			.build();
		// @end
	}

	Tm1Session passport(Supplier<Mono<String>> cognos) {
		// @start region = passport
		// CAM passports expire, so the session asks for a current one on every login
		return Tm1Session.builder()
			.address("https://tm1.example.com:8010")
			.authenticator(Tm1Authenticator.camPassport(cognos))
			.build();
		// @end
	}

	Flux<MessageLogEntry> request(Tm1Session session) {
		// @start region = request
		// GET MessageLogEntries, which has no service yet: the latest 20 entries
		return session.stream(Tm1Request.list(ODataPath.of("MessageLogEntries"),
				ODataQuery.none().orderByDescending(MessageLogEntry.TIME_STAMP).top(20), MessageLogEntry.class));
		// @end
	}

	Mono<Void> sandbox(Tm1Session session) {
		// @start region = sandbox
		// The cell: every dimension, in the cube's order
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Region", "North");
		cell.put("Month", "Jan");
		return new SandboxService(session).create(SandboxPatch.builder().name("What-if").build())
			.then(new CellService(session.withSandbox("What-if")).update("Sales", cell, 1200));
		// @end
	}

	private SessionExamples() {
	}

}
