// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Docker.DotNet;
using Docker.DotNet.Models;
using DotNet.Testcontainers.Networks;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A whole Planning Analytics setup on one network, wired as the Java
/// <c>PlanningAnalyticsEnvironment</c>: TM1 servers by name, a shared Admin Server when there's more
/// than one (or when asked for), TM1 Web and PAW listing them, and Cognos (with Keycloak for OIDC)
/// putting every server in CAM security. Each part's customizer runs once it's wired, so it can
/// change anything.</summary>
/// <example><code>
/// await using var env = PlanningAnalyticsEnvironment.Builder()
///     .Tm1("planning", tm1 => tm1.WithSeed(Tm1Seed.Sdata))
///     .Tm1("reporting")
///     .Tm1Web()
///     .Build();
/// await env.StartAsync();
/// </code></example>
/// <remarks>
/// <para>Cognos takes minutes to start, and PAW longer. With <see cref="EnvironmentBuilder.Reuse"/>, the environment keeps its
/// containers running between test runs, so the next run starts in seconds:</para>
/// <code>
/// // Cognos and the server keep running after the run, for the next one, when testcontainers.reuse.enable=true is set too
/// await using var env = PlanningAnalyticsEnvironment
///     .Builder()
///     .Tm1("planning")
///     .Cognos(cognos => cognos.WithUser("admin", "adminpw", "Admins"))
///     .Reuse(true)
///     .Build();
/// await env.StartAsync(); // in seconds, once a run with this configuration has started it
/// var url = env.Tm1("planning").RestUrl;
/// // disposing left it running
/// </code>
/// <para>Reuse also needs your own switch, as for any reused container: <c>testcontainers.reuse.enable=true</c> in
/// <c>~/.testcontainers.properties</c>, or <c>TESTCONTAINERS_REUSE_ENABLE=true</c>. Without it, <c>Reuse</c> has no effect. With
/// both:</para>
/// <list type="bullet">
/// <item>The containers and the network are named after the environment's configuration (its identity, a hash): the label
/// <c>io.skipcheck.tm1.environment</c> on each container, and the network <c>tm1-environment-&lt;identity&gt;</c>. A run with the
/// same configuration finds them; a different configuration gets its own, and never another's.</item>
/// <item><see cref="StopAsync"/> and disposing leave them running, PAW too. Testcontainers doesn't remove reusable containers
/// at the end of a run either.</item>
/// <item>What tests leave behind stays: objects they created in TM1, sessions, users in Cognos. Tests should create uniquely
/// named objects and delete them, as with <see cref="Tm1ServerContainer.SharedAsync"/>.</item>
/// <item>A configuration you no longer use keeps its containers running, and Cognos holds about 8 GB. To start clean, remove
/// them all: <c>docker rm -f $(docker ps -aq --filter label=io.skipcheck.tm1.environment)</c>, then
/// <c>docker network rm $(docker network ls -q --filter label=io.skipcheck.tm1.environment)</c>.</item>
/// <item>The identity covers each part's image, source, environment variables, command, network aliases, labels, exposed
/// ports, mounts and copied files, the Cognos users and the CAM settings. What a container subclass sets that the classes here
/// don't show (its host name, say) isn't seen: remove the containers after changing one.</item>
/// <item>PAW isn't a Testcontainers container: IBM's compose files give it fixed names, so there's one per Docker host
/// whatever the identity. Each start runs IBM's compose again, which takes about a minute.</item>
/// </list>
/// </remarks>
public sealed partial class PlanningAnalyticsEnvironment : IStartable
{
    /// <summary>The label on a reused environment's containers and network. Its value is the environment's identity, a hash
    /// of its configuration.</summary>
    internal const string ReuseLabel = "io.skipcheck.tm1.environment";

    /// <summary>The start of a reused environment's network name; the identity follows.</summary>
    internal const string NetworkPrefix = "tm1-environment-";

    private readonly bool reused;
    private readonly Lock identifying = new();
    private string? identity;
    private readonly bool ownsNetwork;
    private readonly Tm1WebContainer? tm1Web;
    private readonly PawContainer? paw;
    private readonly CognosContainer? cognos;
    private readonly KeycloakContainer? keycloak;

    private PlanningAnalyticsEnvironment(EnvironmentBuilder b, bool reuseEnabled)
    {
        reused = b.ReuseSet && reuseEnabled;
        var network = b.NetworkSet
            ?? (reused ? Testcontainers.Network.Reusable(new ReusableNetwork(() => NetworkPrefix + Identity())) : Testcontainers.Network.NewNetwork());
        Network = network;
        ownsNetwork = b.NetworkSet is null;
        keycloak = b.KeycloakCustomizer is null
            ? null
            : (b.KeycloakImage is null ? new KeycloakContainer() : new KeycloakContainer(b.KeycloakImage)).WithNetwork(network);
        if (b.CognosCustomizer is not null)
        {
            var source = b.Get(
                b.CognosSourceSet,
                ibm => new CognosSource.Zip(ibm),
                CognosSource.Discover,
                image => new CognosSource.PrivateImage(image),
                b.CognosImage,
                Xunit.RequiresCognosFactAttribute.NoSourceMessage
            );
            cognos = (
                Runs(b.CognosImage, source is CognosSource.PrivateImage) ? new CognosContainer(b.CognosImage, source) : new CognosContainer(source)
            ).WithNetwork(network);
            if (keycloak is not null)
            {
                cognos.WithKeycloak(keycloak);
            }
            b.CognosCustomizer(cognos);
        }
        var tm1Source = b.Get(
            b.Tm1SourceSet,
            ibm => new Tm1Source.Bundle(ibm),
            Tm1Source.Discover,
            image => new Tm1Source.PrivateImage(image),
            b.Tm1Image,
            Xunit.RequiresTm1FactAttribute.NoSourceMessage
        );
        var tm1Image = Runs(b.Tm1Image, tm1Source is Tm1Source.PrivateImage) ? b.Tm1Image : null;
        AdminServer =
            b.AdminServerCustomizer is not null || b.Tm1Customizers.Count > 1
                ? (tm1Image is not null ? new Tm1AdminServerContainer(tm1Image, tm1Source) : new Tm1AdminServerContainer(tm1Source)).WithNetwork(network)
                : null;
        var servers = new Dictionary<string, Tm1ServerContainer>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var name in b.Tm1Names)
        {
            var server = (tm1Image is not null ? new Tm1ServerContainer(tm1Image, tm1Source) : new Tm1ServerContainer(tm1Source))
                .WithNetwork(network)
                .WithNetworkAliases(name)
                .WithEnv("TM1_SERVER_NAME", name);
            if (AdminServer is not null)
            {
                server.WithAdminServer(AdminServer);
            }
            if (cognos is not null)
            {
                server.WithCamSecurity(cognos, b.CamNamespaceSet, b.CamAdmins(cognos));
            }
            servers[name] = server;
            order.Add(name);
        }
        Tm1Servers = new OrderedServers(order, servers);
        var first = servers[order[0]];
        if (b.Tm1WebCustomizer is not null)
        {
            var source = b.Get(
                b.Tm1WebSourceSet,
                ibm => new Tm1WebSource.Bundle(ibm),
                Tm1WebSource.Discover,
                image => new Tm1WebSource.PrivateImage(image),
                b.Tm1WebImage,
                Xunit.RequiresTm1WebFactAttribute.NoSourceMessage
            );
            tm1Web = (
                Runs(b.Tm1WebImage, source is Tm1WebSource.PrivateImage) ? new Tm1WebContainer(b.Tm1WebImage, source) : new Tm1WebContainer(source)
            ).WithTm1Server(first);
            if (AdminServer is not null)
            {
                tm1Web.WithAdminServer(AdminServer);
            }
            foreach (var server in order.Select(n => servers[n]))
            {
                tm1Web.DependsOn(server);
            }
        }
        if (b.PawCustomizer is not null)
        {
            paw = new PawContainer(
                b.Get(b.PawSourceSet, b.PawFromIbm, PawSource.Discover, null, null, Xunit.RequiresPawFactAttribute.NoSourceMessage)
            ).WithTm1Server(first);
            if (AdminServer is not null)
            {
                paw.WithAdminServer(AdminServer);
            }
        }
        Apply(b.AdminServerCustomizer, AdminServer);
        foreach (var name in order)
        {
            b.Tm1Customizers[name](servers[name]);
        }
        Apply(b.Tm1WebCustomizer, tm1Web);
        Apply(b.PawCustomizer, paw);
        Apply(b.KeycloakCustomizer, keycloak);
    }

    /// <summary>A builder for an environment.</summary>
    public static EnvironmentBuilder Builder() => new();

    /// <summary>Starts building the environment a <c>pa-environment.yaml</c> describes, the file <c>pa-setup</c> reads
    /// (<c>docker/pa-setup</c>), so tests run what developers run. The file is checked as <c>pa-setup</c> checks it, with its
    /// defaults. The builder comes back configured; change it further, or call <see cref="EnvironmentBuilder.Build()"/>.</summary>
    /// <remarks>
    /// <para>How each setting maps:</para>
    /// <list type="bullet">
    /// <item><c>tm1.servers.&lt;name&gt;</c>: <see cref="EnvironmentBuilder.Tm1(string, Action{Tm1ServerContainer})"/>, with <c>seed</c> as
    /// <see cref="Tm1ServerContainer.WithSeed"/> and each <c>cfg</c> parameter as <c>TM1_CFG_&lt;name&gt;</c>, written as <c>pa-setup</c>
    /// writes it (<c>true</c> is <c>True</c>);</item>
    /// <item><c>tm1.adminServer</c>: <see cref="EnvironmentBuilder.AdminServer()"/>; with more than one server there's one anyway, and
    /// <c>false</c> then fails, as in <c>pa-setup</c>;</item>
    /// <item><c>tm1Web</c>: <see cref="EnvironmentBuilder.Tm1Web()"/>, with <c>heap</c> as <c>TM1_WEB_HEAP</c> (<c>1g</c>);</item>
    /// <item><c>cognos</c>: <see cref="EnvironmentBuilder.Cognos"/>, with <c>users</c> as <see cref="CognosContainer.WithUser"/> and
    /// <c>memory</c> as <c>COGNOS_MAX_MEMORY</c> (<c>6144</c>); <c>oidc</c> adds <see cref="EnvironmentBuilder.Keycloak()"/>; <c>cam</c> is
    /// <see cref="EnvironmentBuilder.CamSecurity"/>, by default LDAP with the first user as administrator;</item>
    /// <item><c>paw</c>: <see cref="EnvironmentBuilder.Paw"/> with <see cref="PawContainer.AcceptLicense"/>, which the file must say
    /// (<c>acceptLicense: true</c>); the zip is <c>zip</c> in <c>ibm</c>, or the newest there;</item>
    /// <item><c>ibm</c>: each part's source, the folder as <see cref="Testcontainers.Tm1Source.Bundle"/>,
    /// <see cref="Testcontainers.Tm1WebSource.Bundle"/> and <see cref="Testcontainers.CognosSource.Zip"/>. A relative path is from the
    /// file's folder; <c>~</c> is the user's home;</item>
    /// <item><c>images</c>: the image each part runs, as a container's image argument. A part with no other source takes its image as
    /// the private image with IBM's software built in (<see cref="Testcontainers.Tm1Source.PrivateImage"/> and the like).</item>
    /// </list>
    /// <para>A part's source is the one set on the builder, else <c>ibm</c>, else what <c>Discover()</c> finds (the variables
    /// pa-setup's <c>.env</c> sets, such as <c>TM1_BUNDLE_URL</c>), else its image as a private image.</para>
    /// <para>Ignored, because they place things on the host that Testcontainers manages itself: <c>name</c> (the compose project; the
    /// environment has its own network), every <c>port</c>, <c>adminPort</c> and <c>keycloakPort</c> (Testcontainers maps random host
    /// ports), and <c>files</c> (the host folder pa-setup keeps databases in; each test environment starts from a new database, or one
    /// copied in with <see cref="Tm1ServerContainer.WithData"/>). They're still checked as <c>pa-setup</c> checks them.</para>
    /// <para>Refused, because the environment can't run them yet: <c>cognos</c> without <c>tm1.servers</c>, and
    /// <c>seed: 24retail</c>.</para>
    /// </remarks>
    /// <param name="path">The file, usually <c>pa-environment.yaml</c>.</param>
    /// <returns>A builder configured from the file.</returns>
    /// <exception cref="ArgumentException">The file can't be read, isn't valid, or asks for what the environment can't run; the
    /// message names the setting.</exception>
    public static EnvironmentBuilder FromConfig(string path)
    {
        ArgumentNullException.ThrowIfNull(path);
        return PaEnvironmentFile.Read(path);
    }

    /// <summary>Starts everything, what's depended on first, then PAW.</summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (reused)
        {
            PrepareForReuse();
        }
        await Task.WhenAll(Containers().Where(c => c != paw).Select(c => c.StartAsync(cancellationToken))).ConfigureAwait(false);
        if (paw is not null)
        {
            await paw.StartAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Stops everything, last started first, and removes the network it made. A reused environment
    /// (<see cref="EnvironmentBuilder.Reuse"/>) is left running for the next run.</summary>
    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        if (reused)
        {
            return;
        }
        foreach (var part in Enumerable.Reverse(Containers()))
        {
            await part.StopAsync(cancellationToken).ConfigureAwait(false);
        }
        if (ownsNetwork)
        {
            await Network.DisposeAsync().ConfigureAwait(false);
        }
    }

    /// <summary>Stops it.</summary>
    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);

    /// <summary>Whether every part is running.</summary>
    public bool IsRunning => Containers().All(c => c.IsRunning);

    /// <summary>The TM1 server named <paramref name="name"/>.</summary>
    /// <exception cref="ArgumentException">There's none.</exception>
    public Tm1ServerContainer Tm1(string name) =>
        Tm1Servers.TryGetValue(name, out var server)
            ? server
            : throw new ArgumentException($"No TM1 server {name}; there's [{string.Join(", ", Tm1Servers.Keys)}]", nameof(name));

    /// <summary>The TM1 servers by name, in the order added.</summary>
    public IReadOnlyDictionary<string, Tm1ServerContainer> Tm1Servers { get; }

    /// <summary>The shared Admin Server, or null when the one TM1 server uses its own.</summary>
    public Tm1AdminServerContainer? AdminServer { get; }

    /// <summary>TM1 Web.</summary>
    /// <exception cref="InvalidOperationException">It wasn't added.</exception>
    public Tm1WebContainer Tm1Web() => Require(tm1Web, "TM1 Web", "Tm1Web()");

    /// <summary>PAW.</summary>
    /// <exception cref="InvalidOperationException">It wasn't added.</exception>
    public PawContainer Paw() => Require(paw, "PAW", "Paw(...)");

    /// <summary>Cognos.</summary>
    /// <exception cref="InvalidOperationException">It wasn't added.</exception>
    public CognosContainer Cognos() => Require(cognos, "Cognos", "Cognos(...)");

    /// <summary>Keycloak.</summary>
    /// <exception cref="InvalidOperationException">It wasn't added.</exception>
    public KeycloakContainer Keycloak() => Require(keycloak, "Keycloak", "Keycloak()");

    /// <summary>The network everything is on.</summary>
    public Network Network { get; }

    /// <summary>Whether the containers are kept for later runs: <see cref="EnvironmentBuilder.Reuse"/> and the developer's
    /// reuse switch.</summary>
    internal bool Reused => reused;

    /// <summary>The environment's identity: a hash of every part's configuration, the same for the same configuration in every
    /// run. Fixed the first time it's asked for, which <see cref="StartAsync"/> does before anything starts.</summary>
    internal string Identity()
    {
        lock (identifying)
        {
            if (identity is null)
            {
                var all = new StringBuilder();
                foreach (var part in Containers())
                {
                    all.Append(Describe(part)).Append('\n');
                }
                identity = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(all.ToString())))[..16];
            }
            return identity;
        }
    }

    /// <summary>Marks every container reusable, with the identity as a label. Testcontainers for .NET finds a container to
    /// reuse by a hash of what it creates it with; the label puts in that hash what it leaves out (the copied files, the
    /// mounts, each part's source). Unlike Testcontainers for Java, it adds no random network alias, so there's none to take
    /// away.</summary>
    internal void PrepareForReuse()
    {
        var id = Identity();
        foreach (var part in Containers())
        {
            if (part is IGenericContainer container)
            {
                container.MarkReused(ReuseLabel, id);
            }
        }
    }

    /// <summary>One part's configuration, as far as it can be read before it starts.</summary>
    private static string Describe(IStartable part)
    {
        if (part is PawContainer paw)
        {
            return "PawContainer source=" + paw.Source + " settings=" + Map(paw.PawSettings) + " tm1=" + ServerName(paw.Tm1Server);
        }
        var c = (IGenericContainer)part;
        var d = new StringBuilder(part.GetType().Name);
        d.Append(" image=").Append(c.DockerImageName);
        d.Append(" aliases=").Append(List(c.NetworkAliases.Order(StringComparer.Ordinal)));
        d.Append(" command=").Append(List(c.CommandParts));
        d.Append(" ports=").Append(List(c.ExposedPorts.Order().Select(p => p.ToString(CultureInfo.InvariantCulture))));
        d.Append(" env=").Append(Map(c.EnvMap));
        d.Append(" labels=").Append(Map(c.Labels.Where(l => l.Key != ReuseLabel)));
        d.Append(" files=").Append(List(c.Copies.Select(f => f.Target + "=" + f.Source).Order(StringComparer.Ordinal)));
        d.Append(" mounts=").Append(List(c.Binds.Order(StringComparer.Ordinal)));
        switch (part)
        {
            case Tm1ServerContainer tm1:
                d.Append(" source=").Append(tm1.Source).Append(" cam=").Append(tm1.CamNamespace?.Id()).Append(List(tm1.CamAdmins));
                break;
            case Tm1AdminServerContainer admin:
                d.Append(" source=").Append(admin.Source);
                break;
            case Tm1WebContainer web:
                d.Append(" source=").Append(web.Source).Append(" tm1=").Append(ServerName(web.Tm1Server));
                break;
            case CognosContainer cognos:
                d.Append(" source=").Append(cognos.Source).Append(" users=").Append(cognos.UsersSpec());
                break;
        }
        return d.ToString();
    }

    /// <summary>A TM1 server's name in this environment, its <c>TM1_SERVER_NAME</c>.</summary>
    private static string? ServerName(Tm1ServerContainer? server) => server?.EnvMap.GetValueOrDefault("TM1_SERVER_NAME");

    private static string List(IEnumerable<string> items) => "[" + string.Join(", ", items) + "]";

    private static string Map(IEnumerable<KeyValuePair<string, string>> map) =>
        "{" + string.Join(", ", map.OrderBy(e => e.Key, StringComparer.Ordinal).Select(e => e.Key + "=" + e.Value)) + "}";

    /// <summary>Every part, in start order: Keycloak, Cognos, the Admin Server, the TM1 servers, TM1 Web, PAW.</summary>
    internal List<IStartable> Containers()
    {
        var all = new List<IStartable>();
        if (keycloak is not null)
        {
            all.Add(keycloak);
        }
        if (cognos is not null)
        {
            all.Add(cognos);
        }
        if (AdminServer is not null)
        {
            all.Add(AdminServer);
        }
        all.AddRange(Tm1Servers.Values);
        if (tm1Web is not null)
        {
            all.Add(tm1Web);
        }
        if (paw is not null)
        {
            all.Add(paw);
        }
        return all;
    }

    private static void Apply<T>(Action<T>? customizer, T? part)
        where T : class
    {
        if (customizer is not null && part is not null)
        {
            customizer(part);
        }
    }

    private static T Require<T>(T? part, string what, string how)
        where T : class => part ?? throw new InvalidOperationException($"This environment has no {what}; add it with {how}");

    /// <summary>Whether a part runs pa-setup's image setting: not when its source is a private image, which is the image.</summary>
    private static bool Runs([NotNullWhen(true)] string? image, bool privateImage) => image is not null && !privateImage;

    /// <summary>The TM1 servers by name, enumerated in the order added.</summary>
    private sealed class OrderedServers(List<string> order, Dictionary<string, Tm1ServerContainer> servers) : IReadOnlyDictionary<string, Tm1ServerContainer>
    {
        public Tm1ServerContainer this[string key] => servers[key];

        public IEnumerable<string> Keys => order;

        public IEnumerable<Tm1ServerContainer> Values => order.Select(n => servers[n]);

        public int Count => order.Count;

        public bool ContainsKey(string key) => servers.ContainsKey(key);

        public bool TryGetValue(string key, [System.Diagnostics.CodeAnalysis.MaybeNullWhen(false)] out Tm1ServerContainer value) =>
            servers.TryGetValue(key, out value);

        public IEnumerator<KeyValuePair<string, Tm1ServerContainer>> GetEnumerator() => order.Select(n => KeyValuePair.Create(n, servers[n])).GetEnumerator();

        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
    }

    /// <summary>Builds a <see cref="PlanningAnalyticsEnvironment"/>, as Java's <c>PlanningAnalyticsEnvironment.Builder</c>.</summary>
    public sealed partial class EnvironmentBuilder
    {
        internal EnvironmentBuilder() { }

        internal List<string> Tm1Names { get; } = [];

        internal Dictionary<string, Action<Tm1ServerContainer>> Tm1Customizers { get; } = new(StringComparer.Ordinal);

        internal Action<Tm1AdminServerContainer>? AdminServerCustomizer { get; private set; }

        internal Action<Tm1WebContainer>? Tm1WebCustomizer { get; private set; }

        internal Action<PawContainer>? PawCustomizer { get; private set; }

        internal Action<CognosContainer>? CognosCustomizer { get; private set; }

        internal Action<KeycloakContainer>? KeycloakCustomizer { get; private set; }

        internal CognosContainer.Namespace CamNamespaceSet { get; private set; } = CognosContainer.Namespace.Ldap;

        internal IReadOnlyList<string> CamAdminsSet { get; private set; } = [];

        internal Tm1Source? Tm1SourceSet { get; private set; }

        internal Tm1WebSource? Tm1WebSourceSet { get; private set; }

        internal PawSource? PawSourceSet { get; private set; }

        internal CognosSource? CognosSourceSet { get; private set; }

        internal Testcontainers.Network? NetworkSet { get; private set; }

        internal bool ReuseSet { get; private set; }

        // From pa-environment.yaml (FromConfig): the IBM downloads folder, PAW's zip in it, and each part's image
        internal string? Ibm { get; set; }

        internal string? PawZip { get; set; }

        internal string? Tm1Image { get; set; }

        internal string? Tm1WebImage { get; set; }

        internal string? CognosImage { get; set; }

        internal string? KeycloakImage { get; set; }

        /// <summary>Adds a TM1 server, named <paramref name="name"/> (its server name and network alias).</summary>
        public EnvironmentBuilder Tm1(string name) => Tm1(name, _ => { });

        /// <summary>Adds a TM1 server, and changes it with <paramref name="customizer"/> once wired.</summary>
        /// <exception cref="ArgumentException">A bad name, or one added twice.</exception>
        public EnvironmentBuilder Tm1(string name, Action<Tm1ServerContainer> customizer)
        {
            ArgumentNullException.ThrowIfNull(customizer);
            if (name is null || !ServerName().IsMatch(name))
            {
                throw new ArgumentException($"Bad TM1 server name '{name}': use letters, digits, _ and -", nameof(name));
            }
            if (!Tm1Customizers.TryAdd(name, customizer))
            {
                throw new ArgumentException($"TM1 server {name} was already added", nameof(name));
            }
            Tm1Names.Add(name);
            return this;
        }

        /// <summary>Adds a shared Admin Server even for one TM1 server.</summary>
        public EnvironmentBuilder AdminServer() => AdminServer(_ => { });

        /// <summary>Adds a shared Admin Server, changed with <paramref name="customizer"/>.</summary>
        public EnvironmentBuilder AdminServer(Action<Tm1AdminServerContainer> customizer)
        {
            AdminServerCustomizer = customizer ?? throw new ArgumentNullException(nameof(customizer));
            return this;
        }

        /// <summary>Adds TM1 Web.</summary>
        public EnvironmentBuilder Tm1Web() => Tm1Web(_ => { });

        /// <summary>Adds TM1 Web, changed with <paramref name="customizer"/>.</summary>
        public EnvironmentBuilder Tm1Web(Action<Tm1WebContainer> customizer)
        {
            Tm1WebCustomizer = customizer ?? throw new ArgumentNullException(nameof(customizer));
            return this;
        }

        /// <summary>Adds PAW, changed with <paramref name="customizer"/> (to accept the license, say). With <see cref="Cognos"/>, users log
        /// on to PAW through Cognos, in the CAM namespace.</summary>
        public EnvironmentBuilder Paw(Action<PawContainer> customizer)
        {
            PawCustomizer = customizer ?? throw new ArgumentNullException(nameof(customizer));
            return this;
        }

        /// <summary>Adds Cognos, putting every TM1 server in CAM security; add its test users with <paramref name="customizer"/>.</summary>
        public EnvironmentBuilder Cognos(Action<CognosContainer> customizer)
        {
            CognosCustomizer = customizer ?? throw new ArgumentNullException(nameof(customizer));
            return this;
        }

        /// <summary>Adds Keycloak, giving Cognos an OIDC namespace.</summary>
        public EnvironmentBuilder Keycloak() => Keycloak(_ => { });

        /// <summary>Adds Keycloak, changed with <paramref name="customizer"/>.</summary>
        public EnvironmentBuilder Keycloak(Action<KeycloakContainer> customizer)
        {
            KeycloakCustomizer = customizer ?? throw new ArgumentNullException(nameof(customizer));
            return this;
        }

        /// <summary>The CAM namespace and the TM1 administrators (by default, the first test user).</summary>
        public EnvironmentBuilder CamSecurity(CognosContainer.Namespace @namespace, params string[] admins)
        {
            CamNamespaceSet = @namespace;
            CamAdminsSet = [.. admins];
            return this;
        }

        /// <summary>Where TM1 comes from, rather than <see cref="Testcontainers.Tm1Source.Discover()"/>.</summary>
        public EnvironmentBuilder Tm1Source(Tm1Source source)
        {
            Tm1SourceSet = source ?? throw new ArgumentNullException(nameof(source));
            return this;
        }

        /// <summary>Where TM1 Web comes from.</summary>
        public EnvironmentBuilder Tm1WebSource(Tm1WebSource source)
        {
            Tm1WebSourceSet = source ?? throw new ArgumentNullException(nameof(source));
            return this;
        }

        /// <summary>Where PAW comes from.</summary>
        public EnvironmentBuilder PawSource(PawSource source)
        {
            PawSourceSet = source ?? throw new ArgumentNullException(nameof(source));
            return this;
        }

        /// <summary>Where Cognos comes from.</summary>
        public EnvironmentBuilder CognosSource(CognosSource source)
        {
            CognosSourceSet = source ?? throw new ArgumentNullException(nameof(source));
            return this;
        }

        /// <summary>The network to use, rather than a new one (which the environment removes when it stops).</summary>
        public EnvironmentBuilder Network(Testcontainers.Network network)
        {
            NetworkSet = network ?? throw new ArgumentNullException(nameof(network));
            return this;
        }

        /// <summary>Keeps the containers running between test runs, when you've also turned on reuse
        /// (<c>testcontainers.reuse.enable=true</c> in <c>~/.testcontainers.properties</c>, or <c>TESTCONTAINERS_REUSE_ENABLE=true</c>).
        /// A later run with the same configuration finds them, and objects tests left behind are still there. Without the switch
        /// it has no effect. See <see cref="PlanningAnalyticsEnvironment"/> for what's reused and how to start clean.</summary>
        /// <remarks>With your own <see cref="Network"/>, reuse only works if it's the same network each run: Testcontainers for
        /// .NET finds a container by a hash that leaves its network out, so a container found would still be on the network it
        /// was created on.</remarks>
        /// <param name="reuse">Whether to keep the containers.</param>
        /// <returns>This builder.</returns>
        public EnvironmentBuilder Reuse(bool reuse)
        {
            ReuseSet = reuse;
            return this;
        }

        /// <summary>The environment, wired.</summary>
        /// <exception cref="InvalidOperationException">Parts are missing, or don't go together.</exception>
        public PlanningAnalyticsEnvironment Build() => Build(DockerHost.ReuseEnabled());

        /// <summary>Builds, with the developer's reuse switch given, so tests needn't set it.</summary>
        internal PlanningAnalyticsEnvironment Build(bool reuseEnabled)
        {
            if (Tm1Names.Count == 0)
            {
                throw new InvalidOperationException("Add at least one TM1 server with Tm1(name)");
            }
            if (KeycloakCustomizer is not null && CognosCustomizer is null)
            {
                throw new InvalidOperationException("Keycloak gives Cognos its OIDC namespace: add Cognos(...) too");
            }
            if (CamNamespaceSet == CognosContainer.Namespace.Oidc && KeycloakCustomizer is null)
            {
                throw new InvalidOperationException("CAM security through OIDC needs Keycloak()");
            }
            return new PlanningAnalyticsEnvironment(this, reuseEnabled);
        }

        internal string[] CamAdmins(CognosContainer cognos)
        {
            if (CamAdminsSet.Count > 0)
            {
                return [.. CamAdminsSet];
            }
            var users = cognos.Users;
            return users.Count == 0 ? throw new InvalidOperationException("Cognos needs test users for CAM security: add them with WithUser") : [users[0].Name];
        }

        /// <summary>A part's source: the one set on the builder, else the file's <c>ibm</c> folder, else what
        /// <paramref name="discover"/> finds, else the file's image as a private image.</summary>
        internal T Get<T>(T? configured, Func<string, T> fromIbm, Func<T?> discover, Func<string, T>? privateImage, string? image, string message)
            where T : class
        {
            if (configured is not null)
            {
                return configured;
            }
            if (Ibm is not null)
            {
                if (!Directory.Exists(Ibm))
                {
                    throw new InvalidOperationException("ibm in pa-environment.yaml isn't a folder: " + Ibm);
                }
                return fromIbm(Ibm);
            }
            if (discover() is { } found)
            {
                return found;
            }
            if (privateImage is not null && image is not null)
            {
                return privateImage(image);
            }
            throw new InvalidOperationException(message);
        }

        internal PawSource PawFromIbm(string ibm)
        {
            if (PawZip is not null)
            {
                return new PawSource(Path.Combine(ibm, PawZip));
            }
            return Testcontainers.PawSource.Discover(name => name == Testcontainers.PawSource.BundleDir ? ibm : null)
                ?? throw new InvalidOperationException("No PAW Local zip in " + ibm);
        }

        [GeneratedRegex("^[A-Za-z0-9_-]+$")]
        private static partial Regex ServerName();
    }

    /// <summary>A reused environment's Docker network: named after its identity, found if it's there and created if not. It
    /// carries none of Testcontainers' session labels, so Testcontainers' resource reaper doesn't remove it after the run, and
    /// disposing keeps it for the next.</summary>
    internal sealed class ReusableNetwork(Func<string> name) : INetwork
    {
        /// <summary>The network's name: <c>tm1-environment-&lt;identity&gt;</c>.</summary>
        public string Name => name();

        /// <summary>Finds the network, or creates it.</summary>
        public async Task CreateAsync(CancellationToken ct = default)
        {
            var networkName = Name;
            using var docker = DockerHost.Client();
            if (await FindAsync(docker, networkName, ct).ConfigureAwait(false))
            {
                return;
            }
            try
            {
                var labels = new Dictionary<string, string>(StringComparer.Ordinal) { [ReuseLabel] = networkName[NetworkPrefix.Length..] };
                await docker.Networks.CreateNetworkAsync(new NetworksCreateParameters { Name = networkName, Labels = labels }, ct).ConfigureAwait(false);
            }
            catch (DockerApiException e) when (e.StatusCode == HttpStatusCode.Conflict)
            {
                // Another test run created it first
                if (!await FindAsync(docker, networkName, ct).ConfigureAwait(false))
                {
                    throw;
                }
            }
        }

        /// <summary>Keeps the network for the next run.</summary>
        public Task DeleteAsync(CancellationToken ct = default) => Task.CompletedTask;

        /// <summary>Keeps the network for the next run.</summary>
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;

        private static async Task<bool> FindAsync(IDockerClient docker, string networkName, CancellationToken ct)
        {
            // The name filter matches part of a name, so check the whole
            var filters = new Dictionary<string, IDictionary<string, bool>>(StringComparer.Ordinal)
            {
                ["name"] = new Dictionary<string, bool>(StringComparer.Ordinal) { [networkName] = true },
            };
            var networks = await docker.Networks.ListNetworksAsync(new NetworksListParameters { Filters = filters }, ct).ConfigureAwait(false);
            return networks.Any(n => n.Name == networkName);
        }
    }
}
