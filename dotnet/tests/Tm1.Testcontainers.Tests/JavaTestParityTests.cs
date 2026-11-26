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
/// is here too. Every test of a Java <c>*Test</c> class (a <c>@Test</c> or a
/// <c>@ParameterizedTest</c>) has a test of the same name in Pascal case
/// (<c>usesTheZipSetDirectly</c> is <c>UsesTheZipSetDirectly</c>): a <c>[Fact]</c> of any kind (a
/// <c>[Theory]</c> or a <c>[RequiresTm1Fact]</c> is one) of any class here; or
/// dotnet/coverage/testcontainers-unit-excluded.txt says why not, as
/// <c>ClassName#methodName: reason</c>, or <c>ClassName: reason</c> for a whole class. A line naming
/// a class or a test Java doesn't have, a test this library has after all, or a test of a class
/// excluded whole, fails too, so the list can't rot.
/// <para>A test may be in any class: the tests here are grouped by what they test, not always by
/// Java's classes. So a name several Java classes use (<c>findsNothingWhenNothingIsSet</c>) is met
/// by one test of that name here. A Java test that needs TM1, TM1 Web, Cognos or PAW is here with
/// the <c>[Requires…Fact]</c> that skips it without one. This reads sources and this assembly only,
/// and builds no attribute, so it starts nothing.</para></summary>
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
            var tests = TestPattern().Matches(File.ReadAllText(file)).Select(m => m.Groups["name"].Value).ToList();
            if (tests.Count > 0)
            {
                Assert.True(result.TryAdd(name, tests), "two Java test classes named " + name);
            }
        }
        return result;
    }

    /// <summary>Every test of this assembly: the public methods with a <c>[Fact]</c> of any kind.
    /// The attributes are looked for, never built: a <c>[RequiresTm1Fact]</c> asks Docker when it is.</summary>
    public static HashSet<string> TestsHere() =>
        typeof(JavaTestParityTests)
            .Assembly.GetTypes()
            .SelectMany(t => t.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
            .Where(m => m.IsDefined(typeof(FactAttribute), false))
            .Select(m => m.Name)
            .ToHashSet();

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
    /// assembly, <paramref name="excluded"/> testcontainers-unit-excluded.txt read.</summary>
    public static List<string> Problems(SortedDictionary<string, List<string>> java, ISet<string> here, IReadOnlyDictionary<string, string> excluded)
    {
        var result = new List<string>();
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
            else if (method is not null && here.Contains(Named(method)))
            {
                result.Add($"{ExcludedName} excludes {said}, but {Named(method)} is here: take the line off");
            }
            else if (method is not null && excluded.ContainsKey(javaClass))
            {
                result.Add($"{ExcludedName} excludes {said} and the whole of {javaClass}");
            }
            else if (method is null && methods.All(m => here.Contains(Named(m))))
            {
                result.Add($"{ExcludedName} excludes {said}, but every test of {javaClass} is here: take the line off");
            }
        }
        foreach (var (javaClass, methods) in java)
        {
            if (excluded.ContainsKey(javaClass))
            {
                continue;
            }
            result.AddRange(
                methods
                    .Where(m => !here.Contains(Named(m)) && !excluded.ContainsKey(javaClass + "#" + m))
                    .Select(m => $"{javaClass}#{m} has no {Named(m)} here: port the test (and what it tests), or say why not in {ExcludedName}")
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
        Assert.Contains(nameof(EveryJavaTestIsHereOrExcludedWithAReason), here);
        Assert.Contains("UsesTheZipSetDirectly", here);
        Assert.Contains("StartsATm1ServerAndReportsItsVersion", here); // a [RequiresTm1Fact], and a Task
        Assert.Contains(nameof(JavaParityTests.EveryJavaTypeHasItsDotNetCounterpart), here); // a [Theory]
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
            here.ToHashSet(),
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

    // @Test or @ParameterizedTest, any annotations after it, then the method
    [GeneratedRegex(@"@(?:Test|ParameterizedTest)\b(?:\([^)]*\))?(?:\s*@\w+(?:\((?:[^()""]|""[^""]*"")*\))?)*\s+(?:public\s+)?void\s+(?<name>\w+)\s*\(")]
    internal static partial Regex TestPattern();
}
