package io.skipcheck.tm1.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Capability;

class Tm1OperationTest {

	@Test
	void aBoundOperationNeedsItsBindingAndOperation() {
		Tm1Operation activate = new Tm1Operation(Tm1Operation.Kind.ACTION, "tm1.Activate", "tm1.Chore", null, List.of(),
				null, "10.2.2.1", "11.8.3");
		assertThat(activate.bound()).isTrue();
		assertThat(activate.deprecated()).isTrue();
		assertThat(activate.endpoint()).isEqualTo("Action Chore/Activate");
		assertThat(activate.capability())
			.isEqualTo(Tm1Capability.boundOperation("tm1.Chore", "tm1.Activate").since("10.2.2.1"));
	}

	@Test
	void anUnboundOperationNeedsItsImport() {
		Tm1Operation mdx = new Tm1Operation(Tm1Operation.Kind.ACTION, "tm1.ExecuteMDX", null, "ExecuteMDX",
				List.of(new Tm1Operation.Parameter("MDX", "Edm.String", false)), "tm1.Cellset", null, null);
		assertThat(mdx.bound()).isFalse();
		assertThat(mdx.endpoint()).isEqualTo("ActionImport ExecuteMDX");
		assertThat(mdx.capability()).isEqualTo(Tm1Capability.unboundOperation("ExecuteMDX"));
	}

	@Test
	void needsExactlyOneOfABindingAndAnImport() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> new Tm1Operation(Tm1Operation.Kind.FUNCTION, "tm1.F", null, null, List.of(), null, null, null));
		assertThatIllegalArgumentException().isThrownBy(() -> new Tm1Operation(Tm1Operation.Kind.FUNCTION, "tm1.F",
				"tm1.Cube", "F", List.of(), null, null, null));
	}

}
