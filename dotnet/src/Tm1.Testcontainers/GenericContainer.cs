// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using Docker.DotNet.Models;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Containers;
using Microsoft.Extensions.Logging;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A container configured as Testcontainers for Java's <c>GenericContainer</c> is: its
/// environment, network, aliases and dependencies are set and read before it starts (which is what
/// lets <see cref="PlanningAnalyticsEnvironment"/> wire containers together and tests check the
/// wiring without Docker), and it's built with Testcontainers for .NET's <see cref="ContainerBuilder"/>
/// when it starts. <see cref="Container"/> is the started container, for anything not wrapped here.</summary>
/// <typeparam name="TSelf">The container's own type, which the <c>With…</c> methods return.</typeparam>
public abstract class GenericContainer<TSelf> : IStartable, IGenericContainer
    where TSelf : GenericContainer<TSelf>
{
    private readonly Dictionary<string, string> env = new(StringComparer.Ordinal);
    private readonly List<string> aliases = [];
    private readonly List<int> ports = [];
    private readonly List<IStartable> dependencies = [];
    private readonly Dictionary<string, string> extraHosts = new(StringComparer.Ordinal);
    private readonly List<(string Source, string Target)> copies = [];
    private readonly Dictionary<string, string> labels = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim starting = new(1, 1);
    private string[] command = [];
    private string? hostname;
    private bool reuse;
    private WaitFor? wait;
    private IContainer? container;

    /// <summary>A container of <paramref name="image"/>.</summary>
    protected GenericContainer(string image)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(image);
        DockerImageName = image;
    }

    /// <summary>The image it runs.</summary>
    public string DockerImageName { get; }

    /// <summary>Its environment variables, so far.</summary>
    public IReadOnlyDictionary<string, string> EnvMap => env;

    /// <summary>The command it runs, if not the image's.</summary>
    public IReadOnlyList<string> CommandParts => command;

    /// <summary>The ports it exposes on the Docker host.</summary>
    public IReadOnlyList<int> ExposedPorts => ports;

    /// <summary>The network it joins, or null for Docker's default bridge.</summary>
    public Network? Network { get; private set; }

    /// <summary>Its names on <see cref="Network"/>.</summary>
    public IReadOnlyList<string> NetworkAliases => aliases;

    /// <summary>What starts before it.</summary>
    public IReadOnlyCollection<IStartable> Dependencies => dependencies;

    /// <summary>The started container.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public IContainer Container => container ?? throw new InvalidOperationException("The container isn't started yet");

    /// <inheritdoc/>
    public bool IsRunning => container is { State: TestcontainersStates.Running };

    /// <summary>The host its ports are mapped on.</summary>
    public string Host => Container.Hostname;

    /// <summary>The Docker container's ID.</summary>
    public string ContainerId => Container.Id;

    /// <summary>The host port <paramref name="port"/> is mapped to.</summary>
    public int GetMappedPort(int port) => Container.GetMappedPublicPort(port);

    /// <summary>Sets an environment variable.</summary>
    public TSelf WithEnv(string name, string value)
    {
        ArgumentNullException.ThrowIfNull(name);
        ArgumentNullException.ThrowIfNull(value);
        env[name] = value;
        return Self;
    }

    /// <summary>Sets environment variables.</summary>
    public TSelf WithEnv(IReadOnlyDictionary<string, string> variables)
    {
        ArgumentNullException.ThrowIfNull(variables);
        foreach (var (name, value) in variables)
        {
            env[name] = value;
        }
        return Self;
    }

    /// <summary>Runs <paramref name="commandParts"/> in place of the image's command.</summary>
    public TSelf WithCommand(params string[] commandParts)
    {
        command = [.. commandParts];
        return Self;
    }

    /// <summary>Exposes ports on the Docker host, each on a free port.</summary>
    public TSelf WithExposedPorts(params int[] exposedPorts)
    {
        ports.AddRange(exposedPorts.Where(p => !ports.Contains(p)));
        return Self;
    }

    /// <summary>Joins <paramref name="network"/> (created when the first container on it starts).</summary>
    public TSelf WithNetwork(Network? network)
    {
        Network = network;
        return Self;
    }

    /// <summary>Adds names it's reached by on <see cref="Network"/>.</summary>
    public TSelf WithNetworkAliases(params string[] networkAliases)
    {
        aliases.AddRange(networkAliases.Where(a => !aliases.Contains(a)));
        return Self;
    }

    /// <summary>Starts <paramref name="startables"/> before it.</summary>
    public TSelf DependsOn(params IStartable[] startables)
    {
        foreach (var s in startables)
        {
            ArgumentNullException.ThrowIfNull(s);
            if (!dependencies.Contains(s))
            {
                dependencies.Add(s);
            }
        }
        return Self;
    }

    /// <summary>Adds an <c>/etc/hosts</c> entry.</summary>
    public TSelf WithExtraHost(string host, string ipAddress)
    {
        extraHosts[host] = ipAddress;
        return Self;
    }

    /// <summary>Keeps the container for later runs, when reuse is switched on as Testcontainers for
    /// Java has it (<c>testcontainers.reuse.enable=true</c> in <c>~/.testcontainers.properties</c>,
    /// or <c>TESTCONTAINERS_REUSE_ENABLE=true</c>); otherwise it's removed after the run.</summary>
    public TSelf WithReuse(bool reusable)
    {
        reuse = reusable;
        return Self;
    }

    bool IGenericContainer.ShouldBeReused => reuse;

    IReadOnlyDictionary<string, string> IGenericContainer.Labels => labels;

    IReadOnlyList<(string Source, string Target)> IGenericContainer.Copies => copies;

    IReadOnlyList<string> IGenericContainer.Binds => Binds;

    void IGenericContainer.MarkReused(string label, string value)
    {
        reuse = true;
        labels[label] = value;
    }

    /// <summary>Copies a host file or folder into the container before it starts.</summary>
    public TSelf WithCopyFileToContainer(string hostPath, string containerPath)
    {
        copies.Add((hostPath, containerPath));
        return Self;
    }

    /// <summary>How long to wait for it to be ready.</summary>
    public TSelf WithStartupTimeout(TimeSpan timeout)
    {
        if (wait is not null)
        {
            wait = wait with { Timeout = timeout };
        }
        return Self;
    }

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
            // As Java's Startables.deepStart: what it depends on starts first
            await Task.WhenAll(dependencies.Select(d => d.StartAsync(cancellationToken))).ConfigureAwait(false);
            await DependenciesStartedAsync(cancellationToken).ConfigureAwait(false);
            Configure();
            if (Network is not null)
            {
                await Network.CreateAsync(cancellationToken).ConfigureAwait(false);
            }
            var started = Build();
            container = started;
            try
            {
                await started.StartAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (ContainerNotRunningException e)
            {
                // As Java's and the other clients': the exit code and the end of the log, in one message
                var message = await ExitedAsync(started, cancellationToken).ConfigureAwait(false);
                container = null;
                await started.DisposeAsync().ConfigureAwait(false);
                throw new InvalidOperationException(message, e);
            }
            await ContainerIsStartedAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            starting.Release();
        }
    }

    /// <inheritdoc/>
    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        if (container is { } c)
        {
            container = null;
            await c.DisposeAsync().ConfigureAwait(false);
        }
    }

    /// <summary>Stops it.</summary>
    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        starting.Dispose();
        GC.SuppressFinalize(this);
    }

    /// <summary>Its log, so far.</summary>
    public async Task<string> GetLogsAsync(CancellationToken cancellationToken = default)
    {
        var (stdout, stderr) = await Container.GetLogsAsync(ct: cancellationToken).ConfigureAwait(false);
        return stdout + stderr;
    }

    /// <summary>What Docker says of the running container, as Java's <c>getContainerInfo()</c>.</summary>
    internal async Task<ContainerInspectResponse> InspectAsync(CancellationToken cancellationToken = default)
    {
        using var docker = DockerHost.Client();
        return await docker.Containers.InspectContainerAsync(ContainerId, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Finishes the configuration as it starts, once what it depends on is running; the
    /// tests call it to check the wiring.</summary>
    protected internal virtual void Configure() { }

    /// <summary>Runs once what it depends on is running, before <see cref="Configure"/>.</summary>
    /// <summary>The platform to run the image for. TM1 and IBM's other servers only exist for x86-64, so on
    /// Apple Silicon they run under Rosetta; a container whose image is built for the machine says
    /// <c>null</c>, as the Java module sets no platform for it.</summary>
    private protected virtual string? Platform => "linux/amd64";

    private protected virtual Task DependenciesStartedAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <summary>Runs once it's ready.</summary>
    protected virtual Task ContainerIsStartedAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <summary>Waits until <paramref name="path"/> on <paramref name="port"/> answers with a status <paramref name="ok"/> accepts.</summary>
    protected void WaitingForHttp(string path, int port, Predicate<HttpStatusCode> ok, TimeSpan timeout) => wait = new WaitFor(path, port, ok, timeout);

    /// <summary>Sets the container's host name.</summary>
    protected void WithHostname(string name) => hostname = name;

    /// <summary>The bind mounts, <c>source:target[:ro]</c>, source a host folder or a volume.</summary>
    internal List<string> Binds { get; } = [];

    /// <summary>The logger Testcontainers gives the container.</summary>
    protected ILogger Logger => Container.Logger;

    private TSelf Self => (TSelf)this;

    private IContainer Build()
    {
        var builder = new ContainerBuilder(DockerImageName)
            .WithEnvironment(env)
            .WithLabel("io.skipcheck.tm1.testcontainers", "dotnet")
            .WithLabel(labels)
            .WithCreateParameterModifier(p =>
            {
                if (Platform is not null)
                {
                    p.Platform = Platform;
                }
                p.HostConfig ??= new HostConfig();
                p.HostConfig.Binds = [.. p.HostConfig.Binds ?? [], .. Binds];
                // Testcontainers for .NET names a container's network only as an endpoint, which leaves
                // its network mode as Docker's default bridge; Java's, Python's and Node's name it here too
                if (Network is not null)
                {
                    p.HostConfig.NetworkMode = Network.Name;
                }
            });
        if (command.Length > 0)
        {
            builder = builder.WithCommand(command);
        }
        foreach (var port in ports)
        {
            builder = builder.WithPortBinding(port, true);
        }
        if (Network is not null)
        {
            builder = builder.WithNetwork(Network.Resource);
            if (aliases.Count > 0)
            {
                builder = builder.WithNetworkAliases(aliases);
            }
        }
        foreach (var (host, ip) in extraHosts)
        {
            builder = builder.WithExtraHost(host, ip);
        }
        if (hostname is not null)
        {
            builder = builder.WithHostname(hostname);
        }
        foreach (var (source, target) in copies)
        {
            builder = System.IO.Directory.Exists(source)
                ? builder.WithResourceMapping(DirectoryPath.Of(source), DirectoryPath.Of(target))
                : builder.WithResourceMapping(FilePath.Of(source), DirectoryPath.Of(target));
        }
        if (reuse && DockerHost.ReuseEnabled())
        {
            builder = builder.WithReuse(true);
        }
        if (wait is { } w)
        {
            builder = builder.WithWaitStrategy(
                Wait.ForUnixContainer()
                    .UntilHttpRequestIsSucceeded(r => r.ForPath(w.Path).ForPort((ushort)w.Port).ForStatusCodeMatching(w.Ok), s => s.WithTimeout(w.Timeout))
            );
        }
        return builder.Build();
    }

    private sealed record WaitFor(string Path, int Port, Predicate<HttpStatusCode> Ok, TimeSpan Timeout);

    /// <summary>Why a container that exited while starting did: its exit code and the end of its log.</summary>
    private static async Task<string> ExitedAsync(IContainer exited, CancellationToken cancellationToken)
    {
        long code;
        string stdout;
        string stderr;
        try
        {
            code = await exited.GetExitCodeAsync(cancellationToken).ConfigureAwait(false);
            // With timestamps, so the two streams can be put back in the order they were written
            (stdout, stderr) = await exited.GetLogsAsync(timestampsEnabled: true, ct: cancellationToken).ConfigureAwait(false);
        }
        catch (Exception e) when (e is not OutOfMemoryException and not OperationCanceledException)
        {
            return "The container exited while starting; its exit code and log couldn't be read: " + e.Message;
        }
        var lines = (stdout + "\n" + stderr)
            .Split('\n')
            .Select(l => l.TrimEnd('\r'))
            .Where(l => l.Length > 0)
            .Select(l => l.IndexOf(' ', StringComparison.Ordinal) is var space and > 0 ? (Time: l[..space], Text: l[(space + 1)..]) : (Time: "", Text: l))
            .OrderBy(l => l.Time, StringComparer.Ordinal)
            .Select(l => l.Text)
            .ToList();
        return $"The container exited while starting, with code {code}. The end of its log:\n" + string.Join('\n', lines.TakeLast(30));
    }
}

/// <summary>What <see cref="PlanningAnalyticsEnvironment"/> reads and sets on each of its containers, whatever its type.</summary>
internal interface IGenericContainer
{
    string DockerImageName { get; }

    IReadOnlyDictionary<string, string> EnvMap { get; }

    IReadOnlyList<string> CommandParts { get; }

    IReadOnlyList<int> ExposedPorts { get; }

    IReadOnlyList<string> NetworkAliases { get; }

    /// <summary>Its labels, besides the one every container of this library has.</summary>
    IReadOnlyDictionary<string, string> Labels { get; }

    /// <summary>The host files and folders copied in, and where to.</summary>
    IReadOnlyList<(string Source, string Target)> Copies { get; }

    /// <summary>The bind mounts, <c>source:target[:ro]</c>.</summary>
    IReadOnlyList<string> Binds { get; }

    /// <summary>Whether it's kept for later runs, as Java's <c>isShouldBeReused()</c>: <see cref="GenericContainer{TSelf}.WithReuse"/>,
    /// whatever the developer's switch says.</summary>
    bool ShouldBeReused { get; }

    /// <summary>Marks it reusable, with <paramref name="label"/> set to <paramref name="value"/>.</summary>
    void MarkReused(string label, string value);
}
