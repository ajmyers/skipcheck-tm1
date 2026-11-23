// SPDX-License-Identifier: Apache-2.0

using System;
using System.Threading;
using System.Threading.Tasks;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Networks;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A Docker network for containers that work together, as Testcontainers for Java's
/// <c>Network</c>: made without Docker (so wiring can be set up and checked anywhere), created when
/// the first container on it starts, and removed after the run (or by <see cref="DisposeAsync"/>).</summary>
public sealed class Network : IAsyncDisposable
{
    private readonly Func<string> name;
    private readonly Lazy<INetwork> resource;
    private readonly Lock creating = new();
    private Task? created;

    private Network(Func<string> name, Func<INetwork> build, bool reusable = false)
    {
        this.name = name;
        resource = new Lazy<INetwork>(build, LazyThreadSafetyMode.ExecutionAndPublication);
        IsReusable = reusable;
    }

    /// <summary>A new network, with a unique name.</summary>
    public static Network NewNetwork()
    {
        var name = "tm1-testcontainers-" + Guid.NewGuid().ToString("N")[..12];
        return new Network(() => name, () => new NetworkBuilder().WithName(name).Build());
    }

    /// <summary>A Testcontainers for .NET network, to share with containers made elsewhere.</summary>
    public static Network Of(INetwork network)
    {
        ArgumentNullException.ThrowIfNull(network);
        var name = network.Name;
        return new Network(() => name, () => network);
    }

    /// <summary>A reused environment's network, which keeps its Docker network for the next run; its name is read when it's
    /// first needed, as the environment's identity is.</summary>
    internal static Network Reusable(INetwork network) => new(() => network.Name, () => network, reusable: true);

    /// <summary>Its Docker name.</summary>
    public string Name => name();

    /// <summary>Whether it's kept for later runs: a reused environment's network.</summary>
    internal bool IsReusable { get; }

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
