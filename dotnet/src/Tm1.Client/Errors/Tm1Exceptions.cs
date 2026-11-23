// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace Skipcheck.Tm1.Client.Errors;

/// <summary>What went wrong, independent of the status TM1 chose to report it with. A
/// <see cref="Tm1ErrorClassifier"/> picks the kind from the status, TM1's error code and its
/// message, since TM1 reports the same failure with different statuses across versions.</summary>
public enum Tm1ErrorKind
{
    /// <summary>The object doesn't exist.</summary>
    NotFound,

    /// <summary>An object of that name already exists.</summary>
    AlreadyExists,

    /// <summary>The object is locked (a reserved cube, a locked element).</summary>
    Locked,

    /// <summary>The object is in use, so TM1 refused to change or delete it.</summary>
    InUse,

    /// <summary>Some other conflict with the object's state.</summary>
    Conflict,

    /// <summary>An <c>If-Match</c> ETag didn't match: someone else changed the object.</summary>
    PreconditionFailed,

    /// <summary>The session isn't logged in, or no longer is.</summary>
    Unauthenticated,

    /// <summary>The login's credentials were refused (or the impersonated user is disabled).</summary>
    CredentialRejected,

    /// <summary>The user may not do this.</summary>
    Forbidden,

    /// <summary>The user can't read the object.</summary>
    NoReadRights,

    /// <summary>The user may not impersonate the one asked for.</summary>
    ImpersonationRefused,

    /// <summary>The user to impersonate doesn't exist.</summary>
    UnknownUser,

    /// <summary>A data spread couldn't be applied.</summary>
    DataSpreadFailed,

    /// <summary>A member's unique name doesn't resolve.</summary>
    InvalidUniqueName,

    /// <summary>The operation was cancelled on TM1.</summary>
    Cancelled,

    /// <summary>TM1 doesn't implement the operation.</summary>
    NotImplemented,

    /// <summary>None of the above.</summary>
    Other,
}

/// <summary>The <c>error</c> object of an OData error response: a code and message, and optionally a
/// target, details and an inner error. TM1 sends some errors as plain text instead, read by
/// <see cref="FromText"/>.</summary>
public sealed record ODataError
{
    private const int MaxText = 1000;

    /// <summary>An error body. A null code or message reads as an empty string.</summary>
    /// <param name="code">The error code, as text.</param>
    /// <param name="message">The message.</param>
    /// <param name="target">What the error applies to, or null.</param>
    /// <param name="details">Further errors, or null for none.</param>
    /// <param name="innerError">The server's own extra information, or null.</param>
    public ODataError(string? code, string? message, string? target = null, IReadOnlyList<ODataError>? details = null, JsonElement? innerError = null)
    {
        Code = code ?? "";
        Message = message ?? "";
        Target = target;
        Details = details?.ToArray() ?? [];
        InnerError = innerError;
    }

    /// <summary>TM1's error code, or empty.</summary>
    public string Code { get; }

    /// <summary>TM1's message, or empty.</summary>
    public string Message { get; }

    /// <summary>What the error applies to, or null.</summary>
    public string? Target { get; }

    /// <summary>Further errors; empty if there are none.</summary>
    public IReadOnlyList<ODataError> Details { get; }

    /// <summary>The server's own extra information (<c>innererror</c>), or null.</summary>
    public JsonElement? InnerError { get; }

    /// <summary>The error in a JSON body, wrapped in <c>error</c> or not; null if it isn't one.</summary>
    public static ODataError? From(JsonElement? body)
    {
        if (body is not { ValueKind: JsonValueKind.Object } b)
        {
            return null;
        }
        var error = b.TryGetProperty("error", out var e) ? e : b;
        if (error.ValueKind != JsonValueKind.Object || !(error.TryGetProperty("code", out _) || error.TryGetProperty("message", out _)))
        {
            return null;
        }
        var details = new List<ODataError>();
        if (error.TryGetProperty("details", out var d) && d.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in d.EnumerateArray())
            {
                if (From(item) is { } detail)
                {
                    details.Add(detail);
                }
            }
        }
        JsonElement? inner = error.TryGetProperty("innererror", out var i) ? i.Clone() : null;
        return new ODataError(Text(error, "code"), Text(error, "message"), Text(error, "target"), details, inner);
    }

    /// <summary>The error in a plain-text body, as TM1 sends some (its <c>Error:</c> lines dropped,
    /// the rest joined on one line and cut at 1000 characters); null if there's no text.</summary>
    public static ODataError? FromText(string body)
    {
        var message = new StringBuilder();
        foreach (var line in body.Split(["\r\n", "\n", "\r"], StringSplitOptions.None))
        {
            var trimmed = line.Trim();
            if (trimmed.Length == 0 || trimmed.StartsWith("Error:", StringComparison.Ordinal))
            {
                continue;
            }
            message.Append(message.Length == 0 ? "" : " ").Append(trimmed);
        }
        if (message.Length == 0)
        {
            return null;
        }
        var text = message.Length > MaxText ? message.ToString(0, MaxText) + "…" : message.ToString();
        return new ODataError("", text);
    }

    /// <summary>The error in a response body, whatever its form: JSON, then plain text; null if empty.</summary>
    public static ODataError? Parse(byte[]? body)
    {
        if (body is null || body.Length == 0)
        {
            return null;
        }
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (From(doc.RootElement) is { } error)
            {
                return error;
            }
        }
        catch (JsonException)
        {
            // Not JSON: TM1 sends some errors as plain text, read below
        }
        return FromText(Encoding.UTF8.GetString(body).TrimStart('﻿'));
    }

    private static string? Text(JsonElement node, string field) =>
        !node.TryGetProperty(field, out var v) || v.ValueKind == JsonValueKind.Null ? null
        : v.ValueKind == JsonValueKind.String ? v.GetString()
        : v.ToString();

    /// <inheritdoc/>
    public bool Equals(ODataError? other) =>
        other is not null
        && Code == other.Code
        && Message == other.Message
        && Target == other.Target
        && Details.SequenceEqual(other.Details)
        && InnerError?.GetRawText() == other.InnerError?.GetRawText();

    /// <inheritdoc/>
    public override int GetHashCode() => HashCode.Combine(Code, Message, Target, Details.Count);
}

/// <summary>Any failure talking to TM1: TM1 refused the request (<see cref="Tm1ApiException"/>), it
/// got no complete response (<see cref="Tm1TransportException"/>), the response couldn't be read
/// (<see cref="Tm1ProtocolException"/>), or the server lacks what the request needs
/// (<see cref="Tm1UnsupportedOperationException"/>). Each says whether retrying could help and
/// whether the request may have taken effect.</summary>
public abstract class Tm1Exception : Exception
{
    /// <summary>An exception for a failed request.</summary>
    /// <param name="message">The detail message.</param>
    /// <param name="method">The request's HTTP method, or null if not known.</param>
    /// <param name="url">The request's URL, without credentials, or null if not known.</param>
    /// <param name="cause">The underlying failure, or null.</param>
    protected Tm1Exception(string message, string? method, string? url, Exception? cause)
        : base(message, cause)
    {
        Method = method;
        Url = url;
    }

    /// <summary>The request's HTTP method, when the failure is a request's.</summary>
    public string? Method { get; }

    /// <summary>The request's URL, when the failure is a request's.</summary>
    public string? Url { get; }

    /// <summary>Whether sending the request again could succeed.</summary>
    public abstract bool IsRetryable();

    /// <summary>Whether the request may have taken effect on TM1 despite the failure.</summary>
    public abstract bool MayHaveApplied();

    internal static string Describe(string? method, string? url) => method is null ? "" : $" ({method} {url})";
}

/// <summary>TM1 refused a request with an error status. The class follows the
/// <see cref="Kind"/> (a missing object is a <see cref="Tm1NotFoundException"/> whatever status TM1
/// used), then the status.</summary>
public class Tm1ApiException : Tm1Exception
{
    /// <summary>An exception for an error response.</summary>
    /// <param name="status">The HTTP status.</param>
    /// <param name="kind">What the error means.</param>
    /// <param name="error">The parsed error body, or null.</param>
    /// <param name="method">The request's HTTP method.</param>
    /// <param name="url">The request's URL, without credentials.</param>
    protected internal Tm1ApiException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(Describe(status, error, method, url), method, url, null)
    {
        Status = status;
        Kind = kind;
        Error = error;
    }

    /// <summary>The HTTP status, such as 404.</summary>
    public int Status { get; }

    /// <summary>What the error means.</summary>
    public Tm1ErrorKind Kind { get; }

    /// <summary>TM1's error, when the response had one.</summary>
    public ODataError? Error { get; }

    /// <summary>The exception for a refused request, its kind from <paramref name="classifier"/>.</summary>
    public static Tm1ApiException Of(int status, ODataError? error, string method, string url, Tm1ErrorClassifier classifier) =>
        Of(status, (classifier ?? throw new ArgumentNullException(nameof(classifier))).Classify(status, error), error, method, url);

    /// <summary>The exception for a refused request of a known kind, of the class the kind calls for.</summary>
    public static Tm1ApiException Of(int status, Tm1ErrorKind kind, ODataError? error, string method, string url) =>
        kind switch
        {
            Tm1ErrorKind.NotFound => new Tm1NotFoundException(status, kind, error, method, url),
            Tm1ErrorKind.AlreadyExists or Tm1ErrorKind.Locked or Tm1ErrorKind.InUse or Tm1ErrorKind.Conflict => new Tm1ConflictException(
                status,
                kind,
                error,
                method,
                url
            ),
            Tm1ErrorKind.PreconditionFailed => new Tm1PreconditionFailedException(status, kind, error, method, url),
            Tm1ErrorKind.Unauthenticated or Tm1ErrorKind.CredentialRejected => new Tm1AuthenticationException(status, kind, error, method, url),
            Tm1ErrorKind.Forbidden or Tm1ErrorKind.NoReadRights or Tm1ErrorKind.ImpersonationRefused => new Tm1ForbiddenException(
                status,
                kind,
                error,
                method,
                url
            ),
            Tm1ErrorKind.NotImplemented => new Tm1NotImplementedException(status, kind, error, method, url),
            _ when status >= 500 => new Tm1ServerException(status, kind, error, method, url),
            _ when status >= 400 => new Tm1BadRequestException(status, kind, error, method, url),
            _ => new Tm1ApiException(status, kind, error, method, url),
        };

    /// <summary>The exception for an error status and its body (JSON or TM1's plain text).</summary>
    internal static Tm1ApiException FromBody(int status, byte[]? body, string method, string url, Tm1ErrorClassifier classifier) =>
        Of(status, ODataError.Parse(body), method, url, classifier);

    /// <summary>429, 502, 503 and 504: TM1 (or a proxy before it) is busy or restarting.</summary>
    public override bool IsRetryable() => Status is 429 or 502 or 503 or 504;

    /// <summary>A 5xx other than 501: TM1 may have failed after applying the change.</summary>
    public override bool MayHaveApplied() => Status >= 500 && Status != 501;

    private static string Describe(int status, ODataError? error, string method, string url)
    {
        var detail = error is null || error.Message.Length == 0 ? "" : ": " + error.Message;
        var code = error is null || error.Code.Length == 0 ? "" : $" [code {error.Code}]";
        return $"TM1 returned {status}{detail}{code}{Describe(method, url)}";
    }
}

/// <summary>A 4xx TM1 rejected as invalid.</summary>
public sealed class Tm1BadRequestException : Tm1ApiException
{
    internal Tm1BadRequestException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>Not logged in, or the credentials were refused.</summary>
public sealed class Tm1AuthenticationException : Tm1ApiException
{
    internal Tm1AuthenticationException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>The user may not do this, can't read the object, or may not impersonate.</summary>
public sealed class Tm1ForbiddenException : Tm1ApiException
{
    internal Tm1ForbiddenException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>The object doesn't exist, whatever status TM1 said so with.</summary>
public sealed class Tm1NotFoundException : Tm1ApiException
{
    internal Tm1NotFoundException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>The object exists already, is locked, in use or otherwise in the way.</summary>
public sealed class Tm1ConflictException : Tm1ApiException
{
    internal Tm1ConflictException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>An ETag didn't match: someone else changed the object.</summary>
public sealed class Tm1PreconditionFailedException : Tm1ApiException
{
    internal Tm1PreconditionFailedException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>TM1 doesn't implement the operation.</summary>
public sealed class Tm1NotImplementedException : Tm1ApiException
{
    internal Tm1NotImplementedException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>TM1 failed with a 5xx.</summary>
public sealed class Tm1ServerException : Tm1ApiException
{
    internal Tm1ServerException(int status, Tm1ErrorKind kind, ODataError? error, string method, string url)
        : base(status, kind, error, method, url) { }
}

/// <summary>The request got no complete response: it couldn't be sent (<see cref="Phase.Connect"/>:
/// refused, unknown host, TLS handshake, connect timeout), so it didn't apply; or the response
/// didn't arrive whole (<see cref="Phase.Response"/>: a timeout, a dropped connection), so it may
/// have.</summary>
public sealed class Tm1TransportException : Tm1Exception
{
    /// <summary>How far a request got.</summary>
    public enum Phase
    {
        /// <summary>The request never reached TM1.</summary>
        Connect,

        /// <summary>The request was sent but no complete response came back.</summary>
        Response,
    }

    private readonly bool _idempotent;

    /// <summary>An exception for a request that got no complete response.</summary>
    /// <param name="phase">How far the request got.</param>
    /// <param name="idempotent">Whether the request is safe to repeat (GET, PUT, DELETE), which
    /// makes a response-phase failure retryable.</param>
    /// <param name="method">The request's HTTP method.</param>
    /// <param name="url">The request's URL, without credentials.</param>
    /// <param name="cause">The underlying failure, or null.</param>
    public Tm1TransportException(Phase phase, bool idempotent, string method, string url, Exception? cause)
        : base(
            (phase == Phase.Connect ? "Couldn't send the request to TM1" : "No complete response from TM1")
                + Describe(method, url)
                + (cause?.Message is { } m ? ": " + m : ""),
            method,
            url,
            cause
        )
    {
        TransportPhase = phase;
        _idempotent = idempotent;
    }

    /// <summary>Where the request failed (Java's <c>phase()</c>).</summary>
    public Phase TransportPhase { get; }

    /// <summary>Unsent requests, and idempotent ones (GET, PUT, DELETE, HEAD), may be sent again.</summary>
    public override bool IsRetryable() => TransportPhase == Phase.Connect || _idempotent;

    /// <summary>False only if the request was never sent.</summary>
    public override bool MayHaveApplied() => TransportPhase == Phase.Response;
}

/// <summary>TM1 answered with something this client can't read: not JSON, a login without a
/// cookie, an async result that isn't an HTTP message. The request may have applied.</summary>
public sealed class Tm1ProtocolException : Tm1Exception
{
    /// <summary>An exception for a response that couldn't be read.</summary>
    /// <param name="message">What was wrong with the response; the method and URL are
    /// appended.</param>
    /// <param name="error">The error TM1 reported in the response, or null.</param>
    /// <param name="method">The request's HTTP method, or null if not known.</param>
    /// <param name="url">The request's URL, without credentials, or null if not known.</param>
    /// <param name="cause">The underlying failure, such as a parse error, or null.</param>
    public Tm1ProtocolException(string message, ODataError? error, string? method, string? url, Exception? cause)
        : base(message + Describe(method, url), method, url, cause)
    {
        Error = error;
    }

    /// <summary>A protocol failure that isn't a request's.</summary>
    public Tm1ProtocolException(string message, Exception? cause = null)
        : this(message, null, null, null, cause) { }

    /// <summary>The error TM1 reported in the response, or null if it reported none.</summary>
    public ODataError? Error { get; }

    /// <summary>Never: the same response would come back.</summary>
    public override bool IsRetryable() => false;

    /// <summary>Always: TM1 had started to answer, so the request may have taken effect.</summary>
    public override bool MayHaveApplied() => true;
}

/// <summary>A request this client refuses to send because the server's <c>$metadata</c> lacks what
/// it needs (a function, an entity set, a property) or its version is too old; nothing was sent.</summary>
public sealed class Tm1UnsupportedOperationException : Tm1Exception
{
    /// <summary>An exception for a request the server can't take.</summary>
    /// <param name="message">The full message.</param>
    /// <param name="missing">What's missing, such as <c>Cube.Reserve</c>.</param>
    public Tm1UnsupportedOperationException(string message, string missing)
        : base(message, null, null, null)
    {
        Missing = missing;
    }

    /// <summary>What's missing, such as <c>Cube.Reserve</c>.</summary>
    public string Missing { get; }

    /// <summary>Never: the server won't define it on a second try.</summary>
    public override bool IsRetryable() => false;

    /// <summary>Never: the request was not sent.</summary>
    public override bool MayHaveApplied() => false;
}
