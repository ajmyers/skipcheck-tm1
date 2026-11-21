package net.ajmyers.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.odata.csdl.CsdlDocument;
import net.ajmyers.tm1.client.odata.csdl.CsdlParser;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * Compares the test server's {@code $metadata} with the committed snapshot.
 *
 * <ul>
 *   <li><b>Same schema version:</b> the live metadata must equal the snapshot byte for byte.
 *       A difference means the snapshot is stale.
 *   <li><b>Another version:</b> writes what it adds and removes to
 *       {@code build/reports/tm1-metadata-drift.txt} and passes. This is how running the tests
 *       against another TM1 version shows its API differences (plan §12.4).
 * </ul>
 */
@RequiresTm1
class MetadataDriftTest {

	@Test
	void matchesTheSnapshotOrReportsTheDifferences(Tm1ServerContainer tm1) throws Exception {
		String live = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + "$metadata")).build(), HttpResponse.BodyHandlers.ofString())
			.body();
		CsdlDocument liveDoc = CsdlParser.parse(live);

		if (liveDoc.schemaVersion().orElse("").equals(MetadataSnapshot.VERSION)) {
			assertThat(live)
				.as("live $metadata (schema %s) against the snapshot; regenerate it with TM1_UPDATE_METADATA_SNAPSHOT=true", MetadataSnapshot.VERSION)
				.isEqualTo(MetadataSnapshot.text());
			return;
		}

		SortedSet<String> snapshot = Signatures.of(MetadataSnapshot.load());
		SortedSet<String> server = Signatures.of(liveDoc);
		SortedSet<String> added = new TreeSet<>(server);
		added.removeAll(snapshot);
		SortedSet<String> removed = new TreeSet<>(snapshot);
		removed.removeAll(server);

		StringBuilder report = new StringBuilder()
			.append("TM1 ").append(tm1.getTm1Version()).append(" (schema ").append(liveDoc.schemaVersion().orElse("unknown"))
			.append(") against the snapshot (schema ").append(MetadataSnapshot.VERSION).append(")\n\n")
			.append("Only on this server (").append(added.size()).append("):\n");
		added.forEach(s -> report.append("  + ").append(s).append('\n'));
		report.append("\nOnly in the snapshot (").append(removed.size()).append("):\n");
		removed.forEach(s -> report.append("  - ").append(s).append('\n'));

		Path file = Path.of("build/reports/tm1-metadata-drift.txt");
		Files.createDirectories(file.getParent());
		Files.writeString(file, report, StandardCharsets.UTF_8);
		System.out.println(report);
	}
}
