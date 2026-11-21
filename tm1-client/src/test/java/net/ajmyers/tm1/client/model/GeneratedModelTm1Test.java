package net.ajmyers.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.session.Tm1Session;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

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
								"SELECT {[}Groups].[}Groups].MEMBERS} ON 0 FROM [}ClientGroups]"),
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

}
