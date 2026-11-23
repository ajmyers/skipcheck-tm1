// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.version.Tm1Capabilities;
import io.skipcheck.tm1.client.version.Tm1Capability;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link Tm1Capability} and {@link Tm1Capabilities}, quoted in their
 * Javadoc.
 */
final class Tm1CapabilitiesExamples {

	Mono<Boolean> supports(Tm1Session session) {
		// @start region = supports
		// Does the server's $metadata define the cube's tm1.Reserve action?
		Tm1Capability reserve = Tm1Capability.boundOperation("tm1.Cube", "tm1.Reserve").since("11.8.34");
		return session.capabilities().map(capabilities -> capabilities.supports(reserve));
		// @end
	}

	private Tm1CapabilitiesExamples() {
	}

}
