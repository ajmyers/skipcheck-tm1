using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>TM1 over plain HTTP, as the Java tests' <c>RawTm1</c>: for setting up, checking what
/// the client left on TM1, and checking TM1's own behaviour, without going through the client.</summary>
public sealed class RawTm1 : IDisposable
{
    private const string Cookie = "TM1SessionId";

    private readonly HttpClient _http;
    private readonly string _root;
    private readonly string _basic;

    public RawTm1()
    {
        var (url, user, password) = Live.Target();
        _root = url;
        User = user;
        Password = password;
        _basic = Basic(user, password);
        _http = new HttpClient(Handler()) { Timeout = TimeSpan.FromSeconds(60) };
    }

    public string User { get; }

    public string Password { get; }

    public string Root => _root;

    /// <summary>The handler live tests send through: no cookie jar, and no certificate check with <c>TM1_VERIFY=false</c>.</summary>
    public static SocketsHttpHandler Handler()
    {
        // A handler of the test's own, so told to write TM1's headers as UTF-8, as the client's own is
        var handler = new SocketsHttpHandler
        {
            UseCookies = false,
            PooledConnectionIdleTimeout = Connection.MaxIdle,
            RequestHeaderEncodingSelector = Tm1Session.Utf8Headers,
        };
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            handler.SslOptions.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        }
        return handler;
    }

    /// <summary>Settings for a session of the TM1 under test, logging in as <see cref="Live.Client"/> does.</summary>
    public static Tm1SessionBuilder Sessions()
    {
        var (url, user, password) = Live.Target();
        var ns = Environment.GetEnvironmentVariable("TM1_URL") is { Length: > 0 } ? Environment.GetEnvironmentVariable("TM1_NAMESPACE") : null;
        var builder = Tm1Session
            .Builder()
            .BaseUrl(url)
            .Authenticator(ns is null ? Tm1Authenticator.Basic(user, password) : Tm1Authenticator.CamNamespace(user, password, ns));
        if (Environment.GetEnvironmentVariable("TM1_VERIFY") == "false")
        {
            builder.HttpHandler(Handler());
        }
        return builder;
    }

    public static string Basic(string user, string password) => "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes($"{user}:{password}"));

    /// <summary>A label unique to one test, for <see cref="SessionCount"/>.</summary>
    public static string UniqueContext() => "test-" + Guid.NewGuid();

    /// <summary>Logs in with Basic credentials and no cookie; the new session's cookie, as <c>name=value</c>.</summary>
    public string Login() =>
        CookieOf(Send(Request("GET", "ActiveSession?$select=ID").WithBasic())) ?? throw new InvalidOperationException("TM1 set no session cookie");

    public long SessionId(string cookie) => Json(Send(Request("GET", "ActiveSession?$select=ID").WithCookie(cookie))).GetProperty("ID").GetInt64();

    public void Close(string cookie) => Send(Request("POST", "ActiveSession/tm1.Close").WithCookie(cookie).WithBody("{}"));

    /// <summary>Kills another session by ID from <paramref name="admin"/>'s session.</summary>
    public int Kill(string admin, long id) => (int)Send(Request("POST", $"Sessions({id})/tm1.Close").WithCookie(admin).WithBody("{}")).Status;

    /// <summary>Waits until session <paramref name="id"/> is no longer listed (TM1 takes a few seconds).</summary>
    public bool AwaitGone(string admin, long id, TimeSpan timeout)
    {
        var deadline = Stopwatch.StartNew();
        while (Send(Request("GET", $"Sessions({id})?$select=ID").WithCookie(admin)).Status == HttpStatusCode.OK)
        {
            if (deadline.Elapsed > timeout)
            {
                return false;
            }
            Thread.Sleep(250);
        }
        return true;
    }

    /// <summary>Waits until TM1 lists <paramref name="expected"/> sessions labelled <paramref name="context"/>; the last count seen.</summary>
    public int AwaitSessionCount(string admin, string context, int expected, TimeSpan timeout)
    {
        var deadline = Stopwatch.StartNew();
        var count = SessionCount(admin, context);
        while (count != expected && deadline.Elapsed < timeout)
        {
            Thread.Sleep(250);
            count = SessionCount(admin, context);
        }
        return count;
    }

    /// <summary>How many sessions labelled <paramref name="context"/> TM1 has open: leak checks count
    /// a label unique to the test, since the server is shared.</summary>
    public int SessionCount(string admin, string context) =>
        Json(Send(Request("GET", "Sessions?$select=ID,Context").WithCookie(admin)))
            .GetProperty("value")
            .EnumerateArray()
            .Count(s => s.TryGetProperty("Context", out var c) && c.ValueKind == JsonValueKind.String && c.GetString() == context);

    /// <summary>How many sessions TM1 has open, those with no user included.</summary>
    public int AllSessionCount(string admin) => Json(Send(Request("GET", "Sessions?$select=ID").WithCookie(admin))).GetProperty("value").GetArrayLength();

    /// <summary>Creates a TM1 user with no groups (so not an administrator).</summary>
    public void CreateUser(string admin, string name, string password)
    {
        var created = Send(Request("POST", "Users").WithCookie(admin).WithBody(JsonSerializer.Serialize(new { Name = name, Password = password })));
        if (created.Status != HttpStatusCode.Created)
        {
            throw new InvalidOperationException($"Couldn't create user {name}: {(int)created.Status} {created.Body}");
        }
    }

    public void DeleteUser(string admin, string name) =>
        Send(Request("DELETE", $"Users('{Uri.EscapeDataString(name.Replace("'", "''", StringComparison.Ordinal))}')").WithCookie(admin));

    /// <summary>A GET over a bare socket, with one header whose value goes out as exactly
    /// <paramref name="value"/>'s bytes: an HTTP client would encode the value its own way, or refuse it.</summary>
    public Bytes GetWithHeaderBytes(string path, string authorization, string header, byte[] value)
    {
        var uri = new Uri(_root + path);
        var head =
            $"GET {uri.PathAndQuery} HTTP/1.1\r\nHost: {uri.Host}:{uri.Port}\r\nAuthorization: {authorization}"
            + $"\r\nAccept: application/json\r\nConnection: close\r\n{header}: ";
        using var socket = new TcpClient(uri.Host, uri.Port) { ReceiveTimeout = 30_000 };
        Stream stream = socket.GetStream();
        if (uri.Scheme == "https")
        {
            var tls = new SslStream(stream, false, (_, _, _, errors) =>
                errors == SslPolicyErrors.None || Environment.GetEnvironmentVariable("TM1_VERIFY") == "false"
            );
            tls.AuthenticateAsClient(uri.Host);
            stream = tls;
        }
        using (stream)
        {
            stream.Write(Encoding.ASCII.GetBytes(head));
            stream.Write(value);
            stream.Write("\r\n\r\n"u8);
            stream.Flush();
            var text = Encoding.UTF8.GetString(OneResponse(stream));
            return new Bytes(int.Parse(text.AsSpan(9, 3), CultureInfo.InvariantCulture), text);
        }
    }

    /// <summary>Reads one response and no further: TM1 keeps the connection open whatever the request's
    /// <c>Connection</c> header says, so reading to the end would wait forever.</summary>
    private static byte[] OneResponse(Stream stream)
    {
        using var bytes = new MemoryStream();
        var buffer = new byte[8192];
        while (true)
        {
            var soFar = Encoding.Latin1.GetString(bytes.GetBuffer(), 0, (int)bytes.Length);
            var headEnd = soFar.IndexOf("\r\n\r\n", StringComparison.Ordinal);
            if (headEnd >= 0)
            {
                var head = soFar[..headEnd].ToLowerInvariant();
                var length = head.IndexOf("content-length:", StringComparison.Ordinal);
                var done = head.Contains("transfer-encoding: chunked", StringComparison.Ordinal)
                    ? soFar.EndsWith("0\r\n\r\n", StringComparison.Ordinal)
                    : length < 0 || bytes.Length >= headEnd + 4 + int.Parse(head[(length + 15)..].Split("\r\n", 2)[0].Trim(), CultureInfo.InvariantCulture);
                if (done)
                {
                    return bytes.ToArray();
                }
            }
            var read = stream.Read(buffer);
            if (read <= 0)
            {
                return bytes.ToArray();
            }
            bytes.Write(buffer, 0, read);
        }
    }

    /// <summary>What <see cref="GetWithHeaderBytes"/> got: the status, and the whole response as text.</summary>
    public sealed record Bytes(int Status, string Text)
    {
        /// <summary>The session cookie TM1 set, if it opened a session.</summary>
        public string? Cookie
        {
            get
            {
                var at = Text.IndexOf(RawTm1.Cookie + "=", StringComparison.Ordinal);
                return at < 0 ? null : Text[at..Text.IndexOf(';', at)];
            }
        }
    }

    public RawRequest Request(string method, string path) => new(this, method, path);

    /// <summary>A response: status, headers (all values of each) and body.</summary>
    public sealed record RawResponse(HttpStatusCode Status, IReadOnlyDictionary<string, string[]> Headers, string Body)
    {
        public string? Header(string name) => Headers.TryGetValue(name, out var v) ? v.FirstOrDefault() : null;

        public IEnumerable<string> All(string name) => Headers.TryGetValue(name, out var v) ? v : [];
    }

    /// <summary>One request: method, path relative to the service root, and optional credentials, cookie, headers and JSON body.</summary>
    public sealed class RawRequest(RawTm1 tm1, string method, string path)
    {
        private readonly List<(string Name, string Value)> _headers = new();

        public string Method => method;

        public string? Body { get; private set; }

        public RawRequest WithBasic() => WithHeader("Authorization", tm1._basic);

        public RawRequest WithCookie(string cookie) => WithHeader("Cookie", cookie);

        public RawRequest WithHeader(string name, string value)
        {
            _headers.Add((name, value));
            return this;
        }

        public RawRequest WithBody(string json)
        {
            Body = json;
            return this;
        }

        internal HttpRequestMessage Build()
        {
            var message = new HttpRequestMessage(new HttpMethod(method), tm1._root + path);
            foreach (var (name, value) in _headers)
            {
                message.Headers.TryAddWithoutValidation(name, value);
            }
            if (Body is not null)
            {
                message.Content = new StringContent(Body, Encoding.UTF8, "application/json");
            }
            return message;
        }

        public override string ToString() => $"{method} {path}";
    }

    /// <summary>Sends a request; a GET or DELETE on a kept-alive connection TM1 closed gets one more try.</summary>
    public RawResponse Send(RawRequest request) => SendAsync(request).GetAwaiter().GetResult();

    public async Task<RawResponse> SendAsync(RawRequest request)
    {
        try
        {
            return await SendOnceAsync(request);
        }
        catch (HttpRequestException) when (request.Method is "GET" or "DELETE")
        {
            return await SendOnceAsync(request);
        }
    }

    private async Task<RawResponse> SendOnceAsync(RawRequest request)
    {
        using var message = request.Build();
        using var response = await _http.SendAsync(message);
        var headers = response
            .Headers.Concat(response.Content.Headers)
            .GroupBy(h => h.Key, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.SelectMany(h => h.Value).ToArray(), StringComparer.OrdinalIgnoreCase);
        return new RawResponse(response.StatusCode, headers, await response.Content.ReadAsStringAsync());
    }

    /// <summary>The <c>TM1SessionId</c> cookie a response sets, as <c>name=value</c>.</summary>
    public static string? CookieOf(RawResponse response) =>
        response.All("Set-Cookie").Where(c => c.StartsWith(Cookie + "=", StringComparison.Ordinal)).Select(c => c.Split(';')[0]).FirstOrDefault();

    public static JsonElement Json(RawResponse response) => JsonDocument.Parse(response.Body).RootElement.Clone();

    public static string Scratch(string prefix = "zz_session_") => prefix + Guid.NewGuid().ToString("N")[..8];

    public void Dispose() => _http.Dispose();
}
