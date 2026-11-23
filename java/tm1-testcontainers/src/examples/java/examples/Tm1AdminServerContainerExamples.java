// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.testcontainers.Tm1AdminServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Examples for {@link Tm1AdminServerContainer}, quoted in its Javadoc. Test code, so it
 * may block.
 */
final class Tm1AdminServerContainerExamples {

	String severalServers(Tm1Source tm1Source) {
		// @start region = several-servers
		try (var admin = new Tm1AdminServerContainer(tm1Source);
				var planning = new Tm1ServerContainer(tm1Source).withEnv("TM1_SERVER_NAME", "planning")
					.withAdminServer(admin);
				var reporting = new Tm1ServerContainer(tm1Source).withEnv("TM1_SERVER_NAME", "reporting")
					.withAdminServer(admin)) {
			planning.start();
			reporting.start();
			return admin.getRestUrl() + "Servers"; // lists planning and reporting
		}
		// @end
	}

}
