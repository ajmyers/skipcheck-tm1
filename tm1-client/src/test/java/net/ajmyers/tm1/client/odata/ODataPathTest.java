package net.ajmyers.tm1.client.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ODataPathTest {

	@Test
	void addressesTheMetadataDocument() {
		assertThat(ODataPath.metadata()).hasToString("$metadata");
		assertThatIllegalStateException().isThrownBy(() -> ODataPath.metadata().key("x"));
	}

	@Test
	void buildsNestedPaths() {
		assertThat(ODataPath.of("Dimensions").key("Region").nav("Hierarchies").key("Region").nav("Elements"))
			.hasToString("Dimensions('Region')/Hierarchies('Region')/Elements");
		assertThat(ODataPath.of("Cubes").key("Sales").action("tm1.Reserve")).hasToString("Cubes('Sales')/tm1.Reserve");
		assertThat(ODataPath.of("Cubes").key("Sales").nav("Views").cast("tm1.NativeView"))
			.hasToString("Cubes('Sales')/Views/tm1.NativeView");
		assertThat(ODataPath.of("Configuration").nav("ProductVersion").value())
			.hasToString("Configuration/ProductVersion/$value");
		assertThat(ODataPath.of("Dimensions").count()).hasToString("Dimensions/$count");
		assertThat(ODataPath.of("Cubes").key("Sales").nav("Dimensions").ref())
			.hasToString("Cubes('Sales')/Dimensions/$ref");
		assertThat(ODataPath.of("Threads").key(42)).hasToString("Threads(42)");
	}

	@Test
	void writesCompoundKeysInOrder() {
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("ParentName", "Total");
		key.put("ComponentName", "Q1 '26");
		assertThat(ODataPath.of("Edges").key(key)).hasToString("Edges(ParentName='Total',ComponentName='Q1%20''26')");
	}

	@Test
	void callsFunctionsWithLiteralsOrAliases() {
		Map<String, Object> range = new LinkedHashMap<>();
		range.put("Begin", 0);
		range.put("End", 99);
		assertThat(ODataPath.of("Cellsets").key("abc").function("tm1.GetPartition", range))
			.hasToString("Cellsets('abc')/tm1.GetPartition(Begin=0,End=99)");
		assertThat(ODataPath.of("Hierarchies").function("tm1.Fn", Map.of("p", new ODataPath.Alias("@p"))))
			.hasToString("Hierarchies/tm1.Fn(p=@p)");
	}

	@ParameterizedTest
	@ValueSource(strings = { "}Clients", "Year 2026", "O'Neil", "a/b", "50%", "{x}", "a&b=c+d#e", "Région", "日本", "😀",
			"''", "%2F", "?", "\\", "a\tb" })
	void keepsAwkwardNamesIntact(String name) {
		ODataPath path = ODataPath.of("Dimensions").key(name).nav("Hierarchies");
		assertThat(keyOf(path)).isEqualTo(name);
	}

	@Test
	void encodesSlashesSoTheyCantSplitThePath() {
		assertThat(ODataPath.of("Dimensions").key("a/b")).hasToString("Dimensions('a%2Fb')");
		assertThat(ODataPath.of("Dimensions").key("O'Neil")).hasToString("Dimensions('O''Neil')");
		assertThat(ODataPath.of("Dimensions").key("}Clients")).hasToString("Dimensions('%7DClients')");
		assertThat(ODataPath.of("Dimensions").key("a b")).hasToString("Dimensions('a%20b')");
	}

	@Test
	void survivesFuzzedNames() {
		Random random = new Random(20261121);
		String alphabet = "aZ09 '\"/\\%{}[]()&=+#?!$@:;,.*~_-^|<>`\tÉé日😀";
		int[] codePoints = alphabet.codePoints().toArray();
		for (int i = 0; i < 5000; i++) {
			StringBuilder name = new StringBuilder();
			int length = 1 + random.nextInt(12);
			for (int j = 0; j < length; j++) {
				name.appendCodePoint(codePoints[random.nextInt(codePoints.length)]);
			}
			ODataPath path = ODataPath.of("Cubes").key(name.toString()).nav("Views").key(name.toString());
			assertThat(URI.create("http://tm1:8010/api/v1/" + path)).as(name.toString()).isNotNull();
			assertThat(keyOf(ODataPath.of("Cubes").key(name.toString()))).as(name.toString())
				.isEqualTo(name.toString());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "Cubes('x')", "a/b", "1abc", "$metadata", "Cubes Sales" })
	void refusesNamesThatArentIdentifiers(String name) {
		assertThatIllegalArgumentException().isThrownBy(() -> ODataPath.of(name));
		assertThatIllegalArgumentException().isThrownBy(() -> ODataPath.of("Cubes").nav(name));
	}

	@Test
	void refusesTwoKeysOnOneSegment() {
		assertThatIllegalStateException().isThrownBy(() -> ODataPath.of("Cubes").key("a").key("b"));
		assertThatIllegalStateException().isThrownBy(() -> ODataPath.of("Cubes").count().key("a"));
	}

	@Test
	void isAValueType() {
		assertThat(ODataPath.of("Cubes").key("a")).isEqualTo(ODataPath.of("Cubes").key("a"))
			.hasSameHashCodeAs(ODataPath.of("Cubes").key("a"));
	}

	/**
	 * Decodes a path once, as a server would, and reads back the first segment's key
	 * literal.
	 */
	private static String keyOf(ODataPath path) {
		String decoded = URI.create("http://tm1:8010/api/v1/" + path.toString().split("/", -1)[0]).getPath();
		String segment = decoded.substring(decoded.lastIndexOf("/api/v1/") + 8);
		String literal = segment.substring(segment.indexOf('(') + 1, segment.lastIndexOf(')'));
		assertThat(literal).startsWith("'").endsWith("'");
		return literal.substring(1, literal.length() - 1).replace("''", "'");
	}

	@Test
	void callsAFunctionImportFromTheServiceRoot() {
		assertThat(ODataPath.functionImport("ModelCubes", Map.of())).hasToString("ModelCubes()");
		Map<String, Object> top = new LinkedHashMap<>();
		top.put("Top", 10);
		assertThat(ODataPath.functionImport("TailMessageLog", top)).hasToString("TailMessageLog(Top=10)");
	}

}
