// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * Probes the TM1 test server and writes {@code tm1-probe-<schema version>.json} beside
 * the {@code $metadata} snapshot. Runs only when asked: {@code ./gradlew probeTm1}, with
 * a TM1 source set as for the other TM1 tests (see
 * {@code java/tools/tm1-probe/README.md}).
 */
@RequiresTm1
@EnabledIfEnvironmentVariable(named = "TM1_PROBE", matches = "true")
class ProbeRunTm1Test {

	/**
	 * {@code writes} to probe the writes alone, keeping the committed report's other
	 * findings (one server is enough).
	 */
	static final String ONLY = "TM1_PROBE_ONLY";

	/** How many servers to probe with; 4 unless set. */
	static final String SERVERS = "TM1_PROBE_SERVERS";

	/**
	 * Where the server's binary is in a tm1-server container, whatever the TM1 source.
	 */
	static final String BINARY = "/opt/ibm/cognos/tm1_64/bin64/tm1s.exe";

	@Test
	void probesTheServer(Tm1ServerContainer tm1) throws IOException {
		Path dir = Path.of(System.getProperty("tm1.probe.metadataDir"));
		byte[] binary = tm1.copyFileFromContainer(BINARY, InputStream::readAllBytes);
		List<String> earlier = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(f -> f.getFileName().toString().matches("tm1-probe-.*\\.json"))
				.sorted()
				.toList()) {
				earlier.add(Files.readString(f, StandardCharsets.UTF_8));
			}
		}
		// More servers, from the same source: one TM1 answers about 35,000 names a second
		int count = "writes".equals(System.getenv(ONLY)) ? 1
				: Integer.parseInt(System.getenv().getOrDefault(SERVERS, "4"));
		List<Tm1ServerContainer> extra = new ArrayList<>();
		for (int i = 1; i < count; i++) {
			extra.add(new Tm1ServerContainer(tm1.getSource()));
		}
		List<ProbeClient> clients = new ArrayList<>();
		try {
			extra.parallelStream().forEach(Tm1ServerContainer::start);
			clients.add(new ProbeClient(tm1.getRestUrl(), tm1.getAuthorization()));
			for (Tm1ServerContainer server : extra) {
				clients.add(new ProbeClient(server.getRestUrl(), server.getAuthorization()));
			}
			CsdlDocument metadata = CsdlParser.parse(clients.get(0).text("$metadata"));
			Tm1Probe probe = new Tm1Probe(clients, metadata, 4, System.out::println);
			ProbeReport report;
			if ("writes".equals(System.getenv(ONLY))) {
				// The writes alone, on the committed report's names
				Path committed = dir.resolve(ProbeReport.fileName(metadata.schemaVersion().orElse("")));
				report = probe.runWrites(ProbeReport.fromJson(Files.readString(committed, StandardCharsets.UTF_8)));
			}
			else {
				report = probe.run(binary, Tm1Probe.earlierNames(earlier));
			}
			Path file = dir.resolve(ProbeReport.fileName(report.schemaVersion()));
			Files.writeString(file, report.toJson(), StandardCharsets.UTF_8);
			System.out.println("Wrote " + file);
			assertThat(report.undeclared()).isNotEmpty();
		}
		finally {
			clients.forEach(ProbeClient::close);
			extra.forEach(Tm1ServerContainer::stop);
		}
	}

}
