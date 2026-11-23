package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.error.Tm1UnsupportedOperationException;
import io.skipcheck.tm1.client.metadata.MetadataSnapshot;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Capability;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Version and capabilities read from a real TM1. */
@RequiresTm1
class Tm1CapabilitiesTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void readsTheVersionAndTheCapabilitiesTheSnapshotDescribes(Tm1ServerContainer server) {
		Tm1Session session = session(server);
		try {
			assertThat(session.version().block(WAIT)).isEqualTo(server.getTm1Version());
			Tm1Capabilities capabilities = session.capabilities().block(WAIT);
			CsdlDocument snapshot = MetadataSnapshot.load();
			assertThat(capabilities.schemaVersion()).isEqualTo(snapshot.schemaVersion());

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
			assertThat(capabilities.supports(Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve"))).isTrue();
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void refusesARequestTheServerDoesntDefine(Tm1ServerContainer server) {
		Tm1Session session = session(server);
		try {
			Tm1Request<JsonNode> missing = Tm1Request.get(ODataPath.of("Bananas"), ODataQuery.none(), JsonNode.class)
				.requires(Tm1Capability.entitySet("Bananas").since("99.0"));
			Object outcome = session.execute(missing).map(n -> (Object) n).onErrorResume(e -> Mono.just(e)).block(WAIT);
			assertThat(outcome).isInstanceOfSatisfying(Tm1UnsupportedOperationException.class,
					e -> assertThat(e.getMessage()).contains("added in schema 99.0")
						.contains("reports schema " + MetadataSnapshot.VERSION)
						.contains("product version " + server.getTm1Version()));
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void sharesOneFetchBetweenAPoolsSessions(Tm1ServerContainer server) {
		Tm1SessionPool pool = Tm1SessionPool.builder(
				Tm1Session.builder().baseUrl(server.getRestUrl()).basicAuth(server.getUsername(), server.getPassword()))
			.build();
		try {
			Tm1Capabilities first = pool.session().capabilities().block(WAIT);
			Tm1Capabilities second = pool
				.session(Tm1Identity.withCredentials("again",
						Tm1Authenticator.basic(server.getUsername(), server.getPassword())))
				.capabilities()
				.block(WAIT);
			assertThat(second).isSameAs(first);
		}
		finally {
			pool.close().block(WAIT);
		}
	}

	private static Tm1Session session(Tm1ServerContainer server) {
		return Tm1Session.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

}
