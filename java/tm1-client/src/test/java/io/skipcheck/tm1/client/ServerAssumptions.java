// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.skipcheck.tm1.client.metadata.MetadataSnapshot;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * What a live test needs of the server it runs against, so the suite runs against every
 * supported TM1: a test of what an older server doesn't define is skipped there, not
 * failed. The server's own {@code $metadata} decides, as the client decides.
 */
public final class ServerAssumptions {

	private static final Duration WAIT = Duration.ofSeconds(30);

	/**
	 * From this TM1 the first Git pull after the remote moves plans the commit TM1 had,
	 * and the next the new one; before, the first plans the new one. Tried on every
	 * Planning Analytics 2.1 release: 11.8.03200.3 (2.1.13 to 2.1.16) plans the new one,
	 * 11.8.03400.2 (2.1.17) the stale, and no build between them was released.
	 */
	public static final Tm1Version STALE_FIRST_PULL_FROM = Tm1Version.parse("11.8.03400");

	/**
	 * From this TM1 the server makes a local key file itself, so the image's settings
	 * check as valid. Tried at 11.8.035 (invalid) and 11.8.040 (valid); a build between
	 * falls on the older side unverified.
	 */
	public static final Tm1Version MAKES_OWN_KEY_FROM = Tm1Version.parse("11.8.04000");

	private static final Pattern SCHEMA_VERSION = Pattern
		.compile("Term=\"Core\\.SchemaVersion\">\\s*<String>([^<]+)</String>");

	private ServerAssumptions() {
	}

	/** The server's capabilities, as the client reads them. */
	public static Tm1Capabilities capabilities(Tm1Client tm1) {
		return tm1.session().capabilities().block(WAIT);
	}

	/** Skips the rest of the test unless the server defines the operation. */
	public static void assumeSupports(Tm1Client tm1, Tm1Operation operation) {
		Tm1Capabilities capabilities = capabilities(tm1);
		assumeTrue(capabilities.supports(operation.capability()),
				() -> describe(capabilities) + " doesn't define " + operation.endpoint());
	}

	/** Skips the rest of the test unless the server's operation has the parameter. */
	public static void assumeSupportsParameter(Tm1Client tm1, Tm1Operation operation, String parameter) {
		Tm1Capabilities capabilities = capabilities(tm1);
		assumeTrue(capabilities.supportsParameter(operation.capability(), parameter),
				() -> describe(capabilities) + " has no parameter " + parameter + " on " + operation.endpoint());
	}

	/** The server's product version. */
	public static Tm1Version productVersion(Tm1Client tm1) {
		return capabilities(tm1).productVersion().orElseThrow();
	}

	/** Whether the server's product version is at least the version. */
	public static boolean isAtLeast(Tm1Client tm1, Tm1Version version) {
		return productVersion(tm1).isAtLeast(version);
	}

	/**
	 * The committed snapshot of the server's own schema version: its first document,
	 * whose elements a later document of the schema has too ({@code MetadataSnapshotTest}
	 * holds them to it). Every supported version has one; a server of a schema without
	 * one (a newer TM1) skips the rest of the test, as {@code MetadataDriftTm1Test}
	 * passes it by.
	 */
	public static CsdlDocument ownSnapshot(Tm1Capabilities capabilities) {
		String schema = capabilities.schemaVersion().orElseThrow();
		Optional<String> text = MetadataSnapshot.text(schema);
		assumeTrue(text.isPresent(), () -> "No metadata snapshot is committed for schema " + schema);
		return CsdlParser.parse(text.get());
	}

	/**
	 * The schema version a {@code $metadata} document's {@code Core.SchemaVersion}
	 * annotation gives, read from its text rather than by the client's parser.
	 */
	public static String schemaVersionIn(String metadata) {
		Matcher m = SCHEMA_VERSION.matcher(metadata);
		if (!m.find()) {
			throw new IllegalStateException("$metadata has no Core.SchemaVersion annotation");
		}
		return m.group(1).strip();
	}

	private static String describe(Tm1Capabilities capabilities) {
		return "the server (schema " + capabilities.schemaVersion().orElse("?") + ", product version "
				+ capabilities.productVersion().map(Object::toString).orElse("?") + ")";
	}

}
