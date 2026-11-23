using System;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>A TM1 Admin Server shared by several TM1 servers, as the Java
/// <c>Tm1AdminServerContainer</c>: the <c>tm1-server</c> image run with <c>admin</c>, reached as
/// <c>tm1-admin</c> on its network. Servers register with it through
/// <see cref="Tm1ServerContainer.WithAdminServer"/>.</summary>
public class Tm1AdminServerContainer : GenericContainer<Tm1AdminServerContainer>
{
    /// <summary>The Admin Server's REST port.</summary>
    public const int RestPort = 5895;

    /// <summary>Its name on the network.</summary>
    public const string NetworkAlias = "tm1-admin";

    private static readonly TimeSpan StartupTimeout = TimeSpan.FromMinutes(5);

    /// <summary>An Admin Server from <paramref name="source"/>, in the image TM1 runs in.</summary>
    public Tm1AdminServerContainer(Tm1Source source)
        : this(Tm1ServerContainer.DefaultImageFor(source ?? throw new ArgumentNullException(nameof(source))), source) { }

    /// <summary>An Admin Server from <paramref name="source"/>, in <paramref name="image"/>.</summary>
    public Tm1AdminServerContainer(string image, Tm1Source source)
        : base(image)
    {
        ArgumentNullException.ThrowIfNull(source);
        Source = source;
        WithCommand("admin");
        WithExposedPorts(RestPort);
        WithNetworkAliases(NetworkAlias);
        WaitingForHttp("/api/v1/Servers", RestPort, code => code == HttpStatusCode.OK, StartupTimeout);
        Tm1ServerContainer.MountSource(source, Binds, (name, value) => WithEnv(name, value));
    }

    /// <summary>Where TM1 comes from.</summary>
    public Tm1Source Source { get; }

    /// <summary>The Admin Server's REST root, <c>http://host:port/api/v1/</c>.</summary>
    public string RestUrl => $"http://{Host}:{GetMappedPort(RestPort)}/api/v1/";

    /// <inheritdoc/>
    protected override Task ContainerIsStartedAsync(CancellationToken cancellationToken)
    {
        Logger.LogInformation("TM1 Admin Server is up at {Url} ({Source})", RestUrl, Source.Describe());
        return Task.CompletedTask;
    }
}
