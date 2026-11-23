# SPDX-License-Identifier: Apache-2.0

"""How a recording of an example's output hashes its code and keeps what it printed, as the Java
tests' ExampleOutputTest: the same in every language, and in the site's build."""

from __future__ import annotations

import pytest

from .example_output import lines, source_hash

# The file every language hashes, with this result
SOURCE = """class Example {
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
"""

HASH = "674465729e3fa78a6fe01513b135a8aeaad2efe66b046efc5cd13eb55235eebc"


def test_hashes_a_regions_lines_without_their_indentation() -> None:
    assert source_hash(SOURCE, "list") == HASH
    assert source_hash(SOURCE.replace("\t", "  ").replace("\n", "\r\n"), "list") == HASH, "indented and ended another way"
    assert source_hash(SOURCE.replace("println(name)", "println(name.strip())"), "list") != HASH


def test_fails_on_a_region_the_file_doesnt_have() -> None:
    with pytest.raises(ValueError, match="no region missing"):
        source_hash(SOURCE, "missing")


def test_keeps_printed_lines_without_their_trailing_space() -> None:
    assert lines("Jan: 12  \r\nFeb: 9\n") == ["Jan: 12", "Feb: 9"]
    assert lines("") == []
    assert lines("a\n\nb\n\n") == ["a", "", "b"]
