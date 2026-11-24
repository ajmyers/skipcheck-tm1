// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

class ProbePathsTest {

	private static final String METADATA = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			 <edmx:DataServices>
			  <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			   <EntityType Name="Cube"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			    <NavigationProperty Name="Views" Type="Collection(tm1.View)" ContainsTarget="true"/>
			   </EntityType>
			   <EntityType Name="View"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			   </EntityType>
			   <EntityType Name="Hidden"><Key><PropertyRef Name="ID"/></Key>
			    <Property Name="ID" Type="Edm.String" Nullable="false"/>
			   </EntityType>
			   <EntityContainer Name="API">
			    <EntitySet Name="Cubes" EntityType="tm1.Cube"/>
			   </EntityContainer>
			  </Schema>
			 </edmx:DataServices>
			</edmx:Edmx>""";

	/** A server with a control cube and a fixture cube; views don't check names. */
	private static FakeTm1.Reply serve(String target) {
		return switch (target) {
			case "Cubes?$top=100" -> FakeTm1.Reply.ok("""
					{"value": [{"Name": "}Control"}, {"Name": "Other"}, {"Name": "SkipcheckProbeCube"}]}""");
			case "Cubes?$top=1&$select=Name" -> FakeTm1.Reply.ok("{}");
			case "Cubes('SkipcheckProbeCube')/Views?$top=100" -> FakeTm1.Reply.ok("""
					{"value": [{"@odata.id": "Cubes('SkipcheckProbeCube')/Views('v')", "Name": "v"}]}""");
			default -> target.startsWith("Cubes('SkipcheckProbeCube')/Views") && target.contains("$select=")
					? FakeTm1.Reply.ok("{}") : FakeTm1.Reply.refused();
		};
	}

	@Test
	void walksFromTheEntitySetsThroughTheFixtures() throws Exception {
		CsdlDocument metadata = CsdlParser.parse(METADATA);
		try (FakeTm1 tm1 = new FakeTm1(ProbePathsTest::serve)) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			Map<String, ProbePaths.Reach> reach = new ProbePaths(metadata, client, new PropertyNameOracle(client, 1),
					"SkipcheckProbe")
				.find(Map.of());
			assertThat(reach).containsOnlyKeys("Cube", "Hidden", "View");
			assertThat(reach.get("Cube"))
				.isEqualTo(new ProbePaths.Reach("Cubes?$top=1", "Cubes('SkipcheckProbeCube')"));
			// Found, but TM1 accepts any name there
			assertThat(reach.get("View"))
				.isEqualTo(new ProbePaths.Reach(null, "Cubes('SkipcheckProbeCube')/Views('v')"));
			assertThat(reach.get("Hidden")).isEqualTo(new ProbePaths.Reach(null, null));
		}
	}

}
