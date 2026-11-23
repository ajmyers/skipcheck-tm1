// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlOperation;

class ApproximateSchemasTest {

	@Test
	void theSnapshotsOwnVersionChangesNothing() {
		CsdlDocument same = ApproximateSchemas.snapshotAsOf(MetadataSnapshot.VERSION);
		assertThat(Signatures.of(same)).isEqualTo(Signatures.of(MetadataSnapshot.load()));
	}

	@Test
	void anOlderServerLacksWhatCameLater() {
		CsdlDocument v11820 = ApproximateSchemas.snapshotAsOf("11.8.20");
		assertThat(v11820.schemaVersion()).contains("11.8.20");
		// Cube.Reserve and Release arrived in 11.8.34; Load in 11.6.0
		assertThat(v11820.boundTo("tm1.Cube")).extracting(CsdlOperation::name)
			.contains("Load", "CheckRules")
			.doesNotContain("Reserve", "Release");
		// Encryption-at-rest configuration arrived in 11.8.35; data file encryption in
		// 11.3.0
		assertThat(v11820.entityContainer().orElseThrow().actionImports())
			.extracting(CsdlEntityContainer.ActionImport::name)
			.contains("DecryptDataFile")
			.doesNotContain("ConfigureEncryptionAtRest");
	}

	@Test
	void anOldServerLacksWholeAreas() {
		CsdlDocument v10 = ApproximateSchemas.snapshotAsOf("10.2.2.0");
		// Git support arrived in 11.5.0
		assertThat(v10.entityContainer().orElseThrow().entitySets()).extracting(CsdlEntityContainer.EntitySet::name)
			.contains("Cubes", "Dimensions")
			.doesNotContain("GitPlans");
		assertThat(Endpoints.of(v10)).hasSizeLessThan(Endpoints.of(MetadataSnapshot.load()).size());
	}

	@Test
	void neverDropsABindingParameter() {
		ApproximateSchemas.snapshotAsOf("10.2.2.0")
			.schemas()
			.forEach(s -> s.operations()
				.stream()
				.filter(CsdlOperation::bound)
				.forEach(op -> assertThat(op.bindingType()).as(op.name()).isPresent()));
	}

}
