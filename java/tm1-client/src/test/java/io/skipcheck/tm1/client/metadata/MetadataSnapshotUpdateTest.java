// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.skipcheck.tm1.csdl.CsdlParser;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

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
 * only {@link MetadataDriftTm1Test} reads, and against a build that reports a committed
 * schema but serves another document, a later document of the schema
 * ({@link MetadataSnapshot#LATER_DOCUMENTS}).
 */
@RequiresTm1
@EnabledIfEnvironmentVariable(named = "TM1_UPDATE_METADATA_SNAPSHOT", matches = "true")
class MetadataSnapshotUpdateTest {

	@Test
	void writesTheSnapshot(Tm1ServerContainer tm1) throws Exception {
		String live = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(tm1.getRestUrl() + "$metadata")).build(),
					HttpResponse.BodyHandlers.ofString())
			.body();
		String version = CsdlParser.parse(live).schemaVersion().orElseThrow();

		// A schema committed with another document: this build's is a later one, named by
		// the build (11.8.02900.8 gives 11.8.20-11.8.02900), to add to LATER_DOCUMENTS
		String name = version;
		Optional<String> own = MetadataSnapshot.text(version, tm1.getTm1Version());
		if (own.isPresent() && !own.get().equals(live)) {
			String[] build = tm1.getTm1Version().toString().split("\\.", -1);
			name = version + "-" + String.join(".", Arrays.copyOf(build, Math.min(3, build.length)));
			System.out.println("Schema " + version + " is committed with another document: writing tm1-schema-" + name
					+ ".xml. Add " + name.substring(version.length() + 1) + " to MetadataSnapshot.LATER_DOCUMENTS");
		}

		// Tests run with the module folder as the working directory
		Path file = Path.of("src/model/metadata/tm1-schema-" + name + ".xml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, live, StandardCharsets.UTF_8);
		assertThat(file).exists();
	}

}
