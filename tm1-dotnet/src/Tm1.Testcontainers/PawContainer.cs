using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Docker.DotNet;
using Docker.DotNet.Models;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Configurations;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>Planning Analytics Workspace, from IBM's PAW Local zip, logging users on with a TM1
/// server's own security, as the Java <c>PawContainer</c>. PAW is several containers run by IBM's
/// compose file, so this isn't one container: it runs <c>paw-local.sh</c> (from
/// <c>docker/paw</c>, embedded in this library) in a <c>docker:cli</c> runner with the Docker
/// socket, and joins PAW's containers to TM1's network. IBM's license must be accepted first
/// (<c>PAW_ACCEPT_LICENSE=true</c>, or <see cref="AcceptLicense"/>).</summary>
public class PawContainer : IStartable
{
    /// <summary>The setting that accepts IBM's PAW license.</summary>
    public const string AcceptLicenseSetting = "PAW_ACCEPT_LICENSE";

    /// <summary>The volume PAW's state lives in, kept between runs.</summary>
    public const string HomeVolume = "tm1-paw-home";

    internal const string PawNetwork = "paw_default";
    internal const int AdminPort = 5895;
    private const string ComposeProject = "paw";
    private const string Script = "paw-local.sh";
    private const string RunnerBase = "docker:cli";
    private static readonly TimeSpan DefaultStartupTimeout = TimeSpan.FromMinutes(20);
    private static readonly TimeSpan ReadyTimeout = TimeSpan.FromMinutes(10);
    private static readonly SemaphoreSlim SharedLock = new(1, 1);
    private static PawContainer? shared;
    private static string? sharedFailure;
    private readonly Dictionary<string, string> settings = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim starting = new(1, 1);
    private Tm1AdminServerContainer? adminServer;
    private bool licenseAccepted = LicenseAcceptedBySetting();
    private TimeSpan startupTimeout = DefaultStartupTimeout;
    private int httpPort;

    /// <summary>PAW from <paramref name="source"/>.</summary>
    public PawContainer(PawSource source)
    {
        Source = source ?? throw new ArgumentNullException(nameof(source));
    }

    /// <summary>The one PAW the tests of a run share, wired to <see cref="Tm1ServerContainer.SharedAsync"/>;
    /// a failure to start is remembered, so later tests fail fast.</summary>
    public static async Task<PawContainer> SharedAsync(CancellationToken cancellationToken = default)
    {
        await SharedLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (sharedFailure is not null)
            {
                throw new InvalidOperationException("The shared PAW failed to start earlier in this run: " + sharedFailure);
            }
            if (shared is null)
            {
                try
                {
                    var source = PawSource.Discover() ?? throw new InvalidOperationException(Xunit.RequiresPawFactAttribute.NoSourceMessage);
                    var paw = new PawContainer(source).WithTm1Server(await Tm1ServerContainer.SharedAsync(cancellationToken).ConfigureAwait(false));
                    await paw.StartAsync(cancellationToken).ConfigureAwait(false);
                    shared = paw;
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    sharedFailure = e.Message;
                    throw;
                }
            }
            return shared;
        }
        finally
        {
            SharedLock.Release();
        }
    }

    /// <summary>Logs users on to <paramref name="tm1"/>, and lists its Admin Server's servers.</summary>
    public PawContainer WithTm1Server(Tm1ServerContainer tm1)
    {
        Tm1Server = tm1 ?? throw new ArgumentNullException(nameof(tm1));
        return this;
    }

    /// <summary>Lists the servers of a shared Admin Server, which must be on TM1's network.</summary>
    public PawContainer WithAdminServer(Tm1AdminServerContainer admin)
    {
        adminServer = admin ?? throw new ArgumentNullException(nameof(admin));
        return this;
    }

    /// <summary>Accepts IBM's PAW license (read it: <c>licenses/LA_en</c> in the zip).</summary>
    public PawContainer AcceptLicense()
    {
        licenseAccepted = true;
        return this;
    }

    /// <summary>Sets a <c>paw-local.sh</c> setting.</summary>
    /// <exception cref="ArgumentException">It doesn't start with <c>PAW_</c>.</exception>
    public PawContainer WithSetting(string name, string value)
    {
        ArgumentNullException.ThrowIfNull(name);
        if (!name.StartsWith("PAW_", StringComparison.Ordinal))
        {
            throw new ArgumentException("Not a paw-local.sh setting: " + name, nameof(name));
        }
        settings[name] = value ?? throw new ArgumentNullException(nameof(value));
        return this;
    }

    /// <summary>How long the first start (which loads IBM's images) may take.</summary>
    public PawContainer WithStartupTimeout(TimeSpan timeout)
    {
        startupTimeout = timeout;
        return this;
    }

    /// <summary>Where PAW comes from.</summary>
    public PawSource Source { get; }

    /// <summary>The TM1 server PAW logs users on to, or null.</summary>
    public Tm1ServerContainer? Tm1Server { get; private set; }

    /// <inheritdoc/>
    public bool IsRunning { get; private set; }

    /// <summary>PAW's address on the Docker host.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public string Url => IsRunning ? $"http://{DockerHostAddress()}:{httpPort}/" : throw new InvalidOperationException("PAW isn't started");

    /// <inheritdoc/>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        await starting.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (IsRunning)
            {
                return;
            }
            if (!licenseAccepted)
            {
                throw new InvalidOperationException(Xunit.RequiresPawFactAttribute.NoLicenseMessage + " Or call AcceptLicense().");
            }
            var server = Tm1Server ?? throw new InvalidOperationException("No TM1 server: set one with WithTm1Server");
            await server.StartAsync(cancellationToken).ConfigureAwait(false);
            var (tm1NetworkId, tm1Address) = await NetworkOfAsync(server, cancellationToken).ConfigureAwait(false);
            var adminAddress = tm1Address;
            if (adminServer is { } admin)
            {
                await admin.StartAsync(cancellationToken).ConfigureAwait(false);
                var (adminNetworkId, address) = await NetworkOfAsync(admin, cancellationToken).ConfigureAwait(false);
                if (adminNetworkId != tm1NetworkId)
                {
                    throw new InvalidOperationException("PAW needs the Admin Server and the TM1 server on one network");
                }
                adminAddress = address;
            }
            httpPort = FreePort();
            var env = new Dictionary<string, string>(settings, StringComparer.Ordinal)
            {
                ["PAW_TM1_ADMIN_URL"] = $"http://{adminAddress}:{AdminPort}",
                ["PAW_TM1_LOGIN_URL"] = $"http://{tm1Address}:{Tm1ServerContainer.RestPort}",
                ["PAW_AUTH_MODE"] = "tm1",
                ["PAW_HTTP_PORT"] = httpPort.ToString(System.Globalization.CultureInfo.InvariantCulture),
                ["PAW_HTTPS_PORT"] = FreePort().ToString(System.Globalization.CultureInfo.InvariantCulture),
                ["PAW_NETWORK"] = PawNetwork,
                ["PAW_WAIT"] = "0",
            };
            using var docker = DockerHost.Client();
            await RunScriptAsync(docker, "up", env, startupTimeout, cancellationToken).ConfigureAwait(false);
            await ConnectToNetworkAsync(docker, tm1NetworkId, cancellationToken).ConfigureAwait(false);
            IsRunning = true;
            await AwaitReadyAsync(server, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            starting.Release();
        }
    }

    /// <inheritdoc/>
    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        if (!IsRunning)
        {
            return;
        }
        IsRunning = false;
        using var docker = DockerHost.Client();
        try
        {
            await RunScriptAsync(docker, "down", new Dictionary<string, string>(), TimeSpan.FromMinutes(5), cancellationToken).ConfigureAwait(false);
        }
        catch (InvalidOperationException)
        {
            // Couldn't stop PAW; its containers may still be running
        }
    }

    /// <summary>Stops it.</summary>
    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        starting.Dispose();
        GC.SuppressFinalize(this);
    }

    /// <inheritdoc/>
    public override string ToString() => "PawContainer[" + Source.Describe() + (IsRunning ? ", " + Url : "") + "]";

    /// <summary>Whether <c>PAW_ACCEPT_LICENSE=true</c> is set.</summary>
    internal static bool LicenseAcceptedBySetting() => string.Equals(Settings.Get(AcceptLicenseSetting)?.Trim(), "true", StringComparison.OrdinalIgnoreCase);

    private async Task RunScriptAsync(IDockerClient docker, string command, Dictionary<string, string> env, TimeSpan timeout, CancellationToken cancellationToken)
    {
        var home = await HomeMountpointAsync(docker, cancellationToken).ConfigureAwait(false);
        var zipName = Path.GetFileName(Source.Zip);
        var socket = DockerHost.SocketPath();
        var image = await RunnerImageAsync(cancellationToken).ConfigureAwait(false);
        await using var runner = new ContainerBuilder(image)
            .WithCommand(Script, command)
            .WithEnvironment(env)
            .WithEnvironment("PAW_ACCEPT_LICENSE", "true")
            .WithEnvironment("PAW_ZIP", "/zip/" + zipName)
            .WithEnvironment("PAW_HOME", home)
            .WithBindMount(Source.Zip, "/zip/" + zipName, AccessMode.ReadOnly)
            .WithCreateParameterModifier(p =>
            {
                p.HostConfig ??= new HostConfig();
                p.HostConfig.Binds = [.. p.HostConfig.Binds ?? [], $"{socket}:/var/run/docker.sock", $"{HomeVolume}:{home}"];
            })
            .Build();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        await runner.StartAsync(deadline.Token).ConfigureAwait(false);
        var exit = await runner.GetExitCodeAsync(deadline.Token).ConfigureAwait(false);
        if (exit != 0)
        {
            var (stdout, stderr) = await runner.GetLogsAsync(ct: cancellationToken).ConfigureAwait(false);
            throw new InvalidOperationException($"paw-local.sh {command} failed (exit {exit}):\n{stdout}{stderr}");
        }
    }

    private static async Task<string> HomeMountpointAsync(IDockerClient docker, CancellationToken cancellationToken)
    {
        try
        {
            return (await docker.Volumes.InspectAsync(HomeVolume, cancellationToken).ConfigureAwait(false)).Mountpoint;
        }
        catch (DockerApiException e) when (e.StatusCode == HttpStatusCode.NotFound)
        {
            await docker.Volumes.CreateAsync(new VolumesCreateParameters { Name = HomeVolume }, cancellationToken).ConfigureAwait(false);
            return (await docker.Volumes.InspectAsync(HomeVolume, cancellationToken).ConfigureAwait(false)).Mountpoint;
        }
    }

    private static async Task ConnectToNetworkAsync(IDockerClient docker, string networkId, CancellationToken cancellationToken)
    {
        var containers = await docker.Containers.ListContainersAsync(new ContainersListParameters
        {
            Filters = new Dictionary<string, IDictionary<string, bool>> { ["label"] = new Dictionary<string, bool> { ["com.docker.compose.project=" + ComposeProject] = true } },
        }, cancellationToken).ConfigureAwait(false);
        foreach (var container in containers)
        {
            var attached = container.NetworkSettings?.Networks?.Values.Any(n => n.NetworkID == networkId) ?? false;
            if (!attached)
            {
                await docker.Networks.ConnectNetworkAsync(networkId, new NetworkConnectParameters { Container = container.ID }, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    private async Task AwaitReadyAsync(Tm1ServerContainer server, CancellationToken cancellationToken)
    {
        var deadline = DateTime.UtcNow + ReadyTimeout;
        var last = "no answer yet";
        while (DateTime.UtcNow < deadline)
        {
            try
            {
                using var session = new PawSession(Url);
                await session.LoginAsync(server.Username, server.Password, cancellationToken).ConfigureAwait(false);
                var servers = await session.ServersAsync(cancellationToken).ConfigureAwait(false);
                if (servers.Count > 0)
                {
                    var (status, body) = await session.Tm1GetAsync(servers[0], "Configuration/ProductVersion/$value", cancellationToken).ConfigureAwait(false);
                    if (status == HttpStatusCode.OK)
                    {
                        return;
                    }
                    last = $"a TM1 call through PAW answered {(int)status}: {body}";
                }
                else
                {
                    last = "PAW lists no TM1 servers yet";
                }
            }
            catch (IOException e)
            {
                last = e.Message;
            }
            await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken).ConfigureAwait(false);
        }
        throw new InvalidOperationException($"PAW wasn't ready within {ReadyTimeout}: {last}");
    }

    private static async Task<(string NetworkId, string Address)> NetworkOfAsync<T>(GenericContainer<T> container, CancellationToken cancellationToken)
        where T : GenericContainer<T>
    {
        var info = await container.InspectAsync(cancellationToken).ConfigureAwait(false);
        var address = Tm1WebContainer.AddressOn(info, container.Network?.Name, "PAW");
        return (info.NetworkSettings!.Networks[container.Network?.Name ?? "bridge"].NetworkID, address);
    }

    /// <summary>The runner image, <c>docker:cli</c> with bash, curl and the script, named by the
    /// script's hash so a changed script builds a new one.</summary>
    private static async Task<string> RunnerImageAsync(CancellationToken cancellationToken)
    {
        var script = ScriptBytes();
        var name = "tm1-paw-runner:" + Convert.ToHexStringLower(SHA256.HashData(script))[..12];
        var context = Directory.CreateTempSubdirectory("tm1-paw-runner");
        try
        {
            await File.WriteAllBytesAsync(Path.Combine(context.FullName, Script), script, cancellationToken).ConfigureAwait(false);
            await File.WriteAllTextAsync(Path.Combine(context.FullName, "Dockerfile"),
                $"FROM {RunnerBase}\nRUN apk add --no-cache bash curl\nCOPY {Script} /usr/local/bin/{Script}\nRUN chmod 755 /usr/local/bin/{Script}\n", cancellationToken).ConfigureAwait(false);
            var image = new ImageFromDockerfileBuilder()
                .WithName(name)
                .WithDockerfileDirectory(context.FullName)
                .WithDeleteIfExists(false)
                .WithCleanUp(false)
                .Build();
            await image.CreateAsync(cancellationToken).ConfigureAwait(false);
            return name;
        }
        finally
        {
            context.Delete(true);
        }
    }

    private static byte[] ScriptBytes()
    {
        using var stream = typeof(PawContainer).Assembly.GetManifestResourceStream(Script)
            ?? throw new InvalidOperationException($"Missing {Script} in the AjMyers.Tm1.Testcontainers assembly");
        using var copy = new MemoryStream();
        stream.CopyTo(copy);
        return copy.ToArray();
    }

    private static string DockerHostAddress()
    {
        var endpoint = TestcontainersSettings.OS.DockerEndpointAuthConfig?.Endpoint;
        return endpoint is null || endpoint.Scheme is "unix" or "npipe" ? "localhost" : endpoint.Host;
    }

    private static int FreePort()
    {
        using var socket = new TcpListener(IPAddress.Loopback, 0);
        socket.Start();
        return ((IPEndPoint)socket.LocalEndpoint).Port;
    }
}
