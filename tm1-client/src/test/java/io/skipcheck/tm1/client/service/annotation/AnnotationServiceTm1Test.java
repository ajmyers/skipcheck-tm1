package io.skipcheck.tm1.client.service.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Annotation;
import io.skipcheck.tm1.client.model.AnnotationPatch;
import io.skipcheck.tm1.client.model.ApplicationContextFacet;
import io.skipcheck.tm1.client.model.ApplicationContextFacetValue;
import io.skipcheck.tm1.client.model.Cellset;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** AnnotationService: comments on cells, found by cube, cell and ID, and their facets. */
@RequiresTm1
class AnnotationServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static final String CUBE = "}ClientGroups";

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
	void annotatesACellAndFindsItByCubeCellAndId() {
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("}Clients", "Admin");
		cell.put("}Groups", "ADMIN");
		Annotation made = tm1.annotations().create(CUBE, cell, "first note").block(WAIT);
		String id = made.id();
		try {
			assertThat(id).startsWith("C" + CUBE + "~");
			assertThat(made.dynamicProperties()).containsKeys("objectName", "commentValue", "commentLocation");
			assertThat(made.dynamicProperties().get("commentLocation").asString()).isEqualTo("Admin,ADMIN");

			assertThat(tm1.annotations().list(ODataQuery.none()).collectList().block(WAIT)).as("TM1 lists none here")
				.isEmpty();
			assertThat(tm1.annotations()
				.cubeAnnotations(CUBE, ODataQuery.none())
				.map(Annotation::id)
				.collectList()
				.block(WAIT)).contains(id);
			Cellset cellset = tm1.cellsets()
				.execute("SELECT {[}Clients].[Admin]} ON 0, {[}Groups].[ADMIN]} ON 1 FROM [" + CUBE + "]",
						ODataQuery.none())
				.block(WAIT);
			try {
				assertThat(tm1.annotations()
					.cellAnnotations(cellset.id(), 0, ODataQuery.none())
					.map(Annotation::id)
					.collectList()
					.block(WAIT)).containsExactly(id);
			}
			finally {
				tm1.cellsets().delete(cellset.id()).block(WAIT);
			}

			assertThat(tm1.annotations()
				.dimensionalContext(id, ODataQuery.none().select("UniqueName"))
				.map(Member::uniqueName)
				.collectList()
				.block(WAIT)).containsExactly("[}Clients].[}Clients].[Admin]", "[}Groups].[}Groups].[ADMIN]");
			assertThat(tm1.annotations().applicationContext(id, ODataQuery.none()).collectList().block(WAIT))
				.as("reads back empty, though the facet value exists")
				.isEmpty();
			assertThat(tm1.annotations()
				.facetValues("}Cubes", ODataQuery.none())
				.map(ApplicationContextFacetValue::value)
				.collectList()
				.block(WAIT)).contains(CUBE);
			assertThat(tm1.annotations().facet("}Cubes", CUBE, ODataQuery.none()).block(WAIT).name())
				.isEqualTo("}Cubes");
			assertThat(tm1.annotations()
				.facets(ODataQuery.none())
				.map(ApplicationContextFacet::name)
				.collectList()
				.block(WAIT)).contains("}Cubes");
			assertThat(tm1.annotations().attachments(id, ODataQuery.none()).collectList().block(WAIT)).isEmpty();

			tm1.annotations().update(id, AnnotationPatch.builder().text("second note").build()).block(WAIT);
			Annotation changed = tm1.annotations().get(id, ODataQuery.none()).block(WAIT);
			assertThat(changed.text()).isEqualTo("second note");
			assertThat(changed.lastUpdatedBy()).isNotNull();
			assertThat(changed.dynamicProperties().get("commentValue").asString()).as("kept apart from Text")
				.isEqualTo("first note");
		}
		finally {
			tm1.annotations().delete(id).block(WAIT);
		}
		assertThatThrownBy(() -> tm1.annotations().get(id, ODataQuery.none()).block(WAIT))
			.isInstanceOf(Tm1NotFoundException.class);
		assertThatThrownBy(() -> tm1.annotations().get("no-such-annotation", ODataQuery.none()).block(WAIT))
			.as("an ID TM1 never gave out is a bad request, not a missing annotation")
			.isInstanceOfSatisfying(Tm1BadRequestException.class,
					e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.INVALID_UNIQUE_NAME));
	}

	@Test
	void deletesEveryAnnotationArtifact() {
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("}Clients", "Admin");
		cell.put("}Groups", "ADMIN");
		tm1.annotations().create(CUBE, cell, "to be swept").block(WAIT);
		tm1.annotations().deleteAnnotationArtifacts().block(WAIT);
		assertThat(tm1.annotations().cubeAnnotations(CUBE, ODataQuery.none()).collectList().block(WAIT)).isEmpty();
	}

}
