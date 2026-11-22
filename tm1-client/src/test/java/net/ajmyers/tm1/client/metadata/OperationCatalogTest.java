package net.ajmyers.tm1.client.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.NavigableSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.version.Tm1Capabilities;

/** The generated operation catalog matches the snapshot the coverage gate reads. */
class OperationCatalogTest {

	@Test
	void hasOneDescriptorPerOperationEndpointOfTheCoverageGate() {
		NavigableSet<String> operations = new TreeSet<>(Endpoints.of(MetadataSnapshot.load()));
		operations.removeIf(e -> !e.matches("(Action|Function|ActionImport|FunctionImport) .*"));
		assertThat(Tm1Operations.ALL).extracting(Tm1Operation::endpoint)
			.doesNotHaveDuplicates()
			.containsExactlyInAnyOrderElementsOf(operations)
			.hasSize(105);
	}

	@Test
	void everyCapabilityIsOneTheSnapshotHas() {
		Tm1Capabilities snapshot = Tm1Capabilities.of(MetadataSnapshot.load(), null);
		assertThat(Tm1Operations.ALL)
			.allSatisfy(op -> assertThat(snapshot.supports(op.capability())).as("%s", op.endpoint()).isTrue());
	}

	@Test
	@SuppressWarnings("deprecation")
	void carriesRevisionsParametersAndReturnTypes() {
		assertThat(Tm1Operations.CHORE_ACTIVATE.deprecatedIn()).isEqualTo("11.8.3");
		assertThat(Tm1Operations.EXECUTE_MDX).satisfies(op -> {
			assertThat(op.kind()).isEqualTo(Tm1Operation.Kind.ACTION);
			assertThat(op.importName()).isEqualTo("ExecuteMDX");
			assertThat(op.parameters()).containsExactly(new Tm1Operation.Parameter("MDX", "Edm.String", false));
			assertThat(op.returnType()).isEqualTo("tm1.Cellset");
			assertThat(op.addedIn()).isEqualTo("10.2.2.1");
		});
		assertThat(Tm1Operations.ALL).filteredOn(Tm1Operation::deprecated).isNotEmpty();
	}

}
