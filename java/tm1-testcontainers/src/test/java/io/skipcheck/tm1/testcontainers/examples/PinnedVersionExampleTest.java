// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/**
 * <b>Making sure tests run against the TM1 version you mean.</b>
 *
 * <p>
 * Two ways, depending on what a wrong version means for you:
 *
 * <ul>
 * <li><b>It's a mistake:</b> {@code withExpectedVersion} makes the container refuse to
 * start, with a clear message, before any test runs. Use it when tests must run on one
 * version, such as reproducing a bug customers see on 2.1.19.
 * <li><b>The test only applies to some versions:</b> check {@code getTm1Version()} and
 * skip. Use it for behavior that was added or changed in a TM1 release.
 * </ul>
 */
@RequiresTm1
class PinnedVersionExampleTest {

	// The version this example pins to. Change it to yours.
	private static final Tm1Version PA_2_1_19 = Tm1Version.parse("11.8.03500.6");

	@Test
	void refusesToStartOnAnyOtherVersion(Tm1ServerContainer shared) {
		assumeTrue(shared.getTm1Version().equals(PA_2_1_19), "this example pins to " + PA_2_1_19);

		try (var server = new Tm1ServerContainer(shared.getSource()).withExpectedVersion(PA_2_1_19)) {
			server.start(); // throws "Expected TM1 11.8.03500.6 but the server from ...
							// reports ..." on a mismatch
			assertThat(server.getTm1Version()).isEqualTo(PA_2_1_19);
		}
	}

	@Test
	void skipsWhereTheFeatureDoesntExist(Tm1ServerContainer tm1) {
		// Say the behavior under test first appeared in this (made-up) release
		Tm1Version introducedIn = Tm1Version.parse("11.8.03000");
		assumeTrue(tm1.getTm1Version().isAtLeast(introducedIn), "needs TM1 " + introducedIn + " or newer");

		assertThat(tm1.getTm1Version()).isGreaterThanOrEqualTo(introducedIn); // ...then
																				// test
																				// the
																				// behavior
	}

}
