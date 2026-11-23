using System;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>What every service is: methods over one session. A client over a view of the session
/// (a sandbox, a batch) makes services over that view.</summary>
public abstract class Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    protected Tm1Service(Tm1Session session) => Session = session ?? throw new ArgumentNullException(nameof(session));

    /// <summary>The session the requests go through.</summary>
    protected Tm1Session Session { get; }

    /// <summary><paramref name="value"/>, which an argument named <paramref name="name"/> must
    /// give.</summary>
    /// <exception cref="ArgumentNullException">It is null.</exception>
    protected static T Require<T>(T? value, string name)
        where T : class => value ?? throw new ArgumentNullException(name);
}
