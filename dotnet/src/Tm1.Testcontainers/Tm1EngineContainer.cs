// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using Docker.DotNet;
using Microsoft.Extensions.Logging;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A TM1 v12 engine (Planning Analytics 3.x) in Docker, for tests, as the Java
/// <c>Tm1EngineContainer</c>: the <c>tm1-engine</c> image with TM1 from a <see cref="Tm1EngineSource"/>,
/// and the MongoDB it keeps its metadata in (<see cref="Datastore"/>), on a network of their own. For
/// TM1 11, use <see cref="Tm1ServerContainer"/>.
/// <para>One engine holds instances, each holding databases. Most tests use the one instance,
/// <c>tm1</c>, and a database of their own: <see cref="Xunit.Tm1DatabaseFixture{TTestClass}"/> gives
/// each test class one. A container you make yourself has the databases you name with
/// <see cref="WithDatabase(string)"/>, or one called <c>tm1</c>.</para>
/// <para>TM1 v12 has no passwords of its own. A test logs in through the instance's application
/// (<see cref="Tm1Login"/>), which this container creates and keeps the credentials of.</para>
/// <para>Starting takes about 40 seconds, and a database about 10 more to create
/// (<see cref="CreateDatabaseAsync(string, CancellationToken)"/>). The image exists for x86-64 only,
/// as TM1 does; on Apple Silicon it runs under Rosetta.</para></summary>
/// <example><code>
/// await using var engine = new Tm1EngineContainer(source).WithDatabase("planning").WithUser("analyst", "Analysts");
/// await engine.StartAsync();
/// var planning = engine.Database("planning");
/// var url = planning.RestUrl; // http://localhost:54321/tm1/api/v1/Databases('planning')/
/// var analyst = planning.Login("analyst");
/// </code></example>
public partial class Tm1EngineContainer : GenericContainer<Tm1EngineContainer>
{
    /// <summary>The <c>tm1-engine</c> image, without its tag (the library's version, or <c>edge</c>).</summary>
    public const string DefaultImage = "ghcr.io/ajmyers/tm1-engine";

    /// <summary>The setting that replaces <see cref="DefaultImage"/> (not used with
    /// <see cref="Tm1EngineSource.PrivateImage"/>), e.g. <c>tm1-engine:local</c> for a local build.</summary>
    public const string EngineImage = "TM1_ENGINE_IMAGE";

    /// <summary>The MongoDB image the engine's datastore runs.</summary>
    public const string DatastoreImage = "mongo:8.0";

    /// <summary>The port of TM1's gateway inside the container: the REST API, logins and management.</summary>
    public const int Port = 4444;

    /// <summary>The instance databases are in unless one is named.</summary>
    public const string DefaultInstance = "tm1";

    /// <summary>The database a container has when none is named.</summary>
    public const string DefaultDatabase = "tm1";

    /// <summary>The datastore's name on the engine's network.</summary>
    public const string DatastoreAlias = "tm1-datastore";

    /// <summary>The volume package installs are cached in, shared by every engine container on this
    /// Docker host (and with the Java, Python and TypeScript tests). Only the first container to see
    /// a package installs it. Remove the volume to clear it.</summary>
    public const string CacheVolume = "tm1-engine-testcontainers-cache";

    internal const string InstallPath = "/opt/ibm/tm1-engine";

    // The datastore is this engine's alone and goes with it: nothing else is given the password
    private const string DatastoreUser = "root";
    private const string DatastorePassword = "tm1datastore";

    private static readonly TimeSpan StartupTimeout = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan CommandTimeout = TimeSpan.FromMinutes(3);

    // The image itself waits up to five minutes for a new database to be ready: longer than that, so
    // it's the image's own message that comes back
    private static readonly TimeSpan CreateTimeout = TimeSpan.FromSeconds(330);
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(30);
    private static readonly SemaphoreSlim SharedLock = new(1, 1);
    private static Tm1EngineContainer? shared;

    // instance/database, in the order they were added
    private readonly List<string> databases = [];
    private readonly List<(string Name, string[] Groups)> users = [];

    // One command at a time, with the reading of what it changed: test classes run side by side, and
    // each creates and drops its database in the one shared engine
    private readonly SemaphoreSlim commands = new(1, 1);
    private Credentials? credentials;
    private Tm1Version? version;

    // The network this made for itself, which goes with a start that failed; not one it was given
    private Network? ownNetwork;

    /// <summary>TM1 from <paramref name="source"/>, in the default image for it: a private image as it
    /// is; otherwise <c>TM1_ENGINE_IMAGE</c> if set; otherwise <see cref="DefaultImage"/> tagged with
    /// this library's version (<c>edge</c> for a development build).</summary>
    public Tm1EngineContainer(Tm1EngineSource source)
        : this(DefaultImageFor(source ?? throw new ArgumentNullException(nameof(source))), source) { }

    /// <summary>TM1 from <paramref name="source"/>, in an image of your own, such as a locally built one.</summary>
    public Tm1EngineContainer(string image, Tm1EngineSource source)
        : base(image)
    {
        ArgumentNullException.ThrowIfNull(source);
        Source = source;
        WithExposedPorts(Port);
        // The gateway refuses a request without a login (401) once it's up. That isn't ready yet:
        // ContainerIsStartedAsync then waits for the image to finish setting TM1 up. The wait stops
        // if the container exits, as it does when TM1 can't start.
        WaitingForHttp("/", Port, code => code is HttpStatusCode.Unauthorized or HttpStatusCode.OK, StartupTimeout);
        Datastore = new DatastoreContainer(DatastoreImage)
            .WithNetworkAliases(DatastoreAlias)
            .WithEnv("MONGO_INITDB_ROOT_USERNAME", DatastoreUser)
            .WithEnv("MONGO_INITDB_ROOT_PASSWORD", DatastorePassword)
            // Enough for TM1's metadata; MongoDB would otherwise take half the machine's memory
            .WithCommand("--wiredTigerCacheSizeGB", "0.5");
        WithEnv("TM1_DATASTORE_URL", $"mongodb://{DatastoreUser}:{DatastorePassword}@{DatastoreAlias}:27017");
        DependsOn(Datastore);
        // A network of their own unless one is given. It's made in Docker only when used.
        ownNetwork = Network.NewNetwork();
        WithNetwork(ownNetwork);
        MountSource();
    }

    /// <summary>The engine most tests share: one per test run, started on first use with the source
    /// <see cref="Tm1EngineSource.Discover()"/> finds, and removed when the run ends. It has one
    /// database, <c>tm1</c>; <see cref="Xunit.Tm1DatabaseFixture{TTestClass}"/> creates one more for
    /// each test class. It isn't marked reusable: an engine and its datastore belong together, and a
    /// kept engine would keep every database tests left in it.</summary>
    /// <exception cref="InvalidOperationException">No source is configured.</exception>
    public static async Task<Tm1EngineContainer> SharedAsync(CancellationToken cancellationToken = default)
    {
        await SharedLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (shared is null)
            {
                var source = Tm1EngineSource.Discover() ?? throw new InvalidOperationException(Xunit.RequiresTm1EngineFactAttribute.NoSourceMessage);
                var container = new Tm1EngineContainer(source);
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

    /// <summary>Adds a database, in the instance <c>tm1</c>. Without any, the container has one called
    /// <c>tm1</c>.</summary>
    /// <param name="name">The database's name: at least three letters, digits, <c>_</c> or <c>-</c>.
    /// <c>Sales</c> and <c>sales</c> are two databases.</param>
    /// <exception cref="ArgumentException">The image can't take the name.</exception>
    public Tm1EngineContainer WithDatabase(string name) => WithDatabase(DefaultInstance, name);

    /// <summary>Adds a database in a named instance, which is created with an application of its own.
    /// An instance's users, sessions and applications are separate from another's.</summary>
    /// <exception cref="ArgumentException">The image can't take a name.</exception>
    public Tm1EngineContainer WithDatabase(string instance, string name)
    {
        var database = RequireInstance(instance) + "/" + RequireName(name, "database");
        if (!databases.Contains(database))
        {
            databases.Add(database);
        }
        return this;
    }

    /// <summary>Adds a user to every database, in exactly these groups (created if missing). A new
    /// database has one user already: <c>admin</c>, in the <c>ADMIN</c> group.</summary>
    /// <exception cref="ArgumentException">The image can't take a name, or the user is already added.</exception>
    public Tm1EngineContainer WithUser(string name, params string[] groups)
    {
        ArgumentNullException.ThrowIfNull(groups);
        if (name is null || !UserName().IsMatch(name))
        {
            throw new ArgumentException("A user's name is letters, digits, '.', '_' and '-': " + name, nameof(name));
        }
        foreach (var group in groups)
        {
            if (group is null || !UserName().IsMatch(group))
            {
                throw new ArgumentException("A group's name is letters, digits, '.', '_' and '-': " + group, nameof(groups));
            }
        }
        if (users.Any(u => u.Name == name))
        {
            throw new ArgumentException("The user " + name + " is already added", nameof(name));
        }
        users.Add((name, [.. groups]));
        return this;
    }

    /// <summary>Where TM1 comes from.</summary>
    public Tm1EngineSource Source { get; }

    /// <summary>The MongoDB the engine keeps its metadata in, started and stopped with it.</summary>
    public DatastoreContainer Datastore { get; }

    /// <summary>The gateway as seen from the host, ending in <c>/</c>, e.g. <c>http://localhost:54321/</c>.</summary>
    public string Url => $"http://{Host}:{GetMappedPort(Port)}/";

    /// <summary>The management API, which lists and creates instances, applications and databases,
    /// ending in <c>/</c>, e.g. <c>http://localhost:54321/manage/v1/</c>. It takes the root
    /// credentials as Basic authentication (<see cref="RootClientId"/>, <see cref="RootClientSecret"/>).</summary>
    public string ManagementUrl => Url + "manage/v1/";

    /// <summary>The client ID of the engine's root credentials, for the management API.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public string RootClientId => Current().RootClientId;

    /// <summary>The client secret of the engine's root credentials.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public string RootClientSecret => Current().RootClientSecret;

    /// <summary>The version of TM1 the engine runs. Only available once started.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public Tm1Version Tm1Version => version ?? throw new InvalidOperationException("The container isn't started yet");

    /// <summary>The databases the engine has now, in every instance, read from the engine again.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public async Task<IReadOnlyList<Tm1Database>> DatabasesAsync(CancellationToken cancellationToken = default)
    {
        // Read again: something else may have created or dropped one
        return Databases(await ChangeAsync(null, cancellationToken).ConfigureAwait(false));
    }

    /// <summary>A database of the instance <c>tm1</c>.</summary>
    /// <exception cref="ArgumentException">There's no such database.</exception>
    public Tm1Database Database(string name) => Database(DefaultInstance, name);

    /// <summary>A database of a named instance: of those the engine had when it started, or was last
    /// asked (<see cref="DatabasesAsync"/>, <see cref="CreateDatabaseAsync(string, string, CancellationToken)"/>,
    /// <see cref="DropDatabaseAsync"/>); before it starts, of those added with <see cref="WithDatabase(string, string)"/>.</summary>
    /// <exception cref="ArgumentException">There's no such database.</exception>
    public Tm1Database Database(string instance, string name)
    {
        var wanted = new Tm1Database(this, instance, name);
        if (IsRunning)
        {
            var found = Databases(Current());
            if (!found.Contains(wanted))
            {
                throw new ArgumentException($"No database {instance}/{name} in this engine. It has [{string.Join(", ", found)}]", nameof(name));
            }
        }
        else if (!Configured().Contains(instance + "/" + name))
        {
            throw new ArgumentException($"No database {instance}/{name} was added (WithDatabase). Added: [{string.Join(", ", Configured())}]", nameof(name));
        }
        return wanted;
    }

    /// <summary>Creates a database in the instance <c>tm1</c> of the running engine, with the users of
    /// <see cref="WithUser"/>. Takes about 10 seconds.</summary>
    /// <exception cref="InvalidOperationException">It isn't started, or TM1 refuses.</exception>
    public Task<Tm1Database> CreateDatabaseAsync(string name, CancellationToken cancellationToken = default) =>
        CreateDatabaseAsync(DefaultInstance, name, cancellationToken);

    /// <summary>Creates a database in a named instance of the running engine, which must exist. If
    /// that fails, what was made of the database is dropped again.</summary>
    /// <exception cref="InvalidOperationException">It isn't started, or TM1 refuses.</exception>
    public async Task<Tm1Database> CreateDatabaseAsync(string instance, string name, CancellationToken cancellationToken = default)
    {
        var database = RequireInstance(instance) + "/" + RequireName(name, "database");
        await ChangeAsync(["database", "create", database], cancellationToken, CreateTimeout, undo: ["database", "drop", database]).ConfigureAwait(false);
        return new Tm1Database(this, instance, name);
    }

    /// <summary>Deletes a database of the running engine, with everything in it. Takes about 7 seconds.</summary>
    /// <exception cref="InvalidOperationException">It isn't started, or TM1 refuses.</exception>
    public async Task DropDatabaseAsync(Tm1Database database, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(database);
        await ChangeAsync(["database", "drop", database.Instance + "/" + database.Name], cancellationToken).ConfigureAwait(false);
    }

    /// <summary>A login to an instance, through its first application whose secret the image holds.</summary>
    internal Tm1Login Login(string instance, string user)
    {
        var (clientId, clientSecret) = Current().Application(instance)
            ?? throw new InvalidOperationException("The instance " + instance + " has no application to log in through");
        return new Tm1Login(Url + instance + "/auth/v1/", instance, clientId, clientSecret, user);
    }

    /// <summary>What's passed to the image as TM1_DATABASES: what was added, or the default.</summary>
    internal IReadOnlyList<string> Configured() => databases.Count == 0 ? [DefaultInstance + "/" + DefaultDatabase] : databases;

    /// <summary>Before anything starts: the datastore, which this depends on, starts ahead of <see cref="Configure"/>.</summary>
    private protected override void BeforeDependenciesStart() => JoinNetwork();

    /// <summary>The datastore goes on the engine's network at once, not when the engine starts: whatever starts the
    /// engine may start what it depends on first, and the datastore must be there by then.</summary>
    private protected override void NetworkSet(Network? network)
    {
        // Null while the base class is still being made
        Datastore?.WithNetwork(network);
        if (!ReferenceEquals(network, ownNetwork))
        {
            // A network it was given is the giver's to remove
            ownNetwork = null;
        }
    }

    /// <inheritdoc/>
    protected internal override void Configure()
    {
        JoinNetwork();
        WithEnv("TM1_DATABASES", string.Join(',', Configured()));
        if (users.Count > 0)
        {
            WithEnv("TM1_USERS", string.Join(',', users.Select(u => u.Groups.Length == 0 ? u.Name : u.Name + ":" + string.Join(':', u.Groups))));
        }
    }

    /// <inheritdoc/>
    protected override async Task ContainerIsStartedAsync(CancellationToken cancellationToken)
    {
        await AwaitSetupAsync(cancellationToken).ConfigureAwait(false);
        var found = Databases(await ChangeAsync(null, cancellationToken).ConfigureAwait(false));
        version = await ReadVersionAsync(found, cancellationToken).ConfigureAwait(false);
        Logger.LogInformation("TM1 {Version} is up at {Url} ({Source}), with {Databases}", version, Url, Source.Describe(), string.Join(", ", found));
    }

    /// <summary>The datastore isn't a dependent: what this depends on is started with it, and stopping is left to it.</summary>
    private protected override async Task StoppedAsync(CancellationToken cancellationToken)
    {
        await Datastore.StopAsync(cancellationToken).ConfigureAwait(false);
        credentials = null;
        version = null;
    }

    /// <summary>A start that failed leaves nothing behind: not the engine, were it up and never set up, nor its
    /// datastore, nor the network this made for them. As Java's <c>start()</c>, which stops what it started.</summary>
    private protected override async Task StartFailedAsync()
    {
#pragma warning disable CA1031 // whatever goes wrong clearing up, it's the start's failure that's thrown
        try
        {
            await StopAsync(CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Testcontainers removes what's left when the run ends
        }
        if (ownNetwork is { } made)
        {
            // Another for the next start, as a new engine has
            ownNetwork = Network.NewNetwork();
            WithNetwork(ownNetwork);
            try
            {
                await made.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception)
            {
                // As above
            }
        }
#pragma warning restore CA1031
    }

    /// <summary>The image for <paramref name="source"/>, as Java's <c>defaultImage</c>.</summary>
    internal static string DefaultImageFor(Tm1EngineSource source) =>
        source is Tm1EngineSource.PrivateImage p ? p.Image : Tm1ServerContainer.ImageFor(EngineImage, DefaultImage);

    /// <summary>Puts the engine and its datastore on one network: the one given, or a new one.</summary>
    private void JoinNetwork()
    {
        // Only if it was taken off its network (WithNetwork(null)): it has had one since it was made
        if (Network is null)
        {
            ownNetwork = Network.NewNetwork();
            WithNetwork(ownNetwork);
        }
        Datastore.WithNetwork(Network);
    }

    /// <summary>Waits until the image has created every instance, application, database and user, and
    /// every database is ready: what its health check reports.</summary>
    private async Task AwaitSetupAsync(CancellationToken cancellationToken)
    {
        var waited = Stopwatch.StartNew();
        while (true)
        {
            var state = (await InspectAsync(cancellationToken).ConfigureAwait(false)).State;
            if (state is not { Running: true })
            {
                // As the wait before it: the exit code and the end of the log, in one message
                throw new InvalidOperationException(await ExitedAsync(Container, cancellationToken).ConfigureAwait(false));
            }
            try
            {
                if ((await Container.ExecAsync(["tm1-engine", "health"], cancellationToken).ConfigureAwait(false)).ExitCode == 0)
                {
                    return;
                }
            }
            catch (DockerApiException e)
            {
                // The container stopped between the two calls: the next look at it says so
                Logger.LogDebug(e, "Couldn't ask the container whether TM1 is set up");
            }
            if (waited.Elapsed > StartupTimeout)
            {
                throw new InvalidOperationException(
                    $"Timed out after {XmlConvert.ToString(StartupTimeout)} waiting for TM1 to be set up. "
                        + await GetLogsAsync(cancellationToken).ConfigureAwait(false)
                );
            }
            await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Runs the image's tm1-engine command, and returns what it printed.</summary>
    private async Task<string> CommandAsync(string[] arguments, TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (!IsRunning)
        {
            throw new InvalidOperationException("The container isn't started yet");
        }
        var seconds = ((int)timeout.TotalSeconds).ToString(System.Globalization.CultureInfo.InvariantCulture);
        var result = await Container.ExecAsync(["timeout", seconds, "tm1-engine", .. arguments], cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0)
        {
            throw new InvalidOperationException(
                $"tm1-engine {string.Join(' ', arguments)} failed (exit {result.ExitCode}): {(result.Stderr + result.Stdout).Trim()}"
            );
        }
        return result.Stdout;
    }

    /// <summary>Runs a command that changes the databases, if one is given, then reads the engine's
    /// addresses and credentials again, as the image reports them, and keeps them. One at a time, so
    /// what's kept is never older than a change that has finished.</summary>
    /// <param name="command">The command, or null only to read.</param>
    /// <param name="cancellationToken">Stops waiting.</param>
    /// <param name="timeout">How long the command may take; <see cref="CommandTimeout"/> if null.</param>
    /// <param name="undo">A command to run if <paramref name="command"/> fails, before its failure is thrown on.</param>
    private async Task<Credentials> ChangeAsync(string[]? command, CancellationToken cancellationToken, TimeSpan? timeout = null, string[]? undo = null)
    {
        await commands.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (command is not null)
            {
                try
                {
                    await CommandAsync(command, timeout ?? CommandTimeout, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception) when (undo is not null)
                {
#pragma warning disable CA1031 // whatever goes wrong undoing it, it's the command's failure that's thrown
                    try
                    {
                        // Not the caller's token: a create that was cancelled is undone too
                        await CommandAsync(undo, CommandTimeout, CancellationToken.None).ConfigureAwait(false);
                    }
                    catch (Exception)
                    {
                        // Nothing was made, or it can't be dropped: either way the first failure says why
                    }
#pragma warning restore CA1031
                    throw;
                }
            }
            var read = Credentials.Parse(await CommandAsync(["credentials", "--json"], CommandTimeout, cancellationToken).ConfigureAwait(false));
            credentials = read;
            return read;
        }
        finally
        {
            commands.Release();
        }
    }

    /// <summary>The credentials last read: at the start, and whenever the databases were asked for or changed.</summary>
    private Credentials Current() => credentials ?? throw new InvalidOperationException("The container isn't started yet");

    private List<Tm1Database> Databases(Credentials from) =>
        [.. from.Instances.SelectMany(instance => instance.Databases.Select(name => new Tm1Database(this, instance.Name, name)))];

    /// <summary>Reads the version from the first database, logged in as its administrator. Plain HTTP
    /// rather than the client, so a client bug can't break the harness that tests the client.</summary>
    private static async Task<Tm1Version> ReadVersionAsync(List<Tm1Database> found, CancellationToken cancellationToken)
    {
        var first = found.FirstOrDefault() ?? throw new InvalidOperationException("The engine has no database to read its version from");
        using var http = new HttpClient(
            new SocketsHttpHandler
            {
                UseCookies = false,
                AllowAutoRedirect = false,
                ConnectTimeout = RequestTimeout,
            }
        )
        {
            Timeout = RequestTimeout,
        };
        using var request = new HttpRequestMessage(HttpMethod.Get, first.RestUrl + "Configuration/ProductVersion/$value");
        await first.Login().AuthorizeAsync(request, cancellationToken).ConfigureAwait(false);
        try
        {
            using var response = await http.SendAsync(request, cancellationToken).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK)
            {
                throw new InvalidOperationException($"TM1 didn't give its version (HTTP {(int)response.StatusCode}): {body}");
            }
            return Tm1Version.Parse(body);
        }
        catch (HttpRequestException e)
        {
            throw new IOException("Couldn't read the TM1 version from " + first.RestUrl, e);
        }
    }

    private void MountSource()
    {
        switch (Source)
        {
            case Tm1EngineSource.InstallDir dir:
                Binds.Add($"{dir.Dir}:{InstallPath}:ro");
                break;
            case Tm1EngineSource.InstallVolume volume:
                Binds.Add($"{volume.Name}:{InstallPath}");
                break;
            case Tm1EngineSource.Bundle bundle:
                Binds.Add($"{bundle.Dir}:/ibm:ro");
                if (bundle.File is not null)
                {
                    WithEnv("TM1_ENGINE_BUNDLE", bundle.File);
                }
                Binds.Add($"{CacheVolume}:/tm1/cache");
                break;
            case Tm1EngineSource.BundleUrl url:
                WithEnv("TM1_ENGINE_BUNDLE_URL", url.Url.OriginalString);
                if (url.Sha256 is not null)
                {
                    WithEnv("TM1_ENGINE_BUNDLE_SHA256", url.Sha256);
                }
                Binds.Add($"{CacheVolume}:/tm1/cache");
                break;
            // Tm1EngineSource.PrivateImage: TM1 is already in the image
        }
    }

    private static string RequireInstance(string name)
    {
        if (name is null || !InstanceName().IsMatch(name))
        {
            throw new ArgumentException($"A TM1 instance's name is letters, digits, '_' and '-', starting with a letter or digit: {name}", nameof(name));
        }
        return name;
    }

    private static string RequireName(string name, string what)
    {
        if (name is null || !DatabaseName().IsMatch(name))
        {
            throw new ArgumentException(
                $"A TM1 {what}'s name is at least three letters, digits, '_' and '-', starting with a letter or digit: {name}",
                nameof(name)
            );
        }
        return name;
    }

    // The image's own rule: at least three characters, and nothing the commands or a URL would need quoting for
    [GeneratedRegex(@"\A[A-Za-z0-9][A-Za-z0-9_-]{2,}\z")]
    private static partial Regex DatabaseName();

    // An instance's name has no least length: only a database's does
    [GeneratedRegex(@"\A[A-Za-z0-9][A-Za-z0-9_-]*\z")]
    private static partial Regex InstanceName();

    [GeneratedRegex(@"\A[A-Za-z0-9._-]+\z")]
    private static partial Regex UserName();

    /// <summary>The MongoDB a <see cref="Tm1EngineContainer"/> keeps its metadata in: Java's is a plain
    /// <c>GenericContainer</c>, which here is a class of its own.</summary>
    public sealed class DatastoreContainer : GenericContainer<DatastoreContainer>
    {
        internal DatastoreContainer(string image)
            : base(image) { }

        /// <summary>MongoDB's image is built for every platform, so it runs as the machine's own.</summary>
        private protected override string? Platform => null;
    }

    /// <summary>What <c>tm1-engine credentials --json</c> prints.</summary>
    internal sealed record Credentials(string RootClientId, string RootClientSecret, IReadOnlyList<Credentials.Instance> Instances)
    {
        public static Credentials Parse(string json)
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            var rootCredentials = Property(root, "root");
            return new Credentials(
                Text(rootCredentials, "clientId"),
                Text(rootCredentials, "clientSecret"),
                [
                    .. Items(root, "instances")
                        .Select(instance => new Instance(
                            Text(instance, "name"),
                            [.. Items(instance, "applications").Select(application => (Text(application, "clientId"), Text(application, "clientSecret")))],
                            [.. Items(instance, "databases").Select(database => Text(database, "name"))]
                        )),
                ]
            );
        }

        /// <summary>The application to log in to <paramref name="instance"/> through: its first whose secret
        /// the image holds (it lists one whose secret it doesn't with <c>"clientSecret": null</c>), or null.</summary>
        public (string ClientId, string ClientSecret)? Application(string instance)
        {
            foreach (var found in Instances.Where(i => i.Name == instance))
            {
                foreach (var application in found.Applications.Where(a => a.ClientSecret.Length > 0))
                {
                    return application;
                }
            }
            return null;
        }

        private static JsonElement? Property(JsonElement? parent, string name) =>
            parent is { ValueKind: JsonValueKind.Object } p && p.TryGetProperty(name, out var value) ? value : null;

        // As Jackson's path(...).asString(): empty when it's missing
        private static string Text(JsonElement? parent, string name) =>
            Property(parent, name) is { ValueKind: JsonValueKind.String } value ? value.GetString() ?? "" : "";

        private static IEnumerable<JsonElement?> Items(JsonElement? parent, string name) =>
            Property(parent, name) is { ValueKind: JsonValueKind.Array } value ? [.. value.EnumerateArray().Select(item => (JsonElement?)item.Clone())] : [];

        public sealed record Instance(string Name, IReadOnlyList<(string ClientId, string ClientSecret)> Applications, IReadOnlyList<string> Databases);
    }
}
