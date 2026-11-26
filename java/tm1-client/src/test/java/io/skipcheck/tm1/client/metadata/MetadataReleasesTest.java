// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds {@code tm1-releases.json}, every Planning Analytics release from the oldest
 * supported on, to the committed {@code $metadata} snapshots, and writes
 * {@code spec/tm1-versions.md} from both: each release's TM1 build, schema and document,
 * and what each document adds and removes against the one before it.
 *
 * <p>
 * The file is compared with what this would write, so it can't go stale. Rewrite it with
 *
 * <pre>{@code
 * TM1_UPDATE_VERSIONS_DOC=true ./gradlew :tm1-client:test --tests '*MetadataReleasesTest'
 * }</pre>
 */
class MetadataReleasesTest {

	private static final Path REPO = Path.of(System.getProperty("tm1.repo", "../.."));

	private static final Path DOC = REPO.resolve("spec/tm1-versions.md");

	private static final Path SNAPSHOTS = REPO.resolve("java/tm1-client/src/model/metadata");

	private final List<Release> releases = releases();

	record Release(String planningAnalytics, Tm1Version tm1, String schema, String document) {
	}

	@Test
	void holdsEachReleaseToTheDocumentItsBuildServes() {
		assertThat(releases).isNotEmpty();
		assertThat(releases.get(0).planningAnalytics()).as("the oldest supported release").isEqualTo("2.1.2");
		for (Release r : releases) {
			String text = MetadataSnapshot.text(r.document())
				.orElseThrow(() -> new AssertionError(r + ": no tm1-schema-" + r.document() + ".xml is committed"));
			assertThat(CsdlParser.parse(text).schemaVersion()).as(r.toString()).contains(r.schema());
			assertThat(MetadataSnapshot.text(r.schema(), r.tm1()))
				.as("%s: the document MetadataSnapshot gives TM1 %s", r.planningAnalytics(), r.tm1())
				.contains(text);
		}
		for (int i = 1; i < releases.size(); i++) {
			// In release order, which says which release a document came with
			assertThat(Tm1Version.parse(releases.get(i - 1).planningAnalytics())
				.isBefore(Tm1Version.parse(releases.get(i).planningAnalytics())))
				.as("%s comes after %s", releases.get(i).planningAnalytics(), releases.get(i - 1).planningAnalytics())
				.isTrue();
			assertThat(releases.get(i).tm1().isAtLeast(releases.get(i - 1).tm1()))
				.as("%s's build is no older than %s's", releases.get(i).planningAnalytics(),
						releases.get(i - 1).planningAnalytics())
				.isTrue();
		}
	}

	@Test
	void everyCommittedDocumentIsSomeReleases() throws IOException {
		NavigableSet<String> committed = new TreeSet<>();
		try (var files = Files.list(SNAPSHOTS)) {
			files.map(f -> f.getFileName().toString())
				.filter(n -> n.startsWith("tm1-schema-") && n.endsWith(".xml"))
				.forEach(n -> committed.add(n.substring("tm1-schema-".length(), n.length() - ".xml".length())));
		}
		assertThat(new TreeSet<>(releases.stream().map(Release::document).toList())).isEqualTo(committed);
	}

	@Test
	void theVersionsDocIsWhatTheReleasesAndSnapshotsSay() throws IOException {
		String doc = render();
		if ("true".equals(System.getenv("TM1_UPDATE_VERSIONS_DOC"))) {
			Files.writeString(DOC, doc, StandardCharsets.UTF_8);
		}
		assertThat(Files.exists(DOC) ? Files.readString(DOC, StandardCharsets.UTF_8) : "")
			.as("%s is stale: rewrite it with TM1_UPDATE_VERSIONS_DOC=true", DOC)
			.isEqualTo(doc);
	}

	private String render() {
		StringBuilder out = new StringBuilder("""
				# TM1 versions

				<!-- Written by MetadataReleasesTest from java/tm1-client/src/model/metadata/tm1-releases.json
				     and the $metadata snapshots beside it; rewrite it with TM1_UPDATE_VERSIONS_DOC=true. -->

				Every Planning Analytics 2.1 release from the oldest supported, with the TM1 build it installs, the \
				schema version its `$metadata` reports, and the document it serves, committed as \
				`java/tm1-client/src/model/metadata/tm1-schema-<document>.xml`. Several releases can share a build, \
				and builds can share a schema version yet serve different documents. How the clients fare on each \
				release is in the [README](../README.md#supported-tm1-versions).

				| Planning Analytics | TM1 | Schema | `$metadata` document |
				|---|---|---|---|
				""");
		releases.forEach(r -> out.append("| ")
			.append(r.planningAnalytics())
			.append(" | ")
			.append(r.tm1())
			.append(" | ")
			.append(r.schema())
			.append(" | ")
			.append(r.document())
			.append(" |\n"));
		out.append("""

				## What each document changes

				Against the document before it, as the elements each defines (types, properties, navigations, \
				operations and their parameters, enum members, entity sets and imports). A document that adds and \
				removes nothing writes the same elements differently.
				""");
		Map<String, Release> firsts = new LinkedHashMap<>();
		releases.forEach(r -> firsts.putIfAbsent(r.document(), r));
		List<Release> order = new ArrayList<>(firsts.values());
		for (int i = 1; i < order.size(); i++) {
			Release before = order.get(i - 1);
			Release r = order.get(i);
			NavigableSet<String> was = signatures(before.document());
			NavigableSet<String> is = signatures(r.document());
			NavigableSet<String> added = new TreeSet<>(is);
			added.removeAll(was);
			NavigableSet<String> removed = new TreeSet<>(was);
			removed.removeAll(is);
			out.append("\n### ")
				.append(r.document())
				.append(" (from Planning Analytics ")
				.append(r.planningAnalytics())
				.append(", TM1 ")
				.append(r.tm1())
				.append("), against ")
				.append(before.document())
				.append("\n\n");
			if (added.isEmpty() && removed.isEmpty()) {
				out.append("Nothing added or removed.\n");
				continue;
			}
			section(out, "Added", added);
			section(out, "Removed", removed);
		}
		// One blank line between blocks, and one newline at the end
		return out.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n";
	}

	private static void section(StringBuilder out, String title, NavigableSet<String> lines) {
		if (lines.isEmpty()) {
			return;
		}
		out.append(title).append(" (").append(lines.size()).append("):\n\n");
		lines.forEach(s -> out.append("- `").append(s).append("`\n"));
		out.append('\n');
	}

	private static NavigableSet<String> signatures(String document) {
		return Signatures.of(CsdlParser.parse(MetadataSnapshot.text(document).orElseThrow()));
	}

	private static List<Release> releases() {
		try (InputStream in = MetadataReleasesTest.class.getResourceAsStream("/metadata/tm1-releases.json")) {
			JsonNode root = new ObjectMapper().readTree(in);
			List<Release> out = new ArrayList<>();
			for (JsonNode r : root.path("releases")) {
				out.add(new Release(r.path("planningAnalytics").asString(), Tm1Version.parse(r.path("tm1").asString()),
						r.path("schema").asString(), r.path("document").asString()));
			}
			return out;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
