// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.session;

import static io.skipcheck.tm1.client.session.FakeTm1.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/**
 * Tm1Session.batch: requests queued, sent as one JSON $batch, each with its own result.
 */
class Tm1SessionBatchTest {

	private static final String BATCH = "POST $batch";

	@Test
	void sendsQueuedRequestsTogetherAndHandsEachItsResult() {
		FakeTm1 tm1 = new FakeTm1().on(BATCH, request -> Mono.just(json(200, """
				{"responses":[
				 {"id":"1","status":200,"body":{"Name":"Sales"}},
				 {"id":"2","status":404,"body":{"error":{"code":"278","message":"'Nope' can not be found"}}},
				 {"id":"3","status":200,"body":{"value":[{"Name":"a"},{"Name":"b"}]}},
				 {"id":"4","status":204}
				]}""")));
		Tm1SessionBatch batch = tm1.session().batch();
		Tm1Session queued = batch.session();
		Mono<JsonNode> sales = queued
			.execute(Tm1Request.get(ODataPath.of("Cubes").key("Sales"), ODataQuery.none(), JsonNode.class));
		Mono<JsonNode> nope = queued
			.execute(Tm1Request.get(ODataPath.of("Cubes").key("Nope"), ODataQuery.none(), JsonNode.class));
		List<JsonNode> listed = new ArrayList<>();
		queued.stream(Tm1Request.list(ODataPath.of("Cubes"), ODataQuery.none(), JsonNode.class)).subscribe(listed::add);
		Mono<Void> deleted = queued.execute(Tm1Request.delete(ODataPath.of("Cubes").key("Old")));
		assertThat(batch.size()).isEqualTo(4);
		assertThat(tm1.requests(BATCH)).as("nothing sent before run").isEmpty();

		StepVerifier.create(batch.run()).verifyComplete();
		StepVerifier.create(sales)
			.assertNext(c -> assertThat(c.path("Name").asString()).isEqualTo("Sales"))
			.verifyComplete();
		StepVerifier.create(nope).expectError(Tm1NotFoundException.class).verify();
		StepVerifier.create(deleted).verifyComplete();
		assertThat(listed).extracting(n -> n.path("Name").asString()).containsExactly("a", "b");

		assertThat(tm1.requests(BATCH)).as("one $batch").hasSize(1);
	}

	@Test
	void refusesCallsAfterItRan() {
		FakeTm1 tm1 = new FakeTm1().on(BATCH, request -> Mono.just(json(200, "{\"responses\":[]}")));
		Tm1SessionBatch batch = tm1.session().batch();
		StepVerifier.create(batch.run()).verifyComplete();
		StepVerifier.create(batch.session().execute(Tm1Request.delete(ODataPath.of("Cubes").key("x"))))
			.expectError(IllegalStateException.class)
			.verify();
		StepVerifier.create(batch.run()).expectError(IllegalStateException.class).verify();
	}

	@Test
	void splitsABigQueue() {
		FakeTm1 tm1 = new FakeTm1().on(BATCH, request -> Mono.just(json(200, "{\"responses\":[]}")));
		Tm1SessionBatch batch = tm1.session().batch();
		for (int i = 0; i < 5; i++) {
			batch.session().execute(Tm1Request.delete(ODataPath.of("Cubes").key("c" + i))).subscribe(null, e -> {
			});
		}
		StepVerifier.create(batch.run(2)).verifyComplete();
		assertThat(tm1.requests(BATCH)).hasSize(3);
	}

}
