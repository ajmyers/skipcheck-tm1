// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;

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

	/**
	 * The committed snapshot of the server's own schema version: every supported version
	 * has one.
	 */
	public static CsdlDocument ownSnapshot(Tm1Capabilities capabilities) {
		String schema = capabilities.schemaVersion().orElseThrow();
		return CsdlParser.parse(MetadataSnapshot.text(schema)
			.orElseThrow(() -> new IllegalStateException("No metadata snapshot for schema " + schema)));
	}

	private static String describe(Tm1Capabilities capabilities) {
		return "the server (schema " + capabilities.schemaVersion().orElse("?") + ", product version "
				+ capabilities.productVersion().map(Object::toString).orElse("?") + ")";
	}

}
