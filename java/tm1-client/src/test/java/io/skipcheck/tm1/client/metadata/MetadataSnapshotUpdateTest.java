// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * Writes a new metadata snapshot from the TM1 test server. Runs only when asked:
 *
 * <pre>{@code
 * TM1_UPDATE_METADATA_SNAPSHOT=true TM1_HOME_DIR=~/ibm/tm1_64 \
 *     ./gradlew :tm1-client:test --tests '*MetadataSnapshotUpdateTest'
 * }</pre>
 *
 * <p>
 * It writes {@code src/model/metadata/tm1-schema-<version>.xml}, named by the server's
 * schema version. For a new schema version, also update {@link MetadataSnapshot#VERSION}
 * and the other places that name the newest snapshot's file (the tm1-client and
 * tm1-codegen builds, {@code ConformanceTm1}, the Python, .NET and TypeScript test
 * support, and the package tests), then triage the new endpoints the coverage gate
 * reports. Against an older supported version it writes that version's snapshot, which
 * only {@link MetadataDriftTm1Test} reads.
 *
 * <p>
 * With {@code true} it rewrites the document the server's build is held to
 * ({@link MetadataSnapshot#text(String, io.skipcheck.tm1.client.version.Tm1Version)}):
 * the schema's first, or a later one. With {@code later}, for a build that reports a
 * committed schema and serves another document while older builds still serve theirs, it
 * writes a later document, {@code tm1-schema-<schema>-<build>.xml}, to add to
 * {@link MetadataSnapshot#LATER_DOCUMENTS}. It refuses a build below one the schema's
 * later documents are declared from, since a document is found by the builds above its
 * own.
 */
@LiveTm1
@EnabledIfEnvironmentVariable(named = "TM1_UPDATE_METADATA_SNAPSHOT", matches = "true|later")
class MetadataSnapshotUpdateTest {

	@Test
	void writesTheSnapshot(LiveServer tm1) throws Exception {
		String live = tm1.httpClient()
			.build()
			.send(HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + "$metadata")).build(),
					HttpResponse.BodyHandlers.ofString())
			.body();
		String version = CsdlParser.parse(live).schemaVersion().orElseThrow();

		Tm1Version build = tm1.getTm1Version();
		String name = version;
		if ("later".equals(System.getenv("TM1_UPDATE_METADATA_SNAPSHOT"))) {
			assertThat(MetadataSnapshot.text(version)).as("schema %s has no document committed to follow", version)
				.isPresent();
			assertThat(MetadataSnapshot.LATER_DOCUMENTS.getOrDefault(version, List.of()))
				.as("later documents of %s declared from builds above %s", version, build)
				.noneMatch(declared -> Tm1Version.parse(declared).compareTo(build) > 0);
			name = version + "-" + build;
			System.out
				.println("Writing tm1-schema-" + name + ".xml: add " + build + " to MetadataSnapshot.LATER_DOCUMENTS");
		}
		else {
			String declared = MetadataSnapshot.LATER_DOCUMENTS.getOrDefault(version, List.of())
				.stream()
				.filter(b -> build.isAtLeast(Tm1Version.parse(b)))
				.max(Comparator.comparing(Tm1Version::parse))
				.orElse(null);
			if (declared != null) {
				name = version + "-" + declared;
			}
		}

		// Tests run with the module folder as the working directory
		Path file = Path.of("src/model/metadata/tm1-schema-" + name + ".xml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, live, StandardCharsets.UTF_8);
		assertThat(file).exists();
	}

}
