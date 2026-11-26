// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>This library's tests are the Java tm1-testcontainers', test for test, read from the Java
/// sources (java/tm1-testcontainers/src/test/java), as tests/Tm1.Client.Tests/JavaUnitParityTests.cs
/// holds the client's to the Java client's. It's what keeps a fix made in one language from being
/// left out of another: a behaviour change starts with a Java test, and this fails until that test
/// is here too. Every test of a Java <c>*Test</c> class (a method under <c>@Test</c>,
/// <c>@ParameterizedTest</c>, <c>@RepeatedTest</c> or <c>@TestTemplate</c>, comments aside) has a
/// test of the same name in Pascal case (<c>usesTheZipSetDirectly</c> is
/// <c>UsesTheZipSetDirectly</c>): a <c>[Fact]</c> of any kind (a <c>[Theory]</c> or a
/// <c>[RequiresTm1Fact]</c> is one) of any class here; or
/// dotnet/coverage/testcontainers-unit-excluded.txt says why not, as
/// <c>ClassName#methodName: reason</c>, or <c>ClassName: reason</c> for a whole class. A line naming
/// a class or a test Java doesn't have, a test this library has after all, a class whose every test
/// is here, or a test of a class excluded whole, fails too, so the list can't rot.
/// <para>A test may be in any class: the tests here are grouped by what they test, not always by
/// Java's classes. But they're counted: a name several Java classes use
/// (<c>findsNothingWhenNothingIsSet</c>) needs as many tests of that name here, in as many classes,
/// so one can't answer for them all. A test whose attribute says <c>Skip = "…"</c> never runs, and
/// doesn't count. A Java test that needs TM1, TM1 Web, Cognos or PAW is here with the
/// <c>[Requires…Fact]</c> that skips it without one, and that one counts. This reads sources and this
/// assembly's metadata only, and builds no attribute, so it starts nothing.</para></summary>
public partial class JavaTestParityTests
{
    private const string ExcludedName = "testcontainers-unit-excluded.txt";

    private static readonly string ExcludedFile = Path.Combine(Repo.Root, "dotnet", "coverage", ExcludedName);

    /// <summary>Each Java test class with its tests, in the order they're written. A class with no
    /// tests (a helper whose name ends in Test) is left out.</summary>
    public static SortedDictionary<string, List<string>> JavaTests()
    {
        var result = new SortedDictionary<string, List<string>>(StringComparer.Ordinal);
        foreach (var file in Directory.GetFiles(Repo.JavaTests, "*Test.java", SearchOption.AllDirectories))
        {
            var name = Path.GetFileNameWithoutExtension(file);
            var tests = TestsOf(File.ReadAllText(file));
            if (tests.Count > 0)
            {
                Assert.True(result.TryAdd(name, tests), "two Java test classes named " + name);
            }
        }
        return result;
    }

    /// <summary>The tests of a Java source, in the order they're written: each method under a test
    /// annotation, whatever comes between the two.</summary>
    internal static List<string> TestsOf(string javaSource) => [.. TestPattern().Matches(WithoutComments(javaSource)).Select(m => m.Groups["name"].Value)];

    /// <summary>A Java source without its comments, and with its string and char literals emptied:
    /// a <c>/*</c> or <c>//</c> in a literal starts no comment, and nothing in a literal or a comment
    /// reads as an annotation or a method.</summary>
    internal static string WithoutComments(string java)
    {
        var result = new System.Text.StringBuilder(java.Length);
        var i = 0;
        while (i < java.Length)
        {
            var c = java[i];
            if (c == '"' && string.CompareOrdinal(java, i, "\"\"\"", 0, 3) == 0)
            {
                var end = java.IndexOf("\"\"\"", i + 3, StringComparison.Ordinal);
                result.Append("\"\"");
                i = end < 0 ? java.Length : end + 3;
            }
            else if (c is '"' or '\'')
            {
                var end = i + 1;
                while (end < java.Length && java[end] != c && java[end] != '\n')
                {
                    end += java[end] == '\\' ? 2 : 1;
                }
                result.Append(c).Append(c);
                i = end + 1;
            }
            else if (c == '/' && i + 1 < java.Length && java[i + 1] == '*')
            {
                var end = java.IndexOf("*/", i + 2, StringComparison.Ordinal);
                result.Append(' ');
                i = end < 0 ? java.Length : end + 2;
            }
            else if (c == '/' && i + 1 < java.Length && java[i + 1] == '/')
            {
                var end = java.IndexOf('\n', i);
                i = end < 0 ? java.Length : end;
            }
            else
            {
                result.Append(c);
                i++;
            }
        }
        return result.ToString();
    }

    /// <summary>Every test of this assembly, with how many classes have one of that name.</summary>
    public static Dictionary<string, int> TestsHere() => TestsHere(typeof(JavaTestParityTests).Assembly.GetTypes());

    /// <summary>The tests of <paramref name="types"/>: the public methods with a <c>[Fact]</c> of any
    /// kind, but for one that says <c>Skip = "…"</c>, which never runs. The attributes are read as
    /// written, never built: a <c>[RequiresTm1Fact]</c> asks Docker when it is, and sets its own Skip
    /// then, which is no reason not to count it.</summary>
    internal static Dictionary<string, int> TestsHere(IEnumerable<Type> types) =>
        types
            .SelectMany(t => t.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
            .Where(m =>
                m.GetCustomAttributesData()
                    .Any(a =>
                        typeof(FactAttribute).IsAssignableFrom(a.AttributeType)
                        && !a.NamedArguments.Any(n => n.MemberName == nameof(FactAttribute.Skip) && n.TypedValue.Value is not null)
                    )
            )
            .GroupBy(m => m.Name, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.Select(m => m.DeclaringType).Distinct().Count(), StringComparer.Ordinal);

    /// <summary>A Java test's name here: <c>usesTheZipSetDirectly</c> → <c>UsesTheZipSetDirectly</c>.</summary>
    public static string Named(string method) => char.ToUpperInvariant(method[0]) + method[1..];

    /// <summary>The lines of an exclusions file: <c>Class</c> or <c>Class#method</c> → the reason.
    /// Blank lines and <c>#</c> comments are skipped.</summary>
    internal static Dictionary<string, string> Exclusions(IEnumerable<string> lines)
    {
        var result = new Dictionary<string, string>();
        foreach (var line in lines.Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')))
        {
            var colon = line.IndexOf(':', StringComparison.Ordinal);
            Assert.True(colon > 0 && line[(colon + 1)..].Trim().Length > 0, $"'{line}' needs a reason: ClassName#methodName: reason");
            Assert.True(result.TryAdd(line[..colon].Trim(), line[(colon + 1)..].Trim()), "duplicate exclusion " + line);
        }
        return result;
    }

    /// <summary>What keeps the tests from being Java's: <paramref name="here"/> is every test of this
    /// assembly with how many there are of its name, <paramref name="excluded"/>
    /// testcontainers-unit-excluded.txt read.</summary>
    public static List<string> Problems(
        SortedDictionary<string, List<string>> java,
        IReadOnlyDictionary<string, int> here,
        IReadOnlyDictionary<string, string> excluded
    )
    {
        var result = new List<string>();
        int Here(string method) => here.GetValueOrDefault(Named(method));
        // The Java classes that each need a test of this name here: those with one, and no exclusion for it
        List<string> Wanting(string method) =>
            [.. java.Where(c => c.Value.Contains(method) && !excluded.ContainsKey(c.Key) && !excluded.ContainsKey(c.Key + "#" + method)).Select(c => c.Key)];
        // An excluded test is here after all when there's one more of its name than the other classes need
        bool Spare(string method) => Here(method) > Wanting(method).Count;
        foreach (var said in excluded.Keys.Order(StringComparer.Ordinal))
        {
            var parts = said.Split('#', 2);
            var (javaClass, method) = (parts[0], parts.Length == 2 ? parts[1] : null);
            if (!java.TryGetValue(javaClass, out var methods))
            {
                result.Add($"{ExcludedName} excludes {said}, but Java has no test class {javaClass}");
            }
            else if (method is not null && !methods.Contains(method))
            {
                result.Add($"{ExcludedName} excludes {said}, but {javaClass} has no test {method}");
            }
            else if (method is not null && excluded.ContainsKey(javaClass))
            {
                result.Add($"{ExcludedName} excludes {said} and the whole of {javaClass}");
            }
            else if (method is not null && Spare(method))
            {
                result.Add($"{ExcludedName} excludes {said}, but {Named(method)} is here: take the line off");
            }
            else if (method is null && methods.All(Spare))
            {
                result.Add($"{ExcludedName} excludes {said}, but every test of {javaClass} is here: take the line off");
            }
        }
        foreach (var method in java.Values.SelectMany(m => m).Distinct().Order(StringComparer.Ordinal))
        {
            var wanting = Wanting(method);
            var count = Here(method);
            if (count >= wanting.Count)
            {
                continue;
            }
            result.Add(
                wanting.Count == 1
                    ? $"{wanting[0]}#{method} has no {Named(method)} here: port the test (and what it tests), or say why not in {ExcludedName}"
                    : $"{string.Join(", ", wanting)} each have a test {method}, but {count} of {wanting.Count} {Named(method)} are here, one for each class: "
                        + $"port the missing test (and what it tests), or say why not in {ExcludedName}"
            );
        }
        return result;
    }

    [Fact]
    public void EveryJavaTestIsHereOrExcludedWithAReason()
    {
        var java = JavaTests();
        Assert.True(java.Count > 15 && java.Values.Sum(m => m.Count) > 60, "the Java tests weren't found");
        var found = Problems(java, TestsHere(), Exclusions(File.ReadAllLines(ExcludedFile)));
        Assert.True(found.Count == 0, string.Join("\n", found));
    }

    [Fact]
    public void ReadsTheJavaTests()
    {
        var java = JavaTests();
        Assert.Contains("usesTheZipSetDirectly", java["PawSourceTest"]);
        Assert.Contains("startsATm1ServerAndReportsItsVersion", java["Tm1ServerContainerTest"]); // the class is @RequiresTm1
        Assert.Contains("twoServersAndTm1Web", java["EnvironmentExampleTest"]); // @RequiresTm1Web after its @Test
        Assert.Contains("fromAnInstalledFolder", java["ExplicitSourceExampleTest"]); // an annotation with arguments after its @Test
        Assert.Contains("fromAPrivateImageWithTm1Inside", java["ExplicitSourceExampleTest"]); // the last of five such
        Assert.All(java, c => Assert.NotEmpty(c.Value));
        Assert.DoesNotContain("Tm1WebSession", java.Keys); // helpers, not tests
        Assert.DoesNotContain("Tm1Rest", java.Keys);
        var here = TestsHere();
        Assert.Contains(nameof(EveryJavaTestIsHereOrExcludedWithAReason), here.Keys);
        Assert.Contains("UsesTheZipSetDirectly", here.Keys);
        Assert.Contains("StartsATm1ServerAndReportsItsVersion", here.Keys); // a [RequiresTm1Fact], and a Task
        Assert.Contains("StartsAThrowawayServer", here.Keys); // an attribute that sets its own Skip when built
        Assert.Contains(nameof(JavaParityTests.EveryJavaTypeHasItsDotNetCounterpart), here.Keys); // a [Theory]
        Assert.Equal(java.Count(c => c.Value.Contains("findsNothingWhenNothingIsSet")), here["FindsNothingWhenNothingIsSet"]); // one a class
    }

    // The gate itself

    private static readonly HashSet<string> Complete = ["UsesTheZipSetDirectly", "RefusesToStartWithoutTheLicense", "ReadsTheSampleCubes"];

    private static List<string> Check(IEnumerable<string> here, params string[] excluded) =>
        Problems(
            new SortedDictionary<string, List<string>>(StringComparer.Ordinal)
            {
                ["PawSourceTest"] = ["usesTheZipSetDirectly", "refusesToStartWithoutTheLicense"],
                ["SampleDatabaseExampleTest"] = ["readsTheSampleCubes"],
            },
            here.GroupBy(name => name).ToDictionary(group => group.Key, group => group.Count()),
            Exclusions(excluded)
        );

    [Fact]
    public void PassesWhenEveryTestIsHereWhateverItsClass()
    {
        Assert.Empty(Check(Complete));
        Assert.Empty(Check([.. Complete, "OnlyDotNetHasThis"]));
    }

    [Fact]
    public void FailsOnAJavaTestThatIsntHere() =>
        Assert.Contains(
            "PawSourceTest#usesTheZipSetDirectly has no UsesTheZipSetDirectly here",
            Assert.Single(Check(Complete.Except(["UsesTheZipSetDirectly"])))
        );

    [Fact]
    public void TakesAnExclusionWithAReasonForATestOrAClass() =>
        Assert.Empty(
            Check(
                ["RefusesToStartWithoutTheLicense"],
                "# a comment, and a blank line",
                "",
                "PawSourceTest#usesTheZipSetDirectly: this library takes the folder only",
                "SampleDatabaseExampleTest: tests JUnit's @BeforeAll"
            )
        );

    [Fact]
    public void FailsOnAnExclusionJavaDoesntHaveOrThisLibraryDoes()
    {
        Assert.Contains("Java has no test class GoneTest", Assert.Single(Check(Complete, "GoneTest: was removed")));
        Assert.Contains("PawSourceTest has no test renamedSince", Assert.Single(Check(Complete, "PawSourceTest#renamedSince: no counterpart")));
        Assert.Contains("UsesTheZipSetDirectly is here", Assert.Single(Check(Complete, "PawSourceTest#usesTheZipSetDirectly: no counterpart")));
        Assert.Contains(
            "every test of SampleDatabaseExampleTest is here",
            Assert.Single(Check(Complete, "SampleDatabaseExampleTest: tests JUnit's @BeforeAll"))
        );
        Assert.Contains(
            "and the whole of SampleDatabaseExampleTest",
            Assert.Single(
                Check(
                    Complete.Except(["ReadsTheSampleCubes"]),
                    "SampleDatabaseExampleTest: tests JUnit's @BeforeAll",
                    "SampleDatabaseExampleTest#readsTheSampleCubes: and again"
                )
            )
        );
    }

    [Fact]
    public void RefusesAnExclusionWithoutAReason()
    {
        Assert.ThrowsAny<Exception>(() => Check(Complete, "PawSourceTest#usesTheZipSetDirectly"));
        Assert.ThrowsAny<Exception>(() => Check(Complete, "PawSourceTest#usesTheZipSetDirectly:  "));
    }

    [Fact]
    public void RefusesAnExclusionSaidTwice() =>
        Assert.ThrowsAny<Exception>(() =>
            Check(["RefusesToStartWithoutTheLicense"], "PawSourceTest#usesTheZipSetDirectly: once", "PawSourceTest#usesTheZipSetDirectly: twice")
        );

    [Fact]
    public void FailsOnAWholeClassExclusionOnceEveryTestOfTheClassIsHere()
    {
        var line = "PawSourceTest: this library takes the folder only";
        Assert.Empty(Check(["ReadsTheSampleCubes"], line));
        Assert.Empty(Check(["ReadsTheSampleCubes", "UsesTheZipSetDirectly"], line)); // one of its two
        Assert.Contains("every test of PawSourceTest is here: take the line off", Assert.Single(Check(Complete, line)));
    }

    // A name several Java classes use

    private static List<string> CheckShared(IEnumerable<string> here, params string[] excluded) =>
        Problems(
            new SortedDictionary<string, List<string>>(StringComparer.Ordinal)
            {
                ["PawSourceTest"] = ["findsNothingWhenNothingIsSet"],
                ["Tm1SourceTest"] = ["findsNothingWhenNothingIsSet"],
                ["Tm1WebSourceTest"] = ["findsNothingWhenNothingIsSet"],
            },
            here.GroupBy(name => name).ToDictionary(group => group.Key, group => group.Count()),
            Exclusions(excluded)
        );

    [Fact]
    public void WantsATestForEachJavaClassThatHasOneOfTheName()
    {
        const string Name = "FindsNothingWhenNothingIsSet";
        Assert.Empty(CheckShared([Name, Name, Name]));
        Assert.Empty(CheckShared([Name, Name, Name, Name]));
        var two = Assert.Single(CheckShared([Name, Name]));
        Assert.Contains("PawSourceTest, Tm1SourceTest, Tm1WebSourceTest each have a test findsNothingWhenNothingIsSet", two);
        Assert.Contains("but 2 of 3 FindsNothingWhenNothingIsSet are here", two);
        Assert.Contains("but 0 of 3 FindsNothingWhenNothingIsSet are here", Assert.Single(CheckShared([])));
    }

    [Fact]
    public void CountsAnExclusionAgainstANameSeveralJavaClassesUse()
    {
        const string Name = "FindsNothingWhenNothingIsSet";
        var line = "PawSourceTest#findsNothingWhenNothingIsSet: PAW has one source only";
        Assert.Empty(CheckShared([Name, Name], line)); // the other two classes' tests don't make the line stale
        Assert.Contains("Tm1SourceTest, Tm1WebSourceTest each have a test", Assert.Single(CheckShared([Name], line)));
        Assert.Contains("FindsNothingWhenNothingIsSet is here: take the line off", Assert.Single(CheckShared([Name, Name, Name], line)));
        Assert.Empty(CheckShared([Name, Name], "PawSourceTest: PAW has one source only"));
        Assert.Contains(
            "every test of PawSourceTest is here: take the line off",
            Assert.Single(CheckShared([Name, Name, Name], "PawSourceTest: PAW has one source only"))
        );
    }

    // Tests that never run

    [Fact]
    public void DoesntCountATestThatIsAlwaysSkipped()
    {
        var here = TestsHere([typeof(Skipped), typeof(SkippedToo)]);
        Assert.Equal(["Conditional", "Runs", "RunsInBoth", "TakesData"], here.Keys.Order(StringComparer.Ordinal));
        Assert.Equal(2, here["RunsInBoth"]); // one in each class
        Assert.Equal(1, here["Runs"]); // the other class's is skipped
        Assert.Equal(1, here["TakesData"]); // two of the name in one class are one
        Assert.DoesNotContain("NeverRuns", here.Keys);
        Assert.DoesNotContain("NeverTakesData", here.Keys);
        Assert.DoesNotContain("NotATest", here.Keys);
    }

    [Fact]
    public void FailsOnAJavaTestThatIsOnlyHereSkipped() =>
        Assert.Contains(
            "SampleDatabaseExampleTest#neverRuns has no NeverRuns here",
            Assert.Single(
                Problems(
                    new SortedDictionary<string, List<string>>(StringComparer.Ordinal) { ["SampleDatabaseExampleTest"] = ["runs", "neverRuns"] },
                    TestsHere([typeof(Skipped)]),
                    Exclusions([])
                )
            )
        );

    // Not public, so xunit doesn't list them: read by TestsHere only
#pragma warning disable xUnit1000, xUnit1004, xUnit1024, xUnit1026, CA1822, CA1812, IDE0060
    private sealed class Skipped
    {
        [Fact]
        public void Runs() { }

        [Fact]
        public void RunsInBoth() { }

        [Fact(Skip = "not yet")]
        public void NeverRuns() { }

        [Theory(Skip = "not yet")]
        [InlineData(1)]
        public void NeverTakesData(int value) { }

        [Theory]
        [InlineData(1)]
        public void TakesData(int value) { }

        [Theory]
        [InlineData("1")]
        public void TakesData(string value) { }

        [SometimesFact]
        public void Conditional() { }

        public void NotATest() { }
    }

    private sealed class SkippedToo
    {
        [Fact(Skip = "not yet")]
        public void Runs() { }

        [Fact]
        public void RunsInBoth() { }
    }
#pragma warning restore xUnit1000, xUnit1004, xUnit1024, xUnit1026, CA1822, CA1812, IDE0060

    /// <summary>As a <c>[Requires…Fact]</c>: its Skip is set when it's built, which TestsHere never does.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class SometimesFactAttribute : FactAttribute
    {
        public SometimesFactAttribute() => Skip = "set when built";
    }

    // Reading Java

    [Theory]
    [InlineData("@Test /* why */ void aCommentAfterTheAnnotation() {}", "aCommentAfterTheAnnotation")]
    [InlineData("@Test\n\t// why\n\tvoid aLineCommentBeforeTheMethod() {}", "aLineCommentBeforeTheMethod")]
    [InlineData(
        "@ParameterizedTest(name = \"{0} (legacy)\")\n@ValueSource(strings = { \"a)\", \"b\" })\nvoid parenthesesInItsName(String s) {}",
        "parenthesesInItsName"
    )]
    [InlineData("@Test\nprotected void aProtectedTest() {}", "aProtectedTest")]
    [InlineData("@Test\npublic final void aPublicFinalTest() throws Exception {}", "aPublicFinalTest")]
    [InlineData("@Test\n@Tags({ @Tag(\"slow\"), @Tag(\"docker\") })\nvoid nestedAnnotations() {}", "nestedAnnotations")]
    [InlineData("@RepeatedTest(3)\nvoid repeated() {}", "repeated")]
    [InlineData("@TestTemplate\n@ExtendWith(Each.class)\nvoid aTemplate(int i) {}", "aTemplate")]
    [InlineData("/** Runs before each {@code @Test}. */\n@BeforeEach\nvoid setUp() {}", null)]
    [InlineData("// @Test void commentedOut() {}", null)]
    [InlineData("/* @Test\nvoid commentedOutToo() {} */", null)]
    [InlineData("@TestFactory\nvoid aFactory() {}\n@TestMethodOrder(A.class)\nvoid ordered() {}", null)]
    [InlineData("String s = \"@Test void inAString() {}\";", null)]
    [InlineData("String glob = \"samples/*\";\n@Test\nvoid afterAStringWithACommentStart() {}\nString end = \"*/\";", "afterAStringWithACommentStart")]
    [InlineData("String url = \"http://x\"; @Test void afterAUrl() {}", "afterAUrl")]
    [InlineData("String block = \"\"\"\n\t// @Test void inATextBlock() {\n\t\"quoted\" /*\n\t\"\"\";\n@Test void afterATextBlock() {}", "afterATextBlock")]
    [InlineData("char quote = '\"'; String s = \"a \\\" /* b\"; @Test void afterQuotes() {} // \"", "afterQuotes")]
    public void ReadsATestWhateverComesBetweenItsAnnotationAndItsName(string java, string? test) =>
        Assert.Equal(test is null ? [] : [test], TestsOf("class SomeTest {\n" + java + "\n}"));

    [Fact]
    public void ReadsEveryTestOfAClassInOrder() =>
        Assert.Equal(
            ["first", "second", "third"],
            TestsOf(
                """
                class SomeTest {
                	/** Not a test: {@code @Test} is only named. */
                	@BeforeEach
                	void setUp() {}

                	@Test // the first
                	void first() {}

                	// @Test void gone() {}
                	private static final String ALL = "samples/*";

                	@ParameterizedTest(name = "{0} (legacy)")
                	@Tags({ @Tag("slow") })
                	protected void second(String s) {}

                	private void helper() {}

                	@RepeatedTest(3)
                	void third() {}
                }
                """
            )
        );

    // A test annotation (by its simple name, once comments and literals are gone), then, lazily,
    // whatever comes before the method: its arguments, other annotations, modifiers, line breaks
    [GeneratedRegex(@"@(?:Test|ParameterizedTest|RepeatedTest|TestTemplate)\b.*?\bvoid\s+(?<name>\w+)\s*\(", RegexOptions.Singleline)]
    internal static partial Regex TestPattern();
}
