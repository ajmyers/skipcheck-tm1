package net.ajmyers.tm1.client.service.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.Tm1BadRequestException;
import net.ajmyers.tm1.client.model.AttributeDefinition;
import net.ajmyers.tm1.client.model.AttributeDefinitionPatch;
import net.ajmyers.tm1.client.model.AttributeType;
import net.ajmyers.tm1.client.model.AttributesPatch;
import net.ajmyers.tm1.client.model.DimensionPatch;
import net.ajmyers.tm1.client.model.ElementPatch;
import net.ajmyers.tm1.client.model.ElementType;
import net.ajmyers.tm1.client.model.HierarchyPatch;
import net.ajmyers.tm1.client.model.LocalizedAttributes;
import net.ajmyers.tm1.client.model.LocalizedAttributesPatch;
import net.ajmyers.tm1.client.model.SubsetPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.dimension.SubsetScope;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * AttributeService and LocalizedAttributeService, on a dimension of North and South with
 * a subset S.
 */
@RequiresTm1
class AttributeServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private String d;

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

	@BeforeEach
	void createDimension() {
		d = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.dimensions()
			.create(DimensionPatch.builder()
				.name(d)
				.hierarchies(List.of(HierarchyPatch.builder()
					.name(d)
					.elements(List.of(ElementPatch.builder().name("North").type(ElementType.NUMERIC).build(),
							ElementPatch.builder().name("South").type(ElementType.NUMERIC).build()))
					.build()))
				.build())
			.block(WAIT);
		tm1.subsets()
			.create(d, d, SubsetScope.PUBLIC,
					SubsetPatch.builder().name("S").expression("{[" + d + "].[" + d + "].[North]}").build())
			.block(WAIT);
	}

	@AfterEach
	void deleteDimension() {
		tm1.dimensions().delete(d).block(WAIT);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "dimensions", "hierarchies", "elements", "subsets" })
	void createsListsReadsAndDeletesAttributeDefinitions(String kind) {
		AttributeSet set = switch (kind) {
			case "dimensions" -> AttributeSet.dimensions();
			case "hierarchies" -> AttributeSet.hierarchies(d);
			case "elements" -> AttributeSet.elements(d, d);
			default -> AttributeSet.subsets(d, d);
		};
		String name = "Code " + UUID.randomUUID().toString().substring(0, 4);
		AttributeDefinition created = tm1.attributes()
			.create(set, AttributeDefinitionPatch.builder().name(name).type(AttributeType.STRING).build())
			.block(WAIT);
		assertThat(created.type()).isEqualTo(AttributeType.STRING);
		try {
			assertThat(tm1.attributes()
				.list(set, ODataQuery.none())
				.map(AttributeDefinition::name)
				.collectList()
				.block(WAIT)).contains(name);
			assertThat(tm1.attributes().get(set, name, ODataQuery.none()).block(WAIT).name()).isEqualTo(name);
		}
		finally {
			tm1.attributes().delete(set, name).block(WAIT);
		}
		assertThat(
				tm1.attributes().list(set, ODataQuery.none()).map(AttributeDefinition::name).collectList().block(WAIT))
			.doesNotContain(name);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "dimension", "hierarchy", "element", "member", "subset" })
	void translatesAttributeValues(String kind) {
		AttributeSet set = switch (kind) {
			case "dimension" -> AttributeSet.dimensions();
			case "hierarchy" -> AttributeSet.hierarchies(d);
			case "element", "member" -> AttributeSet.elements(d, d);
			default -> AttributeSet.subsets(d, d);
		};
		AttributeOwner owner = switch (kind) {
			case "dimension" -> AttributeOwner.dimension(d);
			case "hierarchy" -> AttributeOwner.hierarchy(d, d);
			case "element", "member" -> AttributeOwner.element(d, d, "North");
			default -> AttributeOwner.subset(d, d, SubsetScope.PUBLIC, "S");
		};
		// A member's translations are its element's, and read only
		AttributeOwner reader = kind.equals("member") ? AttributeOwner.member(d, d, "North") : owner;
		String label = "Label " + UUID.randomUUID().toString().substring(0, 4);
		tm1.attributes()
			.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.STRING).build())
			.block(WAIT);
		LocalizedAttributeService localized = tm1.localizedAttributes();
		try {
			localized
				.create(owner,
						LocalizedAttributesPatch.builder()
							.localeID("fr")
							.attributes(AttributesPatch.builder().property(label, "Nord").build())
							.build())
				.block(WAIT);
			assertThat(localized.list(reader, ODataQuery.none())
				.map(LocalizedAttributes::localeID)
				.collectList()
				.block(WAIT)).contains("fr");
			assertThat(value(reader, label)).isEqualTo("Nord");

			LocalizedAttributesPatch changed = LocalizedAttributesPatch.builder()
				.localeID("fr")
				.attributes(AttributesPatch.builder().property(label, "Le Nord").build())
				.build();
			ODataPath fr = owner.path().nav("LocalizedAttributes").key("fr");
			assertThatThrownBy(() -> localized.create(owner, changed).block(WAIT)).as("a locale can't be created twice")
				.isInstanceOf(Tm1BadRequestException.class);
			assertThatThrownBy(() -> tm1.session().execute(Tm1Request.patch(fr, changed)).block(WAIT)).as("nor patched")
				.isInstanceOf(Tm1BadRequestException.class);
			assertThatThrownBy(() -> tm1.session().execute(Tm1Request.delete(fr)).block(WAIT)).as("nor deleted")
				.isInstanceOf(Tm1BadRequestException.class);
			assertThat(value(reader, label)).isEqualTo("Nord");
		}
		finally {
			tm1.attributes().delete(set, label).block(WAIT);
		}
	}

	@Test
	void needsTheAttributeDefinedAndRefusesWritesThroughAMember() {
		assertThatThrownBy(() -> tm1.localizedAttributes()
			.create(AttributeOwner.element(d, d, "North"),
					LocalizedAttributesPatch.builder()
						.localeID("fr")
						.attributes(AttributesPatch.builder().caption("Nord").build())
						.build())
			.block(WAIT)).as("Caption isn't an attribute until it's defined")
			.isInstanceOf(Tm1BadRequestException.class);
		assertThatThrownBy(() -> tm1.localizedAttributes()
			.create(AttributeOwner.member(d, d, "North"), LocalizedAttributesPatch.builder().localeID("fr").build())
			.block(WAIT)).as("POST not supported on a member's").isInstanceOf(Tm1BadRequestException.class);
	}

	private String value(AttributeOwner owner, String attribute) {
		return tm1.localizedAttributes()
			.get(owner, "fr", ODataQuery.none())
			.block(WAIT)
			.attributes()
			.dynamicProperties()
			.get(attribute)
			.asString();
	}

}
