using System;

namespace Skipcheck.Tm1.Client.Session;

/// <summary>How long a session waits, as the Java client's <c>Tm1Timeouts</c>: to connect, for a
/// login, for a request's answer, for the close at the end, and how long a refused login is
/// remembered (requests meanwhile fail at once rather than trying the same credentials again; zero
/// to try every time).</summary>
public sealed record Tm1Timeouts
{
    public static readonly Tm1Timeouts Defaults = new(
        TimeSpan.FromSeconds(10),
        TimeSpan.FromSeconds(30),
        TimeSpan.FromMinutes(10),
        TimeSpan.FromSeconds(5),
        TimeSpan.FromSeconds(30)
    );

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

    public TimeSpan Connect { get; }

    public TimeSpan Login { get; }

    public TimeSpan Request { get; }

    public TimeSpan Close { get; }

    public TimeSpan RefusedLogin { get; }

    public static TimeoutsBuilder Builder() => new(Defaults);

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

        public TimeoutsBuilder Connect(TimeSpan value)
        {
            _connect = value;
            return this;
        }

        public TimeoutsBuilder Login(TimeSpan value)
        {
            _login = value;
            return this;
        }

        public TimeoutsBuilder Request(TimeSpan value)
        {
            _request = value;
            return this;
        }

        public TimeoutsBuilder Close(TimeSpan value)
        {
            _close = value;
            return this;
        }

        public TimeoutsBuilder RefusedLogin(TimeSpan value)
        {
            _refusedLogin = value;
            return this;
        }

        public Tm1Timeouts Build() => new(_connect, _login, _request, _close, _refusedLogin);
    }
}
