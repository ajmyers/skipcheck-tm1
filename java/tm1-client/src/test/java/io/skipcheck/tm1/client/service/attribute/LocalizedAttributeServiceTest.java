// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.attribute;

import static io.skipcheck.tm1.client.service.attribute.AttributeArgs.owner;
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
import io.skipcheck.tm1.client.model.AttributesPatch;
import io.skipcheck.tm1.client.model.LocalizedAttributes;
import io.skipcheck.tm1.client.model.LocalizedAttributesCreate;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.cube.ViewScope;
import io.skipcheck.tm1.client.service.dimension.SubsetScope;

/**
 * LocalizedAttributeService against a fake TM1, for every kind of {@link AttributeOwner}.
 * Every call is recorded to
 * {@code spec/conformance/calls/LocalizedAttributeServiceTest.json}, which the Python,
 * .NET and TypeScript clients replay.
 */
class LocalizedAttributeServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	/** Every kind of owner, by the path of its translations. */
	private static Map<String, AttributeOwner> owners() {
		String h = "Dimensions('Region')/Hierarchies('Alt')";
		Map<String, AttributeOwner> owners = new LinkedHashMap<>();
		owners.put("Dimensions('Region')", owner("dimension", "Region"));
		owners.put(h, owner("hierarchy", "Region", "Alt"));
		owners.put(h + "/Elements('North')", owner("element", "Region", "Alt", "North"));
		owners.put(h + "/Members('North')", owner("member", "Region", "Alt", "North"));
		owners.put(h + "/Subsets('S')", owner("subset", "Region", "Alt", SubsetScope.PUBLIC, "S"));
		owners.put(h + "/PrivateSubsets('S')", owner("subset", "Region", "Alt", SubsetScope.PRIVATE, "S"));
		owners.put("Cubes('Sales')", owner("cube", "Sales"));
		owners.put("Cubes('Sales')/Views('V')", owner("view", "Sales", ViewScope.PUBLIC, "V"));
		owners.put("Cubes('Sales')/PrivateViews('V')", owner("view", "Sales", ViewScope.PRIVATE, "V"));
		owners.put("Processes('Load')", owner("process", "Load"));
		owners.put("Chores('Nightly')", owner("chore", "Nightly"));
		return owners;
	}

	@Test
	void listsEachOwnersTranslations() {
		owners().forEach((path, owner) -> {
			tm1().json("{\"value\":[{\"LocaleID\":\"fr\",\"Attributes\":{\"Caption\":\"Nord\",\"Code\":\"N\"}},"
					+ "{\"LocaleID\":\"de-CH\",\"Attributes\":{\"Caption\":\"Norden\"}}]}");
			List<LocalizedAttributes> translations = conformance.call("localizedAttributes", "list", owner,
					ODataQuery.none());
			assertThat(translations).extracting(LocalizedAttributes::localeID).containsExactly("fr", "de-CH");
			assertThat(tm1().last().key()).isEqualTo("GET " + path + "/LocalizedAttributes");
		});

		tm1().json("{\"value\":[{\"LocaleID\":\"fr\"}]}");
		conformance.call("localizedAttributes", "list", owner("cube", "Sales"),
				ODataQuery.none().select("LocaleID").orderBy("LocaleID").top(1));
		assertThat(tm1().last().target())
			.isEqualTo("Cubes('Sales')/LocalizedAttributes?$select=LocaleID&$orderby=LocaleID&$top=1");
	}

	@Test
	void getsALocalesTranslations() {
		owners().forEach((path, owner) -> {
			tm1().json("{\"LocaleID\":\"de-CH\",\"Attributes\":{\"Caption\":\"Norden\",\"Code\":\"N\"}}");
			LocalizedAttributes de = conformance.call("localizedAttributes", "get", owner, "de-CH", ODataQuery.none());
			assertThat(de.attributes().caption()).isEqualTo("Norden");
			assertThat(de.attributes().dynamicProperties().get("Code").asString()).isEqualTo("N");
			assertThat(tm1().last().key()).isEqualTo("GET " + path + "/LocalizedAttributes('de-CH')");
		});

		tm1().json("{\"LocaleID\":\"fr\",\"Attributes\":{\"Caption\":\"Nord\"}}");
		conformance.call("localizedAttributes", "get", owner("element", "}Clients", "}Clients", "O'Neil / Ünï"), "fr",
				ODataQuery.none().select("Attributes"));
		assertThat(tm1().last().target())
			.startsWith("Dimensions('%7DClients')/Hierarchies('%7DClients')/Elements('O''Neil%20%2F%20");
	}

	@Test
	void aMissingLocaleIsNotFound() {
		tm1().error(404, "278", "'xx' can not be found in collection of type 'LocalizedAttributes'.");
		Throwable error = conformance.fail("localizedAttributes", "get", owner("dimension", "Region"), "xx",
				ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsALocalesTranslations() {
		owners().forEach((path, owner) -> {
			tm1().json(201, "{\"LocaleID\":\"fr\",\"Attributes\":{\"Caption\":\"Nord\",\"Code\":\"N\"}}");
			LocalizedAttributes fr = conformance.call("localizedAttributes", "create", owner,
					LocalizedAttributesCreate.builder()
						.localeID("fr")
						.attributes(AttributesPatch.builder().caption("Nord").property("Code", "N").build())
						.build());
			assertThat(fr.localeID()).isEqualTo("fr");
			ConformanceTm1.Recorded post = tm1().last();
			assertThat(post.key()).isEqualTo("POST " + path + "/LocalizedAttributes");
			assertThat(post.text())
				.isEqualTo("{\"LocaleID\":\"fr\",\"Attributes\":{\"Caption\":\"Nord\",\"Code\":\"N\"}}");
		});

		tm1().json(201, "{\"LocaleID\":\"de\",\"Attributes\":{\"Weight\":2.5}}");
		conformance.call("localizedAttributes", "create", owner("process", "Load"),
				LocalizedAttributesCreate.builder()
					.localeID("de")
					.attributes(AttributesPatch.builder().property("Weight", 2.5).property("Note", null).build())
					.build());
		assertThat(tm1().last().text())
			.isEqualTo("{\"LocaleID\":\"de\",\"Attributes\":{\"Weight\":2.5,\"Note\":null}}");
	}

	@Test
	void aLocaleIsCreatedOnceAndNeverThroughAMember() {
		tm1().error(400, "278", "Localized attributes for locale 'fr' exist.");
		Throwable twice = conformance.fail("localizedAttributes", "create",
				owner("element", "Region", "Region", "North"),
				LocalizedAttributesCreate.builder()
					.localeID("fr")
					.attributes(AttributesPatch.builder().caption("Nord").build())
					.build());
		assertThat(twice).isInstanceOf(Tm1BadRequestException.class);

		tm1().error(400, "278", "POST not supported.");
		Throwable member = conformance.fail("localizedAttributes", "create",
				owner("member", "Region", "Region", "North"),
				LocalizedAttributesCreate.builder()
					.localeID("fr")
					.attributes(AttributesPatch.builder().caption("Nord").build())
					.build());
		assertThat(member).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void refusesAMissingLocale() {
		Throwable error = conformance.fail("localizedAttributes", "get", owner("cube", "Sales"), null,
				ODataQuery.none());
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
