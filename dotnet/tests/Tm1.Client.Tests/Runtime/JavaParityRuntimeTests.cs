// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The .NET runtime offers what the Java client's does, read from the Java sources: every
/// public method of the session, version, error, auth, OData and request classes (and their nested
/// builders) has a counterpart, named in Pascal case, with <c>Async</c> when Java returns a
/// <c>Mono</c> or <c>Flux</c>; a property stands for a no-argument accessor. What has no .NET
/// counterpart is listed in <see cref="JavaOnly"/> with the reason.</summary>
public partial class JavaParityRuntimeTests
{
    private static readonly string Java = Path.Combine(Repo.JavaServices, "..");

    /// <summary>Java class (nested ones as <c>Outer.Inner</c>) → the .NET types that hold its members.</summary>
    private static readonly Dictionary<string, (string File, string[] Types)> Classes = new()
    {
        ["Tm1Session"] = ("session/Tm1Session.java", ["Session.Tm1Session"]),
        ["Tm1Session.Builder"] = ("session/Tm1Session.java", ["Session.Tm1SessionBuilder"]),
        ["Tm1SessionBatch"] = ("session/Tm1SessionBatch.java", ["Session.Tm1SessionBatch"]),
        ["Tm1SessionPool"] = ("session/Tm1SessionPool.java", ["Session.Tm1SessionPool"]),
        ["Tm1SessionPool.Builder"] = ("session/Tm1SessionPool.java", ["Session.Tm1SessionPoolBuilder"]),
        ["Tm1Timeouts"] = ("session/Tm1Timeouts.java", ["Session.Tm1Timeouts"]),
        ["Tm1Timeouts.Builder"] = ("session/Tm1Timeouts.java", ["Session.Tm1Timeouts+TimeoutsBuilder"]),
        ["Tm1Identity"] = ("session/Tm1Identity.java", ["Session.Tm1Identity"]),
        ["Tm1Client"] = ("Tm1Client.java", ["Tm1Client"]),
        ["Tm1Client.Builder"] = ("Tm1Client.java", ["Tm1ClientBuilder"]),
        ["Tm1Batch"] = ("Tm1Batch.java", ["Tm1Batch"]),
        ["Tm1Request"] = ("Tm1Request.java", ["Tm1Request", "Tm1Request`1"]),
        ["Tm1Operation"] = ("Tm1Operation.java", ["Tm1Operation"]),
        ["ODataQuery"] = ("odata/ODataQuery.java", ["OData.ODataQuery"]),
        ["ODataPath"] = ("odata/ODataPath.java", ["OData.ODataPath"]),
        ["Filter"] = ("odata/Filter.java", ["OData.Filter"]),
        ["Filter.Variable"] = ("odata/Filter.java", ["OData.Filter+Variable"]),
        ["ODataLiterals"] = ("odata/ODataLiterals.java", ["OData.ODataLiterals"]),
        ["ODataLiterals.Raw"] = ("odata/ODataLiterals.java", ["OData.ODataLiterals+Raw"]),
        ["ODataJson"] = ("odata/ODataJson.java", ["OData.ODataJson"]),
        ["Tm1Version"] = ("version/Tm1Version.java", ["Version.Tm1Version"]),
        ["Tm1Capability"] = ("version/Tm1Capability.java", ["Version.Tm1Capability"]),
        ["Tm1Capabilities"] = ("version/Tm1Capabilities.java", ["Version.Tm1Capabilities"]),
        ["Tm1Dialect"] = ("version/Tm1Dialect.java", ["Version.Tm1Dialect"]),
        ["Tm1ErrorClassifier"] = ("error/Tm1ErrorClassifier.java", ["Errors.Tm1ErrorClassifier"]),
        ["Tm1ErrorClassifier.Rule"] = ("error/Tm1ErrorClassifier.java", ["Errors.Tm1ErrorClassifier+Rule"]),
        ["Tm1Exception"] = ("error/Tm1Exception.java", ["Errors.Tm1Exception"]),
        ["Tm1ApiException"] = ("error/Tm1ApiException.java", ["Errors.Tm1ApiException"]),
        ["Tm1TransportException"] = ("error/Tm1TransportException.java", ["Errors.Tm1TransportException"]),
        ["Tm1ProtocolException"] = ("error/Tm1ProtocolException.java", ["Errors.Tm1ProtocolException"]),
        ["Tm1UnsupportedOperationException"] = ("error/Tm1UnsupportedOperationException.java", ["Errors.Tm1UnsupportedOperationException"]),
        ["ODataError"] = ("error/ODataError.java", ["Errors.ODataError"]),
        ["Tm1Authenticator"] = ("auth/Tm1Authenticator.java", ["Auth.Tm1Authenticator"]),
        ["CognosPassports"] = ("auth/CognosPassports.java", ["Auth.CognosPassports"]),
    };

    /// <summary>Java members with no .NET counterpart, and why.</summary>
    private static readonly Dictionary<string, string> JavaOnly = new()
    {
        ["Tm1Session.Builder.jsonMapper"] = "Jackson; .NET reads and writes JSON with System.Text.Json (Tm1Json.Options)",
        ["Tm1Session.Builder.observationRegistry"] = "Micrometer; .NET has no counterpart here (wrap the HttpHandler to observe requests)",
        ["Tm1Session.Builder.webClient"] = "Spring's WebClient; .NET sends through an HttpMessageHandler: HttpHandler",
        ["Tm1Session.Builder.connector"] = "Reactor Netty's connector; .NET sends through an HttpMessageHandler: HttpHandler",
        ["Tm1Client.Builder.jsonMapper"] = "Jackson; see Tm1Session.Builder.jsonMapper",
        ["Tm1Client.Builder.observationRegistry"] = "Micrometer; see Tm1Session.Builder.observationRegistry",
        ["Tm1Client.Builder.webClient"] = "Spring's WebClient; HttpHandler",
        ["Tm1Client.Builder.connector"] = "Reactor Netty's connector; HttpHandler",
        ["Tm1SessionPool.Builder.scheduler"] = "Reactor's Scheduler; .NET takes a TimeProvider, for its clock and timers",
        ["Tm1Capabilities.of"] = "Built from a CsdlDocument, which .NET doesn't model; Tm1Capabilities.Parse reads $metadata",
        ["Tm1Capabilities.metadata"] = "The CsdlDocument, which .NET doesn't model",
        ["Tm1Request.type"] = "The answer's Class<T>; in .NET it's the type parameter T",
    };

    public static IEnumerable<object[]> Names() => Classes.Keys.Order().Select(k => new object[] { k });

    [Theory]
    [MemberData(nameof(Names))]
    public void EveryPublicJavaMemberHasADotNetCounterpart(string name)
    {
        var (file, types) = Classes[name];
        var resolved = types.Select(t => typeof(Tm1Client).Assembly.GetType("Skipcheck.Tm1.Client." + t)).ToList();
        Assert.True(resolved.All(t => t is not null), $"{name}: no {string.Join(" or ", types)}");
        var nested = name.Contains('.') ? name[(name.IndexOf('.') + 1)..] : null;
        var missing = new List<string>();
        foreach (var (member, reactive) in JavaMembers(Path.Combine(Java, file), nested))
        {
            if (member is "toString" or "equals" or "hashCode" || JavaOnly.ContainsKey($"{name}.{member}"))
            {
                continue;
            }
            var pascal = member.StartsWith("odata", StringComparison.Ordinal) ? "OData" + member[5..] : char.ToUpperInvariant(member[0]) + member[1..];
            var dotnet = pascal + (reactive ? "Async" : "");
            const BindingFlags All = BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.FlattenHierarchy;
            if (!resolved.Any(t => t!.GetMember(dotnet, All).Length > 0))
            {
                missing.Add($"{member} → {dotnet}");
            }
        }
        Assert.True(missing.Count == 0, $"{name} lacks: " + string.Join(", ", missing));
    }

    [Fact]
    public void ListsOnlyJavaMembersThatExist()
    {
        foreach (var key in JavaOnly.Keys)
        {
            var member = key[(key.LastIndexOf('.') + 1)..];
            var name = key[..key.LastIndexOf('.')];
            var nested = name.Contains('.') ? name[(name.IndexOf('.') + 1)..] : null;
            Assert.Contains(member, JavaMembers(Path.Combine(Java, Classes[name].File), nested).Select(m => m.Name));
        }
    }

    /// <summary>The public methods of a Java class, or of its nested class <paramref name="nested"/>,
    /// with whether each returns a <c>Mono</c> or <c>Flux</c>. An interface's members count as public.</summary>
    public static List<(string Name, bool Reactive)> JavaMembers(string file, string? nested)
    {
        var lines = File.ReadAllLines(file);
        var isInterface = lines.Any(l => InterfacePattern().IsMatch(l));
        var result = new List<(string, bool)>();
        string? current = null;
        foreach (var line in lines)
        {
            var type = NestedPattern().Match(line);
            if (type.Success)
            {
                current = type.Groups["name"].Value;
                continue;
            }
            if (line is "\t}" or "\t};")
            {
                current = null;
                continue;
            }
            var depth = line.Length - line.TrimStart('\t').Length;
            var wanted = nested is null ? depth == 1 && current is null : depth == 2 && current == nested;
            if (!wanted)
            {
                continue;
            }
            var trimmed = line.Trim();
            if (trimmed.StartsWith("private ", StringComparison.Ordinal) || trimmed.StartsWith("protected ", StringComparison.Ordinal))
            {
                continue;
            }
            var m = MethodPattern().Match(trimmed);
            if (
                !m.Success
                || (!m.Groups["public"].Success && !isInterface)
                || m.Groups["name"].Value is "if" or "for" or "while" or "switch" or "return" or "new"
            )
            {
                continue;
            }
            var ret = m.Groups["ret"].Value;
            result.Add((m.Groups["name"].Value, ret.StartsWith("Mono", StringComparison.Ordinal) || ret.StartsWith("Flux", StringComparison.Ordinal)));
        }
        return result;
    }

    [GeneratedRegex(@"^public\s+(sealed\s+)?interface\s+")]
    private static partial Regex InterfacePattern();

    [GeneratedRegex(@"^\t(public\s+)?(static\s+)?(final\s+)?(sealed\s+)?(class|record|interface|enum)\s+(?<name>\w+)")]
    private static partial Regex NestedPattern();

    [GeneratedRegex(
        @"^(?<public>public\s+)?(static\s+)?(default\s+)?(synchronized\s+)?(<[^>]+>\s+)?(?<ret>[\w.<>\[\]?, @]+?)\s+(?<name>\w+)\s*\([^)]*\)?\s*(throws [\w., ]+)?(\{|;)?$"
    )]
    private static partial Regex MethodPattern();
}
