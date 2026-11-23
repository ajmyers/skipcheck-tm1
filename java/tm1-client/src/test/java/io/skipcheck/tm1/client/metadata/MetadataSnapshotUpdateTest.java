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
 * It writes {@code src/model/metadata/tm1-schema-<version>.xml}. For a new schema
 * version, also update {@link MetadataSnapshot#VERSION}, then triage the new endpoints
 * the coverage gate reports.
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

		// Tests run with the module folder as the working directory
		Path file = Path.of("src/model/metadata/tm1-schema-" + version + ".xml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, live, StandardCharsets.UTF_8);
		assertThat(file).exists();
	}

}
