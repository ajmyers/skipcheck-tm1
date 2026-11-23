using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>Replays the Java client's recorded service calls (spec/conformance/calls): the same
/// call, with the same arguments, must send exactly the requests the Java client sent and, given
/// the answers it got, return the same result or fail the same way.</summary>
public class ConformanceCallsTests
{
    private static readonly string Calls = Path.Combine(Repo.Conformance, "calls");

    private static readonly string UnsupportedFile = Path.Combine(Repo.Root, "dotnet", "conformance", "unsupported.txt");

    private static readonly Lazy<IReadOnlyDictionary<string, (JsonObject Session, JsonObject Case)>> All = new(Load);

    private static Dictionary<string, (JsonObject, JsonObject)> Load()
    {
        var cases = new Dictionary<string, (JsonObject, JsonObject)>(StringComparer.Ordinal);
        foreach (var file in Directory.GetFiles(Calls, "*.json").Order(StringComparer.Ordinal))
        {
            var fixture = JsonNode.Parse(File.ReadAllText(file))!.AsObject();
            if (fixture["specVersion"]!.GetValue<int>() != ConformanceCodec.SpecVersion)
            {
                throw new InvalidOperationException($"{file}: unknown specVersion {fixture["specVersion"]}");
            }
            foreach (var c in fixture["cases"]!.AsArray())
            {
                cases.Add(c!["id"]!.GetValue<string>(), (fixture["session"]!.AsObject(), c.AsObject()));
            }
        }
        return cases;
    }

    /// <summary>Cases this client can't express, each with its reason: <c>id  reason</c>.</summary>
    private static Dictionary<string, string> Unsupported() =>
        !File.Exists(UnsupportedFile)
            ? []
            : File.ReadAllLines(UnsupportedFile)
                .Select(l => l.Trim())
                .Where(l => l.Length > 0 && !l.StartsWith('#'))
                .ToDictionary(l => l.Split(' ', 2)[0], l => l.Split(' ', 2) is [_, var r] ? r.Trim() : "", StringComparer.Ordinal);

    public static IEnumerable<object[]> Cases() => All.Value.Keys.Order(StringComparer.Ordinal).Select(id => new object[] { id });

    [Fact]
    public void HasFixturesAndListsOnlyRealCasesAsUnsupported()
    {
        Assert.NotEmpty(All.Value);
        Assert.All(Unsupported().Keys, id => Assert.True(All.Value.ContainsKey(id), "unsupported.txt lists " + id + ", which no fixture has"));
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task ReplaysTheJavaCall(string id)
    {
        if (Unsupported().ContainsKey(id))
        {
            return;
        }
        var (session, c) = All.Value[id];
        var fake = new FakeTm1 { ProductVersion = session["productVersion"]!.GetValue<string>() };
        // Each entry a list of exchanges: one, or an unordered group's, answered by the equal request
        var entries = new Queue<List<JsonNode>>(
            c["exchanges"]!.AsArray().Select(x => x!["unordered"] is JsonArray group ? group.Select(g => g!).ToList() : [x!])
        );
        var answered = new List<(JsonNode Sent, JsonNode Expected)>();
        fake.Respond = r =>
        {
            lock (answered)
            {
                return Respond(r);
            }
        };
        HttpResponseMessage Respond(FakeTm1.Recorded r)
        {
            while (entries.Count > 0 && entries.Peek().Count == 0)
            {
                entries.Dequeue();
            }
            if (entries.Count == 0)
            {
                return new HttpResponseMessage((HttpStatusCode)599);
            }
            var group = entries.Peek();
            var sent = ConformanceCodec.Request(r.Method, r.Target, r.Headers, r.Body);
            var exchange = group.FirstOrDefault(x => ConformanceCodec.Comparable(x["request"]) == ConformanceCodec.Comparable(sent)) ?? group[0];
            group.Remove(exchange);
            answered.Add((sent, exchange["request"]!));
            return Answer(exchange["response"]!);
        }
        var client = fake.Client();

        var service = typeof(Tm1Client).GetProperty(ConformanceCodec.Pascal(c["service"]!.GetValue<string>()))?.GetValue(client)
            ?? throw new UnknownConformanceValueException("Tm1Client has no " + c["service"]);
        var names = c["params"]!.AsArray().Select(p => p!.GetValue<string>()).ToList();
        var args = c["args"]!.AsArray();
        var (method, values) = ConformanceCodec.Bind(service.GetType(), ConformanceCodec.Pascal(c["method"]!.GetValue<string>()) + "Async", names, args);

        JsonObject outcome;
        try
        {
            outcome = new JsonObject { ["result"] = await ConformanceCodec.Call(service, method, values, c["take"]?.GetValue<int>()) };
        }
        catch (UnknownConformanceValueException)
        {
            throw;
        }
        catch (Exception e) when (e is not Xunit.Sdk.XunitException)
        {
            outcome = new JsonObject { ["error"] = ConformanceCodec.Error(e) };
        }

        // Each request against the recorded one it was answered with
        var recorded = c["exchanges"]!.AsArray().Sum(x => x!["unordered"] is JsonArray group ? group.Count : 1);
        foreach (var (sent, expected) in answered)
        {
            Assert.Equal(ConformanceCodec.Comparable(expected), ConformanceCodec.Comparable(sent));
        }
        Assert.True(recorded == fake.Requests.Count, $"sent {fake.Requests.Count} requests, Java sent {recorded}; .NET gave {outcome.ToJsonString()}");

        if (c["error"] is JsonObject error)
        {
            Assert.True(
                outcome["error"] is JsonObject got && ConformanceCodec.SameError(error, got),
                $"Java failed with {error.ToJsonString()}, .NET gave {outcome.ToJsonString()}"
            );
        }
        else
        {
            Assert.True(outcome.ContainsKey("result"), $"Java gave {c["result"]?.ToJsonString()}, .NET failed with {outcome.ToJsonString()}");
            Assert.Equal(ConformanceCodec.Comparable(c["result"], dropNulls: true), ConformanceCodec.Comparable(outcome["result"], dropNulls: true));
        }
    }

    private static HttpResponseMessage Answer(JsonNode r)
    {
        var message = new HttpResponseMessage((HttpStatusCode)r["status"]!.GetValue<int>());
        HttpContent? content =
            r["json"] is { } json ? new ByteArrayContent(Encoding.UTF8.GetBytes(json.ToJsonString()))
            : r["text"] is { } text ? new ByteArrayContent(Encoding.UTF8.GetBytes(text.GetValue<string>()))
            : r["bytes"] is { } bytes ? new ByteArrayContent(Convert.FromBase64String(bytes.GetValue<string>()))
            : null;
        if (content is not null)
        {
            message.Content = content;
        }
        foreach (var (key, value) in r["headers"]?.AsObject() ?? [])
        {
            if (key == "content-type")
            {
                (message.Content ??= new ByteArrayContent([])).Headers.ContentType = MediaTypeHeaderValue.Parse(value!.GetValue<string>());
            }
            else if (!message.Headers.TryAddWithoutValidation(key, value!.GetValue<string>()))
            {
                (message.Content ??= new ByteArrayContent([])).Headers.TryAddWithoutValidation(key, value!.GetValue<string>());
            }
        }
        return message;
    }
}
