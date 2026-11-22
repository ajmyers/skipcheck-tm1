using System;
using System.Threading;
using System.Threading.Tasks;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Networks;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>A Docker network for containers that work together, as Testcontainers for Java's
/// <c>Network</c>: made without Docker (so wiring can be set up and checked anywhere), created when
/// the first container on it starts, and removed after the run (or by <see cref="DisposeAsync"/>).</summary>
public sealed class Network : IAsyncDisposable
{
    private readonly Lazy<INetwork> resource;
    private readonly Lock creating = new();
    private Task? created;

    private Network(string name, Func<INetwork> build)
    {
        Name = name;
        resource = new Lazy<INetwork>(build, LazyThreadSafetyMode.ExecutionAndPublication);
    }

    /// <summary>A new network, with a unique name.</summary>
    public static Network NewNetwork()
    {
        var name = "tm1-testcontainers-" + Guid.NewGuid().ToString("N")[..12];
        return new Network(name, () => new NetworkBuilder().WithName(name).Build());
    }

    /// <summary>A Testcontainers for .NET network, to share with containers made elsewhere.</summary>
    public static Network Of(INetwork network)
    {
        ArgumentNullException.ThrowIfNull(network);
        return new Network(network.Name, () => network);
    }

    /// <summary>Its Docker name.</summary>
    public string Name { get; }

    /// <summary>The Testcontainers for .NET network.</summary>
    public INetwork Resource => resource.Value;

    /// <summary>Removes it, if it was created.</summary>
    public async ValueTask DisposeAsync()
    {
        if (created is not null)
        {
            await Resource.DisposeAsync().ConfigureAwait(false);
        }
    }

    /// <inheritdoc/>
    public override string ToString() => "Network[" + Name + "]";

    /// <summary>Creates it in Docker, once.</summary>
    internal Task CreateAsync(CancellationToken cancellationToken)
    {
        lock (creating)
        {
            created ??= Resource.CreateAsync(CancellationToken.None);
            return created.WaitAsync(cancellationToken);
        }
    }
}
