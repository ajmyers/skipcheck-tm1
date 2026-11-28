// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.AdministrationSettings;
import io.skipcheck.tm1.client.model.LogLevel;
import io.skipcheck.tm1.client.model.Server;
import io.skipcheck.tm1.client.model.ServerSettings;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.server.ServerService}, quoted in
 * its Javadoc.
 */
@SuppressWarnings("deprecation") // Server is deprecated in TM1 schema 12.0.0; active on
									// TM1 11.8 and 12
final class ServerServiceExamples {

	Mono<String> version(Tm1Client tm1) {
		// @start region = version
		return tm1.server()
			.server(ODataQuery.none().select(Server.NAME, Server.PRODUCT_VERSION))
			.map(server -> server.name() + " runs TM1 " + server.productVersion());
		// @end
	}

	Mono<String> dataDirectory(Tm1Client tm1) {
		// @start region = data-directory
		// What the server runs with now, which can differ from tm1s.cfg until it restarts
		return tm1.server()
			.activeConfiguration(ODataQuery.none())
			.mapNotNull(ServerSettings::administration)
			.mapNotNull(AdministrationSettings::dataBaseDirectory);
		// @end
	}

	Mono<Void> loggerLevel(Tm1Client tm1) {
		// @start region = logger-level
		// Takes effect at once, without a restart
		return tm1.server().setLoggerLevel("TM1.Process", LogLevel.DEBUG);
		// @end
	}

	private ServerServiceExamples() {
	}

}
