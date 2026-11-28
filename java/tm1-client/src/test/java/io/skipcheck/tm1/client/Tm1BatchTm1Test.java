// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.Element;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.model.ProcessCreate;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessExecuteStatusCode;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tm1Batch against TM1: many calls in one $batch, each with its own result. */
@LiveTm1
class Tm1BatchTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(LiveServer server) {
		tm1 = server.clientBuilder().build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void runsReadsWritesAndFailuresTogether() {
		String dimension = "batch" + UUID.randomUUID().toString().substring(0, 8);
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(dimension)
				.hierarchies(List.of(HierarchyCreate.builder().name(dimension).build()))
				.build())
			.block(WAIT);
		tm1.processes().create(ProcessCreate.builder().name(process).prologProcedure("x = 1;\n").build()).block(WAIT);
		try {
			Tm1Batch batch = tm1.batch();
			Tm1Client queued = batch.client();
			Mono<Cube> cube = queued.cubes().get("}ClientGroups", ODataQuery.none().select(Cube.NAME));
			Mono<Cube> missing = queued.cubes().get("not-a-cube", ODataQuery.none());
			Mono<Element> north = queued.elements()
				.create(dimension, dimension, ElementCreate.builder().name("North").build());
			Mono<Element> twice = queued.elements()
				.create(dimension, dimension, ElementCreate.builder().name("North").build());
			Flux<Element> listed = queued.elements().list(dimension, dimension, ODataQuery.none());
			Mono<ProcessExecuteResult> run = queued.processes().executeWithReturn(process, Map.of(), ODataQuery.none());
			Mono<String> count = queued.session()
				.execute(Tm1Request.text(ODataPath.of("Dimensions")
					.key(dimension)
					.nav("Hierarchies")
					.key(dimension)
					.nav("Elements")
					.count()));
			assertThat(batch.size()).isEqualTo(7);

			batch.run().block(WAIT);

			assertThat(cube.block(WAIT).name()).isEqualTo("}ClientGroups");
			assertThatThrownBy(() -> missing.block(WAIT)).isInstanceOf(Tm1NotFoundException.class);
			if (ServerAssumptions.isV12(tm1)) {
				// TM1 v12 executes $batch requests concurrently rather than sequentially:
				// one of the two duplicate creates succeeds and the other fails with a
				// conflict, and the write is visible once the batch completes
				List<Object> creates = List.of(north.map(e -> (Object) e.name()).onErrorResume(Mono::just).block(WAIT),
						twice.map(e -> (Object) e.name()).onErrorResume(Mono::just).block(WAIT));
				assertThat(creates).contains("North").anyMatch(Throwable.class::isInstance);
				assertThat(listed.map(Element::name).collectList().block(WAIT)).isNotNull();
				assertThat(run.block(WAIT).processExecuteStatusCode())
					.isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
				assertThat(count.block(WAIT)).as("plain text, which TM1 refuses under the batch's own Accept")
					.isIn("0", "1");
				assertThat(tm1.elements()
					.list(dimension, dimension, ODataQuery.none())
					.map(Element::name)
					.collectList()
					.block(WAIT)).containsExactly("North");
			}
			else {
				assertThat(north.block(WAIT).name()).isEqualTo("North");
				assertThatThrownBy(() -> twice.block(WAIT)).as("TM1 carries on past it, in order").isNotNull();
				assertThat(listed.map(Element::name).collectList().block(WAIT)).as("sees the element made before it")
					.containsExactly("North");
				assertThat(run.block(WAIT).processExecuteStatusCode())
					.isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
				assertThat(count.block(WAIT)).as("plain text, which TM1 refuses under the batch's own Accept")
					.isEqualTo("1");
			}
		}
		finally {
			tm1.processes().delete(process).block(WAIT);
			tm1.dimensions().delete(dimension).block(WAIT);
		}
	}

	@Test
	void runsRequestsInOrder() {
		ServerAssumptions.assumeV11(tm1,
				"TM1 v12 $batch reads don't see writes from earlier requests in the same batch");
		String dimension = "batch" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionCreate.builder()
				.name(dimension)
				.hierarchies(List.of(HierarchyCreate.builder().name(dimension).build()))
				.build())
			.block(WAIT);
		try {
			Tm1Batch batch = tm1.batch();
			Tm1Client queued = batch.client();
			List<Mono<Element>> before = new ArrayList<>();
			List<Mono<Element>> after = new ArrayList<>();
			// Ten times over, so that requests run side by side would show
			for (int i = 0; i < 10; i++) {
				String name = "e" + i;
				before.add(queued.elements().get(dimension, dimension, name, ODataQuery.none()));
				queued.elements().create(dimension, dimension, ElementCreate.builder().name(name).build());
				after.add(queued.elements().get(dimension, dimension, name, ODataQuery.none()));
			}

			batch.run().block(WAIT);

			for (int i = 0; i < 10; i++) {
				Mono<Element> read = before.get(i);
				assertThatThrownBy(() -> read.block(WAIT)).as("runs before the element is made")
					.isInstanceOf(Tm1NotFoundException.class);
				assertThat(after.get(i).block(WAIT).name()).as("sees the element an earlier request made")
					.isEqualTo("e" + i);
			}
		}
		finally {
			tm1.dimensions().delete(dimension).block(WAIT);
		}
	}

	@Test
	void takesAThousandRequests() {
		Tm1Batch batch = tm1.batch();
		List<Mono<Cube>> reads = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			reads.add(batch.client().cubes().get("}ClientGroups", ODataQuery.none().select(Cube.NAME)));
		}
		batch.run(1000).block(WAIT);
		assertThat(Flux.concat(reads).count().block(WAIT)).isEqualTo(1000);
	}

}
