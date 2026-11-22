using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Auth;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Version;

namespace AjMyers.Tm1.Client.Session;

/// <summary>An answer: a status, headers (lower-case names) and a body.</summary>
internal sealed record Reply(int Status, IReadOnlyDictionary<string, string> Headers, byte[] Body)
{
    public string Text => Encoding.UTF8.GetString(Body).TrimStart('﻿');
}

/// <summary>The login, cookie and HTTP connections every view of a session shares, as the Java
/// client's <c>Connection</c>: it logs in lazily with <c>GET ActiveSession</c> and the
/// authenticator's header (and <c>TM1-Impersonate</c>, at login only), then sends only the
/// <c>TM1SessionId</c> cookie; when TM1 answers 401 it logs in again once and resends. A login TM1
/// refused is remembered for <see cref="Tm1Timeouts.RefusedLogin"/>, so requests meanwhile fail at
/// once rather than pile up sessions on TM1.</summary>
internal sealed class Connection
{
    public const string Cookie = "TM1SessionId";
    public const string Impersonate = "TM1-Impersonate";
    public const string SessionContextHeader = "TM1-SessionContext";
    private const string LoginPath = "ActiveSession?$select=ID";
    private const string ClosePath = "ActiveSession/tm1.Close";
    internal const string Json = "application/json;odata.metadata=minimal";

    /// <summary>TM1 closes a kept-alive connection after about 90 s idle: retire ours first.</summary>
    public static readonly TimeSpan MaxIdle = TimeSpan.FromSeconds(60);

    private static readonly ConcurrentDictionary<string, Tm1Capabilities> CapabilitiesCache = new();

    private readonly HttpClient _http;
    private readonly bool _ownsHttp;
    private readonly SemaphoreSlim _lock = new(1, 1);
    private readonly Func<DateTimeOffset> _clock;
    private (string Cookie, long Id)? _session;
    private (Tm1Exception Error, DateTimeOffset At)? _refused;
    private Tm1Version? _version;
    private volatile bool _closed;

    public Connection(string root, Tm1Authenticator authenticator, string? impersonate, string? sessionContext, Tm1Timeouts timeouts,
        Tm1ErrorClassifier classifier, HttpMessageHandler? handler, bool checkCapabilities, Func<DateTimeOffset>? clock)
    {
        Root = root.EndsWith('/') ? root : root + "/";
        Authenticator = authenticator;
        ImpersonateUser = impersonate;
        SessionContext = sessionContext;
        Timeouts = timeouts;
        Classifier = classifier;
        CheckCapabilities = checkCapabilities;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        _ownsHttp = handler is null;
        _http = new HttpClient(handler ?? new SocketsHttpHandler
        {
            UseCookies = false,
            ConnectTimeout = timeouts.Connect,
            PooledConnectionIdleTimeout = MaxIdle,
            AutomaticDecompression = DecompressionMethods.All,
        }, disposeHandler: handler is null)
        {
            Timeout = System.Threading.Timeout.InfiniteTimeSpan,
        };
    }

    public string Root { get; }

    public Tm1Authenticator Authenticator { get; }

    public string? ImpersonateUser { get; }

    public string? SessionContext { get; }

    public Tm1Timeouts Timeouts { get; }

    public Tm1ErrorClassifier Classifier { get; }

    public bool CheckCapabilities { get; }

    public bool IsClosed => _closed;

    public long? SessionId => _session?.Id;

    public string Url(string relative) => new Uri(new Uri(Root), relative).ToString();

    public async Task<long> LoginAsync(CancellationToken ct) => (await CurrentAsync(null, ct).ConfigureAwait(false)).Id;

    private async Task<(string Cookie, long Id)> CurrentAsync((string Cookie, long Id)? expired, CancellationToken ct)
    {
        if (_closed)
        {
            throw new Tm1SessionClosedException($"The TM1 session at {Root} is closed");
        }
        if (_session is { } s && (expired is null || s.Cookie != expired.Value.Cookie))
        {
            return s;
        }
        await _lock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_session is { } again && (expired is null || again.Cookie != expired.Value.Cookie))
            {
                return again;
            }
            if (_refused is { } r && Timeouts.RefusedLogin > TimeSpan.Zero && _clock() - r.At < Timeouts.RefusedLogin)
            {
                throw r.Error;
            }
            try
            {
                _session = await SendLoginAsync(ct).ConfigureAwait(false);
                _refused = null;
                return _session.Value;
            }
            catch (Tm1ApiException e) when (e.Status is 400 or 401 or 403)
            {
                _session = null;
                _refused = (e, _clock());
                throw;
            }
        }
        finally
        {
            _lock.Release();
        }
    }

    private async Task<(string, long)> SendLoginAsync(CancellationToken ct)
    {
        var url = Root + LoginPath;
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        Headers(request, Json);
        request.Headers.TryAddWithoutValidation("Authorization", await Authenticator.AuthorizationAsync(ct).ConfigureAwait(false));
        if (ImpersonateUser is not null)
        {
            // At login only: TM1 decides the identity then and ignores the header later
            request.Headers.TryAddWithoutValidation(Impersonate, ImpersonateUser);
        }
        var reply = await SendRawAsync(request, Timeouts.Login, url, ct).ConfigureAwait(false);
        if (reply.Status == 401)
        {
            // TM1 answers a disabled impersonated user exactly like wrong credentials
            var why = ImpersonateUser is null ? null : new ODataError("", $"The credentials were refused, or the user being impersonated ({ImpersonateUser}) is disabled");
            throw new Tm1AuthenticationException("GET", url, 401, why, Tm1ErrorKind.CredentialRejected);
        }
        if (reply.Status >= 400)
        {
            var error = Tm1ApiException.Of("GET", url, reply.Status, reply.Body, Classifier);
            if (ImpersonateUser is not null && reply.Status == 400 && error.Kind != Tm1ErrorKind.UnknownUser)
            {
                throw new Tm1BadRequestException("GET", url, 400, error.Error, Tm1ErrorKind.UnknownUser);
            }
            if (ImpersonateUser is not null && reply.Status == 403 && error.Kind != Tm1ErrorKind.ImpersonationRefused)
            {
                throw new Tm1ForbiddenException("GET", url, 403, error.Error, Tm1ErrorKind.ImpersonationRefused);
            }
            throw error;
        }
        var cookie = SessionCookie(reply) ?? throw new Tm1ProtocolException($"TM1 at {Root} accepted the login but set no {Cookie} cookie");
        try
        {
            using var doc = JsonDocument.Parse(reply.Body);
            if (!doc.RootElement.TryGetProperty("ID", out var id) || !id.TryGetInt64(out var sessionId))
            {
                throw new Tm1ProtocolException("TM1 accepted the login but didn't return the session ID");
            }
            return (cookie, sessionId);
        }
        catch (JsonException e)
        {
            throw new Tm1ProtocolException("TM1 accepted the login but its response isn't JSON", e);
        }
    }

    /// <summary>Sends a request with the session cookie, logging in again once on 401.</summary>
    public async Task<Reply> SendAsync(string method, string url, byte[]? content, string? contentType, IReadOnlyDictionary<string, string> headers,
        string accept, TimeSpan? timeout, CancellationToken ct)
    {
        var session = await CurrentAsync(null, ct).ConfigureAwait(false);
        for (int attempt = 1; ; attempt++)
        {
            using var request = new HttpRequestMessage(new HttpMethod(method), url);
            Headers(request, accept);
            foreach (var (name, value) in headers)
            {
                if (!name.Equals("Content-Type", StringComparison.OrdinalIgnoreCase))
                {
                    request.Headers.TryAddWithoutValidation(name, value);
                }
            }
            request.Headers.TryAddWithoutValidation("Cookie", $"{Cookie}={session.Cookie}");
            if (content is not null)
            {
                request.Content = new ByteArrayContent(content);
                request.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType ?? "application/json; charset=utf-8");
            }
            var reply = await SendRawAsync(request, timeout ?? Timeouts.Request, url, ct).ConfigureAwait(false);
            if (reply.Status == 401 && attempt == 1)
            {
                session = await CurrentAsync(session, ct).ConfigureAwait(false);
                continue;
            }
            return reply;
        }
    }

    private async Task<Reply> SendRawAsync(HttpRequestMessage request, TimeSpan timeout, string url, CancellationToken ct)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(timeout);
        try
        {
            using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseContentRead, deadline.Token).ConfigureAwait(false);
            var body = await response.Content.ReadAsByteArrayAsync(deadline.Token).ConfigureAwait(false);
            var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var h in response.Headers.Concat(response.Content.Headers))
            {
                headers[h.Key.ToLowerInvariant()] = string.Join(h.Key.Equals("Set-Cookie", StringComparison.OrdinalIgnoreCase) ? "\n" : ", ", h.Value);
            }
            return new Reply((int)response.StatusCode, headers, body);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new Tm1TransportException($"{request.Method} {url}: no answer within {timeout}");
        }
        catch (HttpRequestException e)
        {
            throw new Tm1TransportException($"{request.Method} {url} failed: {e.Message}", e);
        }
    }

    private void Headers(HttpRequestMessage request, string accept)
    {
        request.Headers.TryAddWithoutValidation("OData-Version", "4.0");
        request.Headers.TryAddWithoutValidation("OData-MaxVersion", "4.0");
        request.Headers.TryAddWithoutValidation("Accept", accept);
        if (SessionContext is not null)
        {
            request.Headers.TryAddWithoutValidation(SessionContextHeader, SessionContext);
        }
    }

    /// <summary>The <c>TM1SessionId</c> a response sets, unless it expires it (<c>Max-Age=0</c>).</summary>
    internal static string? SessionCookie(Reply reply)
    {
        if (!reply.Headers.TryGetValue("set-cookie", out var all))
        {
            return null;
        }
        foreach (var header in all.Split('\n'))
        {
            var parts = header.Split(';').Select(p => p.Trim()).ToArray();
            var eq = parts[0].IndexOf('=');
            if (eq < 0 || parts[0][..eq].Trim() != Cookie)
            {
                continue;
            }
            if (parts.Skip(1).Any(p => p.Replace(" ", "", StringComparison.Ordinal).Equals("max-age=0", StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }
            return parts[0][(eq + 1)..].Trim().Trim('"');
        }
        return null;
    }

    public async Task<Tm1Version> VersionAsync(CancellationToken ct)
    {
        if (_version is null)
        {
            var url = Root + "Configuration?$select=ProductVersion";
            var reply = Check("GET", url, await SendAsync("GET", url, null, null, new Dictionary<string, string>(), Json, null, ct).ConfigureAwait(false));
            using var doc = JsonDocument.Parse(reply.Body);
            var text = doc.RootElement.TryGetProperty("ProductVersion", out var v) ? v.GetString() : null;
            _version = Tm1Version.Parse(text ?? throw new Tm1ProtocolException($"TM1 at {Root} didn't report its ProductVersion"));
        }
        return _version;
    }

    /// <summary>What the server's <c>$metadata</c> defines, read once per server and version, for
    /// every session in the process.</summary>
    public async Task<Tm1Capabilities> CapabilitiesAsync(CancellationToken ct)
    {
        var version = await VersionAsync(ct).ConfigureAwait(false);
        var key = Root + " " + version;
        if (CapabilitiesCache.TryGetValue(key, out var cached))
        {
            return cached;
        }
        var url = Root + "$metadata";
        var reply = Check("GET", url, await SendAsync("GET", url, null, null, new Dictionary<string, string>(), "application/xml", null, ct).ConfigureAwait(false));
        return CapabilitiesCache[key] = Tm1Capabilities.Parse(Encoding.UTF8.GetString(reply.Body), version);
    }

    internal static void ForgetCapabilities() => CapabilitiesCache.Clear();

    public Reply Check(string method, string url, Reply reply) =>
        reply.Status >= 400 ? throw Tm1ApiException.Of(method, url, reply.Status, reply.Body, Classifier) : reply;

    public async Task CloseAsync()
    {
        if (_closed)
        {
            return;
        }
        _closed = true;
        var session = _session;
        _session = null;
        try
        {
            if (session is { } s)
            {
                var url = Root + ClosePath;
                using var request = new HttpRequestMessage(HttpMethod.Post, url);
                Headers(request, Json);
                request.Headers.TryAddWithoutValidation("Cookie", $"{Cookie}={s.Cookie}");
                // TM1 sometimes drops the connection that carried a close: don't reuse it
                request.Headers.ConnectionClose = true;
                request.Content = new StringContent("{}", Encoding.UTF8, "application/json");
                try
                {
                    await SendRawAsync(request, Timeouts.Close, url, CancellationToken.None).ConfigureAwait(false);
                }
                catch (Tm1TransportException)
                {
                    // Closing is best effort
                }
            }
        }
        finally
        {
            if (_ownsHttp)
            {
                _http.Dispose();
            }
        }
    }

    /// <summary>The response an async monitor holds once done: an <c>application/http</c> message,
    /// whose body TM1 gzips when the request accepted gzip.</summary>
    internal static Reply AsyncResult(byte[] message)
    {
        var text = Encoding.Latin1.GetString(message);
        var end = text.IndexOf("\r\n\r\n", StringComparison.Ordinal);
        var head = end < 0 ? text : text[..end];
        var lines = head.Split("\r\n");
        var status = lines[0].Split(' ', 3);
        if (status.Length < 2 || !status[0].StartsWith("HTTP/", StringComparison.Ordinal) || !int.TryParse(status[1], out var code))
        {
            throw new Tm1ProtocolException("TM1's async result isn't an HTTP message: " + lines[0]);
        }
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon > 0)
            {
                headers[line[..colon].Trim().ToLowerInvariant()] = line[(colon + 1)..].Trim();
            }
        }
        var body = end < 0 ? [] : message[(end + 4)..];
        if (headers.TryGetValue("content-encoding", out var enc) && enc.Equals("gzip", StringComparison.OrdinalIgnoreCase) && body.Length > 0)
        {
            using var gz = new GZipStream(new MemoryStream(body), CompressionMode.Decompress);
            using var output = new MemoryStream();
            gz.CopyTo(output);
            body = output.ToArray();
        }
        return new Reply(code, headers, body);
    }
}
