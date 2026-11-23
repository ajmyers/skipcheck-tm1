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
    /// <summary>The one value.</summary>
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

    /// <summary>Plain text, such as <c>$value</c> or <c>$count</c>.</summary>
    Text,

    /// <summary>Raw bytes, such as a document's content.</summary>
    Bytes,

    /// <summary>No content (or content that's ignored).</summary>
    None,
}

/// <summary>A request of the REST API, and how its answer is read: what services send through the
/// session, and what callers can send for anything without a service, as the Java client's
/// <c>Tm1Request</c>. Immutable: each <c>With…</c> returns a new request.</summary>
public static class Tm1Request
{
    /// <summary><c>GET</c> of one entity or value, read as <typeparamref name="T"/>.</summary>
    public static Tm1Request<T> Get<T>(ODataPath path, ODataQuery? query = null) => new("GET", path, query, null, Tm1Response.Single);

    /// <summary><c>GET</c> of a collection, read item by item as
    /// <typeparamref name="T"/>.</summary>
    public static Tm1Request<T> List<T>(ODataPath path, ODataQuery? query = null) => new("GET", path, query, null, Tm1Response.Collection);

    /// <summary><c>GET</c> of raw bytes, such as a document's <c>Content</c>.</summary>
    public static Tm1Request<byte[]> Bytes(ODataPath path) => new("GET", path, null, null, Tm1Response.Bytes);

    /// <summary><c>PUT</c> of raw bytes, such as a document's <c>Content</c>. TM1 (11.8) refuses a
    /// chunked body (400, with no message), so the bytes are sent with their
    /// <c>Content-Length</c>.</summary>
    public static Tm1Request<NoContent> Put(ODataPath path, byte[] content, string contentType = "application/octet-stream") =>
        new Tm1Request<NoContent>("PUT", path, null, content ?? throw new ArgumentNullException(nameof(content)), Tm1Response.None).WithHeader(
            "Content-Type",
            contentType
        );

    /// <summary><c>GET</c> of plain text, such as
    /// <c>Configuration/ProductVersion/$value</c>.</summary>
    public static Tm1Request<string> Text(ODataPath path) => new("GET", path, null, null, Tm1Response.Text);

    /// <summary><c>POST</c> whose response is a collection, read item by item as
    /// <typeparamref name="T"/>: an action returning <c>Collection(...)</c>, such as
    /// <c>tm1.CheckRules</c>.</summary>
    public static Tm1Request<T> PostList<T>(ODataPath path, object body) =>
        new("POST", path, null, body ?? throw new ArgumentNullException(nameof(body)), Tm1Response.Collection);

    /// <summary><c>POST</c>: create an entity, or invoke an action. Actions always get a JSON body
    /// (an empty object for a null <paramref name="body"/>), since TM1 refuses an action without a
    /// JSON content type.</summary>
    public static Tm1Request<T> Post<T>(ODataPath path, object? body) => new("POST", path, null, body ?? new Dictionary<string, object?>(), Tm1Response.Single);

    /// <summary><c>POST</c> reading no answer.</summary>
    public static Tm1Request<NoContent> Post(ODataPath path, object? body) =>
        new("POST", path, null, body ?? new Dictionary<string, object?>(), Tm1Response.None);

    /// <summary><c>PATCH</c>: change the properties in <paramref name="body"/>, leaving the rest as
    /// they are.</summary>
    public static Tm1Request<NoContent> Patch(ODataPath path, object body) =>
        new("PATCH", path, null, body ?? throw new ArgumentNullException(nameof(body)), Tm1Response.None);

    /// <summary><c>DELETE</c>.</summary>
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

    /// <summary>The HTTP method.</summary>
    public string Method { get; }

    /// <summary>The resource path, relative to the service root.</summary>
    public ODataPath Path { get; }

    /// <summary>The query options, possibly <see cref="ODataQuery.None"/>.</summary>
    public ODataQuery Query { get; init; }

    /// <summary>The headers this request adds.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; init; } = new Dictionary<string, string>();

    /// <summary>The body, serialized as JSON when sent (bytes are sent as they are), or null for
    /// none.</summary>
    public object? Body { get; }

    /// <summary>How the response body is read.</summary>
    public Tm1Response Response { get; }

    /// <summary>This request's own timeout, or null for the session's.</summary>
    public TimeSpan? Timeout { get; init; }

    /// <summary>What this request needs the server to define, or null.</summary>
    public Tm1Capability? Requirement { get; init; }

    /// <summary>A copy with these query options instead.</summary>
    public Tm1Request<T> WithQuery(ODataQuery? query) => this with { Query = query ?? ODataQuery.None() };

    /// <summary>A copy with a header added or replaced, such as <c>If-Match</c>.</summary>
    public Tm1Request<T> WithHeader(string name, string value)
    {
        var next = new Dictionary<string, string>(Headers)
        {
            [name ?? throw new ArgumentNullException(nameof(name))] = value ?? throw new ArgumentNullException(nameof(value)),
        };
        return this with { Headers = next };
    }

    /// <summary>A copy that may go <paramref name="timeout"/> without receiving any data before it
    /// fails, instead of the session's request timeout. For calls TM1 only answers when the work is
    /// done, such as a long process run.</summary>
    /// <exception cref="ArgumentException"><paramref name="timeout"/> isn't positive.</exception>
    public Tm1Request<T> WithTimeout(TimeSpan timeout) =>
        timeout <= TimeSpan.Zero ? throw new ArgumentException("timeout must be positive: " + timeout) : this with { Timeout = timeout };

    /// <summary>The request, checked against the server's <c>$metadata</c> before it's sent.</summary>
    public Tm1Request<T> Requires(Tm1Capability capability) => this with { Requirement = capability ?? throw new ArgumentNullException(nameof(capability)) };

    /// <summary>A copy sent with <c>If-Match: etag</c>, failing with 412 if the entity has changed
    /// since.</summary>
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

    /// <summary><c>METHOD relative-uri</c>, for logs.</summary>
    public override string ToString() => $"{Method} {RelativeUri()}";
}
