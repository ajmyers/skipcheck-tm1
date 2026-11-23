// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.Annotation;
import io.skipcheck.tm1.client.model.AnnotationPatch;
import io.skipcheck.tm1.client.model.ApplicationContextFacet;
import io.skipcheck.tm1.client.model.ApplicationContextFacetValue;
import io.skipcheck.tm1.client.model.ApplicationContextFacetValuePatch;
import io.skipcheck.tm1.client.model.Attachment;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * AnnotationService against a fake TM1. Every call is recorded to
 * {@code spec/conformance/calls/AnnotationServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class AnnotationServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String ID = "C}ClientGroups~B.B~7uA_4TxLwG";

	private static final String ANNOTATION = "{\"ID\":\"" + ID + "\",\"Text\":\"first note\",\"Creator\":\"Admin\","
			+ "\"Created\":\"2026-01-02T03:04:05.123Z\",\"LastUpdatedBy\":\"Admin\","
			+ "\"LastUpdated\":\"2026-01-02T03:04:05Z\",\"objectName\":\"}ClientGroups\","
			+ "\"commentValue\":\"first note\",\"commentType\":\"ANNOTATION\",\"commentLocation\":\"Admin,ADMIN\"}";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	private static Map<String, String> cell() {
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("}Clients", "Admin");
		cell.put("}Groups", "O'Neil / Ünï");
		return cell;
	}

	@Test
	void listsAnnotations() {
		tm1().json("{\"value\":[]}");
		List<Annotation> none = conformance.call("annotations", "list", ODataQuery.none());
		assertThat(none).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET Annotations");

		tm1().json("{\"value\":[" + ANNOTATION + "]}");
		List<Annotation> some = conformance.call("annotations", "list",
				ODataQuery.none()
					.expand("DimensionalContext", m -> m.select("UniqueName"))
					.filter(Filter.raw("Creator eq 'Admin'")));
		assertThat(some).extracting(Annotation::id).containsExactly(ID);
		assertThat(tm1().last().target())
			.startsWith("Annotations?$expand=DimensionalContext($select%3DUniqueName)&$filter=");
	}

	@Test
	void getsAnAnnotationById() {
		tm1().json(ANNOTATION);
		Annotation annotation = conformance.call("annotations", "get", ID, ODataQuery.none());
		assertThat(annotation.text()).isEqualTo("first note");
		assertThat(annotation.created()).isNotNull();
		assertThat(annotation.dynamicProperties().get("commentLocation").asString()).isEqualTo("Admin,ADMIN");
		assertThat(tm1().last().target()).isEqualTo("Annotations('C%7DClientGroups~B.B~7uA_4TxLwG')");

		tm1().json("{\"ID\":\"" + ID + "\",\"Text\":\"first note\",\"Created\":\"2026-01-02T03:04:00.120+05:30\","
				+ "\"LastUpdated\":\"2026-01-02T03:04:05.000001Z\"}");
		Annotation times = conformance.call("annotations", "get", ID, ODataQuery.none().select("ID", "Text"));
		assertThat(times.created().getOffset().getTotalSeconds()).as("read as UTC").isZero();
		assertThat(tm1().last().target()).endsWith("?$select=ID,Text");
	}

	@Test
	void aMissingAnnotationIsNotFound() {
		tm1().error(404, "278", "'C}x~y' can not be found in collection of type 'Annotation'.");
		Throwable error = conformance.fail("annotations", "get", "C}x~y", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void annotatesACellAsWorkspaceDoes() {
		tm1().json(201, ANNOTATION);
		Annotation made = conformance.call("annotations", "create", "}ClientGroups", cell(), "first note");
		assertThat(made.id()).isEqualTo(ID);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Annotations");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.json().get("ApplicationContext").get(0).get("Facet@odata.bind").asString())
			.isEqualTo("ApplicationContextFacets('%7DCubes')");
		assertThat(post.json().get("DimensionalContext@odata.bind").get(0).asString())
			.isEqualTo("Dimensions('%7DClients')/Hierarchies('%7DClients')/Members('Admin')");
		assertThat(post.json().get("commentLocation").asString()).isEqualTo("Admin,O'Neil / Ünï");
	}

	@Test
	void annotatesWithNoCellElements() {
		tm1().json(201, "{\"ID\":\"C}Sales~~x\",\"Text\":\"cube note\"}");
		conformance.call("annotations", "create", "Sales", new LinkedHashMap<String, String>(), "cube note");
		assertThat(tm1().last().json().get("commentLocation").asString()).isEmpty();
	}

	@Test
	void createsAnAnnotationBuiltByHand() {
		tm1().json(201, "{\"ID\":\"C}Sales~a\",\"Text\":\"by hand\"}");
		Annotation made = conformance.call("annotations", "create",
				AnnotationPatch.builder()
					.text("by hand")
					.applicationContext(List.of(ApplicationContextFacetValuePatch.builder()
						.bindFacet(ODataPath.of("ApplicationContextFacets").key("}Cubes"))
						.value("Sales")
						.build()))
					.bindDimensionalContext(ODataPath.of("Dimensions")
						.key("Region")
						.nav("Hierarchies")
						.key("Region")
						.nav("Members")
						.key("North"))
					.property("commentValue", "by hand")
					.build());
		assertThat(made.text()).isEqualTo("by hand");
		assertThat(tm1().last().key()).isEqualTo("POST Annotations");
	}

	@Test
	void updatesAnAnnotation() {
		tm1().status(204);
		conformance.call("annotations", "update", ID,
				AnnotationPatch.builder().text("second note").property("commentValue", "second note").build());
		ConformanceTm1.Recorded patch = tm1().last();
		assertThat(patch.key()).isEqualTo("PATCH Annotations('C%7DClientGroups~B.B~7uA_4TxLwG')");
		assertThat(patch.text()).isEqualTo("{\"Text\":\"second note\",\"commentValue\":\"second note\"}");
		assertThat(patch.header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("annotations", "update", ID,
				AnnotationPatch.builder()
					.lastUpdated(OffsetDateTime.of(2026, 1, 2, 3, 4, 0, 120_000_000, ZoneOffset.ofHoursMinutes(5, 30)))
					.created(OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC))
					.build());
		assertThat(tm1().last().json().get("Created").asString()).startsWith("2026-01-02T03:04");
	}

	@Test
	void deletesAnAnnotation() {
		tm1().status(204);
		conformance.call("annotations", "delete", ID);
		assertThat(tm1().last().key()).isEqualTo("DELETE Annotations('C%7DClientGroups~B.B~7uA_4TxLwG')");

		tm1().error(404, "278", "'gone' can not be found in collection of type 'Annotation'.");
		assertThat(conformance.fail("annotations", "delete", "gone")).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsAnAnnotationsContextsAndAttachments() {
		tm1().json("{\"value\":[]}");
		List<ApplicationContextFacetValue> context = conformance.call("annotations", "applicationContext", ID,
				ODataQuery.none().expand("Facet"));
		assertThat(context).isEmpty();
		assertThat(tm1().last().target())
			.isEqualTo("Annotations('C%7DClientGroups~B.B~7uA_4TxLwG')/ApplicationContext?$expand=Facet");

		tm1().json("{\"value\":[{\"Name\":\"Admin\",\"UniqueName\":\"[}Clients].[}Clients].[Admin]\"},"
				+ "{\"Name\":\"ADMIN\",\"UniqueName\":\"[}Groups].[}Groups].[ADMIN]\"}]}");
		List<Member> members = conformance.call("annotations", "dimensionalContext", ID,
				ODataQuery.none().select("UniqueName"));
		assertThat(members).extracting(Member::uniqueName)
			.containsExactly("[}Clients].[}Clients].[Admin]", "[}Groups].[}Groups].[ADMIN]");
		assertThat(tm1().last().target()).endsWith("/DimensionalContext?$select=UniqueName");

		tm1().json("{\"value\":[{\"Name\":\"plan.pdf\",\"ContentType\":\"application/pdf\",\"Size\":1024}]}");
		List<Attachment> attachments = conformance.call("annotations", "attachments", ID, ODataQuery.none());
		assertThat(attachments).extracting(Attachment::size).containsExactly(1024L);
		assertThat(tm1().last().target()).endsWith("/Attachments");
	}

	@Test
	void findsAnnotationsByCubeAndByCell() {
		tm1().json("{\"value\":[" + ANNOTATION + "]}");
		List<Annotation> byCube = conformance.call("annotations", "cubeAnnotations", "}ClientGroups",
				ODataQuery.none().select("ID", "Text"));
		assertThat(byCube).extracting(Annotation::id).containsExactly(ID);
		assertThat(tm1().last().target()).isEqualTo("Cubes('%7DClientGroups')/Annotations?$select=ID,Text");

		tm1().json("{\"value\":[{\"ID\":\"" + ID + "\"}]}");
		List<Annotation> byCell = conformance.call("annotations", "cellAnnotations", "iAAAAJ7x", 3L,
				ODataQuery.none().select("ID"));
		assertThat(byCell).extracting(Annotation::id).containsExactly(ID);
		assertThat(tm1().last().target()).isEqualTo("Cellsets('iAAAAJ7x')/Cells(3)/Annotations?$select=ID");
	}

	@Test
	void readsFacetsAndTheirValues() {
		tm1().json("{\"value\":[{\"Name\":\"}Cubes\",\"Values\":[{\"Value\":\"}ClientGroups\"}]}]}");
		List<ApplicationContextFacet> facets = conformance.call("annotations", "facets",
				ODataQuery.none().expand("Values"));
		assertThat(facets).extracting(ApplicationContextFacet::name).containsExactly("}Cubes");
		assertThat(tm1().last().target()).isEqualTo("ApplicationContextFacets?$expand=Values");

		tm1().json("{\"value\":[{\"Value\":\"}ClientGroups\"},{\"Value\":\"Sales\"}]}");
		List<ApplicationContextFacetValue> values = conformance.call("annotations", "facetValues", "}Cubes",
				ODataQuery.none());
		assertThat(values).extracting(ApplicationContextFacetValue::value).containsExactly("}ClientGroups", "Sales");
		assertThat(tm1().last().target()).isEqualTo("ApplicationContextFacets('%7DCubes')/Values");

		tm1().json("{\"Name\":\"}Cubes\"}");
		ApplicationContextFacet facet = conformance.call("annotations", "facet", "}Cubes", "O'Neil's cube",
				ODataQuery.none().select("Name"));
		assertThat(facet.name()).isEqualTo("}Cubes");
		assertThat(tm1().last().target())
			.isEqualTo("ApplicationContextFacets('%7DCubes')/Values('O''Neil''s%20cube')/Facet?$select=Name");
	}

	@Test
	void deletesEveryAnnotationArtifact() {
		tm1().status(204);
		conformance.call("annotations", "deleteAnnotationArtifacts");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST DeleteAnnotationArtifacts");
	}

	@Test
	void refusesMissingArguments() {
		assertThat(conformance.fail("annotations", "create", null, cell(), "text"))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("annotations", "create", "Sales", cell(), null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("annotations", "get", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("annotations", "facetValues", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
