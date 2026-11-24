// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.skipcheck.tm1.client.version.Tm1Departure;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The members of the model that depart from TM1's {@code $metadata}: properties it
 * doesn't declare (found by probing TM1, {@code tm1-probe-<schema>.json}), and the
 * overlay's departures and lenient dates. Every client has these tests.
 */
class DeparturesTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void readsAndWritesBackThePropertiesTm1DoesNotDeclare() {
		String json = "{\"Name\": \"Load\", \"UIData\": \"CubeAction=1511\\f\", \"VariablesUIData\": [\"VarType=32\\f\"],"
				+ " \"Code\": \"#region Prolog\\nx = 1;\\n#endregion\"}";
		Process process = JSON.readValue(json, Process.class);
		assertThat(process.uiData()).isEqualTo("CubeAction=1511\f");
		assertThat(process.variablesUIData()).containsExactly("VarType=32\f");
		assertThat(process.code()).startsWith("#region Prolog");
		assertThat(JSON.readValue("{\"Name\": \"Top\", \"Alias\": \"Caption\"}", Subset.class).alias())
			.isEqualTo("Caption");

		assertThat(JSON.writeValueAsString(ProcessPatch.builder()
			.name("Load")
			.uiData(process.uiData())
			.variablesUIData(process.variablesUIData())
			.build()))
			.isEqualTo("{\"Name\":\"Load\",\"UIData\":\"CubeAction=1511\\f\",\"VariablesUIData\":[\"VarType=32\\f\"]}");
		assertThat(JSON.writeValueAsString(SubsetPatch.builder().alias("Caption").build()))
			.isEqualTo("{\"Alias\":\"Caption\"}");
	}

	@Test
	void tagsEveryDepartureTheSpecLists() throws ClassNotFoundException {
		JsonNode spec = JSON.readTree(Path.of(System.getProperty("tm1.spec")).toFile());
		Map<String, String> listed = new TreeMap<>();
		for (JsonNode type : spec.path("types")) {
			for (JsonNode p : type.path("properties")) {
				String kind = p.has("undeclared") ? "UNDECLARED"
						: p.has("departure") ? "TYPE" : p.has("lenientDate") ? "LENIENT_DATE" : null;
				if (kind != null) {
					listed.put(type.path("name").asString() + "/" + p.path("name").asString(), kind);
				}
			}
		}
		assertThat(listed).containsEntry("Process/UIData", "UNDECLARED")
			.containsEntry("Subset/Alias", "UNDECLARED")
			.containsEntry("CAMSettings/ClientPingCAMPassport", "TYPE")
			.containsEntry("MessageLogEntry/TimeStamp", "LENIENT_DATE");

		Map<String, String> tagged = new TreeMap<>();
		for (JsonNode type : spec.path("types")) {
			String name = type.path("name").asString();
			Set<String> own = new HashSet<>();
			type.path("properties").forEach(p -> own.add(p.path("name").asString()));
			for (RecordComponent component : recordOf(name).getRecordComponents()) {
				// @JsonProperty can't target a record component: it's on the accessor, as
				// is the tag
				Tm1Departure departure = component.getAccessor().getAnnotation(Tm1Departure.class);
				JsonProperty json = component.getAccessor().getAnnotation(JsonProperty.class);
				if (departure != null && json != null && own.contains(json.value())) {
					tagged.put(name + "/" + json.value(), departure.value().name());
				}
			}
		}
		assertThat(tagged).isEqualTo(listed);
	}

	/**
	 * A spec type's record: the type's own, or for the root of a hierarchy (a sealed
	 * interface) the record for a value no subtype stands for.
	 */
	private static Class<?> recordOf(String type) throws ClassNotFoundException {
		Class<?> c = Class.forName(Process.class.getPackageName() + "." + type);
		if (c.isRecord()) {
			return c;
		}
		for (Class<?> nested : c.getDeclaredClasses()) {
			if (nested.isRecord()
					&& (nested.getSimpleName().equals("Plain") || nested.getSimpleName().equals("Unknown"))) {
				return nested;
			}
		}
		throw new IllegalStateException(type + " has no record");
	}

}
