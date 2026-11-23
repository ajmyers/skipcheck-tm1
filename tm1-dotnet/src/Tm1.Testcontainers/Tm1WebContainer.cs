using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>TM1 Web in Docker, as the Java <c>Tm1WebContainer</c>: the <c>tm1-web</c> image (no IBM
/// software in it) with TM1 Web from a <see cref="Tm1WebSource"/>, at <c>/tm1web/</c> on port 9510,
/// listing the servers of a TM1 server's own Admin Server (<see cref="WithTm1Server"/>) or of a
/// shared one (<see cref="WithAdminServer"/>). <see cref="SharedAsync"/> gives the one TM1 Web the
/// tests of a run share, wired to <see cref="Tm1ServerContainer.SharedAsync"/>.</summary>
public class Tm1WebContainer : GenericContainer<Tm1WebContainer>
{
    /// <summary>The image TM1 Web runs in, without its tag.</summary>
    public const string DefaultImage = "ghcr.io/ajmyers/tm1-web";

    /// <summary>The setting that replaces <see cref="DefaultImage"/>.</summary>
    public const string WebImage = "TM1_WEB_IMAGE";

    /// <summary>TM1 Web's HTTP port.</summary>
    public const int HttpPort = 9510;

    private const string InstallPath = "/opt/ibm/cognos/tm1web";
    private static readonly TimeSpan StartupTimeout = TimeSpan.FromMinutes(5);
    private static readonly SemaphoreSlim SharedLock = new(1, 1);
    private static Tm1WebContainer? shared;
    private string? tm1Address;
    private string? tm1Hostname;

    /// <summary>TM1 Web from <paramref name="source"/>, in the image for it.</summary>
    public Tm1WebContainer(Tm1WebSource source)
        : this(DefaultImageFor(source ?? throw new ArgumentNullException(nameof(source))), source) { }

    /// <summary>TM1 Web from <paramref name="source"/>, in <paramref name="image"/>.</summary>
    public Tm1WebContainer(string image, Tm1WebSource source)
        : base(image)
    {
        ArgumentNullException.ThrowIfNull(source);
        Source = source;
        WithExposedPorts(HttpPort);
        WaitingForHttp("/tm1web/", HttpPort, code => code == HttpStatusCode.OK, StartupTimeout);
        switch (source)
        {
            case Tm1WebSource.InstallDir dir:
                Binds.Add($"{dir.Dir}:{InstallPath}:ro");
                break;
            case Tm1WebSource.InstallVolume volume:
                Binds.Add($"{volume.Name}:{InstallPath}");
                break;
            case Tm1WebSource.Bundle bundle:
                Binds.Add($"{bundle.Dir}:/ibm:ro");
                if (bundle.File is not null)
                {
                    WithEnv("TM1_WEB_BUNDLE", bundle.File);
                }
                Binds.Add($"{Tm1ServerContainer.CacheVolume}:/tm1-web/cache");
                break;
            case Tm1WebSource.BundleUrl url:
                WithEnv("TM1_WEB_BUNDLE_URL", url.Url.OriginalString);
                if (url.Sha256 is not null)
                {
                    WithEnv("TM1_WEB_BUNDLE_SHA256", url.Sha256);
                }
                Binds.Add($"{Tm1ServerContainer.CacheVolume}:/tm1-web/cache");
                break;
        }
    }

    /// <summary>The one TM1 Web the tests of a run share, wired to the shared TM1 server, with reuse.</summary>
    /// <exception cref="InvalidOperationException">No TM1 Web source is configured.</exception>
    public static async Task<Tm1WebContainer> SharedAsync(CancellationToken cancellationToken = default)
    {
        await SharedLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (shared is null)
            {
                var source = Tm1WebSource.Discover() ?? throw new InvalidOperationException(Xunit.RequiresTm1WebFactAttribute.NoSourceMessage);
                var tm1 = await Tm1ServerContainer.SharedAsync(cancellationToken).ConfigureAwait(false);
                var container = new Tm1WebContainer(source).WithTm1Server(tm1).WithReuse(true);
                await container.StartAsync(cancellationToken).ConfigureAwait(false);
                shared = container;
            }
            return shared;
        }
        finally
        {
            SharedLock.Release();
        }
    }

    /// <summary>Lists the servers of <paramref name="tm1"/>'s own Admin Server; TM1 starts first,
    /// and gets a network if it has none and isn't running.</summary>
    public Tm1WebContainer WithTm1Server(Tm1ServerContainer tm1)
    {
        ArgumentNullException.ThrowIfNull(tm1);
        Tm1Server = tm1;
        if (!tm1.IsRunning && tm1.Network is null)
        {
            tm1.WithNetwork(Network.NewNetwork());
        }
        return DependsOn(tm1);
    }

    /// <summary>Lists the servers of a shared Admin Server, on its network.</summary>
    public Tm1WebContainer WithAdminServer(Tm1AdminServerContainer admin)
    {
        ArgumentNullException.ThrowIfNull(admin);
        AdminServer = admin;
        Tm1ServerContainer.ShareNetwork(this, admin);
        return DependsOn(admin);
    }

    /// <summary>The shared Admin Server, or null.</summary>
    public Tm1AdminServerContainer? AdminServer { get; private set; }

    /// <summary>Where TM1 Web comes from.</summary>
    public Tm1WebSource Source { get; }

    /// <summary>The TM1 server whose Admin Server it lists, or null.</summary>
    public Tm1ServerContainer? Tm1Server { get; private set; }

    /// <summary>TM1 Web's address on the Docker host, <c>http://host:port/tm1web/</c>.</summary>
    public string Url => $"http://{Host}:{GetMappedPort(HttpPort)}/tm1web/";

    /// <summary>The Admin Server host TM1 Web lists servers from, as a TM1 Web login names it.</summary>
    /// <exception cref="InvalidOperationException">There's none yet.</exception>
    public string AdminHost =>
        EnvMap.TryGetValue("TM1_ADMIN_HOST", out var host)
            ? host
            : throw new InvalidOperationException("No Admin Server yet: set a TM1 server with WithTm1Server and start");

    /// <inheritdoc/>
    protected internal override void Configure()
    {
        if (AdminServer is not null)
        {
            WithEnv("TM1_ADMIN_HOST", Tm1AdminServerContainer.NetworkAlias);
            return;
        }
        if (Tm1Server is not { } tm1)
        {
            return;
        }
        if (!tm1.IsRunning || tm1Address is null)
        {
            throw new InvalidOperationException("The TM1 server isn't running; it should have started first");
        }
        if (tm1.Network is { } network)
        {
            WithNetwork(network);
        }
        else
        {
            WithExtraHost(tm1Hostname!, tm1Address);
        }
        WithEnv("TM1_ADMIN_HOST", tm1Address);
    }

    /// <summary>Reads TM1's address, once it's running, for <see cref="Configure"/>.</summary>
    private protected override async Task DependenciesStartedAsync(CancellationToken cancellationToken)
    {
        if (AdminServer is null && Tm1Server is { IsRunning: true } tm1)
        {
            var info = await tm1.InspectAsync(cancellationToken).ConfigureAwait(false);
            tm1Hostname = info.Config?.Hostname;
            tm1Address = AddressOn(info, tm1.Network?.Name, "TM1 Web");
        }
    }

    /// <inheritdoc/>
    protected override Task ContainerIsStartedAsync(CancellationToken cancellationToken)
    {
        Logger.LogInformation(
            "TM1 Web is up at {Url} ({Source}), listing servers from the Admin Server at {Host}",
            Url,
            Source.Describe(),
            EnvMap.GetValueOrDefault("TM1_ADMIN_HOST", "(none)")
        );
        return Task.CompletedTask;
    }

    /// <summary>A started container's address on the network named <paramref name="network"/>
    /// (Docker's default bridge when null).</summary>
    internal static string AddressOn(Docker.DotNet.Models.ContainerInspectResponse info, string? network, string who)
    {
        var networks = info.NetworkSettings?.Networks ?? new Dictionary<string, Docker.DotNet.Models.EndpointSettings>();
        networks.TryGetValue(network ?? "bridge", out var found);
        if (found is null || string.IsNullOrWhiteSpace(found.IPAddress))
        {
            throw new InvalidOperationException(
                $"{who} can't reach the TM1 server: it's on [{string.Join(", ", networks.Keys)}]. Give the TM1 server a Network (WithNetwork), or leave it on Docker's default bridge network."
            );
        }
        return found.IPAddress;
    }

    private static string DefaultImageFor(Tm1WebSource source) =>
        source is Tm1WebSource.PrivateImage p ? p.Image : Tm1ServerContainer.ImageFor(WebImage, DefaultImage);
}
