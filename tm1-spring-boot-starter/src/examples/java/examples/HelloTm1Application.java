package examples;

// @start region = hello-app
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.odata.ODataQuery;

@SpringBootApplication
public class HelloTm1Application {

	public static void main(String[] args) {
		SpringApplication.run(HelloTm1Application.class, args);
	}

	// The starter built the client from the tm1 settings: just ask for it
	@Bean
	ApplicationRunner hello(Tm1Client tm1) {
		return args -> tm1.session()
			.version()
			.doOnNext(version -> System.out.println("Hello from TM1 " + version))
			.thenMany(tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)))
			.doOnNext(cube -> System.out.println("  " + cube.name()))
			.then()
			.block(); // fine at startup; in a web request, return the Flux instead
	}

}
// @end
