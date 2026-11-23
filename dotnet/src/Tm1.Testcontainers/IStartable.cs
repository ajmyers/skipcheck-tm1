using System;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>Something that starts and stops: a container, PAW (several containers run by IBM's
/// compose file), or a whole <see cref="PlanningAnalyticsEnvironment"/>, as Testcontainers for
/// Java's <c>Startable</c>. Disposing stops it.</summary>
public interface IStartable : IAsyncDisposable
{
    /// <summary>Starts it, and what it depends on first; nothing happens if it's running.</summary>
    Task StartAsync(CancellationToken cancellationToken = default);

    /// <summary>Stops and removes it.</summary>
    Task StopAsync(CancellationToken cancellationToken = default);

    /// <summary>Whether it's running.</summary>
    bool IsRunning { get; }
}
