// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The guide shows every example in each language (api-site/scripts/guide.mjs), so the
/// .NET examples are the Java ones, region for region: each Java examples class
/// (java/&lt;module&gt;/src/examples/java/examples) has a file of the same name in
/// dotnet/examples/&lt;Module&gt;.Examples, with the same regions, marked the same way
/// (<c>// @start region = name</c> … <c>// @end</c>). The examples projects build with the solution,
/// which is what keeps them compiling; this keeps them complete.</summary>
public partial class ExamplesParityTests
{
    public static IEnumerable<object[]> Modules() =>
        [
            ["tm1-client", "Tm1.Client.Examples"],
            ["tm1-testcontainers", "Tm1.Testcontainers.Examples"],
        ];

    private static string JavaExamples(string module) => Path.Combine(Repo.Root, "java", module, "src", "examples", "java", "examples");

    private static string DotNetExamples(string project) => Path.Combine(Repo.Root, "dotnet", "examples", project);

    /// <summary>The regions of an examples file, each with the lines between its markers.</summary>
    public static Dictionary<string, List<string>> Regions(string file)
    {
        var regions = new Dictionary<string, List<string>>();
        List<string>? open = null;
        foreach (var line in File.ReadAllLines(file))
        {
            if (Start().Match(line) is { Success: true } start)
            {
                Assert.True(open is null, $"{file}: a region starts inside another at '{line.Trim()}'");
                Assert.True(regions.TryAdd(start.Groups[1].Value, open = []), $"{file}: two regions named {start.Groups[1].Value}");
            }
            else if (End().IsMatch(line))
            {
                Assert.True(open is not null, $"{file}: an @end with no region open");
                open = null;
            }
            else
            {
                open?.Add(line);
            }
        }
        Assert.True(open is null, $"{file}: a region is never ended");
        return regions;
    }

    [Theory]
    [MemberData(nameof(Modules))]
    public void EveryJavaExampleHasItsDotNetOne(string module, string project)
    {
        var java = Directory.GetFiles(JavaExamples(module), "*.java").ToDictionary(f => Path.GetFileNameWithoutExtension(f)!, Regions);
        var dotnet = Directory.GetFiles(DotNetExamples(project), "*.cs").ToDictionary(f => Path.GetFileNameWithoutExtension(f)!, Regions);
        Assert.NotEmpty(java);
        var problems = new List<string>();
        problems.AddRange(java.Keys.Except(dotnet.Keys).Select(c => $"{c}.java has no {project}/{c}.cs"));
        problems.AddRange(dotnet.Keys.Except(java.Keys).Select(c => $"{project}/{c}.cs has no {c}.java: an example here is one of Java's"));
        foreach (var (name, regions) in java.Where(j => dotnet.ContainsKey(j.Key)))
        {
            var here = dotnet[name];
            problems.AddRange(regions.Keys.Except(here.Keys).Select(r => $"{name}.cs lacks the region {r}"));
            problems.AddRange(here.Keys.Except(regions.Keys).Select(r => $"{name}.cs has a region {r} that {name}.java doesn't"));
            problems.AddRange(here.Where(r => r.Value.All(string.IsNullOrWhiteSpace)).Select(r => $"{name}.cs: the region {r.Key} is empty"));
        }
        Assert.True(problems.Count == 0, string.Join("\n", problems.Order(StringComparer.Ordinal)));
    }

    [Fact]
    public void ReadsRegions()
    {
        var regions = Regions(Path.Combine(JavaExamples("tm1-client"), "CubeServiceExamples.java"));
        Assert.Equal(["create", "list", "update-rules"], regions.Keys.Order(StringComparer.Ordinal));
        Assert.Contains(regions["list"], l => l.Contains("modelCubes", StringComparison.Ordinal));
        Assert.DoesNotContain(regions["list"], l => l.Contains("@end", StringComparison.Ordinal));
    }

    // As guide.mjs reads them
    [GeneratedRegex(@"@start\s+region\s*=\s*""?([\w-]+)""?")]
    private static partial Regex Start();

    [GeneratedRegex(@"@end\b")]
    private static partial Regex End();
}
