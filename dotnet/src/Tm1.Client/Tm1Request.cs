using System;
using System.Collections.Generic;
using System.Text.Json;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client;

/// <summary>The result of a request that reads nothing (Java's <c>Void</c>).</summary>
public sealed class NoContent
{
    public static readonly NoContent Value = new();

    private NoContent() { }
}

/// <summary>How a request's answer is read.</summary>
public enum Tm1Response
{
    /// <summary>One JSON value.</summary>
    Single,

    /// <summary>A collection (<c>{"value": […]}</c>, maybe paged), streamed item by item.</summary>
    Collection,

    Text,
    Bytes,
    None,
}

/// <summary>A request of the REST API, and how its answer is read: what services send through the
/// session, and what callers can send for anything without a service, as the Java client's
/// <c>Tm1Request</c>. Immutable: each <c>With…</c> returns a new request.</summary>
public static class Tm1Request
{
    public static Tm1Request<T> Get<T>(ODataPath path, ODataQuery? query = null) => new("GET", path, query, null, Tm1Response.Single);

    public static Tm1Request<T> List<T>(ODataPath path, ODataQuery? query = null) => new("GET", path, query, null, Tm1Response.Collection);

    public static Tm1Request<byte[]> Bytes(ODataPath path) => new("GET", path, null, null, Tm1Response.Bytes);

    public static Tm1Request<NoContent> Put(ODataPath path, byte[] content, string contentType = "application/octet-stream") =>
        new Tm1Request<NoContent>("PUT", path, null, content ?? throw new ArgumentNullException(nameof(content)), Tm1Response.None).WithHeader(
            "Content-Type",
            contentType
        );

    public static Tm1Request<string> Text(ODataPath path) => new("GET", path, null, null, Tm1Response.Text);

    public static Tm1Request<T> PostList<T>(ODataPath path, object body) =>
        new("POST", path, null, body ?? throw new ArgumentNullException(nameof(body)), Tm1Response.Collection);

    public static Tm1Request<T> Post<T>(ODataPath path, object? body) => new("POST", path, null, body ?? new Dictionary<string, object?>(), Tm1Response.Single);

    /// <summary><c>POST</c> reading no answer.</summary>
    public static Tm1Request<NoContent> Post(ODataPath path, object? body) =>
        new("POST", path, null, body ?? new Dictionary<string, object?>(), Tm1Response.None);

    public static Tm1Request<NoContent> Patch(ODataPath path, object body) =>
        new("PATCH", path, null, body ?? throw new ArgumentNullException(nameof(body)), Tm1Response.None);

    public static Tm1Request<NoContent> Delete(ODataPath path) => new("DELETE", path, null, null, Tm1Response.None);

    /// <summary>GET, PUT, DELETE and HEAD: sending one again does what sending it once did.</summary>
    internal static bool IsIdempotent(string method) => method.ToUpperInvariant() is "GET" or "PUT" or "DELETE" or "HEAD";
}

/// <summary>A request whose answer reads as <typeparamref name="T"/>.</summary>
public sealed record Tm1Request<T>
{
    internal Tm1Request(string method, ODataPath path, ODataQuery? query, object? body, Tm1Response response)
    {
        Method = method;
        Path = path ?? throw new ArgumentNullException(nameof(path));
        Query = query ?? ODataQuery.None();
        Body = body;
        Response = response;
    }

    public string Method { get; }

    public ODataPath Path { get; }

    public ODataQuery Query { get; init; }

    public IReadOnlyDictionary<string, string> Headers { get; init; } = new Dictionary<string, string>();

    public object? Body { get; }

    public Tm1Response Response { get; }

    public TimeSpan? Timeout { get; init; }

    public Tm1Capability? Requirement { get; init; }

    public Tm1Request<T> WithQuery(ODataQuery? query) => this with { Query = query ?? ODataQuery.None() };

    public Tm1Request<T> WithHeader(string name, string value)
    {
        var next = new Dictionary<string, string>(Headers)
        {
            [name ?? throw new ArgumentNullException(nameof(name))] = value ?? throw new ArgumentNullException(nameof(value)),
        };
        return this with { Headers = next };
    }

    public Tm1Request<T> WithTimeout(TimeSpan timeout) =>
        timeout <= TimeSpan.Zero ? throw new ArgumentException("timeout must be positive: " + timeout) : this with { Timeout = timeout };

    /// <summary>The request, checked against the server's <c>$metadata</c> before it's sent.</summary>
    public Tm1Request<T> Requires(Tm1Capability capability) => this with { Requirement = capability ?? throw new ArgumentNullException(nameof(capability)) };

    public Tm1Request<T> IfMatch(string etag) => WithHeader("If-Match", etag);

    /// <summary>Whether sending the request again does what sending it once did (GET, PUT, DELETE,
    /// HEAD), so it may be retried after a response was lost.</summary>
    public bool IsIdempotent() => Tm1Request.IsIdempotent(Method);

    /// <summary>The path and query, relative to the service root.</summary>
    public string RelativeUri()
    {
        var q = Query.ToQueryString();
        return Path + (q.Length == 0 ? "" : "?" + q);
    }

    internal string Accept =>
        Response switch
        {
            Tm1Response.Bytes => "*/*",
            Tm1Response.Text => "text/plain, application/json",
            _ => "application/json",
        };

    /// <summary>The body as JSON bytes, or as is for bytes.</summary>
    internal (byte[]? Content, string? ContentType) Encode() =>
        Body switch
        {
            null => (null, null),
            byte[] bytes => (bytes, Headers.TryGetValue("Content-Type", out var ct) ? ct : "application/octet-stream"),
            _ => (Json(Body), "application/json"),
        };

    private static byte[] Json(object body)
    {
        try
        {
            return JsonSerializer.SerializeToUtf8Bytes(body, body.GetType(), Tm1Json.Options);
        }
        catch (Exception e) when (e is not ArgumentException)
        {
            // The caller's mistake, not a failure talking to TM1
            throw new ArgumentException("The request body can't be written as JSON: " + body.GetType().FullName, e);
        }
    }

    public override string ToString() => $"{Method} {RelativeUri()}";
}
