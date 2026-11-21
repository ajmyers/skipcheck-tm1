package net.ajmyers.tm1.client.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import net.ajmyers.tm1.client.odata.csdl.CsdlDocument;
import net.ajmyers.tm1.client.odata.csdl.CsdlParser;

/**
 * The committed snapshot of TM1's {@code $metadata}: the response exactly as the server sent
 * it, IBM's descriptions included, so it can be compared with a live server byte for byte.
 * {@link MetadataSnapshotUpdateTest} writes it.
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
		try (InputStream in = MetadataSnapshot.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("The metadata snapshot " + RESOURCE + " is missing");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
