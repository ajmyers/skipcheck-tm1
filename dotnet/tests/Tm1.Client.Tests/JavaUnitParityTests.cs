// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The runtime's unit tests are the Java client's, test for test, read from the Java sources
/// (java/tm1-client/src/test/java). It's what keeps a fix made in one client from being left out of
/// another: a behaviour change starts with a Java test, and this fails until that test is here too.
/// Every unit test of a Java test class has a test of the same name in Pascal case
/// (<c>followsANextLinkInAResult</c> is <c>FollowsANextLinkInAResult</c>): a <c>[Fact]</c> or
/// <c>[Theory]</c> of any class here outside the Live namespace; or dotnet/coverage/unit-excluded.txt
/// says why not, as <c>ClassName#methodName: reason</c>, or <c>ClassName: reason</c> for a whole class.
/// A line naming a class or a test Java doesn't have, a test this client has after all, or a test of
/// a class excluded whole, fails too, so the list can't rot.
/// <para>Two kinds of Java test class are someone else's to check, and aren't read here: the live
/// tests (<c>*Tm1Test</c>, <c>*BehaviorTest</c>: LiveTestParityTests), and the service tests that
/// record their calls (<c>new Conformance(</c>: every client replays the recordings in
/// spec/conformance). Unlike the live gate, a test may be in any class: the unit tests here are
/// grouped by what they test, not by Java's classes. This reads sources and this assembly
/// only.</para></summary>
public class JavaUnitParityTests
{
    private const string ExcludedName = "unit-excluded.txt";

    private static readonly string ExcludedFile = Path.Combine(Repo.Root, "dotnet", "coverage", ExcludedName);

    private const string Records = "new Conformance(";

    /// <summary>Each Java unit test class that is neither live nor recorded, with its tests, in the
    /// order they're written. A class with no tests is left out.</summary>
    public static SortedDictionary<string, List<string>> JavaTests()
    {
        var result = new SortedDictionary<string, List<string>>(StringComparer.Ordinal);
        foreach (var file in Directory.GetFiles(Repo.JavaTests, "*Test.java", SearchOption.AllDirectories))
        {
            var name = Path.GetFileNameWithoutExtension(file);
            var source = File.ReadAllText(file);
            if (
                name.EndsWith("Tm1Test", StringComparison.Ordinal)
                || name.EndsWith("BehaviorTest", StringComparison.Ordinal)
                || source.Contains(Records, StringComparison.Ordinal)
            )
            {
                continue;
            }
            var tests = LiveTestParityTests.TestPattern().Matches(source).Select(m => m.Groups["name"].Value).ToList();
            if (tests.Count > 0)
            {
                Assert.True(result.TryAdd(name, tests), "two Java test classes named " + name);
            }
        }
        return result;
    }

    /// <summary>Every test of this assembly outside the Live namespace: the public methods with a
    /// <c>[Fact]</c> of any kind (a <c>[Theory]</c> is one).</summary>
    public static HashSet<string> TestsHere() =>
        typeof(JavaUnitParityTests)
            .Assembly.GetTypes()
            .Where(t => t.Namespace is not { } ns || !(ns + ".").StartsWith(typeof(JavaUnitParityTests).Namespace + ".Live.", StringComparison.Ordinal))
            .SelectMany(t => t.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
            .Where(m => m.GetCustomAttributes<FactAttribute>().Any())
            .Select(m => m.Name)
            .ToHashSet();

    /// <summary>A Java test's name here: <c>followsANextLink</c> → <c>FollowsANextLink</c>.</summary>
    public static string Named(string method) => char.ToUpperInvariant(method[0]) + method[1..];

    /// <summary>What keeps the unit tests from being Java's: <paramref name="here"/> is every unit test
    /// of this client, <paramref name="excluded"/> unit-excluded.txt read.</summary>
    public static List<string> Problems(SortedDictionary<string, List<string>> java, ISet<string> here, IReadOnlyDictionary<string, string> excluded)
    {
        var result = new List<string>();
        foreach (var said in excluded.Keys.Order(StringComparer.Ordinal))
        {
            var parts = said.Split('#', 2);
            var (javaClass, method) = (parts[0], parts.Length == 2 ? parts[1] : null);
            if (!java.TryGetValue(javaClass, out var methods))
            {
                result.Add($"{ExcludedName} excludes {said}, but Java has no unit test class {javaClass}");
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
    public void EveryJavaUnitTestIsHereOrExcludedWithAReason()
    {
        var java = JavaTests();
        Assert.True(java.Count > 20 && java.Values.Sum(m => m.Count) > 150, "the Java unit tests weren't found");
        var found = Problems(java, TestsHere(), LiveTestParityTests.Exclusions(File.ReadAllLines(ExcludedFile)));
        Assert.True(found.Count == 0, string.Join("\n", found));
    }

    [Fact]
    public void ReadsTheJavaUnitTests()
    {
        var java = JavaTests();
        Assert.Contains("splitsABigQueue", java["Tm1SessionBatchTest"]);
        Assert.Contains("decodesAndEncodesBackToTheSameJson", java["PayloadRoundTripTest"]); // a @ParameterizedTest
        Assert.DoesNotContain(java.Keys, c => c.EndsWith("Tm1Test", StringComparison.Ordinal) || c.EndsWith("BehaviorTest", StringComparison.Ordinal));
        Assert.DoesNotContain("GroupServiceTest", java.Keys); // records its calls
        var here = TestsHere();
        Assert.Contains(nameof(EveryJavaUnitTestIsHereOrExcludedWithAReason), here);
        Assert.DoesNotContain("ReadsTheServerAndClosesItsSessionLeavingNoneBehind", here); // a live test
    }

    // The gate itself

    private static readonly HashSet<string> Complete = ["SplitsABigQueue", "RefusesCallsAfterItRan", "ReadsTheSnapshot"];

    private static List<string> Check(IEnumerable<string> here, params string[] excluded) =>
        Problems(
            new SortedDictionary<string, List<string>>(StringComparer.Ordinal)
            {
                ["Tm1SessionBatchTest"] = ["splitsABigQueue", "refusesCallsAfterItRan"],
                ["MetadataSnapshotTest"] = ["readsTheSnapshot"],
            },
            here.ToHashSet(),
            LiveTestParityTests.Exclusions(excluded)
        );

    [Fact]
    public void PassesWhenEveryTestIsHereWhateverItsClass()
    {
        Assert.Empty(Check(Complete));
        Assert.Empty(Check([.. Complete, "OnlyDotNetHasThis"]));
    }

    [Fact]
    public void FailsOnAJavaTestThatIsntHere() =>
        Assert.Contains("Tm1SessionBatchTest#splitsABigQueue has no SplitsABigQueue here", Assert.Single(Check(Complete.Except(["SplitsABigQueue"]))));

    [Fact]
    public void TakesAnExclusionWithAReasonForATestOrAClass() =>
        Assert.Empty(
            Check(
                ["RefusesCallsAfterItRan"],
                "Tm1SessionBatchTest#splitsABigQueue: this client sends a batch whole",
                "MetadataSnapshotTest: tests Java's snapshot"
            )
        );

    [Fact]
    public void FailsOnAnExclusionJavaDoesntHaveOrThisClientDoes()
    {
        Assert.Contains("Java has no unit test class GoneTest", Assert.Single(Check(Complete, "GoneTest: was removed")));
        Assert.Contains("Tm1SessionBatchTest has no test renamedSince", Assert.Single(Check(Complete, "Tm1SessionBatchTest#renamedSince: no counterpart")));
        Assert.Contains("SplitsABigQueue is here", Assert.Single(Check(Complete, "Tm1SessionBatchTest#splitsABigQueue: no counterpart")));
        Assert.Contains(
            "and the whole of MetadataSnapshotTest",
            Assert.Single(
                Check(Complete.Except(["ReadsTheSnapshot"]), "MetadataSnapshotTest: tests Java's snapshot", "MetadataSnapshotTest#readsTheSnapshot: and again")
            )
        );
    }

    [Fact]
    public void RefusesAnExclusionWithoutAReason() => Assert.ThrowsAny<Exception>(() => Check(Complete, "Tm1SessionBatchTest#splitsABigQueue"));
}
