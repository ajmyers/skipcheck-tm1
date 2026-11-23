// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsdlParserTest {

	private static final String DOC = """
			<?xml version="1.0" encoding="utf-8"?>
			<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
			  <edmx:DataServices>
			    <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
			      <Annotation Term="Core.SchemaVersion"><String>11.8.35</String></Annotation>
			      <EnumType Name="ElementType">
			        <Member Name="Numeric" Value="1"/>
			        <Member Name="String" Value="2"/>
			        <Member Name="Consolidated" Value="3"/>
			      </EnumType>
			      <EntityType Name="Entry" Abstract="true">
			        <Key><PropertyRef Name="ID"/></Key>
			        <Property Name="ID" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <EntityType Name="Folder" BaseType="tm1.Entry">
			        <NavigationProperty Name="Contents" Type="Collection(tm1.Entry)" ContainsTarget="true"/>
			      </EntityType>
			      <EntityType Name="Cube">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false">
			          <Annotation Term="Core.Description" String="The cube's name"/>
			        </Property>
			        <Property Name="Rules" Type="Edm.String"/>
			        <NavigationProperty Name="Dimensions" Type="Collection(tm1.Dimension)"/>
			        <Annotation Term="Core.Revisions">
			          <Collection>
			            <Record>
			              <PropertyValue Property="Version"><String>11.0.0</String></PropertyValue>
			              <PropertyValue Property="Kind"><EnumMember>Core.RevisionKind/Added</EnumMember></PropertyValue>
			            </Record>
			            <Record>
			              <PropertyValue Property="Version" String="11.8.3"/>
			              <PropertyValue Property="Kind" EnumMember="Core.RevisionKind/Deprecated"/>
			              <PropertyValue Property="Description" String="Use something else"/>
			            </Record>
			            <Record>
			              <PropertyValue Property="Version" String="12.0.0"/>
			              <PropertyValue Property="Kind" EnumMember="Core.RevisionKind/Retired"/>
			            </Record>
			          </Collection>
			        </Annotation>
			      </EntityType>
			      <EntityType Name="Dimension">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <ComplexType Name="ProcessParameter">
			        <Property Name="Name" Type="Edm.String"/>
			        <Property Name="Value" Type="Edm.PrimitiveType"/>
			      </ComplexType>
			      <Action Name="Reserve" IsBound="true">
			        <Parameter Name="Cube" Type="tm1.Cube" Nullable="false"/>
			      </Action>
			      <Action Name="ExecuteMDX">
			        <Parameter Name="MDX" Type="Edm.String" Nullable="false"/>
			        <ReturnType Type="tm1.Cellset" Nullable="false"/>
			      </Action>
			      <Function Name="DimensionsStorageOrder" IsBound="true" IsComposable="true">
			        <Parameter Name="Cube" Type="tm1.Cube"/>
			        <ReturnType Type="Collection(tm1.Dimension)"/>
			      </Function>
			      <EntityContainer Name="API">
			        <Annotation Term="Capabilities.AsynchronousRequestsSupported"/>
			        <EntitySet Name="Cubes" EntityType="tm1.Cube">
			          <NavigationPropertyBinding Path="Dimensions" Target="Dimensions"/>
			        </EntitySet>
			        <EntitySet Name="Dimensions" EntityType="tm1.Dimension"/>
			        <Singleton Name="ActiveCube" Type="tm1.Cube"/>
			        <ActionImport Name="ExecuteMDX" Action="tm1.ExecuteMDX"/>
			        <SomethingNewer Name="x"/>
			      </EntityContainer>
			      <Annotations Target="tm1.Cube/Rules">
			        <Annotation Term="Core.Description" String="Rules text"/>
			      </Annotations>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""";

	private final CsdlDocument doc = CsdlParser.parse(DOC);

	@Test
	void readsTheSchemaAndItsVersion() {
		assertThat(doc.version()).isEqualTo("4.0");
		assertThat(doc.schemas()).singleElement().satisfies(s -> {
			assertThat(s.namespace()).isEqualTo("ibm.tm1.api.v1");
			assertThat(s.alias()).isEqualTo("tm1");
		});
		assertThat(doc.schemaVersion()).contains("11.8.35");
	}

	@Test
	void findsTypesByNamespaceOrAlias() {
		assertThat(doc.qualify("tm1.Cube")).isEqualTo("ibm.tm1.api.v1.Cube");
		assertThat(doc.qualify("Edm.String")).isEqualTo("Edm.String");
		assertThat(doc.entityType("tm1.Cube")).isEqualTo(doc.entityType("ibm.tm1.api.v1.Cube")).isPresent();
		assertThat(doc.complexType("tm1.ProcessParameter")).isPresent();
		assertThat(doc.structuredType("tm1.ProcessParameter")).isPresent();
		assertThat(doc.entityType("tm1.Nope")).isEmpty();
	}

	@Test
	void readsEntityTypes() {
		CsdlEntityType cube = doc.entityType("tm1.Cube").orElseThrow();
		assertThat(cube.key()).containsExactly("Name");
		assertThat(cube.properties()).extracting(CsdlProperty::name).containsExactly("Name", "Rules");
		assertThat(cube.properties().get(0).nullable()).isFalse();
		assertThat(cube.properties().get(1).nullable()).isTrue();
		assertThat(cube.navigationProperties()).singleElement().satisfies(n -> {
			assertThat(n.name()).isEqualTo("Dimensions");
			assertThat(n.type()).isEqualTo(new CsdlTypeRef("tm1.Dimension", true));
			assertThat(n.type()).hasToString("Collection(tm1.Dimension)");
		});
	}

	@Test
	void followsInheritance() {
		CsdlEntityType folder = doc.entityType("tm1.Folder").orElseThrow();
		assertThat(folder.baseType()).isEqualTo("tm1.Entry");
		assertThat(folder.key()).isEmpty();
		assertThat(folder.navigationProperties().get(0).containsTarget()).isTrue();
		assertThat(doc.typeHierarchy("tm1.Folder")).extracting(CsdlStructuredType::name)
			.containsExactly("Folder", "Entry");
		assertThat(doc.entityType("tm1.Entry").orElseThrow().isAbstract()).isTrue();
	}

	@Test
	void readsEnumMembersInOrder() {
		CsdlEnumType type = doc.enumType("tm1.ElementType").orElseThrow();
		assertThat(type.underlyingType()).isEqualTo("Edm.Int32");
		assertThat(type.members()).extracting(CsdlEnumType.Member::name)
			.containsExactly("Numeric", "String", "Consolidated");
		assertThat(type.members()).extracting(CsdlEnumType.Member::value).containsExactly(1L, 2L, 3L);
	}

	@Test
	void readsOperations() {
		CsdlOperation reserve = doc.operations("tm1.Reserve").get(0);
		assertThat(reserve.kind()).isEqualTo(CsdlOperation.Kind.ACTION);
		assertThat(reserve.bindingType()).contains(new CsdlTypeRef("tm1.Cube", false));
		assertThat(reserve.callerParameters()).isEmpty();
		assertThat(reserve.returnType()).isNull();

		CsdlOperation mdx = doc.operations("ibm.tm1.api.v1.ExecuteMDX").get(0);
		assertThat(mdx.bound()).isFalse();
		assertThat(mdx.callerParameters()).extracting(CsdlParameter::name).containsExactly("MDX");
		assertThat(mdx.returnType()).isEqualTo(new CsdlTypeRef("tm1.Cellset", false));
		assertThat(mdx.returnNullable()).isFalse();

		assertThat(doc.boundTo("tm1.Cube")).extracting(CsdlOperation::name)
			.containsExactly("Reserve", "DimensionsStorageOrder");
		assertThat(doc.operations("tm1.DimensionsStorageOrder").get(0).composable()).isTrue();
	}

	@Test
	void readsTheContainer() {
		CsdlEntityContainer api = doc.entityContainer().orElseThrow();
		assertThat(api.entitySets()).extracting(CsdlEntityContainer.EntitySet::name)
			.containsExactly("Cubes", "Dimensions");
		assertThat(api.entitySets().get(0).navigationBindings()).containsEntry("Dimensions", "Dimensions");
		assertThat(api.singletons()).extracting(CsdlEntityContainer.Singleton::type).containsExactly("tm1.Cube");
		assertThat(api.actionImports()).extracting(CsdlEntityContainer.ActionImport::action)
			.containsExactly("tm1.ExecuteMDX");
		assertThat(api.functionImports()).isEmpty();
		assertThat(api.annotations()).singleElement()
			.satisfies(a -> assertThat(a.value()).isEqualTo(new CsdlValue.Empty()));
	}

	@Test
	void readsAnnotationsInBothForms() {
		CsdlProperty name = doc.entityType("tm1.Cube").orElseThrow().properties().get(0);
		assertThat(CsdlAnnotation.find(name.annotations(), "Core.Description")).get()
			.extracting(CsdlAnnotation::value)
			.isEqualTo(new CsdlValue.Constant("String", "The cube's name"));
		assertThat(doc.schemas().get(0).externalAnnotations()).containsKey("tm1.Cube/Rules");
	}

	@Test
	void readsRevisionsAndToleratesUnknownKinds() {
		List<CsdlAnnotation> annotations = doc.entityType("tm1.Cube").orElseThrow().annotations();
		assertThat(CsdlRevision.of(annotations)).containsExactly(
				new CsdlRevision("11.0.0", CsdlRevision.Kind.ADDED, "Core.RevisionKind/Added", null),
				new CsdlRevision("11.8.3", CsdlRevision.Kind.DEPRECATED, "Core.RevisionKind/Deprecated",
						"Use something else"),
				new CsdlRevision("12.0.0", CsdlRevision.Kind.UNKNOWN, "Core.RevisionKind/Retired", null));
		assertThat(CsdlRevision.addedIn(annotations)).contains("11.0.0");
		assertThat(CsdlRevision.deprecatedIn(annotations)).contains("11.8.3");
		assertThat(CsdlRevision.of(List.of())).isEmpty();
	}

	@Test
	void refusesDoctypesSoEntitiesCantBeExpanded() {
		String xxe = """
				<?xml version="1.0"?>
				<!DOCTYPE edmx:Edmx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
				<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">&x;</edmx:Edmx>
				""";
		assertThatThrownBy(() -> CsdlParser.parse(xxe)).isInstanceOf(CsdlParseException.class)
			.hasMessageContaining("DOCTYPE");
	}

	@Test
	void refusesDocumentsThatArentCsdl() {
		assertThatThrownBy(() -> CsdlParser.parse("<html/>")).isInstanceOf(CsdlParseException.class)
			.hasMessageContaining("not edmx:Edmx");
		assertThatThrownBy(() -> CsdlParser.parse("not xml")).isInstanceOf(CsdlParseException.class);
	}

}
