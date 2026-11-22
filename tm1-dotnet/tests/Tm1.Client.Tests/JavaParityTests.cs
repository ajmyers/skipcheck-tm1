using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using AjMyers.Tm1.Client.Services;

namespace AjMyers.Tm1.Client.Tests;

/// <summary>The .NET client offers what the Java client does, method for method, read from the
/// Java sources (tm1-client/src/main/java/.../service), as the Python client's test_java_parity.py:
/// each Java class is a class of the same name in <c>AjMyers.Tm1.Client.Services</c>; each public
/// method is named in Pascal case, with <c>Async</c> when Java returns a <c>Mono</c> or <c>Flux</c>
/// (<c>modelCubes</c> → <c>ModelCubesAsync</c>), implements the same <c>@Tm1Endpoint</c> IDs, and
/// takes Java's parameters, by Java's names, in order (an <c>ODataQuery</c> is <c>query</c>;
/// overloads become optional parameters; a <c>CancellationToken</c> comes last).</summary>
public partial class JavaParityTests
{
    public sealed record JavaMethod(string Name, bool Reactive, List<List<(string Type, string Name)>> Overloads, HashSet<string> Endpoints);

    public static IEnumerable<object[]> Classes() => Directory.GetFiles(Repo.JavaServices, "*.java", SearchOption.AllDirectories)
        .Where(f => !f.EndsWith("package-info.java", StringComparison.Ordinal))
        .Where(f => Regex.IsMatch(File.ReadAllText(f), $@"public\s+(final\s+)?(class|enum|record|interface)\s+{Path.GetFileNameWithoutExtension(f)}\b"))
        .Order()
        .Select(f => new object[] { Path.GetFileNameWithoutExtension(f) });

    public static Dictionary<string, JavaMethod> JavaMethods(string file)
    {
        var source = File.ReadAllText(file);
        var cls = Path.GetFileNameWithoutExtension(file);
        var result = new Dictionary<string, JavaMethod>();
        foreach (Match m in MethodPattern().Matches(source))
        {
            var name = m.Groups["name"].Value;
            if (name == cls || !m.Groups["decl"].Value.TrimStart('\n', '\r').StartsWith("\tpublic", StringComparison.Ordinal) || name is "toString" or "equals" or "hashCode")
            {
                continue;
            }
            var parameters = Split(m.Groups["params"].Value).Select(p =>
            {
                var parts = p.Replace("@Nullable", "", StringComparison.Ordinal).Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
                return (string.Join(" ", parts[..^1]), parts[^1].TrimStart('.'));
            }).ToList();
            var ret = m.Groups["ret"].Value;
            if (!result.TryGetValue(name, out var method))
            {
                result[name] = method = new JavaMethod(name, ret.StartsWith("Mono", StringComparison.Ordinal) || ret.StartsWith("Flux", StringComparison.Ordinal), new(), new());
            }
            method.Overloads.Add(parameters);
            foreach (Match e in Regex.Matches(m.Groups["ann"].Value, "@Tm1Endpoint\\(\"([^\"]+)\"\\)"))
            {
                method.Endpoints.Add(e.Groups[1].Value);
            }
        }
        return result;
    }

    [Theory]
    [MemberData(nameof(Classes))]
    public void EveryJavaClassHasItsDotNetCounterpart(string name)
    {
        var file = Directory.GetFiles(Repo.JavaServices, name + ".java", SearchOption.AllDirectories).Single();
        var type = typeof(Tm1Client).Assembly.GetType("AjMyers.Tm1.Client.Services." + name);
        Assert.True(type is not null, $"AjMyers.Tm1.Client.Services has no {name}");
        var source = File.ReadAllText(file);
        if (Regex.IsMatch(source, $@"public\s+enum\s+{name}\b"))
        {
            Assert.True(type!.IsEnum, name + " is an enum in Java");
            foreach (Match c in Regex.Matches(source, @"^\t([A-Z][A-Z_0-9]*)\b[,;(]", RegexOptions.Multiline))
            {
                Assert.True(Enum.GetNames(type).Contains(Pascal(c.Groups[1].Value)), $"{name}.{c.Groups[1].Value}");
            }
            return;
        }
        var problems = new List<string>();
        foreach (var method in JavaMethods(file).Values)
        {
            var dotnet = Capitalize(method.Name) + (method.Reactive ? "Async" : "");
            var members = type!.GetMember(dotnet, BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static);
            var overloads = members.OfType<MethodInfo>().ToList();
            if (members.Length == 0)
            {
                problems.Add($"missing {method.Name} → {dotnet}");
                continue;
            }
            if (overloads.Count == 0)
            {
                continue; // a property, for an accessor
            }
            var endpoints = overloads.SelectMany(o => o.GetCustomAttributes<Tm1EndpointAttribute>()).Select(a => a.Value).ToHashSet();
            if (!endpoints.SetEquals(method.Endpoints))
            {
                problems.Add($"{dotnet}: endpoints [{string.Join(", ", endpoints.Order())}], Java [{string.Join(", ", method.Endpoints.Order())}]");
            }
            foreach (var overload in method.Overloads)
            {
                var expected = overload.Select(p => p.Type == "ODataQuery" ? "query" : p.Name).ToList();
                if (!overloads.Any(o => Subsequence(expected, o.GetParameters().Select(p => p.Name!).Where(n => n != "cancellationToken").ToList())))
                {
                    problems.Add($"{dotnet}: no overload takes ({string.Join(", ", expected)}); has " + string.Join(" | ", overloads.Select(o => "(" + string.Join(", ", o.GetParameters().Select(p => p.Name)) + ")")));
                }
            }
        }
        Assert.True(problems.Count == 0, string.Join("\n", problems));
    }

    [Fact]
    public void TheClientHasEveryService()
    {
        var methods = JavaMethods(Path.Combine(Repo.JavaServices, "..", "Tm1Client.java"));
        foreach (var m in methods.Values.Where(m => m.Overloads.All(o => o.Count == 0) && m.Name.EndsWith('s') || m.Name is "server" or "git" or "encryption" or "drillthrough" or "processDebugging"))
        {
            if (m.Name is "builder" or "session" or "batch" or "connect" or "close" or "withoutAsync" or "toString")
            {
                continue;
            }
            Assert.True(typeof(Tm1Client).GetProperty(Capitalize(m.Name)) is not null, m.Name);
        }
    }

    private static bool Subsequence(List<string> expected, List<string> got)
    {
        int i = 0;
        foreach (var g in got)
        {
            if (i < expected.Count && expected[i] == g)
            {
                i++;
            }
        }
        return i == expected.Count;
    }

    private static string Capitalize(string s) => char.ToUpperInvariant(s[0]) + s[1..];

    private static string Pascal(string constant) => string.Concat(constant.Split('_', StringSplitOptions.RemoveEmptyEntries).Select(w => w[0] + w[1..].ToLowerInvariant()));

    private static List<string> Split(string parameters)
    {
        var result = new List<string>();
        int depth = 0;
        var current = "";
        foreach (var c in parameters)
        {
            depth += c == '<' ? 1 : c == '>' ? -1 : 0;
            if (c == ',' && depth == 0)
            {
                result.Add(current);
                current = "";
            }
            else
            {
                current += c;
            }
        }
        if (current.Trim().Length > 0)
        {
            result.Add(current);
        }
        return result.Select(p => Regex.Replace(p, @"\s+", " ").Trim()).ToList();
    }

    [GeneratedRegex(@"(?<ann>(?:\s*@Tm1Endpoint\(""[^""]+""\)\s*)*)(?<decl>\s+public\s+(?:static\s+)?(?:<[^>]+>\s+)?(?<ret>[\w.<>\[\]?, ]+?)\s+(?<name>\w+)\s*\((?<params>[^)]*)\)\s*(?:throws [\w., ]+)?\{)")]
    private static partial Regex MethodPattern();
}
