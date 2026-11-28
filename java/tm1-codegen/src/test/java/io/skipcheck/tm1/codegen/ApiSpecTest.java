// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlMerger;
import io.skipcheck.tm1.csdl.CsdlParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/** The committed API spec is what the exporter makes of the snapshot and the overlay. */
class ApiSpecTest {

	private static final List<Path> SNAPSHOTS = List.of(System.getProperty("tm1.snapshots").split(","))
		.stream()
		.map(Path::of)
		.toList();

	private static final Path SPEC = Path.of(System.getProperty("tm1.spec"));

	@Test
	void committedSpecIsUpToDate() throws IOException {
		assertThat(Files.readString(SPEC, StandardCharsets.UTF_8))
			.as("spec/tm1-api.json is out of date: run ./gradlew :tm1-codegen:exportSpec")
			.isEqualTo(exportWithProbe());
	}

	@Test
	void marksThePropertiesTheProbeFoundUndeclared() throws IOException {
		JsonNode spec = JsonMapper.builder().build().readTree(exportWithProbe());
		assertThat(spec.path("source").path("probe").asString()).isEqualTo("tm1-probe-11.8.35.json");
		assertThat(spec.path("source").path("snapshot").asString()).isEqualTo("tm1-schema-12.6.0.xml");
		assertThat(spec.path("source").path("schemaVersion").asString()).isEqualTo("12.6.0");
		JsonNode uiData = property(spec, "Process", "UIData");
		assertThat(uiData.path("type").asString()).isEqualTo("Edm.String");
		assertThat(uiData.path("undeclared").path("source").asString()).isEqualTo("probe");
		assertThat(uiData.path("undeclared").path("observedIn").get(0).asString()).isEqualTo("11.8.35");
		JsonNode variables = property(spec, "Process", "VariablesUIData");
		assertThat(variables.path("type").asString()).isEqualTo("Edm.String");
		assertThat(variables.path("collection").asBoolean()).isTrue();
		// The probe read it as null: it's read as JSON
		assertThat(property(spec, "Element", "DisplayString").path("departure").path("read").asString())
			.isEqualTo("json");
		// Cube/AllowPersistentHolds was undeclared in 11.8.35, now declared in 12.6.0
		JsonNode holds = property(spec, "Cube", "AllowPersistentHolds");
		assertThat(holds.path("type").asString()).isEqualTo("Edm.Boolean");
		assertThat(holds.has("undeclared")).isFalse();
		// Referenced tm1s and mpu types are exported cleanly without leaking tm1s.Session
		assertThat(typeNode(spec, "DatabaseReplica").path("qualifiedName").asString())
			.isEqualTo("ibm.tm1s.api.v1.DatabaseReplica");
		assertThat(typeNode(spec, "Metric").path("qualifiedName").asString()).isEqualTo("ibm.tm1s.api.v1.Metric");
		assertThat(typeNode(spec, "MultipartUpload").path("qualifiedName").asString())
			.isEqualTo("ibm.odata.mpu.v1.MultipartUpload");
	}

	@Test
	void exportsTheWritePlan() throws IOException {
		JsonNode builders = JsonMapper.builder().build().readTree(exportWithProbe()).path("builders");
		JsonNode cube = builders.path("CubeCreate");
		assertThat(cube.path("type").asString()).isEqualTo("ibm.tm1.api.v1.Cube");
		assertThat(cube.path("write").asString()).isEqualTo("create");
		assertThat(cube.path("required").get(0).asString()).isEqualTo("Dimensions");
		JsonNode dimensions = cube.path("fromRead").path("bindings").get(0);
		assertThat(dimensions.path("json").asString()).isEqualTo("Dimensions");
		assertThat(dimensions.path("by").asString()).isEqualTo("key");
		assertThat(dimensions.path("set").asString()).isEqualTo("Dimensions");
		assertThat(dimensions.path("key").asString()).isEqualTo("Name");
		assertThat(cube.path("dispatch").isNull()).isTrue();
		JsonNode encryption = null;
		for (JsonNode c : builders.path("ServerSettingsPatch").path("fromRead").path("copy")) {
			if (c.path("json").asString().equals("EncryptionAtRest")) {
				encryption = c;
			}
		}
		assertThat(encryption).as("a root's value goes through its of()").isNotNull();
		assertThat(encryption.path("via").asString()).isEqualTo("of");
		assertThat(encryption.path("value").asString()).isEqualTo("EncryptionAtRestProviderSettingsPatch");
		JsonNode view = builders.path("MDXViewPatch");
		assertThat(view.path("base").asString()).isEqualTo("ViewPatch");
		assertThat(view.path("odataType").asString()).isEqualTo("#ibm.tm1.api.v1.MDXView");
		assertThat(view.path("open").asBoolean()).isTrue();
		assertThat(builders.path("ViewPatch").path("dispatch").size()).isEqualTo(2);
	}

	private static JsonNode property(JsonNode spec, String type, String name) {
		for (JsonNode t : spec.path("types")) {
			if (t.path("name").asString().equals(type)) {
				for (JsonNode p : t.path("properties")) {
					if (p.path("name").asString().equals(name)) {
						return p;
					}
				}
			}
		}
		return MissingNode.getInstance();
	}

	private static JsonNode typeNode(JsonNode spec, String name) {
		for (JsonNode t : spec.path("types")) {
			if (t.path("name").asString().equals(name)) {
				return t;
			}
		}
		return MissingNode.getInstance();
	}

	private static String exportWithProbe() throws IOException {
		Tm1Codegen.Model model = Tm1Codegen.Model.of(SNAPSHOTS);
		return new ApiSpecExporter(model.metadata(), model.overlay(), model.snapshots(), model.undeclared(),
				model.writes(), model.offers(), model.probes())
			.export();
	}

	@Test
	void listsTheCoverageGatesEndpointsWithOverlayNotes() throws IOException {
		JsonNode spec = JsonMapper.builder().build().readTree(export(Overlay.bundled()));
		List<String> ids = new ArrayList<>();
		spec.path("endpoints").forEach(e -> ids.add(e.path("id").asString()));
		// The same count as CoverageGateTest in tm1-client
		assertThat(ids).hasSize(256).doesNotHaveDuplicates().isSorted();
		assertThat(ids).contains("EntitySet Cubes", "EntitySet Jobs", "Singleton Server", "Action Cube/Reserve",
				"FunctionImport Metrics", "Navigation ViewTitle/Subset");
		JsonNode groups = null;
		for (JsonNode e : spec.path("endpoints")) {
			if (e.path("id").asString().equals("Navigation User/Groups")) {
				groups = e;
			}
		}
		assertThat(groups).isNotNull();
		List<String> forbid = new ArrayList<>();
		groups.path("forbid").forEach(f -> forbid.add(f.asString()));
		assertThat(forbid).contains("delete");
		assertThat(spec.path("operations")).hasSize(112);
	}

	@Test
	void marksDeparturesOnTheirProperties() throws IOException {
		JsonNode spec = JsonMapper.builder().build().readTree(export(Overlay.bundled()));
		JsonNode cam = null;
		for (JsonNode t : spec.path("types")) {
			if (t.path("name").asString().equals("CAMSettings")) {
				for (JsonNode p : t.path("properties")) {
					if (p.path("name").asString().equals("ClientPingCAMPassport")) {
						cam = p;
					}
				}
			}
		}
		assertThat(cam).isNotNull();
		assertThat(cam.path("departure").path("read").asString()).isEqualTo("json");
	}

	@Test
	void refusesAnOverlayNamingWhatTheSnapshotLacks() throws IOException {
		Overlay overlay = Overlay.read(new ByteArrayInputStream(
				"{\"endpoints\": {\"EntitySet Nope\": {\"note\": \"x\"}}}".getBytes(StandardCharsets.UTF_8)));
		assertThatThrownBy(() -> export(overlay)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("EntitySet Nope");
	}

	private static String export(Overlay overlay) throws IOException {
		List<CsdlDocument> docs = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (Path p : SNAPSHOTS) {
			try (InputStream in = Files.newInputStream(p)) {
				docs.add(CsdlParser.parse(in));
			}
			names.add(p.getFileName().toString());
		}
		Path dir = SNAPSHOTS.get(SNAPSHOTS.size() - 1).toAbsolutePath().getParent();
		CsdlDocument merged = CsdlMerger.resolveReferences(CsdlMerger.merge(docs), uri -> {
			Path target = dir.resolve(uri).normalize();
			if (!target.startsWith(dir) || !Files.isRegularFile(target)) {
				return Optional.empty();
			}
			try (InputStream in = Files.newInputStream(target)) {
				return Optional.of(CsdlParser.parse(in));
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		return new ApiSpecExporter(merged, overlay, names, Undeclared.NONE, Writes.NONE, Offers.NONE, List.of())
			.export();
	}

}
