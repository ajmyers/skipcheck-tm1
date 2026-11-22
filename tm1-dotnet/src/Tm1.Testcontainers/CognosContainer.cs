using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>Cognos Analytics in Docker, for TM1 in CAM security, as the Java <c>CognosContainer</c>:
/// the <c>cognos-server</c> image (no IBM software in it) with Cognos from a
/// <see cref="CognosSource"/>, an LDAP namespace holding the test users added with
/// <see cref="WithUser"/>, reached as <c>cognos</c> on its network. With
/// <see cref="WithKeycloak"/> it has an OIDC namespace too.</summary>
public partial class CognosContainer : GenericContainer<CognosContainer>
{
    /// <summary>The image Cognos runs in, without its tag.</summary>
    public const string DefaultImage = "ghcr.io/ajmyers/cognos-server";

    /// <summary>The setting that replaces <see cref="DefaultImage"/>.</summary>
    public const string ServerImage = "COGNOS_SERVER_IMAGE";

    /// <summary>The dispatcher's port.</summary>
    public const int DispatcherPort = 9300;

    /// <summary>Its name on the network.</summary>
    public const string NetworkAlias = "cognos";

    /// <summary>The LDAP namespace's ID.</summary>
    public const string NamespaceId = "LDAP";

    private const string InstallPath = "/opt/ibm/cognos/analytics";
    private static readonly TimeSpan StartupTimeout = TimeSpan.FromMinutes(15);
    private readonly Dictionary<string, User> users = new(StringComparer.Ordinal);
    private readonly List<string> order = [];

    /// <summary>A Cognos namespace TM1 can log users on through.</summary>
    public enum Namespace
    {
        /// <summary>The image's LDAP namespace, holding the test users.</summary>
        Ldap,

        /// <summary>The OIDC namespace, backed by Keycloak.</summary>
        Oidc,
    }

    /// <summary>A test user in the LDAP namespace.</summary>
    /// <param name="Name">The user's name.</param>
    /// <param name="Password">The password.</param>
    /// <param name="Groups">The groups it's in.</param>
    public sealed record User(string Name, string Password, IReadOnlyList<string> Groups);

    /// <summary>Cognos from <paramref name="source"/>, in the image for it.</summary>
    public CognosContainer(CognosSource source)
        : this(DefaultImageFor(source ?? throw new ArgumentNullException(nameof(source))), source)
    {
    }

    /// <summary>Cognos from <paramref name="source"/>, in <paramref name="image"/>.</summary>
    public CognosContainer(string image, CognosSource source)
        : base(image)
    {
        ArgumentNullException.ThrowIfNull(source);
        Source = source;
        WithExposedPorts(DispatcherPort);
        WithNetworkAliases(NetworkAlias);
        WithEnv("COGNOS_HOST", NetworkAlias);
        WithHostname(NetworkAlias);
        WaitingForHttp("/bi/v1/login", DispatcherPort, code => (int)code is 200 or 441, StartupTimeout);
        switch (source)
        {
            case CognosSource.InstallVolume volume:
                Binds.Add($"{volume.Name}:{InstallPath}");
                break;
            case CognosSource.Zip zip:
                Binds.Add($"{zip.Dir}:/ibm:ro");
                if (zip.File is not null)
                {
                    WithEnv("COGNOS_ZIP", zip.File);
                }
                break;
            case CognosSource.ZipUrl url:
                WithEnv("COGNOS_ZIP_URL", url.Url.OriginalString);
                if (url.Sha256 is not null)
                {
                    WithEnv("COGNOS_ZIP_SHA256", url.Sha256);
                }
                break;
        }
    }

    /// <summary>Adds a test user to the LDAP namespace, in <paramref name="groups"/>.</summary>
    /// <exception cref="ArgumentException">A name the image can't take apart, or a user added twice.</exception>
    public CognosContainer WithUser(string name, string password, params string[] groups)
    {
        RequireName(name, "user name");
        ArgumentNullException.ThrowIfNull(password);
        if (password.Length == 0 || password.Contains(':', StringComparison.Ordinal) || password.Any(char.IsWhiteSpace))
        {
            throw new ArgumentException($"The password for {name} must be non-empty, without ':' or spaces", nameof(password));
        }
        foreach (var group in groups)
        {
            RequireName(group, "group name");
        }
        if (!users.TryAdd(name, new User(name, password, [.. groups])))
        {
            throw new ArgumentException($"User {name} was already added", nameof(name));
        }
        order.Add(name);
        return this;
    }

    /// <summary>Adds the OIDC namespace, backed by <paramref name="keycloak"/>, on its network.</summary>
    public CognosContainer WithKeycloak(KeycloakContainer keycloak)
    {
        ArgumentNullException.ThrowIfNull(keycloak);
        Keycloak = keycloak;
        Tm1ServerContainer.ShareNetwork(this, keycloak);
        DependsOn(keycloak);
        return WithEnv("COGNOS_KEYCLOAK_URL", keycloak.NetworkUrl);
    }

    /// <summary>The Keycloak behind the OIDC namespace, or null.</summary>
    public KeycloakContainer? Keycloak { get; private set; }

    /// <summary>The test users, in the order added.</summary>
    public IReadOnlyList<User> Users => [.. order.Select(n => users[n])];

    /// <summary>A test user.</summary>
    /// <exception cref="ArgumentException">There's no such user.</exception>
    public User GetUser(string name) =>
        users.TryGetValue(name, out var user) ? user : throw new ArgumentException($"No test user {name}; add it with WithUser", nameof(name));

    /// <summary>The test users' groups, in the order first named.</summary>
    public IReadOnlyCollection<string> Groups => [.. Users.SelectMany(u => u.Groups).Distinct()];

    /// <summary>Where Cognos comes from.</summary>
    public CognosSource Source { get; }

    /// <summary>Cognos's web address on the Docker host, <c>http://host:port/bi/</c>.</summary>
    public string Url => $"http://{Host}:{GetMappedPort(DispatcherPort)}/bi/";

    /// <summary>The dispatcher's address on the network, as TM1 reaches it.</summary>
    public string NetworkUrl => "http://" + NetworkAlias + ":" + DispatcherPort;

    /// <summary>The <c>Authorization</c> header that logs <paramref name="user"/> on to TM1 through
    /// the LDAP namespace.</summary>
    public string CamAuthorization(string user) => CamAuthorization(user, Namespace.Ldap);

    /// <summary>The <c>Authorization</c> header that logs <paramref name="user"/> on to TM1 through <paramref name="namespace"/>.</summary>
    public string CamAuthorization(string user, Namespace @namespace) => CamAuthorization(user, GetUser(user).Password, @namespace.Id());

    /// <summary><c>CAMNamespace</c> with <c>user:password:namespace</c>, as TM1 takes it.</summary>
    public static string CamAuthorization(string user, string password, string @namespace) =>
        "CAMNamespace " + Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + password + ":" + @namespace));

    /// <summary>A CAM passport for an OIDC ID token (from <see cref="KeycloakContainer.IdTokenAsync"/>).</summary>
    /// <exception cref="InvalidOperationException">Cognos refused the token.</exception>
    public async Task<string> PassportAsync(string idToken, CancellationToken cancellationToken = default)
    {
        var body = "{\"parameters\":[{\"name\":\"CAMNamespace\",\"value\":\"" + Namespace.Oidc.Id()
            + "\"},{\"name\":\"CAMOIDCIdToken\",\"value\":\"" + idToken + "\"}]}";
        using var http = new HttpClient(new SocketsHttpHandler { UseCookies = false }) { Timeout = TimeSpan.FromSeconds(60) };
        using var content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await http.PutAsync(new Uri($"http://{Host}:{GetMappedPort(DispatcherPort)}/api/v1/session"), content, cancellationToken).ConfigureAwait(false);
        var passport = response.Headers.TryGetValues("Set-Cookie", out var cookies)
            ? cookies.Select(c => c.Split(';', 2)[0].Trim()).FirstOrDefault(c => c.StartsWith("cam_passport=", StringComparison.Ordinal))
            : null;
        if (passport is null)
        {
            var text = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            throw new InvalidOperationException($"Cognos refused the ID token (HTTP {(int)response.StatusCode}): {text}");
        }
        return passport["cam_passport=".Length..];
    }

    /// <summary>The users as the image takes them: <c>name:password[:group,group]</c>, space-separated.</summary>
    internal string UsersSpec() =>
        string.Join(' ', Users.Select(u => u.Name + ":" + u.Password + (u.Groups.Count == 0 ? "" : ":" + string.Join(',', u.Groups))));

    /// <inheritdoc/>
    protected internal override void Configure()
    {
        if (users.Count > 0)
        {
            WithEnv("COGNOS_USERS", UsersSpec());
        }
    }

    /// <inheritdoc/>
    protected override Task ContainerIsStartedAsync(CancellationToken cancellationToken)
    {
        Logger.LogInformation("Cognos is up at {Url} ({Source}), with {Count} test user(s) in namespace {Namespace}", Url, Source.Describe(), users.Count, NamespaceId);
        return Task.CompletedTask;
    }

    private static void RequireName(string? name, string what)
    {
        if (name is null || !Name().IsMatch(name))
        {
            throw new ArgumentException($"Bad {what} '{name}': use letters, digits, '.', '_' and '-'");
        }
    }

    private static string DefaultImageFor(CognosSource source) =>
        source is CognosSource.PrivateImage p ? p.Image : Tm1ServerContainer.ImageFor(ServerImage, DefaultImage);

    [GeneratedRegex("^[A-Za-z0-9._-]+$")]
    private static partial Regex Name();
}

/// <summary>A Cognos namespace's ID and type.</summary>
public static class CognosNamespaceExtensions
{
    /// <summary>The namespace's ID, as TM1's <c>CAMNamespace</c> takes it.</summary>
    public static string Id(this CognosContainer.Namespace @namespace) => @namespace == CognosContainer.Namespace.Oidc ? "OIDC" : "LDAP";

    /// <summary>The namespace's type, as the image takes it.</summary>
    internal static string Type(this CognosContainer.Namespace @namespace) => @namespace == CognosContainer.Namespace.Oidc ? "oidc" : "ldap";
}
