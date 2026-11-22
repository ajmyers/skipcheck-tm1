package net.ajmyers.tm1.client;

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

import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.model.DimensionPatch;
import net.ajmyers.tm1.client.model.Element;
import net.ajmyers.tm1.client.model.ElementPatch;
import net.ajmyers.tm1.client.model.HierarchyPatch;
import net.ajmyers.tm1.client.model.ProcessExecuteResult;
import net.ajmyers.tm1.client.model.ProcessExecuteStatusCode;
import net.ajmyers.tm1.client.model.ProcessPatch;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tm1Batch against TM1: many calls in one $batch, each with its own result. */
@RequiresTm1
class Tm1BatchTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
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
			.create(DimensionPatch.builder()
				.name(dimension)
				.hierarchies(List.of(HierarchyPatch.builder().name(dimension).build()))
				.build())
			.block(WAIT);
		tm1.processes().create(ProcessPatch.builder().name(process).prologProcedure("x = 1;\n").build()).block(WAIT);
		try {
			Tm1Batch batch = tm1.batch();
			Tm1Client queued = batch.client();
			Mono<Cube> cube = queued.cubes().get("}ClientGroups", ODataQuery.none().select(Cube.NAME));
			Mono<Cube> missing = queued.cubes().get("not-a-cube", ODataQuery.none());
			Mono<Element> north = queued.elements()
				.create(dimension, dimension, ElementPatch.builder().name("North").build());
			Mono<Element> twice = queued.elements()
				.create(dimension, dimension, ElementPatch.builder().name("North").build());
			Flux<Element> listed = queued.elements().list(dimension, dimension, ODataQuery.none());
			Mono<ProcessExecuteResult> run = queued.processes().executeWithReturn(process, Map.of(), ODataQuery.none());
			assertThat(batch.size()).isEqualTo(6);

			batch.run().block(WAIT);

			assertThat(cube.block(WAIT).name()).isEqualTo("}ClientGroups");
			assertThatThrownBy(() -> missing.block(WAIT)).isInstanceOf(Tm1NotFoundException.class);
			assertThat(north.block(WAIT).name()).isEqualTo("North");
			assertThatThrownBy(() -> twice.block(WAIT)).as("TM1 carries on past it, in order").isNotNull();
			assertThat(listed.map(Element::name).collectList().block(WAIT)).as("sees the element made before it")
				.containsExactly("North");
			assertThat(run.block(WAIT).processExecuteStatusCode())
				.isEqualTo(ProcessExecuteStatusCode.COMPLETED_SUCCESSFULLY);
		}
		finally {
			tm1.processes().delete(process).block(WAIT);
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
