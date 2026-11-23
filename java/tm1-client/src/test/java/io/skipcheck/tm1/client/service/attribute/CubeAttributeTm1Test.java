package io.skipcheck.tm1.client.service.attribute;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.AttributeDefinition;
import io.skipcheck.tm1.client.model.AttributeDefinitionPatch;
import io.skipcheck.tm1.client.model.AttributeType;
import io.skipcheck.tm1.client.model.AttributesPatch;
import io.skipcheck.tm1.client.model.CubePatch;
import io.skipcheck.tm1.client.model.DimensionPatch;
import io.skipcheck.tm1.client.model.ElementPatch;
import io.skipcheck.tm1.client.model.ElementType;
import io.skipcheck.tm1.client.model.HierarchyPatch;
import io.skipcheck.tm1.client.model.LocalizedAttributes;
import io.skipcheck.tm1.client.model.LocalizedAttributesPatch;
import io.skipcheck.tm1.client.model.MDXViewPatch;
import io.skipcheck.tm1.client.model.NativeViewPatch;
import io.skipcheck.tm1.client.model.SubsetPatch;
import io.skipcheck.tm1.client.model.ViewAxisSelectionPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** Attributes and translations of cubes and views. */
@RequiresTm1
class CubeAttributeTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static String cube;

	private static List<String> dimensions;

	@BeforeAll
	static void createCube(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		cube = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		dimensions = List.of(cube + "-a", cube + "-b");
		for (String d : dimensions) {
			tm1.dimensions()
				.create(DimensionPatch.builder()
					.name(d)
					.hierarchies(List.of(HierarchyPatch.builder()
						.name(d)
						.elements(List.of(ElementPatch.builder().name("X").type(ElementType.NUMERIC).build()))
						.build()))
					.build())
				.block(WAIT);
		}
		tm1.cubes()
			.create(CubePatch.builder()
				.name(cube)
				.bindDimensions(ODataPath.of("Dimensions").key(dimensions.get(0)),
						ODataPath.of("Dimensions").key(dimensions.get(1)))
				.build())
			.block(WAIT);
		tm1.views()
			.create(cube, ViewScope.PUBLIC,
					MDXViewPatch.builder()
						.name("V")
						.mdx("SELECT {[" + dimensions.get(0) + "].[" + dimensions.get(0) + "].Members} ON 0 FROM ["
								+ cube + "]")
						.build())
			.block(WAIT);
		for (String d : dimensions) {
			tm1.subsets()
				.create(d, d, SubsetScope.PUBLIC,
						SubsetPatch.builder().name("All").expression("{TM1SUBSETALL([" + d + "].[" + d + "])}").build())
				.block(WAIT);
		}
		tm1.views()
			.create(cube, ViewScope.PUBLIC,
					NativeViewPatch.builder()
						.name("N")
						.columns(List.of(axis(dimensions.get(0))))
						.rows(List.of(axis(dimensions.get(1))))
						.build())
			.block(WAIT);
	}

	@AfterAll
	static void deleteCube() {
		tm1.cubes().delete(cube).block(WAIT);
		dimensions.forEach(d -> tm1.dimensions().delete(d).block(WAIT));
		tm1.close().block(WAIT);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "cube", "view" })
	void definesAndTranslatesAttributes(String kind) {
		AttributeSet set = kind.equals("cube") ? AttributeSet.cubes() : AttributeSet.views(cube);
		AttributeOwner owner = kind.equals("cube") ? AttributeOwner.cube(cube)
				: AttributeOwner.view(cube, ViewScope.PUBLIC, "V");
		String label = "Label " + UUID.randomUUID().toString().substring(0, 4);
		tm1.attributes()
			.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.STRING).build())
			.block(WAIT);
		try {
			assertThat(tm1.attributes()
				.list(set, ODataQuery.none())
				.map(AttributeDefinition::name)
				.collectList()
				.block(WAIT)).contains(label);
			tm1.localizedAttributes()
				.create(owner,
						LocalizedAttributesPatch.builder()
							.localeID("fr")
							.attributes(AttributesPatch.builder().property(label, "Ventes").build())
							.build())
				.block(WAIT);
			LocalizedAttributes fr = tm1.localizedAttributes().get(owner, "fr", ODataQuery.none()).block(WAIT);
			assertThat(fr.attributes().dynamicProperties().get(label).asString()).isEqualTo("Ventes");
		}
		finally {
			tm1.attributes().delete(set, label).block(WAIT);
		}
	}

	private static ViewAxisSelectionPatch axis(String dimension) {
		return ViewAxisSelectionPatch.builder()
			.bindSubset(ODataPath.of("Dimensions")
				.key(dimension)
				.nav("Hierarchies")
				.key(dimension)
				.nav("Subsets")
				.key("All"))
			.build();
	}

}
