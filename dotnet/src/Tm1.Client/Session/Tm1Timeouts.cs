// SPDX-License-Identifier: Apache-2.0

using System;

namespace Skipcheck.Tm1.Client.Session;

/// <summary>How long a session waits, as the Java client's <c>Tm1Timeouts</c>: to connect, for a
/// login, for a request's answer, for the close at the end, and how long a refused login is
/// remembered (requests meanwhile fail at once rather than trying the same credentials again; zero
/// to try every time).</summary>
public sealed record Tm1Timeouts
{
    /// <summary>The defaults: 10 s to connect, 30 s for a login, 10 min without data for a request,
    /// 5 s to close, and a refused login remembered for 30 s.</summary>
    public static readonly Tm1Timeouts Defaults = new(
        TimeSpan.FromSeconds(10),
        TimeSpan.FromSeconds(30),
        TimeSpan.FromMinutes(10),
        TimeSpan.FromSeconds(5),
        TimeSpan.FromSeconds(30)
    );

    /// <summary>Timeouts, each positive (or, for <paramref name="refusedLogin"/>, not
    /// negative).</summary>
    /// <exception cref="ArgumentException">A timeout is out of range.</exception>
    public Tm1Timeouts(TimeSpan connect, TimeSpan login, TimeSpan request, TimeSpan close, TimeSpan refusedLogin)
    {
        Positive(connect, nameof(connect));
        Positive(login, nameof(login));
        Positive(request, nameof(request));
        Positive(close, nameof(close));
        if (refusedLogin < TimeSpan.Zero)
        {
            throw new ArgumentException("refusedLogin must not be negative: " + refusedLogin);
        }
        Connect = connect;
        Login = login;
        Request = request;
        Close = close;
        RefusedLogin = refusedLogin;
    }

    /// <summary>How long to wait for a connection, including TLS. Default 10 s.</summary>
    public TimeSpan Connect { get; }

    /// <summary>How long to wait for a login response. Short, because a TM1 that is still loading
    /// accepts connections and never answers. The authenticator gets as long again to supply the
    /// credentials first, since it may ask another server, such as Cognos for a passport. Default
    /// 30 s.</summary>
    public TimeSpan Login { get; }

    /// <summary>How long a request may go without receiving any data, unless the request sets its
    /// own (<see cref="Tm1Request{T}.WithTimeout"/>). An idle timeout, not a limit on the whole
    /// response, so a large collection that keeps streaming isn't cut off. Default 10
    /// min.</summary>
    public TimeSpan Request { get; }

    /// <summary>How long to wait for TM1 to close the session. Default 5 s.</summary>
    public TimeSpan Close { get; }

    /// <summary>How long a login TM1 refused (wrong credentials, an unknown or disabled user,
    /// impersonation not allowed) is remembered. Until then, requests fail at once with the same
    /// error instead of logging in again: every attempt leaves a session behind on TM1, so retrying
    /// a refusal at once would pile them up. Zero turns this off. Default 30 s.</summary>
    public TimeSpan RefusedLogin { get; }

    /// <summary>A builder starting from the defaults.</summary>
    public static TimeoutsBuilder Builder() => new(Defaults);

    /// <summary>A builder starting from these timeouts, to change some of them.</summary>
    public TimeoutsBuilder ToBuilder() => new(this);

    private static void Positive(TimeSpan value, string name)
    {
        if (value <= TimeSpan.Zero)
        {
            throw new ArgumentException($"{name} must be positive: {value}");
        }
    }

    /// <summary>Java's <c>Tm1Timeouts.Builder</c>.</summary>
    public sealed class TimeoutsBuilder
    {
        private TimeSpan _connect;
        private TimeSpan _login;
        private TimeSpan _request;
        private TimeSpan _close;
        private TimeSpan _refusedLogin;

        internal TimeoutsBuilder(Tm1Timeouts start)
        {
            _connect = start.Connect;
            _login = start.Login;
            _request = start.Request;
            _close = start.Close;
            _refusedLogin = start.RefusedLogin;
        }

        /// <summary>Sets how long to wait for a connection, including TLS.</summary>
        public TimeoutsBuilder Connect(TimeSpan value)
        {
            _connect = value;
            return this;
        }

        /// <summary>Sets how long to wait for a login response.</summary>
        public TimeoutsBuilder Login(TimeSpan value)
        {
            _login = value;
            return this;
        }

        /// <summary>Sets how long a request may go without receiving any data.</summary>
        public TimeoutsBuilder Request(TimeSpan value)
        {
            _request = value;
            return this;
        }

        /// <summary>Sets how long to wait for TM1 to close the session.</summary>
        public TimeoutsBuilder Close(TimeSpan value)
        {
            _close = value;
            return this;
        }

        /// <summary>Sets how long requests fail at once after TM1 refused a login; zero to log in
        /// again straight away.</summary>
        public TimeoutsBuilder RefusedLogin(TimeSpan value)
        {
            _refusedLogin = value;
            return this;
        }

        /// <summary>The timeouts, with unset values at their defaults.</summary>
        /// <exception cref="ArgumentException">A timeout is zero or negative.</exception>
        public Tm1Timeouts Build() => new(_connect, _login, _request, _close, _refusedLogin);
    }
}
