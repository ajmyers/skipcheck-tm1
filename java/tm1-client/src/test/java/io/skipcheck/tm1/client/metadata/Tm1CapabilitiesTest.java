// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Capability;
import io.skipcheck.tm1.client.version.Tm1Version;

/**
 * Capability checks against the committed snapshot, and against approximate older schemas
 * made from it: the way to test version handling with one TM1 version.
 */
class Tm1CapabilitiesTest {

	private static final Tm1Capability RESERVE = Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve")
		.since("11.8.34");

	private final Tm1Capabilities current = Tm1Capabilities.of(MetadataSnapshot.load(),
			Tm1Version.parse("11.8.03500.6"));

	private final Tm1Capabilities older = Tm1Capabilities.of(ApproximateSchemas.snapshotAsOf("11.8.20"), null);

	@Test
	void findsEachKindOfElement() {
		assertThat(current.supports(Tm1Capability.entitySet("Cubes"))).isTrue();
		assertThat(current.supports(Tm1Capability.entitySet("Bananas"))).isFalse();
		assertThat(current.supports(Tm1Capability.singleton("ActiveSession"))).isTrue();
		assertThat(current.supports(Tm1Capability.singleton("Cubes"))).as("an entity set isn't a singleton").isFalse();
		assertThat(current.supports(Tm1Capability.unboundOperation("ExecuteMDX"))).isTrue();
		assertThat(current.supports(Tm1Capability.unboundOperation("ControlCubes"))).as("a function import").isTrue();
		assertThat(current.supports(RESERVE)).isTrue();
		assertThat(current.supports(Tm1Capability.boundOperation("ibm.tm1.api.v1.Cube", "ibm.tm1.api.v1.Reserve")))
			.as("by namespace rather than alias")
			.isTrue();
		assertThat(current.supports(Tm1Capability.boundOperation("tm1.Dimension", "tm1.CheckRules")))
			.as("bound elsewhere")
			.isFalse();
		assertThat(current.supports(Tm1Capability.property("tm1.Cube", "Rules"))).isTrue();
		assertThat(current.supports(Tm1Capability.property("tm1.Cube", "Views"))).as("a navigation property").isTrue();
		assertThat(current.supports(Tm1Capability.property("tm1.Cube", "Colour"))).isFalse();
	}

	@Test
	void findsOperationsAndPropertiesOfBaseTypes() {
		assertThat(current.supports(Tm1Capability.boundOperation("tm1.Folder", "tm1.Copy")))
			.as("Entry.Copy on a Folder")
			.isTrue();
		assertThat(current.supports(Tm1Capability.property("tm1.NativeView", "Name"))).as("View.Name on a NativeView")
			.isTrue();
	}

	@Test
	void reportsWhatAnOlderServerLacksComparingSchemaVersions() {
		assertThat(older.schemaVersion()).hasValue("11.8.20");
		assertThat(older.supports(RESERVE)).isFalse();
		assertThat(older.supports(Tm1Capability.entitySet("Cubes"))).isTrue();

		assertThat(older.check(RESERVE, "http://tm1:8010/api/v1/")).hasValueSatisfying(e -> {
			assertThat(e.missing()).isEqualTo("Cube.Reserve");
			assertThat(e.getMessage())
				.isEqualTo("Cube.Reserve isn't defined by the TM1 server at http://tm1:8010/api/v1/: "
						+ "it was added in schema 11.8.34, and the server reports schema 11.8.20");
			assertThat(e.mayHaveApplied()).isFalse();
		});
		assertThat(current.check(RESERVE, "x")).isEmpty();
	}

	@Test
	void saysWhatItKnowsWhenTheAddedVersionIsntRecorded() {
		assertThat(current.check(Tm1Capability.entitySet("Bananas"), "http://tm1:8010/api/v1/")).hasValueSatisfying(
				e -> assertThat(e.getMessage()).isEqualTo("entity set Bananas isn't defined by the TM1 server at "
						+ "http://tm1:8010/api/v1/ (the server reports schema 11.8.35, product version 11.8.03500.6)"));
	}

}
