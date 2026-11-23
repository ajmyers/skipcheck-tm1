// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.version.Tm1Version;

/**
 * The walkthrough "Connect and list your cubes"
 * (api-site/content/walkthroughs/tm1-client). The live tests run its steps against IBM's
 * SData sample and record what they print (WalkthroughExamplesTm1Test).
 */
public final class ConnectWalkthrough {

	public static void main(String[] args) {
		Tm1Client tm1 = connect();
		try {
			printVersion(tm1);
			listCubes(tm1);
		}
		finally {
			close(tm1);
		}
	}

	static Tm1Client connect() {
		// @start region = connect
		// The SData sample's address and login
		Tm1Client tm1 = Tm1Client.builder()
			.baseUrl("http://localhost:8011/api/v1/")
			.basicAuth("admin", "apple")
			.build();
		// @end
		return tm1;
	}

	static void printVersion(Tm1Client tm1) {
		// @start region = version
		// Each call returns a Mono or a Flux, which does nothing until asked:
		// block() waits for its result, which is fine in a main()
		Tm1Version version = tm1.session().version().block();
		System.out.println("Connected to TM1 " + version);
		// @end
	}

	static void listCubes(Tm1Client tm1) {
		// @start region = list-cubes
		// The cubes you made, without TM1's control cubes; select asks for
		// their names alone
		for (Cube cube : tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)).toIterable()) {
			System.out.println(cube.name());
		}
		// @end
	}

	static void close(Tm1Client tm1) {
		// @start region = close
		// Ends the session on the server
		tm1.close().block();
		// @end
	}

	private ConnectWalkthrough() {
	}

}
