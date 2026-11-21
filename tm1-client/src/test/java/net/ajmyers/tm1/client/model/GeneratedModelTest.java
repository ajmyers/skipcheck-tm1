package net.ajmyers.tm1.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The generated model reads TM1's JSON (plan §8.1); full round trips come with #67. */
class GeneratedModelTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void readsAnEntityWithItsEtagAndExpandedNavigationIgnoringWhatItDoesntKnow() {
		Cube cube = JSON.readValue("""
				{"@odata.context":"$metadata#Cubes/$entity","@odata.etag":"W/\\"abc\\"","Name":"Sales",
				 "LastDataUpdate":"2026-11-21T10:15:30Z","SomethingNewer":42,
				 "Dimensions":[{"Name":"Region"},{"Name":"Month"}]}
				""", Cube.class);
		assertThat(cube.etag()).isEqualTo("W/\"abc\"");
		assertThat(cube.name()).isEqualTo("Sales");
		assertThat(cube.lastDataUpdate()).isEqualTo(OffsetDateTime.of(2026, 11, 21, 10, 15, 30, 0, ZoneOffset.UTC));
		assertThat(cube.dimensions()).extracting(Dimension::name).containsExactly("Region", "Month");
		assertThat(cube.rules()).as("not selected").isNull();
	}

	@Test
	void readsEnumsAndFallsBackForAValueItDoesntKnow() {
		assertThat(JSON.readValue("{\"Type\":\"Consolidated\"}", Element.class).type())
			.isEqualTo(ElementType.CONSOLIDATED);
		assertThat(JSON.readValue("{\"Type\":\"SomethingNew\"}", Element.class).type()).isEqualTo(ElementType.UNKNOWN);
		assertThat(JSON.writeValueAsString(ElementType.NUMERIC)).isEqualTo("\"Numeric\"");
	}

}
