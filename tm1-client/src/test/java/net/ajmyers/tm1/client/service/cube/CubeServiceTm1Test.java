package net.ajmyers.tm1.client.service.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.error.Tm1PreconditionFailedException;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.model.CubePatch;
import net.ajmyers.tm1.client.model.Dimension;
import net.ajmyers.tm1.client.model.DimensionPatch;
import net.ajmyers.tm1.client.model.ElementPatch;
import net.ajmyers.tm1.client.model.ElementType;
import net.ajmyers.tm1.client.model.HierarchyPatch;
import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Flux;

/** CubeService, on a cube of two fresh dimensions, Region and Measure. */
@RequiresTm1
class CubeServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static CubeService cubes;

	private String cube;

	private String region;

	private String measure;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		cubes = tm1.cubes();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@BeforeEach
	void createCube() {
		String id = UUID.randomUUID().toString().substring(0, 8);
		cube = "tm1-client-" + id;
		region = cube + "-region";
		measure = cube + "-measure";
		dimension(region, "North", "South");
		dimension(measure, "Amount");
		cubes.create(CubePatch.builder().name(cube).bindDimensions(dim(region), dim(measure)).build()).block(WAIT);
	}

	@AfterEach
	void deleteCube() {
		cubes.delete(cube).onErrorComplete().block(WAIT);
		tm1.dimensions().delete(region).block(WAIT);
		tm1.dimensions().delete(measure).block(WAIT);
	}

	@Test
	void createsReadsListsAndDeletesACube() {
		assertThat(cubes.get(cube, ODataQuery.none()).block(WAIT).etag()).isNotBlank();
		assertThat(cubes.list(ODataQuery.none().select(Cube.NAME).filter(Filter.startsWith(Cube.NAME, cube)))
			.map(Cube::name)
			.collectList()
			.block(WAIT)).containsExactly(cube);
		assertThat(names(cubes.dimensions(cube, ODataQuery.none().select(Dimension.NAME)))).as("Sandboxes first")
			.containsExactly("Sandboxes", region, measure);

		cubes.delete(cube).block(WAIT);
		assertThatThrownBy(() -> cubes.get(cube, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
		assertThat(tm1.dimensions().get(region, ODataQuery.none()).block(WAIT).name()).as("its dimensions stay")
			.isEqualTo(region);
	}

	@Test
	void savesRulesEvenIfTheyDontCompileAndChecksTheEtag() {
		String etag = cubes.get(cube, ODataQuery.none()).block(WAIT).etag();
		cubes.update(cube, CubePatch.builder().rules("SKIPCHECK;\n").build(), etag).block(WAIT);
		assertThat(rules()).contains("SKIPCHECK");
		assertThatThrownBy(() -> cubes.update(cube, CubePatch.builder().rules("").build(), etag).block(WAIT))
			.isInstanceOf(Tm1PreconditionFailedException.class);

		cubes.update(cube, CubePatch.builder().rules("['Nowhere'] = N: 1 +;\n").build()).block(WAIT);
		assertThat(rules()).as("TM1 keeps rules that don't compile").contains("Nowhere");
		cubes.update(cube, CubePatch.builder().rules(null).build()).block(WAIT);
		assertThat(rules()).isNullOrEmpty();
	}

	@Test
	void reordersTheStorageOrder() {
		assertThat(names(cubes.dimensionsStorageOrder(cube, ODataQuery.none()))).containsExactlyInAnyOrder(region,
				measure);
		Double change = cubes.reorderDimensions(cube, List.of(measure, region)).block(WAIT);
		assertThat(change).isNotNull();
		assertThat(names(cubes.dimensionsStorageOrder(cube, ODataQuery.none()))).containsExactly(measure, region);
		assertThat(names(cubes.dimensions(cube, ODataQuery.none()))).as("presentation order unchanged")
			.containsExactly("Sandboxes", region, measure);
	}

	@Test
	void loadsUnloadsLocksAndReserves() {
		cubes.unload(cube).then(cubes.load(cube)).block(WAIT);
		cubes.lock(cube).then(cubes.unlock(cube)).block(WAIT);
		cubes.reserve(cube).then(cubes.release(cube)).block(WAIT);
	}

	private String rules() {
		return cubes.get(cube, ODataQuery.none().select(Cube.RULES)).block(WAIT).rules();
	}

	private static List<String> names(Flux<Dimension> dimensions) {
		return dimensions.map(Dimension::name).collectList().block(WAIT);
	}

	private static ODataPath dim(String name) {
		return ODataPath.of("Dimensions").key(name);
	}

	private static void dimension(String name, String... elements) {
		tm1.dimensions()
			.create(DimensionPatch.builder()
				.name(name)
				.hierarchies(List.of(HierarchyPatch.builder()
					.name(name)
					.elements(Arrays.stream(elements)
						.map(e -> ElementPatch.builder().name(e).type(ElementType.NUMERIC).build())
						.toList())
					.build()))
				.build())
			.block(WAIT);
	}

}
