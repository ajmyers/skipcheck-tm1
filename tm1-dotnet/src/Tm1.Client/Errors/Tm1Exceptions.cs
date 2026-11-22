using System;
using System.Text.Json;

namespace AjMyers.Tm1.Client.Errors;

/// <summary>What went wrong, independent of the status TM1 chose to report it with.</summary>
public enum Tm1ErrorKind
{
    Unauthenticated,
    /// <summary>The login's credentials were refused (or the impersonated user is disabled).</summary>
    CredentialRejected,
    Forbidden,
    NotFound,
    AlreadyExists,
    Conflict,
    PreconditionFailed,
    NotImplemented,
    Locked,
    InUse,
    NoReadRights,
    ImpersonationRefused,
    UnknownUser,
    Cancelled,
    DataSpreadFailed,
    InvalidUniqueName,
    Other,
}

/// <summary>The <c>error</c> object of an OData error response, or TM1's plain-text message.</summary>
public sealed record ODataError(string? Code, string Message)
{
    public static ODataError? Parse(byte[]? body)
    {
        if (body is null || body.Length == 0)
        {
            return null;
        }
        var text = System.Text.Encoding.UTF8.GetString(body);
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object && doc.RootElement.TryGetProperty("error", out var e) && e.ValueKind == JsonValueKind.Object)
            {
                var code = e.TryGetProperty("code", out var c) ? (c.ValueKind == JsonValueKind.String ? c.GetString() : c.ToString()) : null;
                var message = e.TryGetProperty("message", out var m) ? m.ToString() : "";
                return new ODataError(code, message);
            }
        }
        catch (JsonException)
        {
            // Plain text, as TM1 sends some errors
        }
        return new ODataError(null, text.Trim());
    }
}

/// <summary>Any failure talking to TM1.</summary>
public class Tm1Exception : Exception
{
    public Tm1Exception(string message, Exception? cause = null)
        : base(message, cause)
    {
    }
}

/// <summary>The request didn't get a response: connection refused, timeout, TLS.</summary>
public class Tm1TransportException(string message, Exception? cause = null) : Tm1Exception(message, cause);

/// <summary>TM1 answered with something this client can't read.</summary>
public class Tm1ProtocolException(string message, Exception? cause = null) : Tm1Exception(message, cause);

/// <summary>A request this client refuses to send, such as one the server's <c>$metadata</c> lacks.</summary>
public class Tm1UnsupportedOperationException(string message, string? missing = null) : Tm1Exception(message)
{
    /// <summary>What's missing, such as <c>Cube.Reserve</c>.</summary>
    public string? Missing { get; } = missing;
}

/// <summary>Using a session after it was closed.</summary>
public class Tm1SessionClosedException(string message) : Tm1Exception(message);

/// <summary>TM1 refused a request.</summary>
public class Tm1ApiException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind)
    : Tm1Exception($"{method} {url} → {status}" + (error is null ? "" : ": " + error.Message + (error.Code is null ? "" : $" (code {error.Code})")))
{
    public string Method { get; } = method;

    public string Url { get; } = url;

    public int Status { get; } = status;

    public ODataError? Error { get; } = error;

    public Tm1ErrorKind Kind { get; } = kind;

    /// <summary>The exception for a refused request, of the class its kind and status call for.</summary>
    public static Tm1ApiException Of(string method, string url, int status, byte[]? body, Tm1ErrorClassifier? classifier = null)
    {
        var error = ODataError.Parse(body);
        var kind = (classifier ?? Tm1ErrorClassifier.Defaults()).Classify(status, error);
        return kind == Tm1ErrorKind.NotFound ? new Tm1NotFoundException(method, url, status, error, kind) : status switch
        {
            400 => new Tm1BadRequestException(method, url, status, error, kind),
            401 => new Tm1AuthenticationException(method, url, status, error, kind),
            403 => new Tm1ForbiddenException(method, url, status, error, kind),
            409 => new Tm1ConflictException(method, url, status, error, kind),
            412 or 428 => new Tm1PreconditionFailedException(method, url, status, error, kind),
            501 => new Tm1NotImplementedException(method, url, status, error, kind),
            >= 500 => new Tm1ServerException(method, url, status, error, kind),
            _ => new Tm1ApiException(method, url, status, error, kind),
        };
    }
}

public class Tm1BadRequestException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1AuthenticationException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1ForbiddenException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1NotFoundException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1ConflictException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1PreconditionFailedException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1NotImplementedException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);

public class Tm1ServerException(string method, string url, int status, ODataError? error, Tm1ErrorKind kind) : Tm1ApiException(method, url, status, error, kind);
