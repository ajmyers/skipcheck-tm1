// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Session;

/// <summary>One TM1 session per identity, built from shared settings and kept while it's used, as the
/// Java client's <c>Tm1SessionPool</c>: for a server that acts for many users, each through their own
/// session (impersonating them, or with their own credentials).
///
/// <para>A session is evicted when it's been idle for <see cref="Tm1SessionPoolBuilder.IdleTimeout"/>
/// (default 10 minutes) or open for <see cref="Tm1SessionPoolBuilder.MaxAge"/> (default 8 hours),
/// checked when it's next asked for and by a sweep every
/// <see cref="Tm1SessionPoolBuilder.SweepInterval"/> (default a minute); and the least recently
/// used one when there are more than <see cref="Tm1SessionPoolBuilder.MaxSize"/> (default 100). An
/// evicted session is closed on TM1 after <see cref="Tm1SessionPoolBuilder.CloseGrace"/> (default
/// 30 seconds), so requests already using it can finish; the next ask for its identity opens a
/// new one.</para>
///
/// <para>Sessions are opened lazily: <see cref="Session(Tm1Identity)"/> doesn't log in, the first
/// request does. Thread-safe.</para></summary>
public sealed class Tm1SessionPool : IAsyncDisposable
{
    private readonly Tm1SessionBuilder _sessions;
    private readonly TimeSpan _idleTimeout;
    private readonly TimeSpan _maxAge;
    private readonly int _maxSize;
    private readonly TimeSpan _closeGrace;
    private readonly TimeProvider _time;
    private readonly ITimer _sweep;

    // Least recently used first
    private readonly LinkedList<(Tm1Identity Identity, Entry Entry)> _order = new();
    private readonly Dictionary<Tm1Identity, LinkedListNode<(Tm1Identity Identity, Entry Entry)>> _entries = new();
    private readonly Dictionary<Tm1Session, CancellationTokenSource> _closing = new();
    private readonly object _gate = new();
    private bool _closed;
    private Task? _closeAll;
    private long _created;
    private long _evictedIdle;
    private long _evictedAge;
    private long _evictedSize;
    private long _closedSessions;

    internal Tm1SessionPool(Tm1SessionPoolBuilder builder)
    {
        _sessions = builder.Sessions;
        _idleTimeout = builder.IdleTimeoutValue;
        _maxAge = builder.MaxAgeValue;
        _maxSize = builder.MaxSizeValue;
        _closeGrace = builder.CloseGraceValue;
        _time = builder.TimeProviderValue;
        // Sessions built by the pool remember refused logins on the pool's clock
        _sessions.Clock(_time.GetUtcNow);
        _sweep = _time.CreateTimer(_ => Sweep(), null, builder.SweepIntervalValue, builder.SweepIntervalValue);
    }

    /// <summary>A pool building its sessions from <paramref name="sessions"/> (copied: later changes
    /// to it don't reach the pool). Its authenticator is the service account's.</summary>
    public static Tm1SessionPoolBuilder Builder(Tm1SessionBuilder sessions) => new((sessions ?? throw new ArgumentNullException(nameof(sessions))).Copy());

    /// <summary>The session for <paramref name="identity"/>: the one held for it, unless that has
    /// expired, or a new one.</summary>
    /// <exception cref="Tm1SessionClosedException">The pool is closed.</exception>
    public Tm1Session Session(Tm1Identity identity)
    {
        ArgumentNullException.ThrowIfNull(identity);
        var now = _time.GetUtcNow();
        lock (_gate)
        {
            if (_closed)
            {
                throw new Tm1SessionClosedException(ServiceRoot);
            }
            Entry? entry = null;
            if (_entries.TryGetValue(identity, out var node))
            {
                entry = node.Value.Entry;
                if (Expiry(entry, now) is { } reason)
                {
                    Evict(node, reason);
                    entry = null;
                }
                else
                {
                    _order.Remove(node);
                    _order.AddLast(node);
                }
            }
            if (entry is null)
            {
                entry = new Entry(identity.ApplyTo(_sessions.Copy()).Build(), now);
                _entries[identity] = _order.AddLast((identity, entry));
                _created++;
                EvictOverSize(identity);
            }
            entry.LastUsed = now;
            return entry.Session;
        }
    }

    /// <summary>The service account's own session.</summary>
    public Tm1Session Session() => Session(Tm1Identity.ServiceAccount());

    /// <summary>Evicts the sessions that are idle or old now, as the sweep does.</summary>
    public void EvictExpired()
    {
        var now = _time.GetUtcNow();
        lock (_gate)
        {
            for (var node = _order.First; node is not null; )
            {
                var next = node.Next;
                if (Expiry(node.Value.Entry, now) is { } reason)
                {
                    Evict(node, reason);
                }
                node = next;
            }
        }
    }

    /// <summary>The pool's counts now.</summary>
    public Tm1SessionPoolStats Stats()
    {
        lock (_gate)
        {
            return new Tm1SessionPoolStats(
                _entries.Count,
                _closing.Count,
                _created,
                _evictedIdle,
                _evictedAge,
                _evictedSize,
                Interlocked.Read(ref _closedSessions)
            );
        }
    }

    /// <summary>Closes every session, held or waiting out its grace, and refuses more. A session
    /// that fails to close doesn't stop the others; closing twice closes once.</summary>
    public Task CloseAsync(CancellationToken cancellationToken = default)
    {
        lock (_gate)
        {
            if (_closeAll is null)
            {
                _closed = true;
                _sweep.Dispose();
                var all = _order.Select(e => e.Entry.Session).ToList();
                _order.Clear();
                _entries.Clear();
                foreach (var (session, grace) in _closing)
                {
                    grace.Cancel();
                    all.Add(session);
                }
                _closing.Clear();
                _closeAll = Task.WhenAll(
                    all.Select(async s =>
                    {
                        try
                        {
                            await s.CloseAsync(CancellationToken.None).ConfigureAwait(false);
                            Interlocked.Increment(ref _closedSessions);
                        }
                        catch (Exception)
                        {
                            // One session failing to close doesn't stop the others
                        }
                    })
                );
            }
            return _closeAll.WaitAsync(cancellationToken);
        }
    }

    /// <summary>Closes the pool: <see cref="CloseAsync"/>.</summary>
    public async ValueTask DisposeAsync() => await CloseAsync().ConfigureAwait(false);

    /// <inheritdoc/>
    public override string ToString() => $"Tm1SessionPool[{ServiceRoot}]";

    private string ServiceRoot => _sessions.BaseUrlOrUnset();

    private void Sweep()
    {
        try
        {
            EvictExpired();
        }
        catch (Exception)
        {
            // The next sweep tries again
        }
    }

    private string? Expiry(Entry entry, DateTimeOffset now)
    {
        if (now - entry.CreatedAt >= _maxAge)
        {
            return "age";
        }
        if (now - entry.LastUsed >= _idleTimeout)
        {
            return "idle";
        }
        return null;
    }

    private void EvictOverSize(Tm1Identity keep)
    {
        while (_entries.Count > _maxSize)
        {
            var eldest = _order.First!; // there's an entry, so there's a first: every entry is in the order
            if (eldest.Value.Identity.Equals(keep))
            {
                return;
            }
            Evict(eldest, "size");
        }
    }

    private void Evict(LinkedListNode<(Tm1Identity Identity, Entry Entry)> node, string reason)
    {
        _order.Remove(node);
        _entries.Remove(node.Value.Identity);
        switch (reason)
        {
            case "idle":
                _evictedIdle++;
                break;
            case "age":
                _evictedAge++;
                break;
            default:
                _evictedSize++;
                break;
        }
        var session = node.Value.Entry.Session;
        var grace = new CancellationTokenSource();
        _closing[session] = grace;
        _ = CloseLaterAsync(session, grace.Token);
    }

    private async Task CloseLaterAsync(Tm1Session session, CancellationToken grace)
    {
        try
        {
            if (_closeGrace > TimeSpan.Zero)
            {
                await Task.Delay(_closeGrace, _time, grace).ConfigureAwait(false);
            }
            await session.CloseAsync(CancellationToken.None).ConfigureAwait(false);
            Interlocked.Increment(ref _closedSessions);
        }
        catch (OperationCanceledException)
        {
            // The pool closed it instead
        }
        catch (Exception)
        {
            // Couldn't close an evicted session: TM1 times it out
        }
        finally
        {
            lock (_gate)
            {
                if (!grace.IsCancellationRequested)
                {
                    _closing.Remove(session);
                }
            }
        }
    }

    private sealed class Entry(Tm1Session session, DateTimeOffset now)
    {
        public Tm1Session Session { get; } = session;

        public DateTimeOffset CreatedAt { get; } = now;

        public DateTimeOffset LastUsed { get; set; } = now;
    }
}

/// <summary>A pool's counts (Java's <c>Tm1SessionPool.Stats</c>): sessions held, evicted ones
/// waiting out their grace, and since the pool started, sessions created, evicted (by idleness, age
/// and size) and closed.</summary>
public sealed record Tm1SessionPoolStats(int Held, int Closing, long Created, long EvictedIdle, long EvictedAge, long EvictedSize, long Closed);

/// <summary>A pool's settings, as the Java client's <c>Tm1SessionPool.Builder</c>.</summary>
public sealed class Tm1SessionPoolBuilder
{
    internal Tm1SessionPoolBuilder(Tm1SessionBuilder sessions)
    {
        Sessions = sessions;
    }

    internal Tm1SessionBuilder Sessions { get; }

    internal TimeSpan IdleTimeoutValue { get; private set; } = TimeSpan.FromMinutes(10);

    internal TimeSpan MaxAgeValue { get; private set; } = TimeSpan.FromHours(8);

    internal int MaxSizeValue { get; private set; } = 100;

    internal TimeSpan CloseGraceValue { get; private set; } = TimeSpan.FromSeconds(30);

    internal TimeSpan SweepIntervalValue { get; private set; } = TimeSpan.FromMinutes(1);

    internal TimeProvider TimeProviderValue { get; private set; } = System.TimeProvider.System;

    /// <summary>How long a session may go unused before it's evicted (default 10 minutes). Keep it
    /// under TM1's own session timeout, or TM1 drops the session first.</summary>
    public Tm1SessionPoolBuilder IdleTimeout(TimeSpan idleTimeout)
    {
        IdleTimeoutValue = Positive(idleTimeout, "idleTimeout");
        return this;
    }

    /// <summary>How long a session may stay open, used or not (default 8 hours): bounds how long a
    /// revoked user's session lives on.</summary>
    public Tm1SessionPoolBuilder MaxAge(TimeSpan maxAge)
    {
        MaxAgeValue = Positive(maxAge, "maxAge");
        return this;
    }

    /// <summary>How many sessions the pool holds (default 100); past it, the least recently used is evicted.</summary>
    public Tm1SessionPoolBuilder MaxSize(int maxSize)
    {
        MaxSizeValue = maxSize < 1 ? throw new ArgumentException("maxSize must be at least 1: " + maxSize) : maxSize;
        return this;
    }

    /// <summary>How long an evicted session stays open for requests already using it (default 30 seconds).</summary>
    public Tm1SessionPoolBuilder CloseGrace(TimeSpan closeGrace)
    {
        CloseGraceValue = closeGrace < TimeSpan.Zero ? throw new ArgumentException("closeGrace must not be negative: " + closeGrace) : closeGrace;
        return this;
    }

    /// <summary>How often the pool evicts expired sessions on its own (default a minute).</summary>
    public Tm1SessionPoolBuilder SweepInterval(TimeSpan sweepInterval)
    {
        SweepIntervalValue = Positive(sweepInterval, "sweepInterval");
        return this;
    }

    /// <summary>The clock and timers the pool runs on (Java's <c>scheduler</c>), for tests.</summary>
    public Tm1SessionPoolBuilder TimeProvider(TimeProvider timeProvider)
    {
        TimeProviderValue = timeProvider ?? throw new ArgumentNullException(nameof(timeProvider));
        return this;
    }

    /// <summary>The pool. It starts its sweep at once, and opens sessions only when
    /// asked.</summary>
    public Tm1SessionPool Build() => new(this);

    private static TimeSpan Positive(TimeSpan value, string name) =>
        value <= TimeSpan.Zero ? throw new ArgumentException($"{name} must be positive: {value}") : value;
}
