// SPDX-License-Identifier: Apache-2.0

import com.sun.net.httpserver.HttpServer;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the published libraries, as an application that got them from Maven Central would: the
 * guide's Hello World against a fake TM1 on localhost, and a class and a resource of each other
 * module. Its argument is the repository's root. See package-tests/README.md.
 */
public final class Smoke {

	public static void main(String[] args) throws Exception {
		byte[] snapshot = Files.readAllBytes(Path.of(args[0], "java", "tm1-client", "src", "model", "metadata", "tm1-schema-11.8.35.xml"));

		// Answers what Hello World asks: a login, the version, $metadata and the cubes
		List<String> requests = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
			requests.add(exchange.getRequestMethod() + " " + path);
			exchange.getRequestBody().readAllBytes();
			byte[] body = new byte[0];
			int status = 200;
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			if (exchange.getRequestMethod().equals("POST")) {
				status = 204;
			}
			else if (path.startsWith("/api/v1/ActiveSession")) {
				exchange.getResponseHeaders().set("Set-Cookie", "TM1SessionId=s1; Path=/api/; HttpOnly");
				body = "{\"ID\":1}".getBytes(StandardCharsets.UTF_8);
			}
			else if (path.startsWith("/api/v1/Server")) {
				body = "{\"ProductVersion\":\"11.8.03500.6\"}".getBytes(StandardCharsets.UTF_8);
			}
			else if (path.equals("/api/v1/$metadata")) {
				exchange.getResponseHeaders().set("Content-Type", "application/xml");
				body = snapshot;
			}
			else if (path.startsWith("/api/v1/ModelCubes()") || path.startsWith("/api/v1/Cubes")) {
				body = "{\"value\":[{\"Name\":\"Sales\"},{\"Name\":\"Plan\"}]}".getBytes(StandardCharsets.UTF_8);
			}
			else {
				status = 404;
			}
			exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
			if (status != 204) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();

		// The guide's Hello World (java/tm1-client/src/examples/java/examples/HelloWorld.java)
		String version;
		List<String> cubes;
		try {
			Tm1Client tm1 = Tm1Client.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/").basicAuth("admin", "").build();
			version = String.valueOf(tm1.session().version().block());
			cubes = tm1.cubes().modelCubes(ODataQuery.none().select(Cube.NAME)).map(Cube::name).collectList().block();
			tm1.close().block();
		}
		finally {
			server.stop(0);
		}
		require(version.startsWith("11.8"), version);
		require(List.of("Sales", "Plan").equals(cubes), String.valueOf(cubes));
		require(requests.stream().anyMatch(r -> r.startsWith("POST /api/v1/ActiveSession/tm1.Close")), String.join("\n", requests));

		// The other modules: a class of each loads, with what it depends on, and what they ship is there
		ClassLoader loader = Smoke.class.getClassLoader();
		for (String name : List.of("io.skipcheck.tm1.csdl.CsdlParser", "io.skipcheck.tm1.spring.boot.autoconfigure.Tm1AutoConfiguration",
				"io.skipcheck.tm1.spring.boot.testcontainers.Tm1ContainerConnectionDetailsFactory")) {
			Class.forName(name, true, loader);
		}
		require(Tm1ServerContainer.class.getResource("paw-local.sh") != null, "paw-local.sh isn't in tm1-testcontainers' jar");
		try (var imports = loader.getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
			require(imports != null && new String(imports.readAllBytes(), StandardCharsets.UTF_8).contains("Tm1AutoConfiguration"),
					"the starter's jar doesn't register Tm1AutoConfiguration");
		}
		String jar = Tm1Client.class.getProtectionDomain().getCodeSource().getLocation().toString();
		require(jar.endsWith(".jar"), "Tm1Client comes from " + jar + ", not from a published jar");
		try (var jarFile = new java.util.jar.JarFile(Path.of(java.net.URI.create(jar)).toFile())) {
			require(jarFile.getEntry("META-INF/LICENSE") != null && jarFile.getEntry("META-INF/NOTICE") != null, jar + " doesn't carry LICENSE and NOTICE");
		}
		System.out.println("ok: Hello from TM1 " + version + ", cubes " + cubes + ", from " + jar.substring(jar.lastIndexOf('/') + 1));
		// Reactor Netty keeps threads of its own
		System.exit(0);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
	}

	private Smoke() {
	}

}
