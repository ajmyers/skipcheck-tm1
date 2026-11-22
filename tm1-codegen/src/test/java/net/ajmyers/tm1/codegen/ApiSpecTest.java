package net.ajmyers.tm1.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.csdl.CsdlDocument;
import net.ajmyers.tm1.csdl.CsdlParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The committed API spec is what the exporter makes of the snapshot and the overlay. */
class ApiSpecTest {

	private static final Path SNAPSHOT = Path.of(System.getProperty("tm1.snapshot"));

	private static final Path SPEC = Path.of(System.getProperty("tm1.spec"));

	@Test
	void committedSpecIsUpToDate() throws IOException {
		assertThat(Files.readString(SPEC, StandardCharsets.UTF_8))
			.as("spec/tm1-api.json is out of date: run ./gradlew :tm1-codegen:exportSpec")
			.isEqualTo(export(Overlay.bundled()));
	}

	@Test
	void listsTheCoverageGatesEndpointsWithOverlayNotes() throws IOException {
		JsonNode spec = JsonMapper.builder().build().readTree(export(Overlay.bundled()));
		List<String> ids = new ArrayList<>();
		spec.path("endpoints").forEach(e -> ids.add(e.path("id").asString()));
		// The same count as CoverageGateTest in tm1-client
		assertThat(ids).hasSize(242).doesNotHaveDuplicates().isSorted();
		assertThat(ids).contains("EntitySet Cubes", "Singleton Server", "Action Cube/Reserve",
				"Navigation ViewTitle/Subset");
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
		assertThat(spec.path("operations")).hasSize(105);
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
		CsdlDocument doc;
		try (InputStream in = Files.newInputStream(SNAPSHOT)) {
			doc = CsdlParser.parse(in);
		}
		return new ApiSpecExporter(doc, overlay, SNAPSHOT.getFileName().toString()).export();
	}

}
