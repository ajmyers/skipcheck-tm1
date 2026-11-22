package net.ajmyers.tm1.client.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.session.Tm1Session;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures JSON from the TM1 test server for {@link PayloadRoundTripTest}. Runs only when
 * asked:
 *
 * <pre>{@code
 * TM1_CAPTURE_PAYLOADS=true TM1_HOME_DIR=~/ibm/tm1_64 \
 *     ./gradlew :tm1-client:test --tests '*PayloadCaptureTest'
 * }</pre>
 *
 * <p>
 * It first builds fixtures with the generated patches (a dimension with a consolidation
 * and subsets, a cube with rules, values and an MDX view, a process with a parameter, a
 * chore), then saves what TM1 returns for the main entity types, as TM1 sent it but
 * indented, under {@code src/test/resources/payloads/<schema version>/}. The requests go
 * through the JDK's HTTP client, so nothing of the client under test touches the JSON.
 */
@RequiresTm1
@EnabledIfEnvironmentVariable(named = "TM1_CAPTURE_PAYLOADS", matches = "true")
class PayloadCaptureTest {

	static final String NAME = "RoundTrip";

	static final String MEASURE = "RoundTripMeasure";

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	@Test
	void capturesPayloads(Tm1ServerContainer tm1) throws Exception {
		Tm1Session session = Tm1Session.builder()
			.baseUrl(tm1.getRestUrl())
			.basicAuth(tm1.getUsername(), tm1.getPassword())
			.build();
		try {
			createFixtures(session);
		}
		finally {
			session.close().block(WAIT);
		}

		// Tests run with the module folder as the working directory
		Path dir = Path.of("src/test/resources/payloads/" + schemaVersion(tm1));
		Files.createDirectories(dir);
		ODataPath dimension = ODataPath.of("Dimensions").key(NAME);
		ODataPath hierarchy = dimension.nav("Hierarchies").key(NAME);
		ODataPath cube = ODataPath.of("Cubes").key(NAME);
		Map<String, String> captures = new LinkedHashMap<>();
		captures.put("server", get(tm1, ODataPath.of("Server"), ODataQuery.none(), false));
		captures.put("configuration", get(tm1, ODataPath.of("Configuration"), ODataQuery.none(), false));
		captures.put("active-session",
				get(tm1, ODataPath.of("ActiveSession"), ODataQuery.none().expand("User").expand("Threads"), false));
		captures.put("users", get(tm1, ODataPath.of("Users"), ODataQuery.none().expand("Groups"), false));
		captures.put("groups", get(tm1, ODataPath.of("Groups"), ODataQuery.none(), false));
		captures.put("dimension",
				get(tm1, dimension,
						ODataQuery.none()
							.expand("Hierarchies",
									h -> h.expand("Elements", e -> e.expand("Parents").expand("Components"))
										.expand("Edges")
										.expand("Subsets")
										.expand("ElementAttributes")),
						false));
		captures.put("members", get(tm1, hierarchy.nav("Members"), ODataQuery.none(), false));
		captures.put("cube", get(tm1, cube, ODataQuery.none().expand("Dimensions").expand("Views"), false));
		captures.put("views", get(tm1, cube.nav("Views"), ODataQuery.none(), false));
		captures.put("process", get(tm1, ODataPath.of("Processes").key(NAME), ODataQuery.none(), false));
		captures.put("chore", get(tm1, ODataPath.of("Chores").key(NAME),
				ODataQuery.none().expand("Tasks", t -> t.expand("Process")), false));
		captures.put("threads", get(tm1, ODataPath.of("Threads"), ODataQuery.none(), false));
		captures.put("cellset",
				post(tm1, ODataPath.of("ExecuteMDX"),
						ODataQuery.none()
							.expand("Axes", a -> a.expand("Tuples", t -> t.expand("Members")))
							.expand("Cells")
							.expand("Cube"),
						"{\"MDX\":\"SELECT {[" + MEASURE + "].[" + MEASURE + "].Members} ON 0, {[" + NAME + "].[" + NAME
								+ "].Members} ON 1 FROM [" + NAME + "]\"}"));
		for (Map.Entry<String, String> capture : captures.entrySet()) {
			JsonNode tree = JSON.readTree(capture.getValue());
			Files.writeString(dir.resolve(capture.getKey() + ".json"), JSON.writeValueAsString(tree) + "\n",
					StandardCharsets.UTF_8);
		}
	}

	private static void createFixtures(Tm1Session session) {
		for (ODataPath old : List.of(ODataPath.of("Chores").key(NAME), ODataPath.of("Processes").key(NAME),
				ODataPath.of("Cubes").key(NAME), ODataPath.of("Dimensions").key(NAME),
				ODataPath.of("Dimensions").key(MEASURE))) {
			session.execute(Tm1Request.delete(old)).onErrorComplete().block(WAIT);
		}
		session.execute(Tm1Request.post(ODataPath.of("Dimensions"), DimensionPatch.builder()
			.name(NAME)
			.hierarchies(List.of(HierarchyPatch.builder()
				.name(NAME)
				.elements(List.of(element("North", ElementType.NUMERIC), element("South", ElementType.NUMERIC),
						element("Total", ElementType.CONSOLIDATED), element("Label", ElementType.STRING)))
				.edges(List.of(EdgePatch.builder().parentName("Total").componentName("North").weight(1.0).build(),
						EdgePatch.builder().parentName("Total").componentName("South").weight(1.0).build()))
				.build()))
			.build(), JsonNode.class)).block(WAIT);
		session.execute(Tm1Request.post(ODataPath.of("Dimensions"),
				DimensionPatch.builder()
					.name(MEASURE)
					.hierarchies(List.of(HierarchyPatch.builder()
						.name(MEASURE)
						.elements(List.of(element("Amount", ElementType.NUMERIC), element("Note", ElementType.STRING)))
						.build()))
					.build(),
				JsonNode.class))
			.block(WAIT);
		ODataPath hierarchy = ODataPath.of("Dimensions").key(NAME).nav("Hierarchies").key(NAME);
		session.execute(Tm1Request.post(hierarchy.nav("ElementAttributes"),
				AttributeDefinitionPatch.builder().name("Caption").type(AttributeType.STRING).build(), JsonNode.class))
			.block(WAIT);
		session.execute(Tm1Request.post(hierarchy.nav("Subsets"),
				SubsetPatch.builder()
					.name("Leaves")
					.bindElements(hierarchy.nav("Elements").key("North"), hierarchy.nav("Elements").key("South"))
					.build(),
				JsonNode.class))
			.block(WAIT);
		session.execute(Tm1Request.post(hierarchy.nav("Subsets"),
				SubsetPatch.builder().name("All").expression("{TM1SUBSETALL([" + NAME + "].[" + NAME + "])}").build(),
				JsonNode.class))
			.block(WAIT);

		ODataPath cube = ODataPath.of("Cubes").key(NAME);
		session.execute(Tm1Request.post(ODataPath.of("Cubes"),
				CubePatch.builder()
					.name(NAME)
					.rules("SKIPCHECK;\n['Total', 'Note'] = S: 'consolidated';\n")
					.bindDimensions(ODataPath.of("Dimensions").key(NAME), ODataPath.of("Dimensions").key(MEASURE))
					.build(),
				JsonNode.class))
			.block(WAIT);
		session.execute(Tm1Request.post(cube.action("tm1.Update"),
				Map.of("Cells",
						List.of(Map.of("Tuple@odata.bind",
								List.of(ODataPath.of("Dimensions")
									.key(NAME)
									.nav("Hierarchies")
									.key(NAME)
									.nav("Elements")
									.key("North")
									.toString(),
										ODataPath.of("Dimensions")
											.key(MEASURE)
											.nav("Hierarchies")
											.key(MEASURE)
											.nav("Elements")
											.key("Amount")
											.toString()))),
						"Value", "42"),
				JsonNode.class))
			.onErrorComplete()
			.block(WAIT);
		session.execute(Tm1Request.post(cube.nav("Views"),
				MDXViewPatch.builder()
					.name("By region")
					.mdx("SELECT {[" + MEASURE + "].[" + MEASURE + "].Members} ON 0, {[" + NAME + "].[" + NAME
							+ "].Members} ON 1 FROM [" + NAME + "]")
					.build(),
				JsonNode.class))
			.block(WAIT);

		session.execute(Tm1Request.post(ODataPath.of("Processes"), ProcessPatch.builder()
			.name(NAME)
			.prologProcedure("nRows = 0;\n")
			.parameters(
					List.of(ProcessParameterPatch.builder().name("pRegion").prompt("Region").value("North").build()))
			.build(), JsonNode.class)).block(WAIT);
		session.execute(Tm1Request.post(ODataPath.of("Chores"),
				ChorePatch.builder()
					.name(NAME)
					.startTime(OffsetDateTime.parse("2026-01-01T02:00:00Z"))
					.dstSensitive(false)
					.active(false)
					.executionMode(ChoreExecutionMode.SINGLE_COMMIT)
					.frequency(Duration.ofDays(1))
					.tasks(List.of(ChoreTaskPatch.builder()
						.bindProcess(ODataPath.of("Processes").key(NAME))
						.parameters(List.of(ChoreTaskParameterPatch.builder().name("pRegion").value("South").build()))
						.build()))
					.build(),
				JsonNode.class))
			.block(WAIT);
	}

	private static ElementPatch element(String name, ElementType type) {
		return ElementPatch.builder().name(name).type(type).build();
	}

	private static String schemaVersion(Tm1ServerContainer tm1) throws Exception {
		String metadata = get(tm1, ODataPath.metadata(), null, true);
		int at = metadata.indexOf("Core.SchemaVersion");
		int start = metadata.indexOf("<String>", at) + "<String>".length();
		return metadata.substring(start, metadata.indexOf("</String>", start));
	}

	private static String get(Tm1ServerContainer tm1, ODataPath path, @Nullable ODataQuery query, boolean xml)
			throws Exception {
		return send(tm1, HttpRequest.newBuilder(uri(tm1, path, query)).GET(), xml);
	}

	private static String post(Tm1ServerContainer tm1, ODataPath path, ODataQuery query, String body) throws Exception {
		return send(tm1,
				HttpRequest.newBuilder(uri(tm1, path, query))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)),
				false);
	}

	private static URI uri(Tm1ServerContainer tm1, ODataPath path, @Nullable ODataQuery query) {
		String q = query == null || query.isEmpty() ? "" : "?" + query.toQueryString();
		return URI.create(tm1.getRestUrl() + path + q);
	}

	private static String send(Tm1ServerContainer tm1, HttpRequest.Builder request, boolean xml) throws Exception {
		String auth = Base64.getEncoder()
			.encodeToString((tm1.getUsername() + ":" + tm1.getPassword()).getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(request.header("Authorization", "Basic " + auth)
				.header("Accept", xml ? "application/xml" : "application/json")
				.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IllegalStateException(response.statusCode() + " for " + response.uri() + ": " + response.body());
		}
		return response.body();
	}

}
