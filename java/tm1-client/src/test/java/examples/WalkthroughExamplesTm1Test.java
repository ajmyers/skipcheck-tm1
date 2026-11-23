// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1Seed;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Runs the walkthroughs' examples (ConnectWalkthrough and the rest) against IBM's SData
 * sample, and records what each step prints for the docs, which show it under the step's
 * code ({@link ExampleOutput}). Run the live tests after changing a walkthrough's
 * example, and commit the recordings in api-site/example-output with it.
 *
 * <p>
 * The walkthroughs are written for SData, so these start a server of their own with it
 * rather than use the shared, empty one. Every language's tests do the same, so they
 * record the same output.
 */
@RequiresTm1
class WalkthroughExamplesTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1ServerContainer sdata;

	private static Tm1Client tm1;

	@BeforeAll
	static void start() {
		sdata = new Tm1ServerContainer(Tm1Source.discover().orElseThrow()).withSeed(Tm1Seed.SDATA);
		sdata.start();
		tm1 = Tm1Client.builder()
			.baseUrl(sdata.getRestUrl())
			.basicAuth(sdata.getUsername(), sdata.getPassword())
			.build();
	}

	@AfterAll
	static void stop() {
		if (tm1 != null) {
			tm1.close().block(WAIT);
		}
		if (sdata != null) {
			sdata.stop();
		}
	}

	@Test
	void connectsAndListsCubes() {
		try (ExampleOutput output = new ExampleOutput("tm1-client", "ConnectWalkthrough")) {
			output.record("version", () -> ConnectWalkthrough.printVersion(tm1));
			output.record("list-cubes", () -> ConnectWalkthrough.listCubes(tm1));
			assertThat(output.output("version")).containsExactly("Connected to TM1 " + sdata.getTm1Version());
			assertThat(output.output("list-cubes")).contains("SalesCube", "PriceCube");
		}
	}

	@Test
	void readsAndWritesCells() {
		try (ExampleOutput output = new ExampleOutput("tm1-client", "CellsWalkthrough")) {
			output.record("read", () -> CellsWalkthrough.read(tm1));
			output.record("write", () -> CellsWalkthrough.write(tm1));
			output.record("read-back", () -> CellsWalkthrough.readBack(tm1));
			assertThat(output.output("read")).hasSize(3);
			assertThat(output.output("read-back")).containsExactly("Budget for Jan: 150");
		}
	}

	@Test
	void runsAProcessAndReadsItsResult() {
		try (ExampleOutput output = new ExampleOutput("tm1-client", "ProcessWalkthrough")) {
			output.record("create", () -> ProcessWalkthrough.create(tm1));
			try {
				output.record("run", () -> ProcessWalkthrough.run(tm1));
				output.record("check", () -> ProcessWalkthrough.check(tm1));
				output.record("fail", () -> ProcessWalkthrough.fail(tm1));
			}
			finally {
				output.record("delete", () -> ProcessWalkthrough.delete(tm1));
			}
			assertThat(output.output("run")).containsExactly("Status: CompletedSuccessfully");
			assertThat(output.output("fail").get(0)).isNotEqualTo("Status: CompletedSuccessfully");
		}
	}

}
