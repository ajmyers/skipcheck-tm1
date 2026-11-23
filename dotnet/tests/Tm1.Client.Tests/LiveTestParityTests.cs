// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The live tests are the Java client's, test for test, read from the Java sources
/// (java/tm1-client/src/test/java: the <c>*Tm1Test</c> and <c>*BehaviorTest</c> classes). Each Java
/// class <c>XTm1Test</c> is the class <c>XLiveTests</c> in Live/XLiveTests.cs (<c>XBehaviorTest</c> is
/// <c>XBehaviorLiveTests</c>), and each of its tests is a test there of the same name in Pascal
/// case, or has a line in dotnet/coverage/live-excluded.txt saying why not. A port has no test Java's
/// class doesn't: one only this client has goes in a file that isn't a port (DotNetOnlyLiveTests).</summary>
public partial class LiveTestParityTests
{
    private static readonly string LiveDirectory = Path.Combine(Repo.Root, "dotnet", "tests", "Tm1.Client.Tests", "Live");

    private static readonly string ExcludedFile = Path.Combine(Repo.Root, "dotnet", "coverage", "live-excluded.txt");

    /// <summary>The Java live test classes, each with its tests, in the order they're written.</summary>
    public static SortedDictionary<string, List<string>> JavaTests()
    {
        var result = new SortedDictionary<string, List<string>>(StringComparer.Ordinal);
        foreach (var file in Directory.GetFiles(Repo.JavaTests, "*.java", SearchOption.AllDirectories))
        {
            var name = Path.GetFileNameWithoutExtension(file);
            if (name.EndsWith("Tm1Test", StringComparison.Ordinal) || name.EndsWith("BehaviorTest", StringComparison.Ordinal))
            {
                result.Add(name, TestPattern().Matches(File.ReadAllText(file)).Select(m => m.Groups["name"].Value).ToList());
            }
        }
        return result;
    }

    /// <summary>The port of a Java class: <c>CubeServiceTm1Test</c> → <c>CubeServiceLiveTests</c>,
    /// <c>Tm1SessionBehaviorTest</c> → <c>Tm1SessionBehaviorLiveTests</c>.</summary>
    public static string PortOf(string javaClass) =>
        (javaClass.EndsWith("Tm1Test", StringComparison.Ordinal) ? javaClass[..^"Tm1Test".Length] : javaClass[..^"Test".Length]) + "LiveTests";

    /// <summary>live-excluded.txt: <c>Class</c> or <c>Class#method</c> → the reason.</summary>
    public static Dictionary<string, string> Excluded()
    {
        var result = new Dictionary<string, string>();
        foreach (var line in File.ReadAllLines(ExcludedFile).Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')))
        {
            var colon = line.IndexOf(':', StringComparison.Ordinal);
            Assert.True(colon > 0 && line[(colon + 1)..].Trim().Length > 0, $"'{line}' needs a reason: ClassName#methodName: reason");
            Assert.True(result.TryAdd(line[..colon].Trim(), line[(colon + 1)..].Trim()), "duplicate exclusion " + line);
        }
        return result;
    }

    private static Type? PortType(string port) => typeof(LiveTestParityTests).Assembly.GetType("Skipcheck.Tm1.Client.Tests.Live." + port);

    /// <summary>The tests a class declares: its public methods with a <c>[Fact]</c> of any kind.</summary>
    private static HashSet<string> TestsOf(Type type) =>
        type.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
            .Where(m => m.GetCustomAttributes<FactAttribute>().Any())
            .Select(m => m.Name)
            .ToHashSet();

    public static IEnumerable<object[]> Classes() => JavaTests().Keys.Select(c => new object[] { c });

    [Theory]
    [MemberData(nameof(Classes))]
    public void EveryJavaLiveTestHasItsPort(string javaClass)
    {
        var excluded = Excluded();
        var port = PortOf(javaClass);
        var type = PortType(port);
        if (excluded.ContainsKey(javaClass))
        {
            Assert.True(type is null, $"{javaClass} is excluded in live-excluded.txt, but {port} exists: take the line off");
            return;
        }
        Assert.True(File.Exists(Path.Combine(LiveDirectory, port + ".cs")), $"{javaClass} has no Live/{port}.cs (or a line in live-excluded.txt)");
        Assert.True(type is not null, $"Live/{port}.cs has no class {port}");
        var tests = TestsOf(type!);
        var problems = new List<string>();
        var expected = new HashSet<string>();
        foreach (var method in JavaTests()[javaClass])
        {
            var name = char.ToUpperInvariant(method[0]) + method[1..];
            expected.Add(name);
            var isExcluded = excluded.ContainsKey(javaClass + "#" + method);
            if (isExcluded && tests.Contains(name))
            {
                problems.Add($"{method} is excluded in live-excluded.txt, but {port}.{name} exists: take the line off");
            }
            else if (!isExcluded && !tests.Contains(name))
            {
                problems.Add($"{method} has no test {port}.{name} (or a line in live-excluded.txt)");
            }
        }
        problems.AddRange(
            tests.Except(expected).Order().Select(t => $"{port}.{t} isn't a test of {javaClass}: rename it, or move it to a file that isn't a port")
        );
        Assert.True(problems.Count == 0, string.Join("\n", problems));
    }

    [Fact]
    public void ExcludesOnlyWhatJavaHas()
    {
        var java = JavaTests();
        foreach (var key in Excluded().Keys)
        {
            var parts = key.Split('#');
            Assert.True(parts.Length <= 2 && java.ContainsKey(parts[0]), $"live-excluded.txt names {key}, but Java has no live test class {parts[0]}");
            Assert.True(parts.Length == 1 || java[parts[0]].Contains(parts[1]), $"live-excluded.txt names {key}, but {parts[0]} has no test {parts.Last()}");
        }
    }

    [Fact]
    public void ReadsTheJavaTests()
    {
        var java = JavaTests();
        Assert.Contains("readsTheServerAndClosesItsSessionLeavingNoneBehind", java["Tm1SessionTm1Test"]);
        Assert.Contains("runsTheScenario", java["ConformanceScenarioTm1Test"]); // a @ParameterizedTest
        Assert.Contains("closesAConnectionIdleForNinetySeconds", java["Tm1SessionBehaviorTest"]);
        Assert.All(java, c => Assert.NotEmpty(c.Value));
        Assert.Equal("Tm1SessionBehaviorLiveTests", PortOf("Tm1SessionBehaviorTest"));
        Assert.Equal("CubeServiceLiveTests", PortOf("CubeServiceTm1Test"));
    }

    // @Test or @ParameterizedTest, any annotations after it, then the method
    [GeneratedRegex(@"@(?:Test|ParameterizedTest)\b(?:\([^)]*\))?(?:\s*@\w+(?:\((?:[^()""]|""[^""]*"")*\))?)*\s+(?:public\s+)?void\s+(?<name>\w+)\s*\(")]
    private static partial Regex TestPattern();
}
