// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>Runs a live conformance scenario (spec/conformance/live/&lt;name&gt;.json), checks
/// each step's expectation, and writes what the client sent to
/// build/conformance-live/dotnet/&lt;name&gt;.jsonl for scripts/compare-live-traffic.py.</summary>
public static class ConformanceLive
{
    public static string Scenarios { get; } = Path.Combine(Repo.Conformance, "live");

    public static string TrafficDirectory { get; set; } = Path.Combine(Repo.Root, "build", "conformance-live");

    /// <summary>The session's own requests, which differ by when each client logs in and caches capabilities.</summary>
    private static readonly string[] SessionRequests = ["ActiveSession", "$metadata", "Server?$select=ProductVersion"];

    public static IEnumerable<string> Names() =>
        Directory.GetFiles(Scenarios, "*.json").Select(Path.GetFileNameWithoutExtension).Order(StringComparer.Ordinal)!;

    public static JsonObject Load(string name) => JsonNode.Parse(File.ReadAllText(Path.Combine(Scenarios, name + ".json")))!.AsObject();

    /// <summary>A fresh name for each placeholder: letters, digits and _ only, so no client encodes them differently.</summary>
    public static Dictionary<string, string> NamesFor(JsonObject scenario)
    {
        var run = Guid.NewGuid().ToString("N")[..8];
        return scenario["names"]!.AsArray().Select(n => n!.GetValue<string>()).ToDictionary(n => n, n => $"tm1conf_{run}_{n}");
    }

    public static JsonNode? Substitute(JsonNode? node, IReadOnlyDictionary<string, string> names) =>
        node switch
        {
            null => null,
            JsonArray a => new JsonArray(a.Select(x => Substitute(x, names)).ToArray()),
            JsonObject o => new JsonObject(o.Select(p => KeyValuePair.Create(Replace(p.Key, names), Substitute(p.Value, names)))),
            JsonValue v when v.GetValueKind() == System.Text.Json.JsonValueKind.String => JsonValue.Create(Replace(v.GetValue<string>(), names)),
            _ => node.DeepClone(),
        };

    private static string Replace(string s, IReadOnlyDictionary<string, string> names) =>
        names.Aggregate(s, (acc, n) => acc.Replace("${" + n.Key + "}", n.Value, StringComparison.Ordinal));

    /// <summary>A handler that records what it sends, labelled with the step.</summary>
    public sealed class Traffic(HttpMessageHandler inner, string root) : DelegatingHandler(inner)
    {
        public string Step { get; set; } = "";

        public List<JsonObject> Exchanges { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = request.Content is null ? [] : await request.Content.ReadAsByteArrayAsync(cancellationToken);
            var headers = request.Headers.ToDictionary(h => h.Key.ToLowerInvariant(), h => string.Join(", ", h.Value));
            foreach (var h in request.Content?.Headers ?? Enumerable.Empty<KeyValuePair<string, IEnumerable<string>>>())
            {
                headers[h.Key.ToLowerInvariant()] = string.Join(", ", h.Value);
            }
            var response = await base.SendAsync(request, cancellationToken);
            // As sent, encoded: ToString() would decode it
            var url = request.RequestUri!.AbsoluteUri;
            var prefix = root.EndsWith('/') ? root : root + "/";
            var target = url.StartsWith(prefix, StringComparison.Ordinal) ? url[prefix.Length..] : url;
            if (!SessionRequests.Any(s => target.StartsWith(s, StringComparison.Ordinal)))
            {
                Exchanges.Add(
                    new JsonObject
                    {
                        ["step"] = Step,
                        ["request"] = ConformanceCodec.Request(request.Method.Method, target, headers, body),
                        ["status"] = (int)response.StatusCode,
                    }
                );
            }
            return response;
        }

        public string Write(string scenario, IReadOnlyDictionary<string, string> names)
        {
            var dir = Path.Combine(TrafficDirectory, "dotnet");
            Directory.CreateDirectory(dir);
            var file = Path.Combine(dir, scenario + ".jsonl");
            var head = new JsonObject
            {
                ["scenario"] = scenario,
                ["language"] = "dotnet",
                ["names"] = new JsonObject(names.Select(n => KeyValuePair.Create(n.Key, (JsonNode?)n.Value))),
            };
            File.WriteAllLines(file, new[] { head }.Concat(Exchanges).Select(x => x.ToJsonString()));
            return file;
        }
    }

    /// <summary>A model result's JSON with its type as <c>@odata.type</c>, for an expectation that names it:
    /// a result's JSON leaves out the type, which its <c>$model</c> gives.</summary>
    private static JsonNode? Typed(JsonObject result)
    {
        if (result["json"] is JsonObject o && !o.ContainsKey("@odata.type"))
        {
            var typed = (JsonObject)o.DeepClone();
            typed["@odata.type"] = "#ibm.tm1.api.v1." + result["$model"]!.GetValue<string>();
            return typed;
        }
        return result["json"];
    }

    /// <summary>Why the outcome doesn't meet the expectation, or null if it does.</summary>
    public static string? Check(JsonObject expect, JsonObject outcome)
    {
        if (expect["error"] is JsonObject error)
        {
            return outcome["error"] is JsonObject got && error.All(p => JsonNode.DeepEquals(p.Value, got[p.Key]))
                ? null
                : $"expected the error {error.ToJsonString()}, got {outcome.ToJsonString()}";
        }
        if (outcome["error"] is JsonObject failed)
        {
            return "failed with " + failed.ToJsonString();
        }
        var result = outcome["result"];
        var json = result is JsonObject m && m.ContainsKey("$model") ? Typed(m) : result;
        if (expect["none"] is not null && result is not null)
        {
            return "expected nothing, got " + result.ToJsonString();
        }
        if (expect["count"] is JsonValue count && (result is not JsonArray list || list.Count != count.GetValue<int>()))
        {
            return $"expected {count} items, got {result?.ToJsonString()}";
        }
        if (expect["fields"] is JsonObject fields && (json is not JsonObject o || fields.Any(f => !Same(f.Value, o[f.Key]))))
        {
            return $"expected the fields {fields.ToJsonString()}, got {result?.ToJsonString()}";
        }
        if (expect["items"] is JsonObject items)
        {
            var values = (result as JsonArray ?? []).Select(i => i is JsonObject x && x.ContainsKey("$model") ? x["json"] : i).ToList();
            foreach (var (field, want) in items)
            {
                var got = new JsonArray(values.Select(v => v?[field]?.DeepClone()).ToArray());
                if (!Same(want, got))
                {
                    return $"expected the items' {field} {want!.ToJsonString()}, got {got.ToJsonString()}";
                }
            }
        }
        return null;
    }

    /// <summary>Equal JSON at any depth: numbers by their value (11 as an int or a double), and a
    /// null as a property left out, as clients differ in whether a model's JSON keeps a null.</summary>
    public static bool Same(JsonNode? expected, JsonNode? got)
    {
        if (expected is null || got is null)
        {
            return expected is null && got is null;
        }
        if (expected is JsonObject e && got is JsonObject g)
        {
            return e.Select(p => p.Key).Union(g.Select(p => p.Key)).All(k => Same(e[k], g[k]));
        }
        if (expected is JsonArray ea && got is JsonArray ga)
        {
            return ea.Count == ga.Count && ea.Zip(ga).All(p => Same(p.First, p.Second));
        }
        if (
            expected is JsonValue ev
            && got is JsonValue gv
            && ev.GetValueKind() == System.Text.Json.JsonValueKind.Number
            && gv.GetValueKind() == System.Text.Json.JsonValueKind.Number
        )
        {
            return ev.GetValue<decimal>() == gv.GetValue<decimal>();
        }
        return JsonNode.DeepEquals(expected, got);
    }

    private static async Task<JsonObject> Outcome(Tm1Client client, JsonNode step, IReadOnlyDictionary<string, string> names)
    {
        var service = typeof(Tm1Client).GetProperty(ConformanceCodec.Pascal(step["service"]!.GetValue<string>()))!.GetValue(client)!;
        var parameters = step["params"]!.AsArray().Select(p => p!.GetValue<string>()).ToList();
        var (method, values) = ConformanceCodec.Bind(
            service.GetType(),
            ConformanceCodec.Pascal(step["method"]!.GetValue<string>()) + "Async",
            parameters,
            Substitute(step["args"], names)!.AsArray()
        );
        try
        {
            return new JsonObject { ["result"] = await ConformanceCodec.Call(service, method, values) };
        }
        catch (UnknownConformanceValueException)
        {
            throw;
        }
        catch (Exception e)
        {
            return new JsonObject { ["error"] = ConformanceCodec.Error(e) };
        }
    }

    /// <summary>Keeps what a step's <c>"save"</c> names from its result, <c>{"note": "ID"}</c>: the result's
    /// <c>ID</c> as the name <c>note</c>, for the steps after it, such as an ID TM1 made up. Why it can't, or
    /// null.</summary>
    public static string? Save(JsonNode step, JsonObject outcome, Dictionary<string, string> names)
    {
        foreach (var (name, field) in step["save"]?.AsObject() ?? [])
        {
            var result = outcome["result"];
            var json = result is JsonObject m && m.ContainsKey("$model") ? m["json"] : result;
            var key = field!.GetValue<string>();
            if ((json as JsonObject)?[key] is not JsonValue value || value.GetValueKind() == System.Text.Json.JsonValueKind.Null)
            {
                return $"no {key} to save as {name} in {result?.ToJsonString() ?? "null"}";
            }
            names[name] = value.GetValueKind() == System.Text.Json.JsonValueKind.String ? value.GetValue<string>() : value.ToJsonString();
        }
        return null;
    }

    /// <summary>Runs the scenario; the steps whose expectation failed. Its <c>finally</c> steps always run.
    /// What a step saves joins <paramref name="names"/>, so the traffic written after it has TM1's made-up
    /// values as their placeholders too.</summary>
    public static async Task<List<string>> Run(Tm1Client client, Traffic traffic, JsonObject scenario, Dictionary<string, string> names)
    {
        var failures = new List<string>();
        try
        {
            foreach (var (step, i) in scenario["steps"]!.AsArray().Select((s, i) => (s!, i)))
            {
                traffic.Step = $"steps[{i}]";
                var outcome = await Outcome(client, step, names);
                var why = Check(Substitute(step["expect"], names)!.AsObject(), outcome) ?? Save(step, outcome, names);
                if (why is not null)
                {
                    failures.Add($"step {i} {step["service"]}.{step["method"]}: {why}");
                    break;
                }
            }
        }
        finally
        {
            foreach (var (step, i) in (scenario["finally"]?.AsArray() ?? []).Select((s, i) => (s!, i)))
            {
                traffic.Step = $"finally[{i}]";
                await Outcome(client, step, names);
            }
        }
        return failures;
    }
}
