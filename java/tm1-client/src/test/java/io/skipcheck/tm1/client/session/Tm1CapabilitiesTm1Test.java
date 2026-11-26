// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1UnsupportedOperationException;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Capability;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Version and capabilities read from a real TM1. */
@LiveTm1
class Tm1CapabilitiesTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void readsTheVersionAndTheCapabilitiesTheSnapshotDescribes(LiveServer server) {
		Tm1Session session = session(server);
		try {
			assertThat(session.version().block(WAIT)).isEqualTo(server.getTm1Version());
			Tm1Capabilities capabilities = session.capabilities().block(WAIT);
			// The schema its $metadata says, read from the text, not by the client
			assertThat(capabilities.schemaVersion()).hasValue(ServerAssumptions.schemaVersionIn(metadata(session)));
			CsdlDocument snapshot = ServerAssumptions.ownSnapshot(capabilities);

			CsdlEntityContainer container = snapshot.entityContainer().orElseThrow();
			container.entitySets()
				.forEach(s -> assertThat(capabilities.supports(Tm1Capability.entitySet(s.name()))).as(s.name())
					.isTrue());
			container.singletons()
				.forEach(s -> assertThat(capabilities.supports(Tm1Capability.singleton(s.name()))).as(s.name())
					.isTrue());
			container.actionImports()
				.forEach(a -> assertThat(capabilities.supports(Tm1Capability.unboundOperation(a.name()))).as(a.name())
					.isTrue());
			assertThat(capabilities.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve")))
				.as("Reserve, from schema 11.8.34")
				.isEqualTo(Tm1Capabilities.of(snapshot, null)
					.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve")));
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void refusesARequestTheServerDoesntDefine(LiveServer server) {
		Tm1Session session = session(server);
		try {
			Tm1Request<JsonNode> missing = Tm1Request.get(ODataPath.of("Bananas"), ODataQuery.none(), JsonNode.class)
				.requires(Tm1Capability.entitySet("Bananas").since("99.0"));
			String schema = ServerAssumptions.schemaVersionIn(metadata(session));
			Object outcome = session.execute(missing).map(n -> (Object) n).onErrorResume(e -> Mono.just(e)).block(WAIT);
			assertThat(outcome).isInstanceOfSatisfying(Tm1UnsupportedOperationException.class,
					e -> assertThat(e.getMessage()).contains("added in schema 99.0")
						.contains("reports schema " + schema)
						.contains("product version " + server.getTm1Version()));
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void sharesOneFetchBetweenAPoolsSessions(LiveServer server) {
		Tm1SessionPool pool = Tm1SessionPool.builder(server.sessionBuilder()).build();
		try {
			Tm1Capabilities first = pool.session().capabilities().block(WAIT);
			Tm1Capabilities second = pool.session(Tm1Identity.withCredentials("again", server.authenticator()))
				.capabilities()
				.block(WAIT);
			assertThat(second).isSameAs(first);
		}
		finally {
			pool.close().block(WAIT);
		}
	}

	private static String metadata(Tm1Session session) {
		return session.execute(Tm1Request.text(ODataPath.metadata()).withHeader(HttpHeaders.ACCEPT, "application/xml"))
			.block(WAIT);
	}

	private static Tm1Session session(LiveServer server) {
		return server.sessionBuilder().build();
	}

}
