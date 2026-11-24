// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PropertyNameOracleTest {

	private static final Set<String> PROCESS = Set.of("Name", "UIData");

	private static FakeTm1.Reply process(String target) {
		String name = target.substring(target.indexOf("$select=") + "$select=".length());
		return PROCESS.contains(name) ? FakeTm1.Reply.ok("{}") : FakeTm1.Reply.refused();
	}

	@Test
	void acceptsWhatTheServerAcceptsInASelect() throws Exception {
		try (FakeTm1 tm1 = new FakeTm1(PropertyNameOracleTest::process)) {
			PropertyNameOracle oracle = new PropertyNameOracle(new ProbeClient(tm1.url(), "Basic x"), 2);
			assertThat(oracle.checks("Processes('p')", "Name")).isTrue();
			assertThat(oracle.accepted("Processes('p')", List.of("UIData", "Bogus", "Name", "Alias")))
				.containsExactly("Name", "UIData");
		}
	}

	@Test
	void sendsAThousandNamesToABatch() throws Exception {
		try (FakeTm1 tm1 = new FakeTm1(PropertyNameOracleTest::process)) {
			PropertyNameOracle oracle = new PropertyNameOracle(new ProbeClient(tm1.url(), "Basic x"), 3);
			List<String> names = new ArrayList<>(List.of("UIData"));
			for (int i = 0; i < 2500; i++) {
				names.add("Candidate" + i);
			}
			assertThat(oracle.accepted("Processes?$top=1", names)).containsExactly("UIData");
			assertThat(tm1.batches).hasValue(3);
		}
	}

	@Test
	void aPathWhereEveryNameIsAcceptedIsNotChecked() throws Exception {
		try (FakeTm1 tm1 = new FakeTm1(t -> FakeTm1.Reply.ok("{}"))) {
			PropertyNameOracle oracle = new PropertyNameOracle(new ProbeClient(tm1.url(), "Basic x"), 1);
			assertThat(oracle.checks("Cubes('c')/Views('v')", "Name")).isFalse();
		}
	}

	@Test
	void aPathThatRefusesEvenADeclaredNameIsNotChecked() throws Exception {
		try (FakeTm1 tm1 = new FakeTm1(t -> FakeTm1.Reply.refused())) {
			PropertyNameOracle oracle = new PropertyNameOracle(new ProbeClient(tm1.url(), "Basic x"), 1);
			assertThat(oracle.checks("GitPlans?$top=1", "ID")).isFalse();
		}
	}

}
