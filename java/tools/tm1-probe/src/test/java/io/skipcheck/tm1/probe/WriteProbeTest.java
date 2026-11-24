// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class WriteProbeTest {

	private static final String METADATA = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			 <edmx:DataServices>
			  <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			   <ComplexType Name="Item"><Property Name="Name" Type="Edm.String"/></ComplexType>
			   <EntityType Name="Thing"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			    <Property Name="Kept" Type="Edm.String"/>
			    <Property Name="Dropped" Type="Edm.Boolean"/>
			    <Property Name="Refused" Type="Edm.Int32"/>
			    <Property Name="Derived" Type="Edm.String"/>
			    <Property Name="Normalised" Type="Edm.String"/>
			    <Property Name="Items" Type="Collection(tm1.Item)"/>
			    <Property Name="Hidden" Type="Edm.String"/>
			   </EntityType>
			   <EntityContainer Name="API"><EntitySet Name="Things" EntityType="tm1.Thing"/></EntityContainer>
			  </Schema>
			 </edmx:DataServices>
			</edmx:Edmx>""";

	private static final String CASES = """
			{"cases": [{"id": "Thing", "type": "Thing", "collection": "Things", "source": "Things('{p}Source')", "base": {}}],
			 "collections": [{"id": "Thing/Items", "collection": "Things",
			   "create": {"Name": "{name}", "Items": [{"Name": "a"}, {"Name": "b"}]},
			   "patch": {"Items": [{"Name": "c"}]}, "read": "Items", "key": "Name"}]}""";

	private static final Pattern ENTITY = Pattern.compile("Things\\('([^']*)'\\)(.*)");

	/**
	 * A server where Kept is applied, Dropped ignored, Refused refused, Derived made from
	 * the name, Normalised stored upper case, a PATCH of Name ignored, and a PATCH of
	 * Items replaces them.
	 */
	static final class Things implements FakeTm1.Handler {

		final Map<String, ObjectNode> things = new LinkedHashMap<>();

		Things() {
			things.put("PSource", (ObjectNode) ProbeClient.JSON.readTree(
					"""
							{"Name": "PSource", "Kept": "k", "Dropped": true, "Refused": 5, "Derived": "d", "Normalised": "n", "Items": []}"""));
		}

		@Override
		public FakeTm1.Reply handle(String method, String target, @Nullable JsonNode body) {
			if (method.equals("POST") && target.equals("Things")) {
				if (body == null || body.has("Refused")) {
					return FakeTm1.Reply.refused();
				}
				ObjectNode thing = ProbeClient.JSON.createObjectNode();
				String name = body.path("Name").asString();
				thing.put("Name", name);
				thing.put("Kept", body.path("Kept").asString(""));
				thing.put("Dropped", false);
				thing.put("Refused", 0);
				thing.put("Derived", "derived-" + name);
				thing.put("Normalised", body.path("Normalised").asString("").toUpperCase(Locale.ROOT));
				thing.set("Items", body.has("Items") ? body.get("Items") : ProbeClient.JSON.createArrayNode());
				things.put(name, thing);
				return new FakeTm1.Reply(201, thing);
			}
			Matcher m = ENTITY.matcher(target);
			if (!m.matches()) {
				return new FakeTm1.Reply(404, null);
			}
			ObjectNode thing = things.get(m.group(1));
			if (thing == null || m.group(2).startsWith("/")) {
				return new FakeTm1.Reply(404, null);
			}
			return switch (method) {
				case "GET" -> new FakeTm1.Reply(200, thing);
				case "DELETE" -> {
					things.remove(m.group(1));
					yield new FakeTm1.Reply(204, null);
				}
				case "PATCH" -> {
					if (body == null || body.has("Refused")) {
						yield FakeTm1.Reply.refused();
					}
					if (body.has("Kept")) {
						thing.set("Kept", body.get("Kept"));
					}
					if (body.has("Normalised")) {
						thing.put("Normalised", body.get("Normalised").asString().toUpperCase(Locale.ROOT));
					}
					if (body.has("Items")) {
						thing.set("Items", body.get("Items"));
					}
					yield new FakeTm1.Reply(200, thing);
				}
				default -> new FakeTm1.Reply(405, null);
			};
		}

	}

	private static WriteProbe.Result probe() throws Exception {
		return probe(new ArrayList<>());
	}

	private static WriteProbe.Result probe(List<String> log) throws Exception {
		CsdlDocument metadata = CsdlParser.parse(METADATA);
		try (FakeTm1 tm1 = new FakeTm1(new Things())) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			return new WriteProbe(client, metadata, Map.of(), ProbeClient.JSON.readTree(CASES), "P", log::add).run();
		}
	}

	private static final String USER_METADATA = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			 <edmx:DataServices>
			  <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			   <EntityType Name="User"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			    <Property Name="Password" Type="Edm.String"/>
			   </EntityType>
			   <EntityContainer Name="API"><EntitySet Name="Users" EntityType="tm1.User"/></EntityContainer>
			  </Schema>
			 </edmx:DataServices>
			</edmx:Edmx>""";

	private static final Pattern USER = Pattern.compile("Users\\('([^']*)'\\)");

	/**
	 * Users whose password is never read back, and who sign in ({@code ActiveUser}) with
	 * the one set: in a create, and in a PATCH unless {@code patchIgnored}.
	 */
	static final class Users implements FakeTm1.Handler {

		final Map<String, String> passwords = new LinkedHashMap<>();

		final boolean patchIgnored;

		Users(boolean patchIgnored) {
			this.patchIgnored = patchIgnored;
			passwords.put("PSource", "");
		}

		@Override
		public FakeTm1.Reply handle(String method, String target, @Nullable JsonNode body) {
			if (target.equals("ActiveUser")) {
				String auth = FakeTm1.AUTHORIZATION.get();
				for (Map.Entry<String, String> user : passwords.entrySet()) {
					String basic = "Basic " + Base64.getEncoder()
						.encodeToString((user.getKey() + ":" + user.getValue()).getBytes(StandardCharsets.UTF_8));
					if (basic.equals(auth)) {
						return FakeTm1.Reply.ok("{\"Name\": \"" + user.getKey() + "\"}");
					}
				}
				return new FakeTm1.Reply(401, null);
			}
			if (method.equals("POST") && target.equals("Users") && body != null) {
				passwords.put(body.path("Name").asString(), body.path("Password").asString(""));
				return FakeTm1.Reply.ok("{\"Name\": \"" + body.path("Name").asString() + "\"}");
			}
			Matcher m = USER.matcher(target.replaceAll("\\?.*", ""));
			if (!m.matches() || !passwords.containsKey(m.group(1))) {
				return new FakeTm1.Reply(404, null);
			}
			return switch (method) {
				case "GET" -> FakeTm1.Reply.ok("{\"Name\": \"" + m.group(1) + "\"}");
				case "DELETE" -> {
					passwords.remove(m.group(1));
					yield new FakeTm1.Reply(204, null);
				}
				case "PATCH" -> {
					if (!patchIgnored && body != null && body.has("Password")) {
						passwords.put(m.group(1), body.path("Password").asString());
					}
					yield new FakeTm1.Reply(204, null);
				}
				default -> new FakeTm1.Reply(405, null);
			};
		}

	}

	private static JsonNode user(boolean patchIgnored) throws Exception {
		CsdlDocument metadata = CsdlParser.parse(USER_METADATA);
		String cases = """
				{"cases": [{"id": "User", "type": "User", "collection": "Users", "source": "Users('{p}Source')", "base": {},
				  "secrets": {"Password": ["one", "two"]}}]}""";
		try (FakeTm1 tm1 = new FakeTm1(new Users(patchIgnored))) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			return new WriteProbe(client, metadata, Map.of(), ProbeClient.JSON.readTree(cases), "P", s -> {
			}).run().writes().get("User");
		}
	}

	private static String outcome(JsonNode node) {
		return node.path("outcome").asString();
	}

	@Test
	void classifiesWhatAPostAndAPatchDoWithEachProperty() throws Exception {
		JsonNode thing = probe().writes().get("Thing");
		assertThat(outcome(thing.path("postRead"))).isEqualTo("rejected");
		assertThat(thing.path("postRead").path("code").asString()).isEqualTo("278");
		assertThat(outcome(thing.path("minimal"))).isEqualTo("accepted");
		assertThat(thing.path("required").size()).isZero();
		for (String method : List.of("post", "patch")) {
			JsonNode m = thing.path(method);
			// Hidden isn't in the read: probed all the same, as every property the type
			// has is
			assertThat(m.propertyNames()).containsExactly("Derived", "Dropped", "Hidden", "Items", "Kept", "Normalised",
					"Refused");
			assertThat(outcome(m.path("Hidden"))).as(method).isEqualTo("ignored");
			assertThat(outcome(m.path("Kept"))).as(method).isEqualTo("applied");
			assertThat(outcome(m.path("Dropped"))).as(method).isEqualTo("ignored");
			assertThat(outcome(m.path("Refused"))).as(method).isEqualTo("rejected");
			assertThat(m.path("Refused").path("status").asInt()).isEqualTo(400);
			// Made from the name, whatever was sent: the same as without it
			assertThat(outcome(m.path("Derived"))).as(method).isEqualTo("ignored");
			assertThat(outcome(m.path("Normalised"))).as(method).isEqualTo("transformed");
			// The fixture holds the default, and no collection value can be made up
			assertThat(outcome(m.path("Items"))).as(method).isEqualTo("untested");
		}
		assertThat(outcome(thing.path("patchNull").path("Kept"))).isEqualTo("accepted");
		assertThat(outcome(thing.path("patchNull").path("Refused"))).isEqualTo("rejected");
		assertThat(outcome(thing.path("rename"))).isEqualTo("ignored");
	}

	@Test
	void logsWhatATransformedWriteSentAndReadBack() throws Exception {
		List<String> log = new ArrayList<>();
		probe(log);
		assertThat(log).anySatisfy(l -> assertThat(l).startsWith("transformed: POST Things Normalised: sent \""))
			.anySatisfy(l -> assertThat(l).startsWith("transformed: PATCH Things Normalised: sent \""));
	}

	@Test
	void checksASecretItCantReadBackBySigningInWithIt() throws Exception {
		JsonNode applied = user(false);
		assertThat(outcome(applied.path("post").path("Password"))).isEqualTo("applied");
		assertThat(outcome(applied.path("patch").path("Password"))).isEqualTo("applied");
		assertThat(applied.path("post").path("Password").path("verifiedBy").asString()).isEqualTo("signIn");
		JsonNode ignored = user(true);
		assertThat(outcome(ignored.path("post").path("Password"))).isEqualTo("applied");
		assertThat(outcome(ignored.path("patch").path("Password"))).as("still signs in with the first")
			.isEqualTo("ignored");
	}

	private static final String PAL_METADATA = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			 <edmx:DataServices>
			  <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			   <EntityType Name="Pal"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			    <NavigationProperty Name="Friends" Type="Collection(tm1.Pal)"/>
			    <NavigationProperty Name="Best" Type="tm1.Pal"/>
			    <NavigationProperty Name="Rivals" Type="Collection(tm1.Rival)"/>
			   </EntityType>
			   <EntityType Name="Rival"><Key><PropertyRef Name="Name"/></Key>
			    <Property Name="Name" Type="Edm.String" Nullable="false"/>
			   </EntityType>
			   <EntityContainer Name="API"><EntitySet Name="Pals" EntityType="tm1.Pal"/></EntityContainer>
			  </Schema>
			 </edmx:DataServices>
			</edmx:Edmx>""";

	private static final Pattern PAL = Pattern.compile("Pals\\('([^']*)'\\)(/\\w+)?");

	/**
	 * Pals whose Friends a create binds or nests, and a PATCH binds (and refuses to
	 * nest); Best is ignored either way.
	 */
	static final class Pals implements FakeTm1.Handler {

		final Map<String, List<String>> friends = new LinkedHashMap<>();

		Pals() {
			friends.put("PSource", new ArrayList<>());
			friends.put("PTarget", new ArrayList<>());
		}

		private void add(String pal, JsonNode body, boolean nest) {
			body.path("Friends@odata.bind").forEach(p -> friends.get(pal).add(WriteProbe.keyOf(p.asString())));
			if (nest) {
				for (JsonNode nested : body.path("Friends")) {
					String name = nested.path("Name").asString();
					friends.put(name, new ArrayList<>());
					friends.get(pal).add(name);
				}
			}
		}

		@Override
		public FakeTm1.Reply handle(String method, String target, @Nullable JsonNode body) {
			if (method.equals("POST") && target.equals("Pals") && body != null) {
				String name = body.path("Name").asString();
				friends.put(name, new ArrayList<>());
				add(name, body, true);
				return FakeTm1.Reply.ok("{\"Name\": \"" + name + "\"}");
			}
			Matcher m = PAL.matcher(target.replaceAll("\\?.*", ""));
			if (!m.matches() || !friends.containsKey(m.group(1))) {
				return new FakeTm1.Reply(404, null);
			}
			String pal = m.group(1);
			if (m.group(2) != null) {
				if (m.group(2).equals("/Best")) {
					return new FakeTm1.Reply(204, null);
				}
				if (m.group(2).equals("/Rivals")) {
					return FakeTm1.Reply.ok("{\"value\": []}");
				}
				StringBuilder value = new StringBuilder();
				for (String f : friends.get(pal)) {
					value.append(value.isEmpty() ? "" : ",").append("{\"Name\": \"").append(f).append("\"}");
				}
				return FakeTm1.Reply.ok("{\"value\": [" + value + "]}");
			}
			return switch (method) {
				case "GET" -> FakeTm1.Reply.ok("{\"Name\": \"" + pal + "\"}");
				case "DELETE" -> {
					friends.remove(pal);
					yield new FakeTm1.Reply(204, null);
				}
				case "PATCH" -> {
					if (body != null && body.has("Friends")) {
						yield FakeTm1.Reply.refused();
					}
					if (body != null) {
						add(pal, body, false);
					}
					yield new FakeTm1.Reply(204, null);
				}
				default -> new FakeTm1.Reply(405, null);
			};
		}

	}

	@Test
	void probesEachNavigationBoundAndNestedInACreateAndAPatch() throws Exception {
		CsdlDocument metadata = CsdlParser.parse(PAL_METADATA);
		String cases = """
				{"targets": {"Pal": "Pals('{p}Target')"},
				 "cases": [{"id": "Pal", "type": "Pal", "collection": "Pals", "source": "Pals('{p}Source')", "base": {}}]}""";
		Pals pals = new Pals();
		JsonNode navigations;
		try (FakeTm1 tm1 = new FakeTm1(pals)) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			navigations = new WriteProbe(client, metadata, Map.of(), ProbeClient.JSON.readTree(cases), "P", s -> {
			}).run().writes().get("Pal").path("navigations");
		}
		assertThat(navigations.propertyNames()).containsExactly("Best", "Friends", "Rivals");
		JsonNode friends = navigations.path("Friends");
		assertThat(outcome(friends.path("post").path("bind"))).isEqualTo("applied");
		assertThat(outcome(friends.path("post").path("nested"))).isEqualTo("applied");
		assertThat(outcome(friends.path("patch").path("bind"))).isEqualTo("applied");
		assertThat(outcome(friends.path("patch").path("nested"))).isEqualTo("rejected");
		JsonNode best = navigations.path("Best");
		for (String method : List.of("post", "patch")) {
			assertThat(outcome(best.path(method).path("bind"))).as(method).isEqualTo("ignored");
			assertThat(outcome(best.path(method).path("nested"))).as(method).isEqualTo("ignored");
			// Nothing to bind a Rival to, nor a case to nest one from
			assertThat(outcome(navigations.path("Rivals").path(method).path("bind"))).isEqualTo("untested");
			assertThat(outcome(navigations.path("Rivals").path(method).path("nested"))).isEqualTo("untested");
		}
		// Every object a try made, nested ones included, is gone
		assertThat(pals.friends.keySet()).containsExactlyInAnyOrder("PSource", "PTarget");
	}

	private static final String TASK_METADATA = """
			<edmx:Edmx xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx" Version="4.0">
			 <edmx:DataServices>
			  <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm" Namespace="ibm.tm1.api.v1" Alias="tm1">
			   <EntityType Name="Task"><Key><PropertyRef Name="Step"/></Key>
			    <Property Name="Step" Type="Edm.Int32" Nullable="false"/>
			    <Property Name="Note" Type="Edm.String"/>
			   </EntityType>
			   <ComplexType Name="Admin"><Property Name="Language" Type="Edm.String"/><Property Name="Port" Type="Edm.Int32"/></ComplexType>
			   <ComplexType Name="Settings"><Property Name="Admin" Type="tm1.Admin"/><Property Name="Fixed" Type="Edm.Boolean"/></ComplexType>
			   <EntityContainer Name="API"><EntitySet Name="Tasks" EntityType="tm1.Task"/></EntityContainer>
			  </Schema>
			 </edmx:DataServices>
			</edmx:Edmx>""";

	/**
	 * Tasks whose Step the server assigns, each made after a setup request (a Log), and
	 * settings whose Language a PATCH changes and whose Fixed it ignores.
	 */
	static final class Tasks implements FakeTm1.Handler {

		final Map<Integer, String> tasks = new LinkedHashMap<>();

		final List<String> logs = new ArrayList<>();

		final ObjectNode settings = (ObjectNode) ProbeClient.JSON
			.readTree("{\"Admin\": {\"Language\": \"en\", \"Port\": 1}, \"Fixed\": false}");

		int next = 1;

		Tasks() {
			tasks.put(0, "first");
		}

		@Override
		public FakeTm1.Reply handle(String method, String target, @Nullable JsonNode body) {
			String path = target.replaceAll("\\?.*", "");
			if (path.equals("Settings")) {
				if (method.equals("PATCH") && body != null) {
					if (body.has("Admin")) {
						body.get("Admin")
							.properties()
							.forEach(e -> ((ObjectNode) settings.get("Admin")).set(e.getKey(), e.getValue()));
					}
					return new FakeTm1.Reply(204, null);
				}
				return new FakeTm1.Reply(200, settings);
			}
			if (method.equals("POST") && path.equals("Logs") && body != null) {
				logs.add(body.path("Name").asString());
				return new FakeTm1.Reply(201, null);
			}
			if (method.equals("DELETE") && path.startsWith("Logs('")) {
				logs.remove(WriteProbe.keyOf(path));
				return new FakeTm1.Reply(204, null);
			}
			if (method.equals("POST") && path.equals("Tasks") && body != null) {
				int step = next++;
				tasks.put(step, body.path("Note").asString(""));
				return FakeTm1.Reply.ok("{\"Step\": " + step + ", \"Note\": \"" + tasks.get(step) + "\"}");
			}
			Matcher m = Pattern.compile("Tasks\\((\\d+)\\)").matcher(path);
			if (!m.matches() || !tasks.containsKey(Integer.valueOf(m.group(1)))) {
				return new FakeTm1.Reply(404, null);
			}
			int step = Integer.parseInt(m.group(1));
			return switch (method) {
				case "GET" -> FakeTm1.Reply.ok("{\"Step\": " + step + ", \"Note\": \"" + tasks.get(step) + "\"}");
				case "DELETE" -> {
					tasks.remove(step);
					yield new FakeTm1.Reply(204, null);
				}
				case "PATCH" -> {
					if (body != null && body.has("Note")) {
						tasks.put(step, body.path("Note").asString());
					}
					yield new FakeTm1.Reply(204, null);
				}
				default -> new FakeTm1.Reply(405, null);
			};
		}

	}

	@Test
	void findsWhatItMadeByTheKeyTm1AssignsAndRunsEachTrysSetupAndTeardown() throws Exception {
		String cases = """
				{"cases": [{"id": "Task", "type": "Task", "collection": "Tasks", "source": "Tasks(0)", "named": false,
				  "assigned": "Step", "base": {"Note": "n"},
				  "setup": [{"method": "POST", "path": "Logs", "body": {"Name": "{name}"}}],
				  "teardown": [{"method": "DELETE", "path": "Logs('{name}')"}]}]}""";
		Tasks tasks = new Tasks();
		JsonNode task;
		try (FakeTm1 tm1 = new FakeTm1(tasks)) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			task = new WriteProbe(client, CsdlParser.parse(TASK_METADATA), Map.of(), ProbeClient.JSON.readTree(cases),
					"P", s -> {
					})
				.run()
				.writes()
				.get("Task");
		}
		assertThat(outcome(task.path("minimal"))).isEqualTo("accepted");
		// The key isn't probed; the rest is, on what each create made
		assertThat(task.path("post").propertyNames()).containsExactly("Note");
		assertThat(outcome(task.path("post").path("Note"))).isEqualTo("applied");
		assertThat(outcome(task.path("patch").path("Note"))).isEqualTo("applied");
		assertThat(task.has("rename")).as("found by its key, not by a name").isFalse();
		assertThat(tasks.tasks).containsOnlyKeys(0);
		assertThat(tasks.logs).as("every setup torn down").isEmpty();
	}

	@Test
	void patchesASingletonsSettingsOneByOneAndSetsEachBack() throws Exception {
		String cases = """
				{"cases": [{"id": "Settings", "type": "Settings", "singleton": true, "source": "Settings",
				  "skip": ["Admin/Port"]}]}""";
		Tasks server = new Tasks();
		JsonNode settings;
		try (FakeTm1 tm1 = new FakeTm1(server)) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			settings = new WriteProbe(client, CsdlParser.parse(TASK_METADATA), Map.of(),
					ProbeClient.JSON.readTree(cases), "P", s -> {
					})
				.run()
				.writes()
				.get("Settings");
		}
		JsonNode patch = settings.path("patch");
		assertThat(patch.propertyNames()).containsExactly("Admin/Language", "Fixed");
		assertThat(outcome(patch.path("Admin/Language"))).isEqualTo("applied");
		assertThat(outcome(patch.path("Fixed"))).isEqualTo("ignored");
		assertThat(server.settings.path("Admin").path("Language").asString()).as("set back").isEqualTo("en");
	}

	@Test
	void tellsAReplacingCollectionFromAnAppendingOne() throws Exception {
		assertThat(outcome(probe().collections().get("Thing/Items"))).isEqualTo("replaces");
	}

	@Test
	void isTheSameEveryRun() throws Exception {
		assertThat(probe()).isEqualTo(probe());
	}

	@Test
	void probesEachFieldOfAStructuredValueByItsPath() throws Exception {
		String cases = """
				{"cases": [{"id": "Thing", "type": "Thing", "collection": "Things", "source": "Things('{p}Source')",
				  "base": {}, "values": {"Items": [{"Name": "first"}]}}]}""";
		JsonNode thing;
		try (FakeTm1 tm1 = new FakeTm1(new Things())) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			thing = new WriteProbe(client, CsdlParser.parse(METADATA), Map.of(), ProbeClient.JSON.readTree(cases), "P",
					s -> {
					})
				.run()
				.writes()
				.get("Thing");
		}
		// The first item's Name, changed and read back, in a create and a PATCH
		assertThat(outcome(thing.path("post").path("Items/Name"))).isEqualTo("applied");
		assertThat(outcome(thing.path("patch").path("Items/Name"))).isEqualTo("applied");
	}

	@Test
	void readsTheKeyABindingPathNamesLast() {
		assertThat(WriteProbe.keyOf("Dimensions('D')/Hierarchies('D')/Elements('A')")).isEqualTo("A");
		assertThat(WriteProbe.keyOf("Users('O''Neil')")).isEqualTo("O'Neil");
		assertThat(WriteProbe.keyOf("Chores('C')/Tasks(0)")).isEqualTo("0");
		assertThat(WriteProbe.keyOf("Edges(ParentName='T',ComponentName='A')")).isEqualTo("A");
	}

	@Test
	void comparesNumbersByValueAndObjectsByTheFieldsSent() {
		JsonNode one = ProbeClient.JSON.readTree("1");
		JsonNode oneDecimal = ProbeClient.JSON.readTree("1.0");
		assertThat(WriteProbe.contains(oneDecimal, one)).isTrue();
		JsonNode sent = ProbeClient.JSON.readTree("{\"Type\": \"ASCII\"}");
		JsonNode read = ProbeClient.JSON.readTree("{\"Type\": \"ASCII\", \"asciiHeaderRecords\": 1}");
		assertThat(WriteProbe.contains(read, sent)).isTrue();
		assertThat(WriteProbe.same(read, sent)).isFalse();
		assertThat(WriteProbe.contains(ProbeClient.JSON.readTree("[1, 2]"), ProbeClient.JSON.readTree("[1]")))
			.isFalse();
	}

}
