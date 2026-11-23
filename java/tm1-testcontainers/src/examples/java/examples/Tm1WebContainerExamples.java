// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.testcontainers.Tm1Seed;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;
import io.skipcheck.tm1.testcontainers.Tm1WebContainer;
import io.skipcheck.tm1.testcontainers.Tm1WebSource;

/**
 * Examples for {@link Tm1WebContainer}, quoted in its Javadoc. Test code, so it may
 * block.
 */
final class Tm1WebContainerExamples {

	String start(Tm1Source tm1Source, Tm1WebSource webSource) {
		// @start region = start
		try (var tm1 = new Tm1ServerContainer(tm1Source).withSeed(Tm1Seed.SDATA);
				var web = new Tm1WebContainer(webSource).withTm1Server(tm1)) {
			web.start(); // starts tm1 first
			return web.getUrl(); // http://localhost:54321/tm1web/
		}
		// @end
	}

}
