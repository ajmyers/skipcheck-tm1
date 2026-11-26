// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlAnnotation;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlOperation;
import io.skipcheck.tm1.csdl.CsdlParser;
import io.skipcheck.tm1.csdl.CsdlRevision;
import io.skipcheck.tm1.csdl.CsdlSchema;

/**
 * Checks the committed snapshot parses into the API surface measured from the live
 * server.
 */
class MetadataSnapshotTest {

	private final CsdlDocument doc = MetadataSnapshot.load();

	@Test
	void isTheVersionItSaysItIs() {
		assertThat(doc.schemaVersion()).contains(MetadataSnapshot.VERSION);
		assertThat(doc.version()).isEqualTo("4.0");
	}

	@Test
	void holdsEachCommittedSnapshotToTheSchemaItsNameSays() throws Exception {
		// Each tm1-schema-<v>.xml is the document of schema <v>, and the newest is
		// VERSION
		List<String> versions = new ArrayList<>();
		try (Stream<Path> files = Files.list(Path.of("src/model/metadata"))) {
			files.map(f -> f.getFileName().toString())
				.filter(n -> n.startsWith("tm1-schema-") && n.endsWith(".xml"))
				.forEach(n -> versions.add(n.substring("tm1-schema-".length(), n.length() - ".xml".length())));
		}
		assertThat(versions).contains("11.8.20", "11.8.32", MetadataSnapshot.VERSION);
		for (String version : versions) {
			assertThat(CsdlParser.parse(MetadataSnapshot.text(version).orElseThrow()).schemaVersion()).as(version)
				.contains(version);
		}
		assertThat(versions.stream().map(Tm1Version::parse).max(Comparator.naturalOrder()))
			.contains(Tm1Version.parse(MetadataSnapshot.VERSION));
		assertThat(MetadataSnapshot.text("0.0")).isEmpty();
	}

	@Test
	void hasTheWholeApiSurface() {
		CsdlSchema tm1 = doc.schemas()
			.stream()
			.filter(s -> "ibm.tm1.api.v1".equals(s.namespace()))
			.findFirst()
			.orElseThrow();
		assertThat(tm1.alias()).isEqualTo("tm1");
		assertThat(tm1.entityTypes()).hasSize(67);
		assertThat(tm1.complexTypes()).hasSize(62);
		assertThat(tm1.enumTypes()).hasSize(31);
		assertThat(tm1.operations().stream().filter(o -> o.kind() == CsdlOperation.Kind.ACTION)).hasSize(91);
		assertThat(tm1.operations().stream().filter(o -> o.kind() == CsdlOperation.Kind.FUNCTION)).hasSize(14);

		CsdlEntityContainer api = doc.entityContainer().orElseThrow();
		assertThat(api.entitySets()).hasSize(27);
		assertThat(api.singletons()).hasSize(6);
		assertThat(api.actionImports()).hasSize(30);
		assertThat(api.functionImports()).hasSize(11);
	}

	@Test
	void keepsTheRevisionHistory() {
		assertThat(CsdlRevision.addedIn(doc.boundTo("tm1.Cube")
			.stream()
			.filter(o -> o.name().equals("Reserve"))
			.findFirst()
			.orElseThrow()
			.annotations())).contains("11.8.34");
		assertThat(CsdlRevision.deprecatedIn(doc.boundTo("tm1.Chore")
			.stream()
			.filter(o -> o.name().equals("Activate"))
			.findFirst()
			.orElseThrow()
			.annotations())).contains("11.8.3");
	}

	@Test
	void keepsTheDescriptions() {
		// IBM's descriptions are kept: the generated model's docs come from them
		var name = doc.entityType("tm1.Cube")
			.orElseThrow()
			.properties()
			.stream()
			.filter(p -> p.name().equals("Name"))
			.findFirst()
			.orElseThrow();
		assertThat(CsdlAnnotation.find(name.annotations(), "Core.Description")).isPresent();
	}

	@Test
	void signaturesCoverEveryKindOfElement() {
		assertThat(Signatures.of(doc))
			.contains("EntitySet Cubes: ibm.tm1.api.v1.Cube", "Singleton Server: ibm.tm1.api.v1.Server",
					"Property ibm.tm1.api.v1.Cube/Name: Edm.String",
					"Navigation ibm.tm1.api.v1.Cube/Views: Collection(tm1.View)",
					"EnumMember ibm.tm1.api.v1.ElementType/Numeric",
					"EntityType ibm.tm1.api.v1.MDXView : ibm.tm1.api.v1.View")
			.anyMatch(s -> s.startsWith("Action ibm.tm1.api.v1.Reserve(Cube: ibm.tm1.api.v1.Cube"));
	}

}
