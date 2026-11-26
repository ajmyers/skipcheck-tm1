// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ProtocolException;
import io.skipcheck.tm1.client.error.Tm1UnsupportedOperationException;
import io.skipcheck.tm1.client.metadata.MetadataSnapshot;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Capability;
import io.skipcheck.tm1.client.version.Tm1Version;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/** Version and capabilities through the session, against {@link FakeTm1}. */
class Tm1SessionCapabilitiesTest {

	private static final String VERSION = "GET Server?$select=ProductVersion";

	private static final String METADATA = "GET $metadata";

	private final FakeTm1 tm1 = new FakeTm1()
		.on(VERSION, r -> Mono.just(FakeTm1.json(200, "{\"ProductVersion\":\"11.8.03500.6\"}")))
		.on(METADATA, r -> Mono.just(xml(MetadataSnapshot.text())))
		.on("GET Cubes('Sales')/tm1.DimensionsStorageOrder()", r -> Mono.just(FakeTm1.json(200, "{}")));

	@Test
	void readsTheVersionOnceInsideTheSession() {
		Tm1Session session = tm1.session();
		StepVerifier.create(session.version()).expectNext(Tm1Version.parse("11.8.03500.6")).verifyComplete();
		StepVerifier.create(session.version()).expectNextCount(1).verifyComplete();
		assertThat(tm1.requests(VERSION)).hasSize(1);
		assertThat(FakeTm1.cookie(tm1.requests(VERSION).get(0))).as("never an anonymous read").isNotNull();
	}

	@Test
	void fetchesMetadataOncePerServerVersionForEverySessionWithTheSameSettings() {
		Tm1Session.Builder settings = Tm1Session.builder();
		Tm1Session first = tm1.session(settings);
		Tm1Session second = tm1.session(settings);
		StepVerifier.create(first.capabilities()).assertNext(c -> {
			assertThat(c.schemaVersion()).hasValue("11.8.35");
			assertThat(c.productVersion()).hasValue(Tm1Version.parse("11.8.03500.6"));
		}).verifyComplete();
		StepVerifier.create(second.capabilities()).expectNextCount(1).verifyComplete();
		assertThat(tm1.requests(METADATA)).hasSize(1);
		assertThat(tm1.requests(METADATA).get(0).headers().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/xml");
	}

	@Test
	void sendsARequestWhoseRequirementTheServerMeets() {
		Tm1Request<JsonNode> order = Tm1Request
			.get(ODataPath.of("Cubes").key("Sales").function("tm1.DimensionsStorageOrder", Map.of()), ODataQuery.none(),
					JsonNode.class)
			.requires(Tm1Capability.boundOperation("tm1.Cube", "tm1.DimensionsStorageOrder").since("11.4.0"));
		StepVerifier.create(tm1.session().execute(order)).expectNextCount(1).verifyComplete();
	}

	@Test
	void refusesARequestTheServerCantServeWithoutSendingIt() {
		Tm1Request<JsonNode> bananas = Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none(), JsonNode.class)
			.requires(Tm1Capability.entitySet("Bananas").since("99.0"));
		StepVerifier.create(tm1.session().stream(bananas))
			.expectErrorSatisfies(
					e -> assertThat(e).isInstanceOfSatisfying(Tm1UnsupportedOperationException.class, u -> {
						assertThat(u.missing()).isEqualTo("entity set Bananas");
						assertThat(u.getMessage()).contains("added in schema 99.0").contains("reports schema 11.8.35");
					}))
			.verify();
		assertThat(tm1.requests("GET Bananas")).isEmpty();
	}

	@Test
	void refusesARequestTheServerCantServeInABatchAndStillSendsTheRest() {
		tm1.on("POST $batch",
				r -> Mono.just(FakeTm1.json(200, "{\"responses\":[{\"id\":\"2\",\"status\":200,\"body\":{}}]}")));
		Tm1SessionBatch batch = tm1.session().batch();
		Flux<JsonNode> bananas = batch.session()
			.stream(Tm1Request.list(ODataPath.of("Bananas"), ODataQuery.none(), JsonNode.class)
				.requires(Tm1Capability.entitySet("Bananas").since("99.0")));
		Mono<JsonNode> server = batch.session()
			.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), JsonNode.class));

		StepVerifier.create(batch.run()).expectComplete().verify(Duration.ofSeconds(5));
		StepVerifier.create(bananas).expectError(Tm1UnsupportedOperationException.class).verify(Duration.ofSeconds(5));
		StepVerifier.create(server).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(5));
		assertThat(tm1.requests("POST $batch")).singleElement()
			.satisfies(sent -> assertThat(new String(ConformanceTm1.body(sent).block(), StandardCharsets.UTF_8))
				.contains("Server")
				.doesNotContain("Bananas"));
	}

	@Test
	void sendsEveryParameterTheServerDefines() {
		tm1.on(CHECK_FEEDERS, r -> Mono.just(FakeTm1.json(200, "{\"value\":[]}")));
		StepVerifier.create(tm1.session().stream(checkFeeders("Both"))).verifyComplete();
		assertThat(sentBody()).contains("\"Selection\":\"Both\"");
	}

	@Test
	void refusesAParameterTheServerDoesntDefineWithoutSendingIt() {
		withoutSelection();
		StepVerifier.create(tm1.session().stream(checkFeeders("Both")))
			.expectErrorSatisfies(
					e -> assertThat(e).isInstanceOfSatisfying(Tm1UnsupportedOperationException.class, u -> {
						assertThat(u.missing()).isEqualTo("parameter Selection of Cube.CheckFeeders");
						assertThat(u.getMessage()).contains("reports schema 11.8.35");
					}))
			.verify();
		assertThat(tm1.requests(CHECK_FEEDERS)).isEmpty();
	}

	@Test
	void leavesOutAParameterTheServerDoesntDefineWhenItsValueIsWhatLeavingItOutMeans() {
		withoutSelection();
		tm1.on(CHECK_FEEDERS, r -> Mono.just(FakeTm1.json(200, "{\"value\":[]}")));
		StepVerifier.create(tm1.session().stream(checkFeeders("Unfed"))).verifyComplete();
		assertThat(sentBody()).contains("Tuple@odata.bind").doesNotContain("Selection");
	}

	@Test
	void leavesOutAParameterInABatchToo() {
		withoutSelection();
		tm1.on("POST $batch", r -> Mono.just(FakeTm1.json(200,
				"{\"responses\":[{\"id\":\"1\",\"status\":200,\"body\":{\"value\":[]}},{\"id\":\"2\",\"status\":200,\"body\":{\"value\":[]}}]}")));
		Tm1SessionBatch batch = tm1.session().batch();
		Flux<JsonNode> unfed = batch.session().stream(checkFeeders("Unfed"));
		Flux<JsonNode> both = batch.session().stream(checkFeeders("Both"));
		StepVerifier.create(batch.run()).expectComplete().verify(Duration.ofSeconds(5));
		StepVerifier.create(unfed).expectComplete().verify(Duration.ofSeconds(5));
		StepVerifier.create(both).expectError(Tm1UnsupportedOperationException.class).verify(Duration.ofSeconds(5));
		assertThat(tm1.requests("POST $batch")).singleElement()
			.satisfies(sent -> assertThat(new String(ConformanceTm1.body(sent).block(), StandardCharsets.UTF_8))
				.contains("tm1.CheckFeeders")
				.doesNotContain("Selection"));
	}

	private static final String CHECK_FEEDERS = "POST Cubes('Sales')/tm1.CheckFeeders";

	/** A server whose CheckFeeders has no Selection, as before schema 11.8.32. */
	private void withoutSelection() {
		String metadata = MetadataSnapshot.text()
			.replaceFirst("(?s)(<Action Name=\"CheckFeeders\".*?)<Parameter Name=\"Selection\".*?</Parameter>", "$1");
		assertThat(metadata).isNotEqualTo(MetadataSnapshot.text());
		tm1.on(METADATA, r -> Mono.just(xml(metadata)));
	}

	private static Tm1Request<JsonNode> checkFeeders(String selection) {
		return Tm1Request
			.postList(ODataPath.of("Cubes").key("Sales").action("tm1.CheckFeeders"),
					Map.of("Tuple@odata.bind", List.of("Dimensions('Region')/Hierarchies('Region')/Elements('Total')"),
							"Selection", selection),
					JsonNode.class)
			.requires(Tm1Operations.CUBE_CHECK_FEEDERS)
			.withParameterDefault("Selection", "Unfed");
	}

	private String sentBody() {
		return new String(ConformanceTm1.body(tm1.requests(CHECK_FEEDERS).get(0)).block(), StandardCharsets.UTF_8);
	}

	@Test
	void readsTheVersionAgainAfterLoggingInAgain() {
		tm1.onEach("GET Server", r -> Mono.just(FakeTm1.status(401, "TM1SessionId=fresh; Path=/api/")),
				r -> Mono.just(FakeTm1.json(200, "{}")));
		Tm1Session session = tm1.session();
		StepVerifier.create(session.version()).expectNextCount(1).verifyComplete();
		StepVerifier.create(session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), JsonNode.class)))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier.create(session.version()).expectNextCount(1).verifyComplete();
		assertThat(tm1.requests(VERSION)).hasSize(2);
	}

	@Test
	void reportsMetadataThatIsntCsdl() {
		tm1.on(METADATA, r -> Mono.just(xml("<html>Not metadata</html>")));
		StepVerifier.create(tm1.session().capabilities()).expectError(Tm1ProtocolException.class).verify();
	}

	@Test
	void doesntKeepAFailedRead() {
		tm1.onEach(VERSION, r -> Mono.just(FakeTm1.json(503, "{\"error\":{\"code\":\"1\",\"message\":\"busy\"}}")),
				r -> Mono.just(FakeTm1.json(200, "{\"ProductVersion\":\"11.8.03500.6\"}")));
		Tm1Session session = tm1.session();
		StepVerifier.create(session.version()).expectError().verify();
		StepVerifier.create(session.version()).expectNextCount(1).verifyComplete();
	}

	private static ClientResponse xml(String body) {
		return ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, "application/xml")
			.body(body)
			.build();
	}

}
