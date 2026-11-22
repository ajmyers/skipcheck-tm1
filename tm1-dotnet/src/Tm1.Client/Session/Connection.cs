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
    internal const string Json = "application/json";

    /// <summary>TM1 closes a kept-alive connection after about 90 s idle: retire ours first.</summary>
    public static readonly TimeSpan MaxIdle = TimeSpan.FromSeconds(60);

    private readonly HttpClient _http;
    private readonly bool _ownsHttp;
    private readonly object _gate = new();
    private readonly Func<DateTimeOffset> _clock;
    private readonly CapabilitiesCache _capabilitiesCache;
    private Attempt? _attempt;
    private (Task<Tm1Version> Read, Attempt For)? _version;
    private bool _closed;
    private Task? _closing;

    public Connection(string root, Tm1Authenticator authenticator, string? impersonate, string? sessionContext, Tm1Timeouts timeouts,
        Tm1ErrorClassifier classifier, HttpMessageHandler? handler, bool checkCapabilities, Func<DateTimeOffset>? clock, CapabilitiesCache? capabilitiesCache = null)
    {
        _capabilitiesCache = capabilitiesCache ?? new CapabilitiesCache();
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

    public bool IsClosed
    {
        get
        {
            lock (_gate)
            {
                return _closed;
            }
        }
    }

    /// <summary>The logged-in session's ID, if it's logged in.</summary>
    public long? SessionId
    {
        get
        {
            var login = _attempt?.Login;
            return login is { IsCompletedSuccessfully: true } ? login.Result.Id : null;
        }
    }

    public string Url(string relative) => new Uri(new Uri(Root), relative).ToString();

    public async Task<long> LoginAsync(CancellationToken ct) => (await Current().Login.WaitAsync(ct).ConfigureAwait(false)).Id;

    /// <summary>One login, shared by every request that needs it while it runs. A failed login is
    /// forgotten, so the next request tries again, unless TM1 refused it (400, 401, 403): then it's
    /// kept for <see cref="Tm1Timeouts.RefusedLogin"/>, so requests meanwhile fail at once rather
    /// than pile up sessions on TM1.</summary>
    private sealed class Attempt
    {
        private readonly Connection _connection;
        private readonly string? _seedCookie;

        public Attempt(Connection connection, string? seedCookie)
        {
            _connection = connection;
            _seedCookie = seedCookie;
        }

        /// <summary>Starts the login, once the attempt is the connection's current one (so a quick
        /// failure finds it there).</summary>
        public Attempt Start()
        {
            Login = Task.Run(async () =>
            {
                try
                {
                    return await _connection.SendLoginAsync(_seedCookie).ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    _connection.Failed(this, e);
                    throw;
                }
            });
            return this;
        }

        public Task<(string Cookie, long Id)> Login { get; private set; } = null!;

        public DateTimeOffset? RefusedAt { get; set; }
    }

    private bool RefusedRecently(Attempt a) => a.RefusedAt is { } at && _clock() - at < Timeouts.RefusedLogin;

    private Attempt Current()
    {
        lock (_gate)
        {
            if (_closed)
            {
                throw new Tm1SessionClosedException(Root);
            }
            if (_attempt is null || (_attempt.RefusedAt is not null && !RefusedRecently(_attempt)))
            {
                _attempt = new Attempt(this, null);
                _attempt.Start();
            }
            return _attempt;
        }
    }

    /// <summary>A new login after <paramref name="expired"/>'s session was refused with 401,
    /// unless another request already started one. It sends the cookie TM1 set with the 401, so
    /// TM1 logs in the session it opened for it rather than opening another.</summary>
    private Attempt Relogin(Attempt expired, string? seedCookie)
    {
        lock (_gate)
        {
            if (_closed)
            {
                throw new Tm1SessionClosedException(Root);
            }
            if (_attempt is not null && RefusedRecently(_attempt))
            {
                return _attempt;
            }
            if (_attempt == expired || _attempt is null || _attempt.RefusedAt is not null)
            {
                _attempt = new Attempt(this, seedCookie);
                _attempt.Start();
            }
            return _attempt;
        }
    }

    private void Failed(Attempt failed, Exception error)
    {
        lock (_gate)
        {
            if (_attempt != failed)
            {
                return;
            }
            if (error is Tm1ApiException { Status: 400 or 401 or 403 } && Timeouts.RefusedLogin > TimeSpan.Zero)
            {
                failed.RefusedAt = _clock();
            }
            else
            {
                _attempt = null;
            }
        }
    }

    private async Task<(string, long)> SendLoginAsync(string? seedCookie)
    {
        var url = Root + LoginPath;
        // The authenticator's own failures (say, a passport it couldn't get) reach the caller unchanged
        var authorization = await Authenticator.AuthorizationAsync(CancellationToken.None).ConfigureAwait(false)
            ?? throw new InvalidOperationException($"The authenticator {Authenticator.Describe()} supplied no credentials");
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        Headers(request, Json);
        request.Headers.TryAddWithoutValidation("Authorization", authorization);
        if (ImpersonateUser is not null)
        {
            // At login only: TM1 decides the identity then and ignores the header later
            request.Headers.TryAddWithoutValidation(Impersonate, ImpersonateUser);
        }
        if (SessionContext is not null)
        {
            request.Headers.TryAddWithoutValidation(SessionContextHeader, SessionContext);
        }
        if (seedCookie is not null)
        {
            // Log in the session TM1 opened for a 401, rather than opening another
            request.Headers.TryAddWithoutValidation("Cookie", $"{Cookie}={seedCookie}");
        }
        var reply = await SendRawAsync(request, Timeouts.Login, url, true, CancellationToken.None).ConfigureAwait(false);
        if (reply.Status == 401)
        {
            // TM1 answers a disabled impersonated user exactly like wrong credentials
            var why = ImpersonateUser is null ? null : new ODataError("", $"The credentials were refused, or the user being impersonated ({ImpersonateUser}) is disabled");
            throw Tm1ApiException.Of(401, Tm1ErrorKind.CredentialRejected, why, "GET", url);
        }
        if (reply.Status >= 400)
        {
            var error = Tm1ApiException.FromBody(reply.Status, reply.Body, "GET", url, Classifier);
            // A login that impersonates and gets a 400 or 403 was refused for the impersonation
            if (ImpersonateUser is not null && reply.Status == 400 && error.Kind != Tm1ErrorKind.UnknownUser)
            {
                throw Tm1ApiException.Of(400, Tm1ErrorKind.UnknownUser, error.Error, "GET", url);
            }
            if (ImpersonateUser is not null && reply.Status == 403 && error.Kind != Tm1ErrorKind.ImpersonationRefused)
            {
                throw Tm1ApiException.Of(403, Tm1ErrorKind.ImpersonationRefused, error.Error, "GET", url);
            }
            throw error;
        }
        var cookie = SessionCookie(reply) ?? seedCookie ?? throw new Tm1ProtocolException($"TM1 accepted the login but set no {Cookie} cookie", null, "GET", url, null);
        try
        {
            using var doc = JsonDocument.Parse(reply.Body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object || !doc.RootElement.TryGetProperty("ID", out var id) || !TryLong(id, out var sessionId))
            {
                throw new Tm1ProtocolException("TM1 accepted the login but didn't return the session ID", null, "GET", url, null);
            }
            return (cookie, sessionId);
        }
        catch (JsonException e)
        {
            throw new Tm1ProtocolException("TM1 accepted the login but its response isn't JSON", null, "GET", url, e);
        }
    }

    private static bool TryLong(JsonElement e, out long value)
    {
        value = 0;
        return e.ValueKind == JsonValueKind.Number ? e.TryGetInt64(out value) : e.ValueKind == JsonValueKind.String && long.TryParse(e.GetString(), out value);
    }

    /// <summary>Sends a request with the session cookie, logging in again once on 401; a second 401
    /// is a <see cref="Tm1AuthenticationException"/> of kind <see cref="Tm1ErrorKind.Unauthenticated"/>.</summary>
    public async Task<Reply> SendAsync(string method, string url, byte[]? content, string? contentType, IReadOnlyDictionary<string, string> headers,
        string accept, TimeSpan? timeout, CancellationToken ct)
    {
        var used = Current();
        var session = await used.Login.WaitAsync(ct).ConfigureAwait(false);
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
                request.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType ?? "application/json");
            }
            var reply = await SendRawAsync(request, timeout ?? Timeouts.Request, url, Tm1Request.IsIdempotent(method), ct).ConfigureAwait(false);
            if (reply.Status == 401)
            {
                if (attempt > 1)
                {
                    throw Tm1ApiException.Of(401, Tm1ErrorKind.Unauthenticated, null, method, url);
                }
                used = Relogin(used, SessionCookie(reply));
                session = await used.Login.WaitAsync(ct).ConfigureAwait(false);
                continue;
            }
            return reply;
        }
    }

    private async Task<Reply> SendRawAsync(HttpRequestMessage request, TimeSpan timeout, string url, bool idempotent, CancellationToken ct)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(timeout);
        var method = request.Method.Method;
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
        catch (OperationCanceledException e) when (!ct.IsCancellationRequested)
        {
            var connect = e.InnerException is TimeoutException t && t.Message.Contains("ConnectTimeout", StringComparison.Ordinal);
            throw new Tm1TransportException(connect ? Tm1TransportException.Phase.Connect : Tm1TransportException.Phase.Response, idempotent, method, url,
                connect ? e.InnerException : new TimeoutException($"no answer within {timeout}", e));
        }
        catch (Exception e) when (e is HttpRequestException or IOException)
        {
            throw Transport(e, method, url, idempotent);
        }
    }

    /// <summary>A failure to get a response, as Java's <c>Transport.map</c>: before sending (refused,
    /// unknown host, unreachable, TLS handshake) is the connect phase; anything later, the response phase.</summary>
    internal static Tm1TransportException Transport(Exception failure, string method, string url, bool idempotent)
    {
        var connect = failure is HttpRequestException
        {
            HttpRequestError: HttpRequestError.ConnectionError or HttpRequestError.NameResolutionError or HttpRequestError.SecureConnectionError,
        };
        for (var t = failure; t is not null && !connect; t = t.InnerException)
        {
            connect = t is System.Net.Sockets.SocketException
            {
                SocketErrorCode: System.Net.Sockets.SocketError.ConnectionRefused or System.Net.Sockets.SocketError.HostNotFound
                    or System.Net.Sockets.SocketError.HostUnreachable or System.Net.Sockets.SocketError.NetworkUnreachable,
            } or System.Security.Authentication.AuthenticationException;
        }
        return new Tm1TransportException(connect ? Tm1TransportException.Phase.Connect : Tm1TransportException.Phase.Response, idempotent, method, url, failure);
    }

    private void Headers(HttpRequestMessage request, string accept)
    {
        request.Headers.TryAddWithoutValidation("OData-Version", "4.0");
        request.Headers.TryAddWithoutValidation("OData-MaxVersion", "4.0");
        request.Headers.TryAddWithoutValidation("Accept", accept);
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

    /// <summary>The server's version, read once per login (TM1 may have been upgraded between them).</summary>
    public Task<Tm1Version> VersionAsync(CancellationToken ct)
    {
        Task<Tm1Version> read;
        lock (_gate)
        {
            var now = Current();
            if (_version is not { } v || v.For != now || v.Read.IsFaulted || v.Read.IsCanceled)
            {
                _version = (ReadVersionAsync(), now);
            }
            read = _version.Value.Read;
        }
        return read.WaitAsync(ct);
    }

    private async Task<Tm1Version> ReadVersionAsync()
    {
        await Task.Yield();
        var url = Root + "Server?$select=ProductVersion";
        var reply = Check("GET", url, await SendAsync("GET", url, null, null, new Dictionary<string, string>(), Json, null, CancellationToken.None).ConfigureAwait(false));
        try
        {
            using var doc = JsonDocument.Parse(reply.Body);
            var text = doc.RootElement.ValueKind == JsonValueKind.Object && doc.RootElement.TryGetProperty("ProductVersion", out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
            return Tm1Version.Parse(text ?? throw new Tm1ProtocolException("TM1 didn't report its ProductVersion", null, "GET", url, null));
        }
        catch (JsonException e)
        {
            throw new Tm1ProtocolException("TM1 didn't report its ProductVersion", null, "GET", url, e);
        }
    }

    /// <summary>What the server's <c>$metadata</c> defines, read once per server and version for
    /// every session built from the same settings.</summary>
    public async Task<Tm1Capabilities> CapabilitiesAsync(CancellationToken ct)
    {
        var version = await VersionAsync(ct).ConfigureAwait(false);
        return await _capabilitiesCache.Get(Root, version, async () =>
        {
            var url = Root + "$metadata";
            var reply = Check("GET", url, await SendAsync("GET", url, null, null, new Dictionary<string, string>(), "application/xml", null, CancellationToken.None).ConfigureAwait(false));
            try
            {
                return Tm1Capabilities.Parse(Encoding.UTF8.GetString(reply.Body), version);
            }
            catch (Tm1ProtocolException e)
            {
                throw new Tm1ProtocolException("TM1 sent $metadata that isn't valid CSDL", null, "GET", url, e);
            }
        }).WaitAsync(ct).ConfigureAwait(false);
    }

    public Reply Check(string method, string url, Reply reply) =>
        reply.Status >= 400 ? throw Tm1ApiException.FromBody(reply.Status, reply.Body, method, url, Classifier) : reply;

    /// <summary>Closes the session on TM1 (waiting for a login under way) and refuses further
    /// requests; closing twice closes once. A session TM1 already dropped (401) is fine.</summary>
    public Task CloseAsync()
    {
        lock (_gate)
        {
            if (_closing is null)
            {
                _closed = true;
                var last = _attempt;
                _attempt = null;
                _closing = SendCloseAsync(last);
            }
            return _closing;
        }
    }

    private async Task SendCloseAsync(Attempt? last)
    {
        await Task.Yield();
        try
        {
            (string Cookie, long Id) session;
            try
            {
                if (last is null)
                {
                    return;
                }
                session = await last.Login.ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Never logged in: nothing to close
                return;
            }
            var url = Root + ClosePath;
            using var request = new HttpRequestMessage(HttpMethod.Post, url);
            Headers(request, Json);
            request.Headers.TryAddWithoutValidation("Cookie", $"{Cookie}={session.Cookie}");
            // TM1 sometimes drops the connection that carried a close: don't reuse it
            request.Headers.ConnectionClose = true;
            request.Content = new StringContent("{}", Encoding.UTF8, "application/json");
            var reply = await SendRawAsync(request, Timeouts.Close, url, true, CancellationToken.None).ConfigureAwait(false);
            // 401: the session was already gone (killed, or timed out), which is what close wants
            if (reply.Status >= 400 && reply.Status != 401)
            {
                throw Tm1ApiException.FromBody(reply.Status, reply.Body, "POST", url, Classifier);
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
            throw new Tm1ProtocolException("TM1's async result isn't an HTTP message: " + lines[0], null, "GET", "_async", null);
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

/// <summary>Capabilities read from <c>$metadata</c>, once per server and version, shared by the
/// sessions built from one set of settings. A failed read isn't kept.</summary>
internal sealed class CapabilitiesCache
{
    private readonly ConcurrentDictionary<string, Lazy<Task<Tm1Capabilities>>> _byServer = new();

    public Task<Tm1Capabilities> Get(string serviceRoot, Tm1Version version, Func<Task<Tm1Capabilities>> fetch)
    {
        var key = serviceRoot + " " + version;
        var entry = _byServer.GetOrAdd(key, _ => new Lazy<Task<Tm1Capabilities>>(() => Task.Run(fetch)));
        var task = entry.Value;
        task.ContinueWith(_ => _byServer.TryRemove(new KeyValuePair<string, Lazy<Task<Tm1Capabilities>>>(key, entry)),
            CancellationToken.None, TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
        return task;
    }
}
