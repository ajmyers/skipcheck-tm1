// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

class BuildersTest {

	private static final CsdlDocument METADATA = CsdlParser.parse("""
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			  <edmx:DataServices>
			    <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			      <EntityType Name="Cube">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <Property Name="Rules" Type="Edm.String"/>
			        <Property Name="Tags" Type="Collection(Edm.String)"/>
			        <Property Name="Box" Type="tm1.Box"/>
			        <Property Name="Drill" Type="tm1.Drill"/>
			        <Property Name="Content" Type="Edm.Stream"/>
			        <NavigationProperty Name="Views" Type="Collection(tm1.View)"/>
			        <NavigationProperty Name="Dimensions" Type="Collection(tm1.Dimension)"/>
			        <NavigationProperty Name="Measure" Type="tm1.Element"/>
			        <NavigationProperty Name="DefaultView" Type="tm1.MDXView"/>
			      </EntityType>
			      <EntityType Name="Dimension">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <EntityType Name="Element">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <Property Name="UniqueName" Type="Edm.String"/>
			      </EntityType>
			      <ComplexType Name="Box" OpenType="true">
			        <Property Name="Size" Type="Edm.Int32"/>
			      </ComplexType>
			      <ComplexType Name="Drill" Abstract="true">
			        <Property Name="Kind" Type="Edm.String"/>
			      </ComplexType>
			      <ComplexType Name="CubeDrill" BaseType="tm1.Drill">
			        <Property Name="Target" Type="Edm.String"/>
			      </ComplexType>
			      <EntityType Name="View" Abstract="true">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <EntityType Name="MDXView" BaseType="tm1.View">
			        <Property Name="MDX" Type="Edm.String"/>
			      </EntityType>
			      <EntityType Name="NativeView" BaseType="tm1.View">
			        <Property Name="Titles" Type="Edm.String"/>
			      </EntityType>
			      <EntityContainer Name="API">
			        <EntitySet Name="Cubes" EntityType="tm1.Cube"/>
			        <EntitySet Name="Dimensions" EntityType="tm1.Dimension"/>
			        <EntitySet Name="Views" EntityType="tm1.View"/>
			      </EntityContainer>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""");

	private static final Writes WRITES = Writes.read("""
			{"server": {"schemaVersion": "11.8.35"},
			 "writes": {"Cube": {"type": "Cube", "required": ["Dimensions@odata.bind"],
			   "post": {"Rules": {"outcome": "applied"}, "Tags": {"outcome": "applied"}},
			   "patch": {"Rules": {"outcome": "applied"}, "Tags": {"outcome": "applied"}}}},
			 "collections": {"Cube/Tags": {"outcome": "appends"}}}""");

	private static final Offers.Offer V = new Offers.Offer("verified", "applied");

	private static final Map<String, Map<String, Offers.Offer>> OFFERS = Map.ofEntries(
			Map.entry("CubeCreate",
					Map.of("Name", V, "Rules", V, "Tags", V, "Box", V, "Content", V, "Views", V,
							"Dimensions@odata.bind", V, "Measure@odata.bind", V, "DefaultView@odata.bind", V)),
			Map.entry("CubePatch",
					Map.of("Rules", V, "Tags", V, "Box", V, "Drill", V, "Dimensions@odata.bind", V,
							"Measure@odata.bind", V)),
			Map.entry("BoxPatch", Map.of("Size", V)), Map.entry("DrillPatch", Map.of("Kind", V)),
			Map.entry("CubeDrillPatch", Map.of("Kind", V, "Target", V)), Map.entry("ViewCreate", Map.of()),
			Map.entry("ViewPatch", Map.of("Name", V)), Map.entry("MDXViewCreate", Map.of("Name", V, "MDX", V)),
			Map.entry("MDXViewPatch", Map.of("Name", V, "MDX", V)), Map.entry("NativeViewPatch", Map.of("Name", V)));

	private static Builders plan(Map<String, Map<String, Offers.Offer>> offers) {
		return Builders.of(METADATA, Overlay.NONE, WRITES, new Offers(offers));
	}

	private static Map<String, Map<String, Offers.Offer>> without(String builder) {
		Map<String, Map<String, Offers.Offer>> offers = new HashMap<>(OFFERS);
		offers.remove(builder);
		return offers;
	}

	private static List<String> setters(Builders.Builder b) {
		return b.setters().stream().map(Builders.Setter::json).toList();
	}

	@Test
	void plansOnlyTheWriteTypesAndSettersTheOffersList() {
		Builders plan = plan(OFFERS);
		assertThat(plan.builders()).containsOnlyKeys(OFFERS.keySet());
		Builders.Builder cube = plan.get("CubeCreate");
		assertThat(cube.write()).isEqualTo("create");
		assertThat(cube.type()).isEqualTo("ibm.tm1.api.v1.Cube");
		assertThat(setters(cube)).as("properties, then navigations; never a stream")
			.containsExactly("Name", "Rules", "Tags", "Box", "Views", "Dimensions", "Measure", "DefaultView");
		assertThat(cube.setter("Box").orElseThrow().value()).isEqualTo("BoxPatch");
		assertThat(cube.setter("Views").orElseThrow()).as("a nested entity is new: its create")
			.isEqualTo(new Builders.Setter("Views", "ibm.tm1.api.v1.Cube", true, true, true, false, "ViewCreate"));
		assertThat(cube.setter("Dimensions").orElseThrow()).as("offered as a binding only, so no value type")
			.isEqualTo(new Builders.Setter("Dimensions", "ibm.tm1.api.v1.Cube", true, true, false, true, null));
		assertThat(cube.required()).as("either form will do").containsExactly("Dimensions");
	}

	@Test
	void copiesWhatAWriteTakesAndBindsWhatTheReadSays() {
		Builders plan = plan(OFFERS);
		Builders.FromRead create = plan.get("CubeCreate").fromRead();
		assertThat(create.copy()).as("not navigations; a structured value through its own write type")
			.containsExactly(new Builders.Copy("Name", null, null), new Builders.Copy("Rules", null, null),
					new Builders.Copy("Tags", null, null), new Builders.Copy("Box", "builder", "BoxPatch"));
		assertThat(create.bindings()).as("by key in an entity set; by UniqueName; by a key and set from the base")
			.containsExactly(new Builders.Binding("Dimensions", true, "key", "Dimensions", "Name"),
					new Builders.Binding("Measure", false, "uniqueName", "Elements", "UniqueName"),
					new Builders.Binding("DefaultView", false, "key", "Views", "Name"));
		assertThat(create.undeclared()).isNull();
		Builders.FromRead patch = plan.get("CubePatch").fromRead();
		assertThat(patch.copy()).as("not a collection a PATCH appends to; a root's value through its of()")
			.containsExactly(new Builders.Copy("Rules", null, null), new Builders.Copy("Box", "builder", "BoxPatch"),
					new Builders.Copy("Drill", "of", "DrillPatch"));
		assertThat(patch.bindings()).as("a patch binds no collection")
			.containsExactly(new Builders.Binding("Measure", false, "uniqueName", "Elements", "UniqueName"));
		assertThat(plan.get("BoxPatch").fromRead().undeclared()).as("an open type's declared names, to skip")
			.containsExactly("Size");
	}

	@Test
	void aRootDispatchesOnTheReadsType() {
		Builders plan = plan(OFFERS);
		Builders.Builder create = plan.get("ViewCreate");
		assertThat(create.fromRead()).isNull();
		assertThat(create.dispatch()).as("only the subtypes with a create")
			.containsExactly(new Builders.Case("ibm.tm1.api.v1.MDXView", "MDXViewCreate"));
		Builders.Builder patch = plan.get("ViewPatch");
		assertThat(patch.dispatch()).extracting(Builders.Case::builder)
			.containsExactly("MDXViewPatch", "NativeViewPatch");
		assertThat(patch.fromRead().copy()).as("the fallback, for a type the client doesn't know")
			.extracting(Builders.Copy::json)
			.containsExactly("Name");
		Builders.Builder mdx = plan.get("MDXViewPatch");
		assertThat(mdx.base()).isEqualTo("ViewPatch");
		assertThat(mdx.odataType()).isEqualTo("#ibm.tm1.api.v1.MDXView");
		assertThat(mdx.setters()).as("its root's level first")
			.extracting(Builders.Setter::json, Builders.Setter::declaredOn)
			.containsExactly(Tuple.tuple("Name", "ibm.tm1.api.v1.View"), Tuple.tuple("MDX", "ibm.tm1.api.v1.MDXView"));
		assertThat(plan.get("ViewPatch").base()).isNull();
		assertThat(plan.get("ViewPatch").odataType()).isNull();
	}

	@Test
	void takesADepartureAsRead() {
		Overlay overlay = Overlay
			.read(new ByteArrayInputStream("{\"departures\": {\"Cube/Box\": {\"read\": \"json\", \"note\": \"x\"}}}"
				.getBytes(StandardCharsets.UTF_8)));
		Builders plan = Builders.of(METADATA, overlay, WRITES, new Offers(OFFERS));
		assertThat(plan.get("CubePatch").setter("Box").orElseThrow().value()).isNull();
		assertThat(plan.get("CubePatch").fromRead().copy()).contains(new Builders.Copy("Box", null, null));
	}

	@Test
	void refusesARootPatchWhoseSubtypeHasNone() {
		assertThatThrownBy(() -> plan(without("NativeViewPatch"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ViewPatch has a subtype without a patch, NativeView");
	}

	@Test
	void refusesASubtypesWriteTypeWithoutItsRoots() {
		assertThatThrownBy(() -> plan(without("ViewCreate"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MDXViewCreate extends ViewCreate, which isn't generated");
	}

	@Test
	void refusesASetterWhoseValuesWriteTypeIsntPlanned() {
		assertThatThrownBy(() -> plan(without("BoxPatch"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CubeCreate.Box takes BoxPatch, which isn't generated");
	}

	@Test
	void refusesASubtypesCreateThatNeedsLessThanItsRoots() {
		Writes writes = Writes.read("""
				{"writes": {"View": {"type": "View", "required": ["Name"], "post": {}, "patch": {}},
				  "MDXView": {"type": "MDXView", "required": [], "post": {}, "patch": {}}}}""");
		assertThatThrownBy(() -> Builders.of(METADATA, Overlay.NONE, writes, new Offers(OFFERS)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ViewCreate needs Name, which MDXViewCreate doesn't");
	}

	@Test
	void aPatchCopiesASetterTheOverlayKeepsThoughThePatchIsUntested() {
		Writes writes = Writes.read("""
				{"writes": {"Cube": {"type": "Cube", "post": {}, "patch": {"Rules": {"outcome": "untested"}}}}}""");
		Map<String, Map<String, Offers.Offer>> offers = new HashMap<>(OFFERS);
		offers.put("CubePatch", Map.of("Rules", new Offers.Offer("unverified", "the probe can't try it")));
		Builders plan = Builders.of(METADATA, Overlay.NONE, writes, new Offers(offers));
		assertThat(plan.get("CubePatch").fromRead().copy()).as("as ServerSettingsPatch's EncryptionAtRest (#358)")
			.containsExactly(new Builders.Copy("Rules", null, null));
		// What a PATCH ignores isn't
		Writes ignored = Writes.read("""
				{"writes": {"Cube": {"type": "Cube", "post": {}, "patch": {"Rules": {"outcome": "ignored"}}}}}""");
		assertThat(Builders.of(METADATA, Overlay.NONE, ignored, new Offers(offers)).get("CubePatch").fromRead().copy())
			.isEmpty();
	}

	@Test
	void withoutOffersPlansNoWriteTypes() {
		assertThat(Builders.of(METADATA, Overlay.NONE, WRITES, Offers.NONE).builders()).isEmpty();
	}

}
