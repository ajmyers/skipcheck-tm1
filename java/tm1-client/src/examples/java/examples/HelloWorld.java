// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;

/**
 * The quick start's example on tm1-client's landing page. The live tests run
 * {@link #hello} against a new tm1-server and record what it prints (HelloWorldTm1Test),
 * which the page shows under the code.
 */
final class HelloWorld {

	// @start region = hello-world
	public static void main(String[] args) {
		Tm1Client tm1 = Tm1Client.builder()
			.baseUrl("http://localhost:8010/api/v1/")
			.basicAuth("admin", "") // tm1-server's default login
			.build();
		// block() is fine in a main(); in an application, return the Mono instead
		hello(tm1).then(tm1.close()).block();
	}

	static Mono<Void> hello(Tm1Client tm1) {
		return tm1.session()
			.version()
			.doOnNext(version -> System.out.println("Hello from TM1 " + version))
			.thenMany(tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)))
			.doOnNext(cube -> System.out.println("  " + cube.name()))
			.then();
	}
	// @end

	private HelloWorld() {
	}

}
