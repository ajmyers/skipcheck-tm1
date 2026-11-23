// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.testcontainers.PawContainer;
import io.skipcheck.tm1.testcontainers.PawSource;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Examples for {@link PawContainer}, quoted in its Javadoc. Test code, so it may block.
 */
final class PawContainerExamples {

	String start(Tm1Source tm1Source, PawSource pawSource) {
		// @start region = start
		try (var tm1 = new Tm1ServerContainer(tm1Source);
				var paw = new PawContainer(pawSource).withTm1Server(tm1).acceptLicense()) {
			paw.start(); // starts tm1 first
			return paw.getUrl(); // http://localhost:54321/
		}
		// @end
	}

}
