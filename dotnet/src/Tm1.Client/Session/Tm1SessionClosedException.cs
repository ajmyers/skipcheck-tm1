using System;

namespace Skipcheck.Tm1.Client.Session;

/// <summary>Using a session, or a pool, after it was closed.</summary>
public sealed class Tm1SessionClosedException : InvalidOperationException
{
    internal Tm1SessionClosedException(string serviceRoot)
        : base($"The TM1 session at {serviceRoot} is closed")
    {
        ServiceRoot = serviceRoot;
    }

    public string ServiceRoot { get; }
}
