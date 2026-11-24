// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** The generated model, read straight from a real TM1 through the session. */
@RequiresTm1
class GeneratedModelTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void readsTheServerAndStreamsDimensionsIntoGeneratedTypes(Tm1ServerContainer tm1) {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		try {
			Server server = session.execute(Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(), Server.class))
				.block(WAIT);
			assertThat(server.productVersion()).isEqualTo(tm1.getTm1Version().toString());
			assertThat(server.etag()).as("TM1 sends no ETag for the Server singleton").isNull();

			List<Dimension> dimensions = session
				.stream(Tm1Request.list(ODataPath.of("Dimensions"), ODataQuery.none().expand("Hierarchies"),
						Dimension.class))
				.collectList()
				.block(WAIT);
			assertThat(dimensions).isNotEmpty().allSatisfy(d -> {
				assertThat(d.name()).isNotBlank();
				assertThat(d.etag()).isNotBlank();
				assertThat(d.hierarchies()).isNotEmpty();
			});
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void createsAViewAsItsSubtypeAndReadsItBackThroughTheHierarchy(Tm1ServerContainer tm1) {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		String name = "tm1-client-" + UUID.randomUUID();
		ODataPath views = ODataPath.of("Cubes").key("}ClientGroups").nav("Views");
		try {
			View created = session
				.execute(Tm1Request.post(views,
						new MDXView(null, name, null, null, null,
								"SELECT {[}Groups].[}Groups].MEMBERS} ON 0 FROM [}ClientGroups]", null),
						View.class))
				.block(WAIT);
			assertThat(created).isInstanceOfSatisfying(MDXView.class, v -> assertThat(v.mdx()).contains("}Groups"));

			List<View> all = session.stream(Tm1Request.list(views, ODataQuery.none(), View.class))
				.collectList()
				.block(WAIT);
			assertThat(all).filteredOn(v -> name.equals(v.name())).singleElement().isInstanceOf(MDXView.class);

			List<Member> members = session.stream(Tm1Request.list(
					ODataPath.of("Dimensions").key("}Groups").nav("Hierarchies").key("}Groups").nav("Members"),
					ODataQuery.none(), Member.class))
				.collectList()
				.block(WAIT);
			assertThat(members).isNotEmpty().allSatisfy(m -> assertThat(m).isInstanceOf(Member.Plain.class));
		}
		finally {
			session.execute(Tm1Request.delete(views.key(name))).onErrorComplete().block(WAIT);
			session.close().block(WAIT);
		}
	}

	@Test
	void queriesWithFieldConstantsAndEnumLiterals(Tm1ServerContainer tm1) {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		ODataPath elements = ODataPath.of("Dimensions")
			.key("}Groups")
			.nav("Hierarchies")
			.key("}Groups")
			.nav("Elements");
		try {
			List<Element> all = session
				.stream(Tm1Request.list(elements,
						ODataQuery.none().select(Element.NAME, Element.TYPE).orderBy(Element.NAME), Element.class))
				.collectList()
				.block(WAIT);
			assertThat(all).isNotEmpty().extracting(Element::name).isSorted();
			ElementType type = all.get(0).type();
			assertThat(type).isNotNull().isNotEqualTo(ElementType.UNKNOWN);

			List<Element> filtered = session
				.stream(Tm1Request.list(elements, ODataQuery.none().filter(Element.TYPE.eq(type)), Element.class))
				.collectList()
				.block(WAIT);
			assertThat(filtered).hasSize((int) all.stream().filter(e -> e.type() == type).count())
				.allSatisfy(e -> assertThat(e.type()).isEqualTo(type));
		}
		finally {
			session.close().block(WAIT);
		}
	}

	@Test
	void createsADimensionAndACubeFromPatchesThenPatchesAndClearsItsRules(Tm1ServerContainer tm1) {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		String name = "tm1-client-" + UUID.randomUUID();
		ODataPath dimension = ODataPath.of("Dimensions").key(name);
		ODataPath cube = ODataPath.of("Cubes").key(name);
		try {
			session.execute(Tm1Request.post(ODataPath.of("Dimensions"),
					DimensionCreate.builder()
						.name(name)
						.hierarchies(List.of(HierarchyCreate.builder()
							.name(name)
							.elements(List.of(ElementCreate.builder().name("North").type(ElementType.NUMERIC).build(),
									ElementCreate.builder().name("South").type(ElementType.NUMERIC).build()))
							.build()))
						.build(),
					Dimension.class))
				.block(WAIT);
			session.execute(Tm1Request.post(ODataPath.of("Cubes"),
					CubeCreate.builder()
						.name(name)
						.bindDimensions(dimension, ODataPath.of("Dimensions").key("}Groups"))
						.build(),
					Cube.class))
				.block(WAIT);

			Cube created = session
				.execute(Tm1Request.get(cube, ODataQuery.none().expand(Cube.DIMENSIONS, d -> d.select(Dimension.NAME)),
						Cube.class))
				.block(WAIT);
			assertThat(created.dimensions()).extracting(Dimension::name)
				.as("TM1 lists its virtual Sandboxes dimension first")
				.containsExactly("Sandboxes", name, "}Groups");
			List<Element> elements = session
				.stream(Tm1Request.list(dimension.nav("Hierarchies").key(name).nav("Elements"), ODataQuery.none(),
						Element.class))
				.collectList()
				.block(WAIT);
			assertThat(elements).extracting(Element::name, Element::type)
				.containsExactlyInAnyOrder(tuple("North", ElementType.NUMERIC), tuple("South", ElementType.NUMERIC));

			session.execute(Tm1Request.patch(cube, CubeCreate.builder().rules("SKIPCHECK;").build())).block(WAIT);
			assertThat(rules(session, cube)).contains("SKIPCHECK");
			session.execute(Tm1Request.patch(cube, CubeCreate.builder().rules(null).build())).block(WAIT);
			assertThat(rules(session, cube)).as("an explicit null clears them").isNullOrEmpty();
		}
		finally {
			session.execute(Tm1Request.delete(cube)).onErrorComplete().block(WAIT);
			session.execute(Tm1Request.delete(dimension)).onErrorComplete().block(WAIT);
			session.close().block(WAIT);
		}
	}

	private static @Nullable String rules(Tm1Session session, ODataPath cube) {
		return session.execute(Tm1Request.get(cube, ODataQuery.none().select(Cube.RULES), Cube.class))
			.block(WAIT)
			.rules();
	}

	@Test
	void theServerHasEveryOperationInTheCatalog(Tm1ServerContainer tm1) {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		try {
			Tm1Capabilities capabilities = session.capabilities().block(WAIT);
			assertThat(Tm1Operations.ALL)
				.allSatisfy(op -> assertThat(capabilities.supports(op.capability())).as("%s", op.endpoint()).isTrue());
		}
		finally {
			session.close().block(WAIT);
		}
	}

}
