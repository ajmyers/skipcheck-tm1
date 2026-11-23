// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>The .NET library offers what the Java tm1-testcontainers does, read from the Java
/// sources as tests/Tm1.Client.Tests/JavaParityTests.cs reads the client's: each public Java type
/// is a type of the same name in <c>Skipcheck.Tm1.Testcontainers</c> (its records nested in it, its
/// annotations <c>[…Fact]</c> attributes in <c>Skipcheck.Tm1.Testcontainers.Xunit</c>); each constant
/// a constant in Pascal case; each constructor and public method a .NET one, by Java's parameter
/// names in order. Getters are properties (<c>getRestUrl()</c> → <c>RestUrl</c>,
/// <c>isRunning()</c> → <c>IsRunning</c>), and what does I/O is <c>…Async</c>
/// (<c>shared()</c> → <c>SharedAsync</c>). <see cref="Renamed"/> lists the names .NET can't
/// keep, and why.</summary>
public partial class JavaParityTests
{
    /// <summary>Java methods that do I/O, which .NET makes <c>…Async</c>.</summary>
    private static readonly HashSet<string> Io = ["shared", "start", "stop", "idToken", "passport"];

    /// <summary>Java names .NET can't keep: a constant can't share its name with a nested type, a
    /// method or a property of the same type. They're the lines with a new name in
    /// dotnet/coverage/members-excluded.txt, which the docs build reads too
    /// (&lt;c&gt;Tm1Source.PRIVATE_IMAGE -&gt; PrivateImageSetting | why&lt;/c&gt;).</summary>
    public static readonly Dictionary<string, (string DotNet, string Why)> Renamed = File.ReadLines(
            Path.Combine(Repo.Root, "dotnet", "coverage", "members-excluded.txt")
        )
        .Select(line => Regex.Match(line, @"^([\w.]+)\s*->\s*(\w+)\s*\|\s*(.+)$"))
        .Where(m => m.Success)
        .ToDictionary(m => m.Groups[1].Value, m => (m.Groups[2].Value, m.Groups[3].Value.Trim()));

    public static IEnumerable<object[]> Types() =>
        Directory
            .GetFiles(Repo.JavaSources, "*.java")
            .Where(f =>
                Regex.IsMatch(File.ReadAllText(f), $@"public\s+(final\s+|sealed\s+)*(class|enum|record|interface)\s+{Path.GetFileNameWithoutExtension(f)}\b")
            )
            .Select(f => Path.GetFileNameWithoutExtension(f))
            .Order()
            .Select(n => new object[] { n });

    public static IEnumerable<object[]> Annotations() =>
        Directory.GetFiles(Repo.JavaSources, "Requires*.java").Select(f => Path.GetFileNameWithoutExtension(f)).Order().Select(n => new object[] { n });

    [Theory]
    [MemberData(nameof(Types))]
    public void EveryJavaTypeHasItsDotNetCounterpart(string name)
    {
        var source = File.ReadAllText(Path.Combine(Repo.JavaSources, name + ".java"));
        var type = Library.GetType("Skipcheck.Tm1.Testcontainers." + name);
        Assert.True(type is not null, "Skipcheck.Tm1.Testcontainers has no " + name);
        var problems = new List<string>();
        if (Regex.IsMatch(source, $@"public\s+enum\s+{name}\b"))
        {
            Assert.True(type!.IsEnum, name + " is an enum in Java");
            foreach (Match c in Regex.Matches(source, @"^\t([A-Z][A-Z_0-9]*)\(", RegexOptions.Multiline))
            {
                Assert.Contains(Pascal(c.Groups[1].Value), Enum.GetNames(type));
            }
            return;
        }
        var isInterface = Regex.IsMatch(source, $@"interface\s+{name}\b");
        CheckConstants(name, source, type!, isInterface, problems);
        foreach (Match r in Regex.Matches(source, @"^\t(public\s+)?(?:static\s+)?(?:final\s+)?(record|enum|class)\s+(\w+)", RegexOptions.Multiline))
        {
            // A class's nested type without "public" is Java's own (an interface's is public anyway)
            if (!isInterface && !r.Groups[1].Success)
            {
                continue;
            }
            var nested = r.Groups[3].Value;
            var dotnet = Renamed.TryGetValue(name + "." + nested, out var renamed) ? renamed.DotNet : nested;
            if (type!.GetNestedType(dotnet) is null)
            {
                problems.Add($"no nested {r.Groups[2].Value} {nested} → {dotnet}");
            }
        }
        CheckMembers(name, Members(source, 1, isInterface), type!, problems);
        Assert.True(problems.Count == 0, string.Join("\n", problems));
    }

    [Fact]
    public void TheEnvironmentBuilderHasEveryBuilderMethod()
    {
        var source = File.ReadAllText(Path.Combine(Repo.JavaSources, "PlanningAnalyticsEnvironment.java"));
        var builder = source[source.IndexOf("public static final class Builder", StringComparison.Ordinal)..];
        // Up to the Builder's closing brace, not the nested types after it
        builder = builder[..builder.IndexOf("\n\t}\n", StringComparison.Ordinal)];
        var problems = new List<string>();
        CheckMembers("PlanningAnalyticsEnvironment.Builder", Members(builder, 2, false), typeof(PlanningAnalyticsEnvironment.EnvironmentBuilder), problems);
        Assert.True(problems.Count == 0, string.Join("\n", problems));
    }

    [Fact]
    public void ReadsTheJavaMembers()
    {
        var members = Members(File.ReadAllText(Path.Combine(Repo.JavaSources, "Tm1ServerContainer.java")), 1, false);
        Assert.Contains(members, m => m is { Name: "withCamSecurity", Constructor: false } && m.Params.SequenceEqual(["cognos", "namespace", "admins"]));
        Assert.Contains(members, m => m is { Name: "getRestUrl", Params.Count: 0 });
        Assert.Contains(members, m => m is { Constructor: true } && m.Params.SequenceEqual(["image", "source"]));
        Assert.DoesNotContain(members, m => m.Name is "configure" or "mountSource" or "readVersion");
        Assert.Contains(Members(File.ReadAllText(Path.Combine(Repo.JavaSources, "Tm1Source.java")), 1, true), m =>
            m is { Name: "discover", Params: ["lookup"] }
        );
    }

    [Fact]
    public void NestedTypesKeepTheirMembers()
    {
        Assert.Equal("LDAP", CognosContainer.Namespace.Ldap.Id());
        Assert.Equal("OIDC", CognosContainer.Namespace.Oidc.Id());
        Assert.NotNull(typeof(CognosContainer.User).GetProperty("Groups"));
        foreach (
            var (type, property) in new[]
            {
                (typeof(Tm1Source.PrivateImage), "Image"),
                (typeof(Tm1Source.InstallDir), "Dir"),
                (typeof(Tm1Source.Bundle), "File"),
                (typeof(Tm1Source.BundleUrl), "Sha256"),
                (typeof(CognosSource.Zip), "File"),
                (typeof(PawSource), "Zip"),
            }
        )
        {
            Assert.NotNull(type.GetProperty(property));
        }
    }

    [Theory]
    [MemberData(nameof(Annotations))]
    public void EveryAnnotationIsAnXunitAttribute(string name)
    {
        var source = File.ReadAllText(Path.Combine(Repo.JavaSources, name + ".java"));
        var type = Library.GetType("Skipcheck.Tm1.Testcontainers.Xunit." + name + "FactAttribute");
        Assert.True(type is not null, "no Skipcheck.Tm1.Testcontainers.Xunit." + name + "FactAttribute");
        Assert.True(typeof(FactAttribute).IsAssignableFrom(type));
        foreach (Match c in Regex.Matches(source, @"^\tString\s+([A-Z][A-Z_0-9]*)\s*=", RegexOptions.Multiline))
        {
            var field = type!.GetField(Pascal(c.Groups[1].Value), BindingFlags.Public | BindingFlags.Static);
            Assert.True(field is { IsLiteral: true }, $"{name}.{c.Groups[1].Value}");
            Assert.Equal(JavaMessage(source, c.Groups[1].Value), field!.GetRawConstantValue());
        }
    }

    private static Assembly Library => typeof(Tm1ServerContainer).Assembly;

    private static void CheckConstants(string name, string source, Type type, bool isInterface, List<string> problems)
    {
        var pattern = isInterface ? @"^\t(?:String|int)\s+([A-Z][A-Z_0-9]*)\s*=" : @"^\tpublic\s+static\s+final\s+[\w.<>]+\s+([A-Z][A-Z_0-9]*)\s*=";
        foreach (Match c in Regex.Matches(source, pattern, RegexOptions.Multiline))
        {
            var java = c.Groups[1].Value;
            var dotnet = Renamed.TryGetValue(name + "." + java, out var renamed) ? renamed.DotNet : Pascal(java);
            if (type.GetMember(dotnet, BindingFlags.Public | BindingFlags.Static).Length == 0)
            {
                problems.Add($"missing constant {java} → {dotnet}");
            }
        }
    }

    private static void CheckMembers(string name, List<(string Name, List<string> Params, bool Constructor)> members, Type type, List<string> problems)
    {
        foreach (var (java, parameters, constructor) in members)
        {
            if (constructor)
            {
                if (!type.GetConstructors().Any(c => Subsequence(parameters, Names(c))))
                {
                    problems.Add($"no constructor takes ({string.Join(", ", parameters)})");
                }
                continue;
            }
            var getter = Regex.Match(java, "^(get|is)([A-Z].*)$");
            var dotnet =
                getter.Success && parameters.Count == 0
                    ? (getter.Groups[1].Value == "is" ? "Is" : "") + getter.Groups[2].Value
                    : Capitalize(java) + (Io.Contains(java) ? "Async" : "");
            var found = type.GetMember(dotnet, BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static);
            if (found.Length == 0)
            {
                problems.Add($"missing {java} → {dotnet}");
                continue;
            }
            var methods = found.OfType<MethodInfo>().ToList();
            if (methods.Count == 0)
            {
                if (parameters.Count > 0)
                {
                    problems.Add($"{dotnet} is a property, but Java's {java} takes ({string.Join(", ", parameters)})");
                }
                continue;
            }
            if (!methods.Any(m => Subsequence(parameters, Names(m))))
            {
                problems.Add(
                    $"{dotnet}: no overload takes ({string.Join(", ", parameters)}); has "
                        + string.Join(" | ", methods.Select(m => "(" + string.Join(", ", Names(m)) + ")"))
                );
            }
        }
    }

    /// <summary>The public constructors and methods declared at <paramref name="depth"/> tabs.</summary>
    private static List<(string Name, List<string> Params, bool Constructor)> Members(string source, int depth, bool isInterface)
    {
        var cls = Regex.Match(source, @"(?:class|record|interface)\s+(\w+)").Groups[1].Value;
        var result = new List<(string, List<string>, bool)>();
        var indent = new string('\t', depth);
        foreach (Match m in MethodPattern().Matches(source))
        {
            if (m.Groups["indent"].Value != indent)
            {
                continue;
            }
            var mods = m.Groups["mods"].Value;
            var ret = m.Groups["ret"].Value.Trim();
            var name = m.Groups["name"].Value;
            var constructor = ret is "public" && name == cls;
            var isPublic = mods.Contains("public", StringComparison.Ordinal) || (isInterface && !ret.Contains("private", StringComparison.Ordinal));
            if (ret.EndsWith("record", StringComparison.Ordinal) || ret.EndsWith("class", StringComparison.Ordinal))
            {
                continue; // a nested type's declaration, checked as a nested type
            }
            if (
                !(isPublic || constructor)
                || ret.Contains("private", StringComparison.Ordinal)
                || ret.Contains("protected", StringComparison.Ordinal)
                || name is "toString" or "equals" or "hashCode"
            )
            {
                continue;
            }
            var parameters = Split(m.Groups["params"].Value)
                .Select(p => p.Replace("@Nullable", "", StringComparison.Ordinal).Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries)[^1].TrimStart('.'))
                .ToList();
            result.Add((name, parameters, constructor));
        }
        return result;
    }

    private static List<string> Names(MethodBase m) => [.. m.GetParameters().Select(p => p.Name!).Where(n => n != "cancellationToken")];

    /// <summary>A Java string constant's value: its literals and the constants it joins, evaluated.</summary>
    private static string JavaMessage(string source, string constant)
    {
        var expression = Regex.Match(source, $@"String\s+{constant}\s*=\s*(.*?);\s*$", RegexOptions.Singleline | RegexOptions.Multiline).Groups[1].Value;
        var text = "";
        foreach (Match part in Regex.Matches(expression, @"""((?:[^""\\]|\\.)*)""|([A-Za-z]\w*)\.([A-Z][A-Z_0-9]*)"))
        {
            if (part.Groups[1].Success)
            {
                text += Regex.Unescape(part.Groups[1].Value);
                continue;
            }
            var file = Path.Combine(Repo.JavaSources, part.Groups[2].Value + ".java");
            text += Regex.Match(File.ReadAllText(file), $@"\b{part.Groups[3].Value}\s*=\s*""([^""]*)""").Groups[1].Value;
        }
        return text;
    }

    private static bool Subsequence(List<string> expected, List<string> got)
    {
        var i = 0;
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

    private static string Pascal(string constant) =>
        string.Concat(constant.Split('_', StringSplitOptions.RemoveEmptyEntries).Select(w => w[0] + w[1..].ToLowerInvariant()));

    private static List<string> Split(string parameters)
    {
        var result = new List<string>();
        var depth = 0;
        var current = "";
        foreach (var c in parameters)
        {
            depth +=
                c == '<' ? 1
                : c == '>' ? -1
                : 0;
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
        return [.. result.Select(p => Regex.Replace(p, @"\s+", " ").Trim())];
    }

    [GeneratedRegex(
        @"^(?<indent>\t+)(?<mods>(?:(?:public|static|synchronized|final|default)\s+)*)(?:<[^>]+>\s+)?(?<ret>[\w.<>\[\]?, @]+?)\s+(?<name>\w+)\s*\((?<params>[^)]*)\)\s*(?:throws\s+[\w., ]+)?[{;]",
        RegexOptions.Multiline
    )]
    private static partial Regex MethodPattern();
}
