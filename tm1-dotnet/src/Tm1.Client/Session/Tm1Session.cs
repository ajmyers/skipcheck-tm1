using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Auth;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Json;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Version;

namespace AjMyers.Tm1.Client.Session;

/// <summary>A session with one TM1 server, and views of it, as the Java client's <c>Tm1Session</c>.
/// A view shares the login and connections, and changes how requests are sent:
/// <see cref="WithSandbox"/> (TM1's <c>!sandbox</c>), <see cref="WithChangeSet"/>
/// (<c>!ChangeSet</c>), <see cref="WithAsync"/> (OData's <c>Prefer: respond-async</c>, polled
/// until done; cancelling deletes the monitor, which stops the work on TM1) and <see cref="Batch"/>
/// (JSON <c>$batch</c>). Views are derived rather than set, so one caller's sandbox can't leak into
/// another's requests.</summary>
public sealed class Tm1Session : IAsyncDisposable
{
    private readonly Connection _connection;
    private readonly string? _sandbox;
    private readonly string? _changeSet;
    private readonly TimeSpan? _async;
    private readonly BatchQueue? _batch;

    internal Tm1Session(Connection connection, string? sandbox, string? changeSet, TimeSpan? async, BatchQueue? batch)
    {
        _connection = connection;
        _sandbox = sandbox;
        _changeSet = changeSet;
        _async = async;
        _batch = batch;
    }

    /// <summary>The settings one by one, as the Java client's <c>Tm1Session.builder()</c>.</summary>
    public static Tm1SessionBuilder Builder() => new();

    /// <summary>The REST API's root, such as <c>http://tm1:8010/api/v1/</c>.</summary>
    public string ServiceRoot() => _connection.Root;

    public string? Sandbox() => _sandbox;

    public string? ChangeSet() => _changeSet;

    /// <summary>How often this view polls an async request's monitor, or null.</summary>
    public TimeSpan? Async() => _async;

    public bool IsClosed() => _connection.IsClosed;

    internal Connection Connection => _connection;

    // Views

    public Tm1Session WithSandbox(string name)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            throw new ArgumentException("sandbox name is blank");
        }
        return new(_connection, name, _changeSet, _async, _batch);
    }

    public Tm1Session WithoutSandbox() => _sandbox is null ? this : new(_connection, null, _changeSet, _async, _batch);

    public Tm1Session WithChangeSet(string id)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            throw new ArgumentException("change set ID is blank");
        }
        return new(_connection, _sandbox, id, _async, _batch);
    }

    public Tm1Session WithoutChangeSet() => _changeSet is null ? this : new(_connection, _sandbox, null, _async, _batch);

    public Tm1Session WithAsync(TimeSpan every)
    {
        if (every <= TimeSpan.Zero)
        {
            throw new ArgumentException("The poll interval must be positive: " + every);
        }
        return new(_connection, _sandbox, _changeSet, every, _batch);
    }

    public Tm1Session WithoutAsync() => _async is null ? this : new(_connection, _sandbox, _changeSet, null, _batch);

    /// <summary>Starts a JSON <c>$batch</c>: its <see cref="Tm1SessionBatch.Session"/> is a view of
    /// this one whose requests are queued, and <c>RunAsync</c> sends them.</summary>
    public Tm1SessionBatch Batch()
    {
        // The $batch itself carries no sandbox: each request in it does
        var queue = new BatchQueue(new Tm1Session(_connection, null, null, null, null));
        return new Tm1SessionBatch(queue, new Tm1Session(_connection, _sandbox, _changeSet, null, queue));
    }

    // Lifecycle

    /// <summary>Logs in now, rather than on the first request.</summary>
    public async Task<Tm1Session> ConnectAsync(CancellationToken cancellationToken = default)
    {
        await _connection.LoginAsync(cancellationToken).ConfigureAwait(false);
        return this;
    }

    /// <summary>The session's ID on TM1 (<c>ActiveSession</c>), logging in if needed.</summary>
    public Task<long> SessionIdAsync(CancellationToken cancellationToken = default) => _connection.LoginAsync(cancellationToken);

    /// <summary>Ends the session on the server, for every view.</summary>
    public Task CloseAsync(CancellationToken cancellationToken = default) => _connection.CloseAsync().WaitAsync(cancellationToken);

    public ValueTask DisposeAsync() => new(CloseAsync());

    public Task<Tm1Version> VersionAsync(CancellationToken cancellationToken = default) => _connection.VersionAsync(cancellationToken);

    public Task<Tm1Capabilities> CapabilitiesAsync(CancellationToken cancellationToken = default) => _connection.CapabilitiesAsync(cancellationToken);

    // Sending

    /// <summary>Sends a request and reads its answer. Collections are read with <see cref="StreamAsync"/>.
    /// In a batch, the task completes once the batch has run.</summary>
    public async Task<T> ExecuteAsync<T>(Tm1Request<T> request, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.Response == Tm1Response.Collection)
        {
            throw new ArgumentException("Collection requests are streamed: use StreamAsync() for " + request);
        }
        await CheckAsync(request.Requirement, cancellationToken).ConfigureAwait(false);
        if (_batch is not null)
        {
            return (T)(await _batch.Add(this, request, r => Read(request, r)).ConfigureAwait(false))!;
        }
        var reply = await SendAsync(request, Target(request.RelativeUri()), cancellationToken).ConfigureAwait(false);
        return Read(request, reply);
    }

    /// <summary>The items of a collection, fetching pages as they're needed (Java's <c>stream</c>).
    /// In a batch, its first page, once the batch has run.</summary>
    public async IAsyncEnumerable<T> StreamAsync<T>(Tm1Request<T> request, [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.Response != Tm1Response.Collection)
        {
            throw new ArgumentException("Only collection requests are streamed: use ExecuteAsync() for " + request);
        }
        await CheckAsync(request.Requirement, cancellationToken).ConfigureAwait(false);
        if (_batch is not null)
        {
            var page = (Page<T>)(await _batch.Add(this, request, r => ReadPage(request, r)).ConfigureAwait(false))!;
            if (page.NextLink is not null)
            {
                throw new Tm1ProtocolException($"{request}: TM1 sent more than one page, which a batch can't follow; ask for fewer with Top()");
            }
            foreach (var item in page.Items)
            {
                yield return item;
            }
            yield break;
        }
        var url = Target(request.RelativeUri());
        while (true)
        {
            var reply = await SendAsync(request, url, cancellationToken).ConfigureAwait(false);
            var page = ReadPage(request, reply);
            foreach (var item in page.Items)
            {
                yield return item;
            }
            if (page.NextLink is null)
            {
                yield break;
            }
            // The link carries the query and TM1's options already
            url = _connection.Url(page.NextLink);
            request = request with { Query = ODataQuery.None() };
        }
    }

    private async Task CheckAsync(Tm1Capability? requirement, CancellationToken ct)
    {
        if (requirement is null || !_connection.CheckCapabilities)
        {
            return;
        }
        var refused = (await _connection.CapabilitiesAsync(ct).ConfigureAwait(false)).Check(requirement, _connection.Root);
        if (refused is not null)
        {
            throw refused;
        }
    }

    /// <summary>The URL of a path and query, with the view's <c>!sandbox</c> and <c>!ChangeSet</c>
    /// unless the request sets its own.</summary>
    internal string Target(string relative) => _connection.Url(Contextual(relative));

    internal string Contextual(string relative)
    {
        var options = new List<string>();
        if (_sandbox is not null && !relative.Contains("!sandbox=", StringComparison.Ordinal))
        {
            options.Add("!sandbox=" + ODataEncoding.QueryValue(_sandbox));
        }
        if (_changeSet is not null && !relative.Contains("!ChangeSet=", StringComparison.Ordinal))
        {
            options.Add("!ChangeSet=" + ODataEncoding.QueryValue(_changeSet));
        }
        return options.Count == 0 ? relative : relative + (relative.Contains('?') ? "&" : "?") + string.Join("&", options);
    }

    private async Task<Reply> SendAsync<T>(Tm1Request<T> request, string url, CancellationToken ct)
    {
        var (content, contentType) = request.Encode();
        Reply reply;
        // Raw bytes (document content) aren't sent async
        if (_async is { } every && request.Response != Tm1Response.Bytes && request.Body is not byte[])
        {
            reply = await SendRespondAsync(request.Method, url, content, contentType, request.Headers, request.Accept, request.Timeout, every, ct).ConfigureAwait(false);
        }
        else
        {
            reply = await _connection.SendAsync(request.Method, url, content, contentType, request.Headers, request.Accept, request.Timeout, ct).ConfigureAwait(false);
        }
        return _connection.Check(request.Method, url, reply);
    }

    private async Task<Reply> SendRespondAsync(string method, string url, byte[]? content, string? contentType, IReadOnlyDictionary<string, string> headers,
        string accept, TimeSpan? timeout, TimeSpan every, CancellationToken ct)
    {
        var withPrefer = new Dictionary<string, string>(headers) { ["Prefer"] = "respond-async" };
        var reply = await _connection.SendAsync(method, url, content, contentType, withPrefer, accept, timeout, ct).ConfigureAwait(false);
        if (reply.Status != 202)
        {
            return reply; // TM1 answered at once
        }
        if (!reply.Headers.TryGetValue("location", out var location))
        {
            throw new Tm1ProtocolException("TM1 accepted an async request without a Location", null, method, url, null);
        }
        // The Location is relative to the request's URL: ../_async('id')
        var monitor = new Uri(new Uri(url), location).ToString();
        var finished = false;
        try
        {
            while (true)
            {
                await Task.Delay(every, ct).ConfigureAwait(false);
                var polled = _connection.Check("GET", monitor, await _connection.SendAsync("GET", monitor, null, null, new Dictionary<string, string>(), "*/*", null, ct).ConfigureAwait(false));
                if (polled.Status != 202)
                {
                    finished = true;
                    return Connection.AsyncResult(polled.Body);
                }
            }
        }
        finally
        {
            if (!finished)
            {
                // Deleting the monitor is what stops the work on TM1
                try
                {
                    await _connection.SendAsync("DELETE", monitor, null, null, new Dictionary<string, string>(), Connection.Json, null, CancellationToken.None).ConfigureAwait(false);
                }
                catch (Tm1Exception)
                {
                    // The work may already be gone
                }
            }
        }
    }

    internal T Read<T>(Tm1Request<T> request, Reply reply)
    {
        object? value = request.Response switch
        {
            Tm1Response.None => NoContent.Value,
            Tm1Response.Bytes => reply.Body,
            Tm1Response.Text => reply.Text,
            _ => ReadJson<T>(reply),
        };
        return (T)value!;
    }

    private static T? ReadJson<T>(Reply reply)
    {
        if (reply.Status == 204 || reply.Body.Length == 0)
        {
            return default;
        }
        try
        {
            using var doc = JsonDocument.Parse(reply.Body);
            return typeof(T) == typeof(JsonElement) ? (T)(object)doc.RootElement.Clone() : Tm1Json.Read<T>(doc.RootElement);
        }
        catch (JsonException e)
        {
            throw new Tm1ProtocolException($"TM1 answered {reply.Status} with something this client can't read: {e.Message}", e);
        }
    }

    internal static Page<T> ReadPage<T>(Tm1Request<T> request, Reply reply)
    {
        try
        {
            using var doc = JsonDocument.Parse(reply.Body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object || !doc.RootElement.TryGetProperty("value", out var items) || items.ValueKind != JsonValueKind.Array)
            {
                throw new Tm1ProtocolException($"{request}: expected a collection");
            }
            var list = items.EnumerateArray().Select(i => typeof(T) == typeof(JsonElement) ? (T)(object)i.Clone() : Tm1Json.Read<T>(i)!).ToList();
            var next = doc.RootElement.TryGetProperty(ODataJson.NextLink, out var n) ? n.GetString() : null;
            return new Page<T>(list, next);
        }
        catch (JsonException e)
        {
            throw new Tm1ProtocolException($"{request}: {e.Message}", e);
        }
    }

    public override string ToString() => $"Tm1Session[{_connection.Root}{(_sandbox is null ? "" : ", sandbox=" + _sandbox)}{(_changeSet is null ? "" : ", changeSet=" + _changeSet)}]";

    internal sealed record Page<T>(IReadOnlyList<T> Items, string? NextLink);
}

/// <summary>A JSON <c>$batch</c>, as the Java client's <c>Tm1SessionBatch</c>: requests through
/// <see cref="Session"/> are queued, and <c>RunAsync</c> sends them together. TM1 (11.8)
/// runs them in order (a later one sees an earlier one's writes) and carries on past failed ones:
/// each request's task gets its own result or error.</summary>
public sealed class Tm1SessionBatch
{
    public const int DefaultPerRequest = 500;

    private readonly BatchQueue _queue;

    internal Tm1SessionBatch(BatchQueue queue, Tm1Session session)
    {
        _queue = queue;
        Session = session;
    }

    /// <summary>The view whose requests join this batch.</summary>
    public Tm1Session Session { get; }

    public int Size() => _queue.Count;

    public Task RunAsync(CancellationToken cancellationToken = default) => RunAsync(DefaultPerRequest, cancellationToken);

    /// <summary>Sends the queued requests, at most <paramref name="perRequest"/> to each <c>$batch</c>.</summary>
    public Task RunAsync(int perRequest, CancellationToken cancellationToken = default) => _queue.RunAsync(perRequest, cancellationToken);
}

internal sealed class BatchQueue
{
    private readonly Tm1Session _sender;
    private readonly List<Entry> _entries = new();
    private bool _ran;

    public BatchQueue(Tm1Session sender) => _sender = sender;

    public int Count
    {
        get
        {
            lock (_entries)
            {
                return _entries.Count;
            }
        }
    }

    public Task<object?> Add<T>(Tm1Session view, Tm1Request<T> request, Func<Reply, object?> read)
    {
        if (request.Response == Tm1Response.Bytes || request.Body is byte[])
        {
            throw new ArgumentException("Raw bytes can't go in a JSON batch: " + request);
        }
        var entry = new Entry(request.Method, view.Contextual(request.RelativeUri()), request.Headers, request.Body, request.Accept, read);
        lock (_entries)
        {
            if (_ran)
            {
                throw new InvalidOperationException("The batch has already run, so this call can't join it: a call whose request depends on another's result can't be batched. " + request);
            }
            _entries.Add(entry);
        }
        return entry.Result.Task;
    }

    public async Task RunAsync(int perRequest, CancellationToken ct)
    {
        List<Entry> all;
        lock (_entries)
        {
            if (_ran)
            {
                throw new InvalidOperationException("The batch has already run");
            }
            _ran = true;
            all = _entries.ToList();
        }
        var connection = _sender.Connection;
        for (int start = 0; start < all.Count; start += perRequest)
        {
            var chunk = all.Skip(start).Take(perRequest).ToList();
            var requests = chunk.Select((e, i) =>
            {
                var item = new Dictionary<string, object?>
                {
                    ["id"] = (i + 1).ToString(System.Globalization.CultureInfo.InvariantCulture),
                    ["method"] = e.Method,
                    ["url"] = e.Url,
                };
                // TM1 (11.8) gives a request of a batch the batch's own Accept unless it has one, and
                // refuses plain text ($count, $value) under the batch's application/json. It reads a
                // header's name as written, and only knows this one in lower case.
                var headers = new Dictionary<string, string> { ["accept"] = e.Accept };
                foreach (var header in e.Headers)
                {
                    headers[header.Key] = header.Value;
                }
                if (e.Body is not null)
                {
                    headers["content-type"] = "application/json";
                    item["body"] = e.Body;
                }
                item["headers"] = headers;
                return item;
            }).ToList();
            try
            {
                var reply = await _sender.ExecuteAsync(Tm1Request.Post<JsonElement>(ODataPath.Batch(), new Dictionary<string, object?> { ["requests"] = requests }), ct).ConfigureAwait(false);
                var byId = reply.TryGetProperty("responses", out var responses)
                    ? responses.EnumerateArray().ToDictionary(r => r.GetProperty("id").ToString())
                    : new Dictionary<string, JsonElement>();
                for (int i = 0; i < chunk.Count; i++)
                {
                    var e = chunk[i];
                    var url = connection.Url(e.Url);
                    if (!byId.TryGetValue((i + 1).ToString(System.Globalization.CultureInfo.InvariantCulture), out var item))
                    {
                        e.Result.TrySetException(new Tm1ProtocolException($"TM1's batch response has no answer for {e.Method} {url}"));
                        continue;
                    }
                    var status = item.TryGetProperty("status", out var s) ? s.GetInt32() : 0;
                    var body = item.TryGetProperty("body", out var b) && b.ValueKind != JsonValueKind.Null
                        ? (b.ValueKind == JsonValueKind.String ? System.Text.Encoding.UTF8.GetBytes(b.GetString()!) : JsonSerializer.SerializeToUtf8Bytes(b))
                        : [];
                    try
                    {
                        e.Result.TrySetResult(e.Read(connection.Check(e.Method, url, new Reply(status, new Dictionary<string, string>(), body))));
                    }
                    catch (Exception error)
                    {
                        e.Result.TrySetException(error);
                    }
                }
            }
            catch (Exception error)
            {
                foreach (var e in chunk)
                {
                    e.Result.TrySetException(error);
                }
            }
        }
    }

    private sealed record Entry(string Method, string Url, IReadOnlyDictionary<string, string> Headers, object? Body, string Accept, Func<Reply, object?> Read)
    {
        public TaskCompletionSource<object?> Result { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }
}

/// <summary>A session's settings one by one, as the Java client's <c>Tm1Session.Builder</c>.</summary>
public class Tm1SessionBuilder
{
    private string? _root;
    private string? _address;
    private Tm1Dialect _dialect = Tm1Dialect.V11;
    private Tm1Authenticator? _authenticator;
    private string? _impersonate;
    private string? _sessionContext;
    private CapabilitiesCache _capabilitiesCache = new();
    private Tm1Timeouts _timeouts = Tm1Timeouts.Defaults;
    private Tm1ErrorClassifier _classifier = Tm1ErrorClassifier.Defaults();
    private HttpMessageHandler? _handler;
    private bool _checkCapabilities = true;
    private Func<DateTimeOffset>? _clock;

    /// <summary>The REST API's root, such as <c>http://tm1:8010/api/v1/</c>.</summary>
    public Tm1SessionBuilder BaseUrl(string baseUrl)
    {
        _root = baseUrl ?? throw new ArgumentNullException(nameof(baseUrl));
        _address = null;
        return this;
    }

    /// <summary>The server's address, read by the dialect: <c>http://tm1:8010</c>.</summary>
    public Tm1SessionBuilder Address(string address)
    {
        _address = address ?? throw new ArgumentNullException(nameof(address));
        _root = null;
        return this;
    }

    public Tm1SessionBuilder Dialect(Tm1Dialect dialect)
    {
        _dialect = dialect ?? throw new ArgumentNullException(nameof(dialect));
        return this;
    }

    public Tm1SessionBuilder BasicAuth(string user, string? password) => Authenticator(Tm1Authenticator.Basic(user, password));

    public Tm1SessionBuilder Authenticator(Tm1Authenticator authenticator)
    {
        _authenticator = authenticator ?? throw new ArgumentNullException(nameof(authenticator));
        return this;
    }

    /// <summary>Logs in as the service account but acts as <paramref name="user"/> (<c>TM1-Impersonate</c>).</summary>
    public Tm1SessionBuilder Impersonate(string user)
    {
        _impersonate = UserNames.CheckImpersonated(user);
        return this;
    }

    public Tm1SessionBuilder WithoutImpersonation()
    {
        _impersonate = null;
        return this;
    }

    /// <summary>A label TM1 shows for the session (<c>TM1-SessionContext</c>, sent with the login),
    /// such as the application's name: in TM1's thread and session lists and its audit log.</summary>
    /// <exception cref="ArgumentException">Longer than 250 characters, which is all TM1 keeps.</exception>
    public Tm1SessionBuilder SessionContext(string sessionContext)
    {
        ArgumentNullException.ThrowIfNull(sessionContext);
        _sessionContext = sessionContext.Length > 250 ? throw new ArgumentException("TM1 keeps at most 250 characters of session context, got " + sessionContext.Length) : sessionContext;
        return this;
    }

    public Tm1SessionBuilder Timeouts(Tm1Timeouts timeouts)
    {
        _timeouts = timeouts ?? throw new ArgumentNullException(nameof(timeouts));
        return this;
    }

    public Tm1SessionBuilder ErrorClassifier(Tm1ErrorClassifier classifier)
    {
        _classifier = classifier ?? throw new ArgumentNullException(nameof(classifier));
        return this;
    }

    /// <summary>The HTTP handler to send through (Java's <c>webClient</c> and <c>connector</c>). Keep
    /// a custom pool's idle connections under 90 s: TM1 closes them then.</summary>
    public Tm1SessionBuilder HttpHandler(HttpMessageHandler handler)
    {
        _handler = handler ?? throw new ArgumentNullException(nameof(handler));
        return this;
    }

    /// <summary>Whether requests check the server's <c>$metadata</c> for what they need.</summary>
    public Tm1SessionBuilder CheckCapabilities(bool check)
    {
        _checkCapabilities = check;
        return this;
    }

    /// <summary>The clock refused logins are timed by (for tests).</summary>
    public Tm1SessionBuilder Clock(Func<DateTimeOffset> clock)
    {
        _clock = clock;
        return this;
    }

    /// <summary>A copy of these settings, for a pool to build one session per identity.</summary>
    public Tm1SessionBuilder Copy() => (Tm1SessionBuilder)MemberwiseClone();

    /// <summary>The service root, or a placeholder if it isn't set yet, for the pool's messages.</summary>
    internal string BaseUrlOrUnset() => _root ?? (_address is not null ? _dialect.ServiceRoot(_address) : "(no base URL)");

    public Tm1Session Build()
    {
        var root = _root ?? (_address is not null ? _dialect.ServiceRoot(_address) : throw new InvalidOperationException("Give the server: BaseUrl or Address"));
        var authenticator = _authenticator ?? throw new InvalidOperationException("Give the login: BasicAuth or Authenticator");
        return new Tm1Session(new Connection(root, authenticator, _impersonate, _sessionContext, _timeouts, _classifier, _handler, _checkCapabilities, _clock, _capabilitiesCache), null, null, null, null);
    }
}
