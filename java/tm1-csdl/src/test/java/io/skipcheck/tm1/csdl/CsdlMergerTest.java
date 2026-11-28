// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.csdl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CsdlMergerTest {

	private static final String V11 = """
			<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
			  <edmx:DataServices>
			    <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
			      <Annotation Term="Core.SchemaVersion"><String>11.8.35</String></Annotation>
			      <EntityType Name="Cube">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <Property Name="LegacyProp" Type="Edm.String"/>
			      </EntityType>
			      <ComplexType Name="EncryptionSettings">
			        <Property Name="KeyRef" Type="Edm.String"/>
			      </ComplexType>
			      <Action Name="Reserve" IsBound="true">
			        <Parameter Name="Cube" Type="tm1.Cube" Nullable="false"/>
			      </Action>
			      <Action Name="ExecuteMDX">
			        <Parameter Name="MDX" Type="Edm.String" Nullable="false"/>
			        <ReturnType Type="tm1.Cube" Nullable="false"/>
			      </Action>
			      <EntityContainer Name="API">
			        <EntitySet Name="Cubes" EntityType="tm1.Cube"/>
			        <ActionImport Name="ExecuteMDX" Action="tm1.ExecuteMDX"/>
			        <ActionImport Name="RotateKey" Action="tm1.RotateKey"/>
			      </EntityContainer>
			      <Action Name="RotateKey"/>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""";

	private static final String V12 = """
			<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
			  <edmx:Reference Uri="https://oasis-tcs.github.io/odata-vocabularies/vocabularies/Org.OData.Core.V1.xml">
			    <edmx:Include Namespace="Org.OData.Core.V1" Alias="Core"/>
			  </edmx:Reference>
			  <edmx:Reference Uri="ibm.tm1s.api.v1.xml">
			    <edmx:Include Namespace="ibm.tm1s.api.v1" Alias="tm1s"/>
			  </edmx:Reference>
			  <edmx:Reference Uri="ibm.odata.mpu.v1.xml">
			    <edmx:Include Namespace="ibm.odata.mpu.v1" Alias="mpu"/>
			  </edmx:Reference>
			  <edmx:DataServices>
			    <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
			      <Annotation Term="Core.SchemaVersion"><String>12.6.0</String></Annotation>
			      <EntityType Name="Cube">
			        <Key><PropertyRef Name="Name"/></Key>
			        <Property Name="Name" Type="Edm.String" Nullable="false"/>
			        <Property Name="Locked" Type="Edm.Boolean"/>
			      </EntityType>
			      <EntityType Name="Job">
			        <Key><PropertyRef Name="ID"/></Key>
			        <Property Name="ID" Type="Edm.String" Nullable="false"/>
			        <NavigationProperty Name="Replica" Type="tm1s.DatabaseReplica" ContainsTarget="true"/>
			      </EntityType>
			      <Action Name="ExecuteMDX">
			        <Parameter Name="MDX" Type="Edm.String" Nullable="false"/>
			        <Parameter Name="Context" Type="Edm.Untyped" Nullable="false">
			          <Annotation Term="Core.OptionalParameter"><Record/></Annotation>
			        </Parameter>
			        <ReturnType Type="tm1.Cube" Nullable="false"/>
			      </Action>
			      <Function Name="Metrics">
			        <ReturnType Type="Collection(tm1s.Metric)" Nullable="false"/>
			      </Function>
			      <EntityContainer Name="API">
			        <EntitySet Name="Cubes" EntityType="tm1.Cube"/>
			        <EntitySet Name="Jobs" EntityType="tm1.Job"/>
			        <ActionImport Name="ExecuteMDX" Action="tm1.ExecuteMDX"/>
			        <FunctionImport Name="Metrics" Function="tm1.Metrics"/>
			      </EntityContainer>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""";

	private static final String TM1S = """
			<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
			  <edmx:DataServices>
			    <Schema Namespace="ibm.tm1s.api.v1" Alias="tm1s" xmlns="http://docs.oasis-open.org/odata/ns/edm">
			      <EnumType Name="ReplicaRole">
			        <Member Name="Leader" Value="0"/>
			      </EnumType>
			      <EntityType Name="DatabaseReplica">
			        <Key><PropertyRef Name="ID"/></Key>
			        <Property Name="ID" Type="Edm.String"/>
			        <Property Name="Role" Type="tm1s.ReplicaRole"/>
			      </EntityType>
			      <ComplexType Name="Metric">
			        <Property Name="Name" Type="Edm.String"/>
			      </ComplexType>
			      <EntityType Name="Session">
			        <Key><PropertyRef Name="ID"/></Key>
			        <Property Name="ID" Type="Edm.Int64"/>
			      </EntityType>
			      <Action Name="Backup" IsBound="true">
			        <Parameter Name="Database" Type="tm1s.DatabaseReplica" Nullable="false"/>
			      </Action>
			      <EntityContainer Name="API">
			        <EntitySet Name="Sessions" EntityType="tm1s.Session"/>
			      </EntityContainer>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""";

	private static final String MPU = """
			<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
			  <edmx:DataServices>
			    <Schema Namespace="ibm.odata.mpu.v1" Alias="mpu" xmlns="http://docs.oasis-open.org/odata/ns/edm">
			      <EntityType Name="MultipartUpload">
			        <Key><PropertyRef Name="UploadID"/></Key>
			        <Property Name="UploadID" Type="Edm.String" Nullable="false"/>
			      </EntityType>
			      <Action Name="Complete" IsBound="true">
			        <Parameter Name="MultipartUpload" Type="mpu.MultipartUpload" Nullable="false"/>
			      </Action>
			    </Schema>
			  </edmx:DataServices>
			</edmx:Edmx>
			""";

	@Test
	void mergesOlderAndNewerSchemasPreservingBothVersions() {
		CsdlDocument merged = CsdlMerger.merge(List.of(CsdlParser.parse(V11), CsdlParser.parse(V12)));
		assertThat(merged.schemaVersion()).contains("12.6.0");

		CsdlEntityType cube = merged.entityType("tm1.Cube").orElseThrow();
		assertThat(cube.properties()).extracting(CsdlProperty::name).containsExactly("Name", "Locked", "LegacyProp");
		assertThat(merged.entityType("tm1.Job")).isPresent();
		assertThat(merged.complexType("tm1.EncryptionSettings")).isPresent();

		// Both v11-only Reserve and v12-only Metrics survive
		assertThat(merged.boundTo("tm1.Cube")).extracting(CsdlOperation::name).containsExactly("Reserve");
		assertThat(merged.entityContainer().orElseThrow().entitySets()).extracting(CsdlEntityContainer.EntitySet::name)
			.containsExactly("Cubes", "Jobs");
		assertThat(merged.entityContainer().orElseThrow().actionImports())
			.extracting(CsdlEntityContainer.ActionImport::name)
			.containsExactly("ExecuteMDX", "RotateKey");

		// ExecuteMDX Context is optional (nullable=true) because it's v12-only and
		// carries Core.OptionalParameter
		CsdlOperation mdx = merged.operations("tm1.ExecuteMDX").get(0);
		assertThat(mdx.callerParameters()).extracting(CsdlParameter::name, CsdlParameter::nullable)
			.containsExactly(tuple("MDX", false), tuple("Context", true));
	}

	@Test
	void resolvesLocalReferencesWithoutCollidingOrImportingOtherServicesOperations() {
		CsdlDocument merged = CsdlMerger.merge(List.of(CsdlParser.parse(V11), CsdlParser.parse(V12)));
		Map<String, CsdlDocument> files = Map.of("ibm.tm1s.api.v1.xml", CsdlParser.parse(TM1S), "ibm.odata.mpu.v1.xml",
				CsdlParser.parse(MPU));
		CsdlDocument resolved = CsdlMerger.resolveReferences(merged, uri -> Optional.ofNullable(files.get(uri)));

		// tm1s: only transitively referenced types (DatabaseReplica, ReplicaRole,
		// Metric) — NOT Session, NOT Backup, NOT its EntityContainer
		assertThat(resolved.entityType("tm1s.DatabaseReplica")).isPresent();
		assertThat(resolved.enumType("tm1s.ReplicaRole")).isPresent();
		assertThat(resolved.complexType("tm1s.Metric")).isPresent();
		assertThat(resolved.entityType("tm1s.Session")).isEmpty();
		assertThat(resolved.operations("tm1s.Backup")).isEmpty();
		// mpu: all types (no container), no operations
		assertThat(resolved.entityType("mpu.MultipartUpload")).isPresent();
		assertThat(resolved.operations("mpu.Complete")).isEmpty();
		// Primary container stays ibm.tm1.api.v1's
		assertThat(resolved.entityContainer().orElseThrow().entitySets())
			.extracting(CsdlEntityContainer.EntitySet::name)
			.containsExactly("Cubes", "Jobs");
	}

	@Test
	void parsesReferencesAndQualifiesReferencedAliases() {
		CsdlDocument doc = CsdlParser.parse(V12);
		assertThat(doc.references()).extracting(CsdlReference::uri)
			.contains("ibm.tm1s.api.v1.xml", "ibm.odata.mpu.v1.xml");
		assertThat(doc.qualify("tm1s.DatabaseReplica")).isEqualTo("ibm.tm1s.api.v1.DatabaseReplica");
		assertThat(doc.qualify("mpu.MultipartUpload")).isEqualTo("ibm.odata.mpu.v1.MultipartUpload");
	}

	@Test
	void refusesEmptyDocumentList() {
		assertThatIllegalArgumentException().isThrownBy(() -> CsdlMerger.merge(List.of()));
	}

	@Test
	void normalizesOptionalParametersEvenForASingleDocument() {
		CsdlDocument merged = CsdlMerger.merge(List.of(CsdlParser.parse(V12)));
		CsdlOperation mdx = merged.operations("tm1.ExecuteMDX").get(0);
		assertThat(mdx.callerParameters()).extracting(CsdlParameter::name, CsdlParameter::nullable)
			.containsExactly(tuple("MDX", false), tuple("Context", true));
	}

	@Test
	void rejectsDuplicateOperationOverloadsInASingleSchema() {
		String overloaded = """
				<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
				  <edmx:DataServices>
				    <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
				      <Function Name="Foo"><Parameter Name="A" Type="Edm.String"/><ReturnType Type="Edm.String"/></Function>
				      <Function Name="Foo"><Parameter Name="B" Type="Edm.Int32"/><ReturnType Type="Edm.String"/></Function>
				    </Schema>
				  </edmx:DataServices>
				</edmx:Edmx>
				""";
		assertThatIllegalArgumentException().isThrownBy(() -> CsdlMerger.merge(List.of(CsdlParser.parse(overloaded))))
			.withMessageContaining("Duplicate operation overload");
	}

	@Test
	void rejectsSimpleNameCollisionsWhenResolvingReferences() {
		String main = """
				<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
				  <edmx:Reference Uri="other.xml">
				    <edmx:Include Namespace="other.v1" Alias="other"/>
				  </edmx:Reference>
				  <edmx:DataServices>
				    <Schema Namespace="ibm.tm1.api.v1" Alias="tm1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
				      <EntityType Name="Cube"><Key><PropertyRef Name="Name"/></Key><Property Name="Name" Type="Edm.String" Nullable="false"/></EntityType>
				    </Schema>
				  </edmx:DataServices>
				</edmx:Edmx>
				""";
		String other = """
				<edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
				  <edmx:DataServices>
				    <Schema Namespace="other.v1" Alias="other" xmlns="http://docs.oasis-open.org/odata/ns/edm">
				      <ComplexType Name="Cube"><Property Name="X" Type="Edm.String"/></ComplexType>
				    </Schema>
				  </edmx:DataServices>
				</edmx:Edmx>
				""";
		assertThatIllegalStateException().isThrownBy(
				() -> CsdlMerger.resolveReferences(CsdlParser.parse(main), uri -> Optional.of(CsdlParser.parse(other))))
			.withMessageContaining("collides on simple name Cube");
	}

}
