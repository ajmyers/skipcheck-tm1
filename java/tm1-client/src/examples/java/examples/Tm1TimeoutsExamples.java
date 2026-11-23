// SPDX-License-Identifier: Apache-2.0

package examples;

import java.time.Duration;

import io.skipcheck.tm1.client.session.Tm1Timeouts;

/**
 * Examples for {@link Tm1Timeouts}, quoted in its Javadoc.
 */
final class Tm1TimeoutsExamples {

	Tm1Timeouts longRequests() {
		// @start region = long-requests
		// Let a request go two hours without data, for a long process run synchronously
		Tm1Timeouts timeouts = Tm1Timeouts.builder().request(Duration.ofHours(2)).build();
		// @end
		return timeouts;
	}

}
