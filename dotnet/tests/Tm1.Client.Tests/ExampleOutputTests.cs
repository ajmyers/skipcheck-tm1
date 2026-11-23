// SPDX-License-Identifier: Apache-2.0

using System;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>How a recording of an example's output hashes its code and keeps what it printed: the same in every
/// language, and in the site's build, which compares the hashes. A port of the Java client's ExampleOutputTest.</summary>
public class ExampleOutputTests
{
    /// <summary>The file every language hashes, with this result: api-site's tests check it too.</summary>
    private static readonly string Source = string.Join(
        "\n",
        "class Example {",
        "\tvoid other() {",
        "\t\t// @start region = other",
        "\t\tignored();",
        "\t\t// @end",
        "\t}",
        "",
        "\tvoid list(List<String> names) {",
        "\t\t// @start region = list",
        "\t\tfor (String name : names) {",
        "",
        "\t\t\tSystem.out.println(name);",
        "\t\t}",
        "\t\t// @end",
        "\t}",
        "}",
        ""
    );

    private const string Hash = "674465729e3fa78a6fe01513b135a8aeaad2efe66b046efc5cd13eb55235eebc";

    [Fact]
    public void HashesARegionsLinesWithoutTheirIndentation()
    {
        Assert.Equal(Hash, ExampleOutput.SourceHash(Source, "list"));
        // Indented and ended another way
        Assert.Equal(
            Hash,
            ExampleOutput.SourceHash(Source.Replace("\t", "  ", StringComparison.Ordinal).Replace("\n", "\r\n", StringComparison.Ordinal), "list")
        );
        Assert.NotEqual(Hash, ExampleOutput.SourceHash(Source.Replace("println(name)", "println(name.strip())", StringComparison.Ordinal), "list"));
    }

    [Fact]
    public void FailsOnARegionTheFileDoesntHave() =>
        Assert.Contains(
            "no region missing",
            Assert.Throws<ArgumentException>(() => ExampleOutput.SourceHash(Source, "missing")).Message,
            StringComparison.Ordinal
        );

    [Fact]
    public void KeepsPrintedLinesWithoutTheirTrailingSpace()
    {
        Assert.Equal(["Jan: 12", "Feb: 9"], ExampleOutput.Lines("Jan: 12  \r\nFeb: 9\n"));
        Assert.Empty(ExampleOutput.Lines(""));
        Assert.Equal(["a", "", "b"], ExampleOutput.Lines("a\n\nb\n\n"));
    }
}
