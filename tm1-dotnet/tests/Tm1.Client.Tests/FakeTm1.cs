using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>Where the repository's shared files are.</summary>
public static class Repo
{
    public static string Root { get; } = Find();

    public static string Spec => Path.Combine(Root, "spec", "tm1-api.json");

    public static string Conformance => Path.Combine(Root, "spec", "conformance");

    public static string Payloads => Path.Combine(Root, "tm1-client", "src", "test", "resources", "payloads", "11.8.35");

    public static string Snapshot => Path.Combine(Root, "tm1-client", "src", "model", "metadata", "tm1-schema-11.8.35.xml");

    public static string JavaServices => Path.Combine(Root, "tm1-client", "src", "main", "java", "io", "skipcheck", "tm1", "client", "service");

    private static string Find()
    {
        var dir = AppContext.BaseDirectory;
        while (dir is not null && !File.Exists(Path.Combine(dir, "spec", "tm1-api.json")))
        {
            dir = Path.GetDirectoryName(dir);
        }
        return dir ?? throw new InvalidOperationException("Can't find the repository root (spec/tm1-api.json)");
    }
}

/// <summary>A fake TM1: answers logins with a session cookie, <c>Configuration</c>'s
/// <c>ProductVersion</c> and <c>$metadata</c> with the snapshot, records every other request, and
/// answers it with <see cref="Respond"/>.</summary>
public sealed class FakeTm1 : HttpMessageHandler
{
    public const string Base = "http://tm1:8010/api/v1/";

    private static readonly byte[] SnapshotBytes = File.ReadAllBytes(Repo.Snapshot);

    public List<Recorded> Requests { get; } = new();

    public int Logins { get; private set; }

    public byte[] Metadata { get; set; } = SnapshotBytes;

    public string ProductVersion { get; set; } = "11.8.03500.6";

    /// <summary>The answer to a recorded request: JSON (any object), a string (text), null (204), or an <see cref="HttpResponseMessage"/>.</summary>
    public Func<Recorded, object?> Respond { get; set; } = _ => new Dictionary<string, object?>();

    public Recorded Last => Requests[^1];

    public sealed record Recorded(string Method, string Target, IReadOnlyDictionary<string, string> Headers, byte[] Body)
    {
        public JsonElement Json => JsonDocument.Parse(Body).RootElement;

        public string Text => Encoding.UTF8.GetString(Body);
    }

    public Tm1Session Session(Func<Tm1SessionBuilder, Tm1SessionBuilder>? configure = null)
    {
        var builder = Tm1Session.Builder().BaseUrl(Base).BasicAuth("admin", "apple").HttpHandler(this);
        return (configure?.Invoke(builder) ?? builder).Build();
    }

    public Tm1Client Client(Func<Tm1SessionBuilder, Tm1SessionBuilder>? configure = null) => Tm1Client.Of(Session(configure));

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var path = request.RequestUri!.PathAndQuery;
        if (path == "/api/v1/ActiveSession?$select=ID" && request.Headers.Contains("Authorization"))
        {
            Logins++;
            var login = Json(new Dictionary<string, object?> { ["ID"] = Logins });
            login.Headers.Add("Set-Cookie", $"TM1SessionId=s{Logins}; Path=/api/; HttpOnly");
            return login;
        }
        if (path == "/api/v1/Server?$select=ProductVersion")
        {
            return Json(new Dictionary<string, object?> { ["ProductVersion"] = ProductVersion });
        }
        if (path == "/api/v1/$metadata")
        {
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Metadata) };
        }
        var body = request.Content is null ? [] : await request.Content.ReadAsByteArrayAsync(cancellationToken);
        var headers = request.Headers.ToDictionary(h => h.Key.ToLowerInvariant(), h => string.Join(", ", h.Value));
        if (request.Content is not null)
        {
            foreach (var h in request.Content.Headers)
            {
                headers[h.Key.ToLowerInvariant()] = string.Join(", ", h.Value);
            }
        }
        var recorded = new Recorded(request.Method.Method, path["/api/v1/".Length..], headers, body);
        lock (Requests)
        {
            Requests.Add(recorded);
        }
        return Respond(recorded) switch
        {
            HttpResponseMessage m => m,
            null => new HttpResponseMessage(HttpStatusCode.NoContent),
            string s => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(s) },
            var o => Json(o),
        };
    }

    public static HttpResponseMessage Json(object value, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status) { Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json") };

    public static Dictionary<string, object?> Collection(params object[] items) => new() { ["value"] = items };
}
