package io.skipcheck.tm1.client.service.attribute;

import static io.skipcheck.tm1.client.service.attribute.AttributeArgs.set;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.AttributeDefinition;
import io.skipcheck.tm1.client.model.AttributeDefinitionPatch;
import io.skipcheck.tm1.client.model.AttributeType;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * AttributeService against a fake TM1, for every kind of {@link AttributeSet}. Every call
 * is recorded to {@code spec/conformance/calls/AttributeServiceTest.json}, which the
 * Python, .NET and TypeScript clients replay.
 */
class AttributeServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	/** Every kind of set, by the path it stands for. */
	private static Map<String, AttributeSet> sets() {
		Map<String, AttributeSet> sets = new LinkedHashMap<>();
		sets.put("DimensionAttributes", set("dimensions"));
		sets.put("Dimensions('Region')/HierarchyAttributes", set("hierarchies", "Region"));
		sets.put("Dimensions('Region')/Hierarchies('Alt')/ElementAttributes", set("elements", "Region", "Alt"));
		sets.put("Dimensions('Region')/Hierarchies('Region')/SubsetAttributes", set("subsets", "Region", "Region"));
		sets.put("CubeAttributes", set("cubes"));
		sets.put("Cubes('Sales')/ViewAttributes", set("views", "Sales"));
		sets.put("ProcessAttributes", set("processes"));
		sets.put("ChoreAttributes", set("chores"));
		return sets;
	}

	@Test
	void listsEachSetsAttributes() {
		sets().forEach((path, set) -> {
			tm1().json(
					"{\"value\":[{\"Name\":\"Caption\",\"Type\":\"Alias\"},{\"Name\":\"Code\",\"Type\":\"String\"}]}");
			List<AttributeDefinition> attributes = conformance.call("attributes", "list", set, ODataQuery.none());
			assertThat(attributes).extracting(AttributeDefinition::type)
				.containsExactly(AttributeType.ALIAS, AttributeType.STRING);
			assertThat(tm1().last().key()).isEqualTo("GET " + path);
		});

		tm1().json("{\"value\":[{\"Name\":\"Code\"}]}");
		conformance.call("attributes", "list", set("elements", "Region", "Region"),
				ODataQuery.none().select("Name").filter(Filter.raw("Type eq 'String'")).orderBy("Name"));
		assertThat(tm1().last().target())
			.startsWith("Dimensions('Region')/Hierarchies('Region')/ElementAttributes?$select=Name&$filter=");
	}

	@Test
	void getsOneAttributeOfEachSet() {
		sets().forEach((path, set) -> {
			tm1().json("{\"Name\":\"Code\",\"Type\":\"String\"}");
			AttributeDefinition code = conformance.call("attributes", "get", set, "Code", ODataQuery.none());
			assertThat(code.name()).isEqualTo("Code");
			assertThat(tm1().last().key()).isEqualTo("GET " + path + "('Code')");
		});

		tm1().json("{\"Name\":\"O'Neil / Ünï\",\"Type\":\"Numeric\"}");
		conformance.call("attributes", "get", set("hierarchies", "}Clients & Co"), "O'Neil / Ünï",
				ODataQuery.none().select("Type"));
		assertThat(tm1().last().target())
			.startsWith("Dimensions('%7DClients%20%26%20Co')/HierarchyAttributes('O''Neil%20%2F%20");
	}

	@Test
	void aMissingAttributeIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'AttributeDefinition'.");
		Throwable error = conformance.fail("attributes", "get", set("cubes"), "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAnAttributeInEachSet() {
		sets().forEach((path, set) -> {
			tm1().json(201, "{\"Name\":\"Code\",\"Type\":\"String\"}");
			AttributeDefinition created = conformance.call("attributes", "create", set,
					AttributeDefinitionPatch.builder().name("Code").type(AttributeType.STRING).build());
			assertThat(created.type()).isEqualTo(AttributeType.STRING);
			ConformanceTm1.Recorded post = tm1().last();
			assertThat(post.key()).isEqualTo("POST " + path);
			assertThat(post.text()).isEqualTo("{\"Name\":\"Code\",\"Type\":\"String\"}");
		});

		tm1().json(201, "{\"Name\":\"Weight\",\"Type\":\"Numeric\"}");
		conformance.call("attributes", "create", set("elements", "Region", "Region"),
				AttributeDefinitionPatch.builder().name("Weight").type(AttributeType.NUMERIC).build());
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"Weight\",\"Type\":\"Numeric\"}");
	}

	@Test
	void aViewAttributeNeedsAPublicNativeView() {
		tm1().error(400, "278", "Cube 'Sales' has no public views.");
		Throwable error = conformance.fail("attributes", "create", set("views", "Sales"),
				AttributeDefinitionPatch.builder().name("Owner").type(AttributeType.STRING).build());
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void deletesAnAttributeFromEachSet() {
		sets().forEach((path, set) -> {
			tm1().status(204);
			conformance.call("attributes", "delete", set, "Code");
			assertThat(tm1().last().key()).isEqualTo("DELETE " + path + "('Code')");
		});

		tm1().status(204);
		conformance.call("attributes", "delete", set("processes"), "O'Neil");
		assertThat(tm1().last().key()).isEqualTo("DELETE ProcessAttributes('O''Neil')");
	}

	@Test
	void refusesAMissingName() {
		assertThat(conformance.fail("attributes", "delete", set("cubes"), null))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("attributes", "get", set("cubes"), null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
