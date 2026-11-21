package net.ajmyers.tm1.client.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ODataQueryTest {

	@Test
	void isEmptyByDefault() {
		assertThat(ODataQuery.none().toQueryString()).isEmpty();
		assertThat(ODataQuery.none().isEmpty()).isTrue();
	}

	@Test
	void writesOptionsInAFixedOrder() {
		ODataQuery query = ODataQuery.none()
			.top(10)
			.withCount()
			.orderBy("Name")
			.skip(5)
			.filter(Filter.raw("Level eq 0"))
			.expand("Hierarchies")
			.select("Name", "Type");
		assertThat(query).hasToString(
				"$select=Name,Type&$expand=Hierarchies&$filter=Level eq 0&$orderby=Name&$top=10&$skip=5&$count=true");
	}

	@Test
	void nestsExpandOptionsWithSemicolons() {
		ODataQuery query = ODataQuery.none()
			.select("Name")
			.expand("Hierarchies", h -> h.select("Name").expand("Elements", e -> e.select("Name").top(1)))
			.expand("Cube");
		assertThat(query)
			.hasToString("$select=Name&$expand=Hierarchies($select=Name;$expand=Elements($select=Name;$top=1)),Cube");
		// '=' inside a nested option is encoded; TM1 accepts it
		assertThat(query.toQueryString()).isEqualTo(
				"$select=Name&$expand=Hierarchies($select%3DName;$expand%3DElements($select%3DName;$top%3D1)),Cube");
	}

	@Test
	void appliesNestedOptionsInAnyOrder() {
		// select() is an instance method, so in a nested expand it adds to that expand's
		// options
		ODataQuery query = ODataQuery.none().expand("Hierarchies", h -> h.top(1).select("Name").orderBy("Name"));
		assertThat(query).hasToString("$expand=Hierarchies($select=Name;$orderby=Name;$top=1)");
	}

	@Test
	void mergesRepeatedExpandsOfOneNavigation() {
		ODataQuery query = ODataQuery.none()
			.expand("Hierarchies", h -> h.select("Name"))
			.expand("Hierarchies", h -> h.top(2));
		assertThat(query).hasToString("$expand=Hierarchies($select=Name;$top=2)");
	}

	@Test
	void ordersDescending() {
		assertThat(ODataQuery.none().orderByDescending("TimeStamp").orderBy("ID"))
			.hasToString("$orderby=TimeStamp desc,ID");
	}

	@Test
	void encodesEachValueOnce() {
		ODataQuery query = ODataQuery.none()
			.filter(ODataProperty.of("Name").eq("a&b=c+d #e%f"))
			.option("!sandbox", "What if & more");
		String encoded = query.toQueryString();
		assertThat(encoded).doesNotContain(" ")
			.doesNotContain("+")
			.contains("%26")
			.contains("%3D")
			.contains("%2B")
			.contains("%23")
			.contains("%25");
		Map<String, String> back = parse(encoded);
		assertThat(back).containsEntry("$filter", "(Name eq 'a&b=c+d #e%f')")
			.containsEntry("!sandbox", "What if & more");
	}

	@Test
	void survivesFuzzedFilterValues() {
		Random random = new Random(42);
		String alphabet = "aZ0 '\"/%{}&=+#?!$@:;,()*É日😀";
		int[] cps = alphabet.codePoints().toArray();
		for (int i = 0; i < 3000; i++) {
			StringBuilder v = new StringBuilder();
			for (int j = 0, n = 1 + random.nextInt(10); j < n; j++) {
				v.appendCodePoint(cps[random.nextInt(cps.length)]);
			}
			String encoded = ODataQuery.none()
				.filter(ODataProperty.of("Name").eq(v.toString()))
				.option("!sandbox", v.toString())
				.toQueryString();
			assertThat(URI.create("http://tm1/api/v1/Cubes?" + encoded)).isNotNull();
			Map<String, String> back = parse(encoded);
			assertThat(back.get("$filter")).as(v.toString())
				.isEqualTo("(Name eq " + ODataLiterals.string(v.toString()) + ")");
			assertThat(back.get("!sandbox")).as(v.toString()).isEqualTo(v.toString());
		}
	}

	@Test
	void definesParameterAliases() {
		ODataQuery query = ODataQuery.none().alias(new ODataPath.Alias("@tuple"), "[\"a\",\"b\"]");
		assertThat(query).hasToString("@tuple=[\"a\",\"b\"]");
		assertThat(parse(query.toQueryString())).containsEntry("@tuple", "[\"a\",\"b\"]");
	}

	@Test
	void refusesBadOptions() {
		assertThatIllegalArgumentException().isThrownBy(() -> ODataQuery.none().option("$top", "1"));
		assertThatIllegalArgumentException().isThrownBy(() -> ODataQuery.none().option("@p", "1"));
		assertThatIllegalArgumentException().isThrownBy(() -> ODataQuery.none().top(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> new ODataPath.Alias("p"));
	}

	@Test
	void isImmutable() {
		ODataQuery base = ODataQuery.none().select("Name");
		ODataQuery more = base.top(1);
		assertThat(base).hasToString("$select=Name");
		assertThat(more).hasToString("$select=Name&$top=1");
		assertThat(base.option("!sandbox")).isNull();
	}

	/**
	 * Splits and decodes a query string once, as a server would. No '+' is ever sent, so
	 * URLDecoder's '+' rule doesn't matter.
	 */
	private static Map<String, String> parse(String query) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String part : query.split("&", -1)) {
			int eq = part.indexOf('=');
			out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	@Test
	void takesPropertiesForExpandAndOrderBy() {
		ODataProperty dimensions = ODataProperty.of("Dimensions");
		ODataProperty name = ODataProperty.of("Name");
		assertThat(ODataQuery.none()
			.expand(dimensions, d -> d.select(name))
			.expand(ODataProperty.of("Views"))
			.orderBy(name)
			.orderByDescending(ODataProperty.of("LastDataUpdate")))
			.isEqualTo(ODataQuery.none()
				.expand("Dimensions", d -> d.select("Name"))
				.expand("Views")
				.orderBy("Name")
				.orderByDescending("LastDataUpdate"));
	}

}
