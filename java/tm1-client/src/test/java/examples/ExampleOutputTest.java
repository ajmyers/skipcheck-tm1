// SPDX-License-Identifier: Apache-2.0

package examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * How a recording of an example's output hashes its code and keeps what it printed: the
 * same in every language, and in the site's build, which compares the hashes.
 */
class ExampleOutputTest {

	/**
	 * The file every language hashes, with this result: api-site's tests check it too.
	 */
	static final String SOURCE = """
			class Example {
				void other() {
					// @start region = other
					ignored();
					// @end
				}

				void list(List<String> names) {
					// @start region = list
					for (String name : names) {

						System.out.println(name);
					}
					// @end
				}
			}
			""";

	static final String HASH = "674465729e3fa78a6fe01513b135a8aeaad2efe66b046efc5cd13eb55235eebc";

	@Test
	void hashesARegionsLinesWithoutTheirIndentation() {
		assertThat(ExampleOutput.sourceHash(SOURCE, "list")).isEqualTo(HASH);
		assertThat(ExampleOutput.sourceHash(SOURCE.replace("\t", "  ").replace("\n", "\r\n"), "list"))
			.as("indented and ended another way")
			.isEqualTo(HASH);
		assertThat(ExampleOutput.sourceHash(SOURCE.replace("println(name)", "println(name.strip())"), "list"))
			.isNotEqualTo(HASH);
	}

	@Test
	void failsOnARegionTheFileDoesntHave() {
		assertThatThrownBy(() -> ExampleOutput.sourceHash(SOURCE, "missing")).hasMessageContaining("no region missing");
	}

	@Test
	void keepsPrintedLinesWithoutTheirTrailingSpace() {
		assertThat(ExampleOutput.lines("Jan: 12  \r\nFeb: 9\n")).containsExactly("Jan: 12", "Feb: 9");
		assertThat(ExampleOutput.lines("")).isEmpty();
		assertThat(ExampleOutput.lines("a\n\nb\n\n")).containsExactly("a", "", "b");
	}

}
