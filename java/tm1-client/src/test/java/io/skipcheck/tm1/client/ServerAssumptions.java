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
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * What a live test needs of the server it runs against, so the suite runs against every
 * supported TM1: a test of what an older server doesn't define is skipped there, not
 * failed. The server's own {@code $metadata} decides, as the client decides.
 */
public final class ServerAssumptions {

	private static final Duration WAIT = Duration.ofSeconds(30);

	/**
	 * From this TM1 the first Git pull after the remote moves plans the commit TM1 had,
	 * and the next the new one; before, the first plans the new one. Tried at 11.8.032
	 * (the new) and 11.8.035 (the stale): a build between falls on the older side
	 * unverified, so a failure there is this bound to move.
	 */
	public static final Tm1Version STALE_FIRST_PULL_FROM = Tm1Version.parse("11.8.03500");

	/**
	 * Whether TM1 can write to its own install, which TM1 needs to make a local
	 * encryption key: validating local key file settings writes the key into the
	 * install's {@code bin64/ssl/ibmtm1.kdb}, and a read-only install checks as invalid
	 * ("Could not generate a new key using the local GSKit installation"), whatever the
	 * version (#439). The scenarios' {@code install} term names one.
	 */
	public enum Install {

		/**
		 * Its own install, writable: a bundle's, a volume's or an image's. Tried with a
		 * bundle install, a volume holding a copy of an installed folder, and the folder
		 * mounted read-write; a private image is taken to be the same, since tm1-server
		 * runs TM1 as root over the image's own layer.
		 */
		WRITABLE("writable"),

		/**
		 * An installed folder ({@code TM1_HOME_DIR}), which tm1-testcontainers mounts
		 * read-only.
		 */
		READ_ONLY("readOnly"),

		/** A server the suite didn't start. */
		UNKNOWN(null);

		private final String term;

		Install(String term) {
			this.term = term;
		}

		/**
		 * What the scenarios call it, {@code writable} or {@code readOnly}; null for an
		 * install no scenario names, which matches none.
		 */
		public String term() {
			return term;
		}

	}

	/**
	 * Whether the install of a TM1 started from the source is writable.
	 * @param source where its TM1 comes from
	 * @return read-only for an installed folder, else writable
	 */
	public static Install install(Tm1Source source) {
		return source instanceof Tm1Source.InstallDir ? Install.READ_ONLY : Install.WRITABLE;
	}

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
