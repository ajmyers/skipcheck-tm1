// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
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
			    <Property Name="Items" Type="Collection(tm1.Item)"/>
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
	 * the name, a PATCH of Name ignored, and a PATCH of Items replaces them.
	 */
	static final class Things implements FakeTm1.Handler {

		final Map<String, ObjectNode> things = new LinkedHashMap<>();

		Things() {
			things.put("PSource", (ObjectNode) ProbeClient.JSON.readTree("""
					{"Name": "PSource", "Kept": "k", "Dropped": true, "Refused": 5, "Derived": "d", "Items": []}"""));
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
		CsdlDocument metadata = CsdlParser.parse(METADATA);
		try (FakeTm1 tm1 = new FakeTm1(new Things())) {
			ProbeClient client = new ProbeClient(tm1.url(), "Basic x");
			return new WriteProbe(client, metadata, Map.of(), ProbeClient.JSON.readTree(CASES), "P", s -> {
			}).run();
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
			assertThat(m.propertyNames()).containsExactly("Derived", "Dropped", "Items", "Kept", "Refused");
			assertThat(outcome(m.path("Kept"))).as(method).isEqualTo("applied");
			assertThat(outcome(m.path("Dropped"))).as(method).isEqualTo("ignored");
			assertThat(outcome(m.path("Refused"))).as(method).isEqualTo("rejected");
			assertThat(m.path("Refused").path("status").asInt()).isEqualTo(400);
			assertThat(outcome(m.path("Derived"))).as(method).isEqualTo("transformed");
			// The fixture holds the default, and no collection value can be made up
			assertThat(outcome(m.path("Items"))).as(method).isEqualTo("untested");
		}
		assertThat(outcome(thing.path("patchNull").path("Kept"))).isEqualTo("accepted");
		assertThat(outcome(thing.path("patchNull").path("Refused"))).isEqualTo("rejected");
		assertThat(outcome(thing.path("rename"))).isEqualTo("ignored");
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
