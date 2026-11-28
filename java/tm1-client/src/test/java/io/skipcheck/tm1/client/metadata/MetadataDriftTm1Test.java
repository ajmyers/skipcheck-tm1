// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * Compares the test server's {@code $metadata} with the committed snapshot.
 *
 * <ul>
 * <li><b>Same schema version:</b> the live metadata must equal the snapshot byte for
 * byte. A difference means the snapshot is stale.
 * <li><b>Another version:</b> writes what it adds and removes to
 * {@code build/reports/tm1-metadata-drift.txt}. This is how running the tests against
 * another TM1 version shows its API differences. If a snapshot of that version is
 * committed too (an older supported version), the live metadata must equal the one its
 * build serves
 * ({@link MetadataSnapshot#text(String, io.skipcheck.tm1.client.version.Tm1Version)})
 * byte for byte; otherwise the test passes.
 * </ul>
 */
@LiveTm1
class MetadataDriftTm1Test {

	@Test
	void matchesTheSnapshotOrReportsTheDifferences(LiveServer tm1) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + "$metadata"));
		if (tm1.isEngine()) {
			tm1.authorize(request);
		}
		HttpResponse<String> response = tm1.httpClient()
			.build()
			.send(request.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("GET $metadata status").isEqualTo(200);
		String live = response.body();
		CsdlDocument liveDoc = CsdlParser.parse(live);

		if (liveDoc.schemaVersion().orElse("").equals(MetadataSnapshot.VERSION)) {
			assertThat(live)
				.as("live $metadata (schema %s, TM1 %s) against the snapshot its build serves; regenerate it with "
						+ "TM1_UPDATE_METADATA_SNAPSHOT=true, or if older builds of the schema still serve the one "
						+ "committed, commit this as a later document with TM1_UPDATE_METADATA_SNAPSHOT=later",
						MetadataSnapshot.VERSION, tm1.getTm1Version())
				.isEqualTo(MetadataSnapshot.text(MetadataSnapshot.VERSION, tm1.getTm1Version()).orElseThrow());
			return;
		}

		String schema = liveDoc.schemaVersion().orElse("unknown");
		NavigableSet<String> snapshot = Signatures.of(MetadataSnapshot.load());
		NavigableSet<String> server = Signatures.of(liveDoc);
		NavigableSet<String> added = new TreeSet<>(server);
		added.removeAll(snapshot);
		NavigableSet<String> removed = new TreeSet<>(snapshot);
		removed.removeAll(server);

		StringBuilder report = new StringBuilder().append("TM1 ")
			.append(tm1.getTm1Version())
			.append(" (schema ")
			.append(schema)
			.append(") against the snapshot (schema ")
			.append(MetadataSnapshot.VERSION)
			.append(")\n\n")
			.append("Only on this server (")
			.append(added.size())
			.append("):\n");
		added.forEach(s -> report.append("  + ").append(s).append('\n'));
		report.append("\nOnly in the snapshot (").append(removed.size()).append("):\n");
		removed.forEach(s -> report.append("  - ").append(s).append('\n'));

		Path file = Path.of("build/reports/tm1-metadata-drift.txt");
		Files.createDirectories(file.getParent());
		Files.writeString(file, report, StandardCharsets.UTF_8);
		System.out.println(report);

		MetadataSnapshot.text(schema, tm1.getTm1Version())
			.ifPresent(own -> assertThat(firstDifference(own, live))
				.as("live $metadata (schema %s, TM1 %s) against its own committed snapshot. Either the server changed, or "
						+ "this release serves another document of the schema than those committed: several releases can "
						+ "report one schema. If the server changed, regenerate it with TM1_UPDATE_METADATA_SNAPSHOT=true; "
						+ "if older builds still serve the one committed, commit this as a later document with "
						+ "TM1_UPDATE_METADATA_SNAPSHOT=later", schema, tm1.getTm1Version())
				.isEmpty());
	}

	/**
	 * The first line where the two differ, as "line N: expected … but was …", or empty.
	 */
	private static String firstDifference(String expected, String actual) {
		String[] want = expected.split("\n", -1);
		String[] got = actual.split("\n", -1);
		for (int i = 0; i < Math.max(want.length, got.length); i++) {
			String w = i < want.length ? want[i] : "(end)";
			String g = i < got.length ? got[i] : "(end)";
			if (!w.equals(g)) {
				return "line " + (i + 1) + ": expected " + w.strip() + " but was " + g.strip();
			}
		}
		return "";
	}

}
