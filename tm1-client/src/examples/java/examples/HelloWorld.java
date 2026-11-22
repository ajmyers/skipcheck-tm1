package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * The Getting started example on tm1-client's overview page.
 */
final class HelloWorld {

	// @start region = hello-world
	public static void main(String[] args) {
		Tm1Client tm1 = Tm1Client.builder()
			.baseUrl("http://localhost:8010/api/v1/")
			.basicAuth("admin", "") // tm1-server's default login
			.build();

		tm1.session()
			.version()
			.doOnNext(version -> System.out.println("Hello from TM1 " + version))
			.thenMany(tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)))
			.doOnNext(cube -> System.out.println("  " + cube.name()))
			.then(tm1.close())
			.block(); // fine in a main(); in an application, return the Mono instead
	}
	// @end

	private HelloWorld() {
	}

}
