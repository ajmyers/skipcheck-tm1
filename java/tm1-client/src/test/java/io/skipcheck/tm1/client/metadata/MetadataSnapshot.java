// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * The committed snapshot of TM1's {@code $metadata}: the response exactly as the server
 * sent it, IBM's descriptions included, so it can be compared with a live server byte for
 * byte. {@link MetadataSnapshotUpdateTest} writes it.
 *
 * <p>
 * The model is generated from the newest, {@link #VERSION}. Older schema versions'
 * snapshots are committed beside it (one per schema version: servers reporting the same
 * schema have served the same document), so {@link MetadataDriftTest} can hold a server
 * of an older supported version to its own snapshot too.
 */
public final class MetadataSnapshot {

	/** The schema version of the committed snapshot. */
	public static final String VERSION = "11.8.35";

	/** The snapshot's location, as a test resource. */
	public static final String RESOURCE = "/metadata/tm1-schema-" + VERSION + ".xml";

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
	 * The committed snapshot of a schema version, if there is one.
	 * @param schemaVersion the schema version, such as {@code 11.8.20}
	 * @return its text, or empty if none is committed
	 */
	public static Optional<String> text(String schemaVersion) {
		try (InputStream in = MetadataSnapshot.class
			.getResourceAsStream("/metadata/tm1-schema-" + schemaVersion + ".xml")) {
			return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
