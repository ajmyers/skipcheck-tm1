using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Tests.Runtime;

/// <summary>A scripted TM1, as the Java client's test <c>FakeTm1</c>: answers each request by its
/// key (<c>METHOD path?query</c>, relative to <see cref="Root"/>) with the handler set for it, and
/// records every request, logins and closes included. A login logs in a new session with cookie
/// <c>c1</c>, <c>c2</c>…, or, like TM1, the session whose cookie it sends (setting no cookie); a
/// close answers 204; anything else unscripted answers 404.</summary>
public sealed class ScriptedTm1 : HttpMessageHandler
{
    public const string Root = "http://tm1.test/api/v1/";
    public const string Login = "GET ActiveSession?$select=ID";
    public const string Close = "POST ActiveSession/tm1.Close";

    private readonly ConcurrentDictionary<string, List<Func<Request, Task<HttpResponseMessage>>>> _handlers = new();
    private readonly List<Request> _requests = new();
    private int _logins;

    public ScriptedTm1()
    {
        On(Login, r =>
        {
            var n = Interlocked.Increment(ref _logins);
            return r.Cookie is not null ? Json(200, $"{{\"ID\":{n}}}") : Json(200, $"{{\"ID\":{n}}}", $"TM1SessionId=c{n}; Path=/api/; HttpOnly");
        });
        On(Close, _ => Status(204));
    }

    /// <summary>A request as sent: its key, URL, headers (any case) and body.</summary>
    public sealed record Request(string Method, string Key, string Url, IReadOnlyDictionary<string, string> Headers, byte[] Body)
    {
        public string? Header(string name) => Headers.TryGetValue(name, out var v) ? v : null;

        /// <summary>The <c>TM1SessionId</c> cookie it sends.</summary>
        public string? Cookie => Header("Cookie")?.Split(';').Select(c => c.Trim()).Where(c => c.StartsWith("TM1SessionId=", StringComparison.Ordinal))
            .Select(c => c["TM1SessionId=".Length..]).FirstOrDefault();

        public string? Authorization => Header("Authorization");

        public string Text => Encoding.UTF8.GetString(Body);

        public JsonElement Json => JsonDocument.Parse(Body).RootElement;
    }

    public ScriptedTm1 On(string key, Func<Request, HttpResponseMessage> handler) => On(key, r => Task.FromResult(handler(r)));

    public ScriptedTm1 On(string key, Func<Request, Task<HttpResponseMessage>> handler)
    {
        _handlers[key] = [handler];
        return this;
    }

    /// <summary>Answers with each handler in turn, then the last one from then on.</summary>
    public ScriptedTm1 OnEach(string key, params Func<Request, HttpResponseMessage>[] answers)
    {
        _handlers[key] = answers.Select(a => (Func<Request, Task<HttpResponseMessage>>)(r => Task.FromResult(a(r)))).ToList();
        return this;
    }

    /// <summary>Fails the request with <paramref name="failure"/>, as a broken connection does.</summary>
    public ScriptedTm1 Fail(string key, Exception failure) => On(key, (Func<Request, Task<HttpResponseMessage>>)(_ => Task.FromException<HttpResponseMessage>(failure)));

    public Tm1Session Session(Func<Tm1SessionBuilder, Tm1SessionBuilder>? settings = null) =>
        SessionWithOwnCredentials(b => (settings?.Invoke(b) ?? b).BasicAuth("admin", "apple"));

    public Tm1Session SessionWithOwnCredentials(Func<Tm1SessionBuilder, Tm1SessionBuilder> settings) => Builder(settings).Build();

    /// <summary>Settings pointed at this fake.</summary>
    public Tm1SessionBuilder Builder(Func<Tm1SessionBuilder, Tm1SessionBuilder>? settings = null) =>
        (settings?.Invoke(Tm1Session.Builder()) ?? Tm1Session.Builder()).BaseUrl(Root).HttpHandler(this);

    public IReadOnlyList<Request> Requests()
    {
        lock (_requests)
        {
            return _requests.ToList();
        }
    }

    public IReadOnlyList<Request> Requests(string key) => Requests().Where(r => r.Key == key).ToList();

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage message, CancellationToken cancellationToken)
    {
        var url = message.RequestUri!.AbsoluteUri;
        var key = message.Method.Method + " " + (url.StartsWith(Root, StringComparison.Ordinal) ? url[Root.Length..] : url);
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var h in message.Headers)
        {
            headers[h.Key] = string.Join(", ", h.Value);
        }
        if (message.Headers.ConnectionClose == true)
        {
            headers["Connection"] = "close";
        }
        var body = message.Content is null ? [] : await message.Content.ReadAsByteArrayAsync(cancellationToken);
        if (message.Content is not null)
        {
            foreach (var h in message.Content.Headers)
            {
                headers[h.Key] = string.Join(", ", h.Value);
            }
        }
        var request = new Request(message.Method.Method, key, url, headers, body);
        lock (_requests)
        {
            _requests.Add(request);
        }
        if (!_handlers.TryGetValue(key, out var answers))
        {
            return Json(404, $"{{\"error\":{{\"code\":\"278\",\"message\":\"No fake for {key.Replace("\"", "'", StringComparison.Ordinal)}\"}}}}");
        }
        Func<Request, Task<HttpResponseMessage>> answer;
        lock (answers)
        {
            answer = answers[0];
            if (answers.Count > 1)
            {
                answers.RemoveAt(0);
            }
        }
        return await answer(request).WaitAsync(cancellationToken);
    }

    public static HttpResponseMessage Json(int status, string body, params string[] setCookies)
    {
        var response = new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        foreach (var cookie in setCookies)
        {
            response.Headers.TryAddWithoutValidation("Set-Cookie", cookie);
        }
        return response;
    }

    public static HttpResponseMessage Status(int status, params string[] setCookies)
    {
        var response = new HttpResponseMessage((HttpStatusCode)status);
        foreach (var cookie in setCookies)
        {
            response.Headers.TryAddWithoutValidation("Set-Cookie", cookie);
        }
        return response;
    }

    public static HttpResponseMessage Text(string body, int status = 200) =>
        new((HttpStatusCode)status) { Content = new StringContent(body, Encoding.UTF8, "text/plain") };

    /// <summary>A response that hasn't come yet: <see cref="Pending.Answer"/> sends it.</summary>
    public sealed class Pending
    {
        private readonly TaskCompletionSource<HttpResponseMessage> _response = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task<HttpResponseMessage> Task => _response.Task;

        public void Answer(HttpResponseMessage response) => _response.TrySetResult(response);
    }

    /// <summary>Waits up to five seconds for <paramref name="condition"/>.</summary>
    public static async Task Eventually(Func<bool> condition, string because = "")
    {
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (!condition())
        {
            if (DateTime.UtcNow > deadline)
            {
                Assert.Fail("Timed out waiting " + because);
            }
            await System.Threading.Tasks.Task.Delay(10);
        }
    }
}

/// <summary>A clock and timers tests move by hand (Java's <c>VirtualTimeScheduler</c>).</summary>
public sealed class ManualTime : TimeProvider
{
    private readonly List<ManualTimer> _timers = new();
    private DateTimeOffset _now = new(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);

    public override DateTimeOffset GetUtcNow()
    {
        lock (_timers)
        {
            return _now;
        }
    }

    /// <summary>Moves the clock on, firing each timer due on the way, in order.</summary>
    public void Advance(TimeSpan by)
    {
        DateTimeOffset end;
        lock (_timers)
        {
            end = _now + by;
        }
        while (true)
        {
            ManualTimer? due;
            lock (_timers)
            {
                due = _timers.Where(t => t.Due is { } d && d <= end).OrderBy(t => t.Due).FirstOrDefault();
                if (due is null)
                {
                    _now = end;
                    return;
                }
                _now = due.Due!.Value;
                due.Due = due.Period is { } p && p > TimeSpan.Zero && p != Timeout.InfiniteTimeSpan ? _now + p : null;
            }
            due.Fire();
        }
    }

    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    {
        var timer = new ManualTimer(this, callback, state);
        timer.Change(dueTime, period);
        return timer;
    }

    private sealed class ManualTimer(ManualTime time, TimerCallback callback, object? state) : ITimer
    {
        public DateTimeOffset? Due { get; set; }

        public TimeSpan? Period { get; private set; }

        public void Fire() => callback(state);

        public bool Change(TimeSpan dueTime, TimeSpan period)
        {
            lock (time._timers)
            {
                Due = dueTime == Timeout.InfiniteTimeSpan ? null : time._now + dueTime;
                Period = period;
                if (!time._timers.Contains(this))
                {
                    time._timers.Add(this);
                }
            }
            return true;
        }

        public void Dispose()
        {
            lock (time._timers)
            {
                time._timers.Remove(this);
            }
        }

        public ValueTask DisposeAsync()
        {
            Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
