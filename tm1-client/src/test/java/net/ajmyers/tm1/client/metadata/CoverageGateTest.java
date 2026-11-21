package net.ajmyers.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * The endpoint coverage gate (plan §12.3): every endpoint in the snapshot must be accounted
 * for, as implemented, excluded with a reason, or pending.
 *
 * <ul>
 *   <li>{@code metadata/coverage-excluded.txt}: endpoints deliberately not implemented, one per
 *       line, as {@code <endpoint> | <reason>}.
 *   <li>{@code metadata/coverage-pending.txt}: endpoints not implemented yet. This list only
 *       shrinks: implementing an endpoint means taking it off.
 * </ul>
 *
 * <p>It's a ratchet. A new snapshot with new endpoints fails until each is triaged, and an
 * implemented endpoint still listed as pending fails too. Lines starting with {@code #} are
 * comments. A summary goes to {@code build/reports/tm1-coverage.txt}.
 */
class CoverageGateTest {

	private static final String EXCLUDED = "/metadata/coverage-excluded.txt";
	private static final String PENDING = "/metadata/coverage-pending.txt";

	private final SortedSet<String> endpoints = Endpoints.of(MetadataSnapshot.load());

	@Test
	void everyEndpointIsAccountedFor() throws IOException {
		Map<String, String> excluded = excluded();
		List<String> pending = lines(PENDING);
		Set<String> implemented = implemented();

		SortedSet<String> untriaged = new TreeSet<>(endpoints);
		untriaged.removeAll(implemented);
		untriaged.removeAll(excluded.keySet());
		pending.forEach(untriaged::remove);
		assertThat(untriaged).as("endpoints in $metadata that are not implemented, excluded or pending: add each to %s or %s", PENDING, EXCLUDED)
			.isEmpty();

		SortedSet<String> unknown = new TreeSet<>(pending);
		unknown.addAll(excluded.keySet());
		unknown.removeAll(endpoints);
		assertThat(unknown).as("entries in the coverage lists that $metadata doesn't define (typos, or removed endpoints)").isEmpty();

		SortedSet<String> stillPending = new TreeSet<>(implemented);
		stillPending.retainAll(pending);
		assertThat(stillPending).as("implemented endpoints still listed in %s: take them off", PENDING).isEmpty();

		SortedSet<String> both = new TreeSet<>(pending);
		both.retainAll(excluded.keySet());
		assertThat(both).as("endpoints listed as both pending and excluded").isEmpty();
		assertThat(pending).as("duplicate lines in %s", PENDING).doesNotHaveDuplicates();

		report(implemented.size(), excluded.size(), pending.size());
	}

	@Test
	void countsEveryKindOfEndpoint() {
		assertThat(endpoints).contains("EntitySet Cubes", "Singleton Server", "ActionImport ExecuteMDX", "FunctionImport TailMessageLog",
				"Action Cube/Reserve", "Function Cube/DimensionsStorageOrder", "Navigation Cube/Views", "Navigation ViewTitle/Subset");
		// 27 entity sets + 6 singletons + 30 action imports + 11 function imports + 64 bound operations
		// + 104 navigation properties (90 on entity types, 14 on complex types)
		assertThat(endpoints).hasSize(242);
	}

	/**
	 * Endpoints the client implements. None yet: the services that implement them, and the way
	 * they declare which endpoints they cover, start with the first endpoint service.
	 */
	private static Set<String> implemented() {
		return Set.of();
	}

	private static Map<String, String> excluded() throws IOException {
		Map<String, String> result = new LinkedHashMap<>();
		for (String line : lines(EXCLUDED)) {
			int bar = line.indexOf('|');
			assertThat(bar).as("'%s' in %s needs a reason: <endpoint> | <reason>", line, EXCLUDED).isPositive();
			String reason = line.substring(bar + 1).strip();
			assertThat(reason).as("the reason for excluding %s", line).isNotBlank();
			assertThat(result.put(line.substring(0, bar).strip(), reason)).as("duplicate exclusion %s", line).isNull();
		}
		return result;
	}

	private static List<String> lines(String resource) throws IOException {
		try (InputStream in = CoverageGateTest.class.getResourceAsStream(resource)) {
			assertThat(in).as(resource).isNotNull();
			List<String> result = new ArrayList<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String l = line.strip();
				if (!l.isEmpty() && !l.startsWith("#")) {
					result.add(l);
				}
			}
			return result;
		}
	}

	private void report(int implemented, int excluded, int pending) throws IOException {
		String report = "TM1 REST API coverage, schema " + MetadataSnapshot.VERSION + "\n\n"
				+ "Endpoints:   " + endpoints.size() + "\n"
				+ "Implemented: " + implemented + "\n"
				+ "Excluded:    " + excluded + "\n"
				+ "Pending:     " + pending + "\n";
		Path file = Path.of("build/reports/tm1-coverage.txt");
		Files.createDirectories(file.getParent());
		Files.writeString(file, report, StandardCharsets.UTF_8);
	}
}
