using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace AjMyers.Tm1.Testcontainers;

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
public sealed partial class PlanningAnalyticsEnvironment : IStartable
{
    private readonly bool ownsNetwork;
    private readonly Tm1WebContainer? tm1Web;
    private readonly PawContainer? paw;
    private readonly CognosContainer? cognos;
    private readonly KeycloakContainer? keycloak;

    private PlanningAnalyticsEnvironment(EnvironmentBuilder b, Network network)
    {
        Network = network;
        ownsNetwork = b.NetworkSet is null;
        keycloak = b.KeycloakCustomizer is null ? null : new KeycloakContainer().WithNetwork(network);
        if (b.CognosCustomizer is not null)
        {
            cognos = new CognosContainer(Get(b.CognosSourceSet, CognosSource.Discover, Xunit.RequiresCognosFactAttribute.NoSourceMessage)).WithNetwork(network);
            if (keycloak is not null)
            {
                cognos.WithKeycloak(keycloak);
            }
            b.CognosCustomizer(cognos);
        }
        var tm1Source = Get(b.Tm1SourceSet, Tm1Source.Discover, Xunit.RequiresTm1FactAttribute.NoSourceMessage);
        AdminServer = b.AdminServerCustomizer is not null || b.Tm1Customizers.Count > 1 ? new Tm1AdminServerContainer(tm1Source).WithNetwork(network) : null;
        var servers = new Dictionary<string, Tm1ServerContainer>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var name in b.Tm1Names)
        {
            var server = new Tm1ServerContainer(tm1Source).WithNetwork(network).WithNetworkAliases(name).WithEnv("TM1_SERVER_NAME", name);
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
            tm1Web = new Tm1WebContainer(Get(b.Tm1WebSourceSet, Tm1WebSource.Discover, Xunit.RequiresTm1WebFactAttribute.NoSourceMessage)).WithTm1Server(first);
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
            paw = new PawContainer(Get(b.PawSourceSet, PawSource.Discover, Xunit.RequiresPawFactAttribute.NoSourceMessage)).WithTm1Server(first);
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

    /// <summary>Starts everything, what's depended on first, then PAW.</summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        await Task.WhenAll(Containers().Where(c => c != paw).Select(c => c.StartAsync(cancellationToken))).ConfigureAwait(false);
        if (paw is not null)
        {
            await paw.StartAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>Stops everything, last started first, and removes the network it made.</summary>
    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
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

    private static T Get<T>(T? configured, Func<T?> discover, string message)
        where T : class => configured ?? discover() ?? throw new InvalidOperationException(message);

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

        /// <summary>Adds PAW, changed with <paramref name="customizer"/> (to accept the license, say).</summary>
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

        /// <summary>The environment, wired.</summary>
        /// <exception cref="InvalidOperationException">Parts are missing, or don't go together.</exception>
        public PlanningAnalyticsEnvironment Build()
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
            if (PawCustomizer is not null && CognosCustomizer is not null)
            {
                throw new InvalidOperationException("PAW with Cognos (CAM) security isn't supported yet: PAW logs users on with TM1's own security");
            }
            return new PlanningAnalyticsEnvironment(this, NetworkSet ?? Testcontainers.Network.NewNetwork());
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

        [GeneratedRegex("^[A-Za-z0-9_-]+$")]
        private static partial Regex ServerName();
    }
}
