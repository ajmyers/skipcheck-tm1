// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * The committed snapshot of TM1's {@code $metadata}: the response exactly as the server
 * sent it, IBM's descriptions included, so it can be compared with a live server byte for
 * byte. {@link MetadataSnapshotUpdateTest} writes it.
 *
 * <p>
 * The model is generated from the newest, {@link #VERSION}. Every document a supported
 * release serves is committed beside it, so {@link MetadataDriftTm1Test} can hold a
 * server of any of them to its own: {@code tm1-schema-<schema>.xml}, the first document
 * of each schema version, and {@code tm1-schema-<schema>-<build>.xml} for a later one
 * ({@link #LATER_DOCUMENTS}).
 */
public final class MetadataSnapshot {

	/**
	 * The schema version of the newest committed snapshot, the one the model is generated
	 * from. {@code MetadataSnapshotTest} checks it's the highest committed.
	 */
	public static final String VERSION = "11.8.35";

	/** The newest snapshot's location, as a test resource. */
	public static final String RESOURCE = resource(VERSION);

	/**
	 * Schema versions whose servers have served more than one document, each later one
	 * named by the first TM1 build that serves it, and committed as
	 * {@code tm1-schema-<schema>-<build>.xml}. A server's document is the latest whose
	 * build it is at least. {@code MetadataSnapshotTest} holds each to the same elements
	 * as its schema's first document, so what a schema defines is read from that one.
	 *
	 * <p>
	 * 11.8.02900.8 (Planning Analytics 2.1.8, and on to 2.1.12) reports schema 11.8.20,
	 * as 11.8.02400 to 11.8.02800 (2.1.2 to 2.1.7) do, and serves it otherwise written:
	 * {@code Core.Ordered} on collections, and boolean annotations without their values.
	 */
	public static final Map<String, List<String>> LATER_DOCUMENTS = Map.of("11.8.20", List.of("11.8.02900.8"));

	private MetadataSnapshot() {
	}

	/** The committed snapshot, parsed. */
	public static CsdlDocument load() {
		return CsdlParser.parse(text());
	}

	/** The committed snapshot's text. */
	public static String text() {
		return text(VERSION)
			.orElseThrow(() -> new IllegalStateException("The metadata snapshot " + RESOURCE + " is missing"));
	}

	/**
	 * The committed snapshot of a schema version (its first document), if there is one.
	 * @param schemaVersion the schema version, such as {@code 11.8.20}, or a later
	 * document's name, such as {@code 11.8.20-11.8.02900.8}
	 * @return its text, or empty if none is committed
	 */
	public static Optional<String> text(String schemaVersion) {
		try (InputStream in = MetadataSnapshot.class.getResourceAsStream(resource(schemaVersion))) {
			return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The committed snapshot of what a server of this schema and build serves: the latest
	 * of the schema's {@link #LATER_DOCUMENTS} whose build it is at least, else the
	 * schema's first document.
	 * @param schemaVersion the schema version, such as {@code 11.8.20}
	 * @param productVersion the server's build, such as {@code 11.8.02900.8}
	 * @return its text, or empty if none is committed for the schema
	 */
	public static Optional<String> text(String schemaVersion, Tm1Version productVersion) {
		Optional<String> build = LATER_DOCUMENTS.getOrDefault(schemaVersion, List.of())
			.stream()
			.filter(b -> productVersion.isAtLeast(Tm1Version.parse(b)))
			.max(Comparator.comparing(Tm1Version::parse));
		return build.isPresent() ? text(schemaVersion + "-" + build.get()) : text(schemaVersion);
	}

	private static String resource(String schemaVersion) {
		return "/metadata/tm1-schema-" + schemaVersion + ".xml";
	}

}
