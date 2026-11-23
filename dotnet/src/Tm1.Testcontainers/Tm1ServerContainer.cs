// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A TM1 server in Docker, as the Java <c>Tm1ServerContainer</c>: the <c>tm1-server</c>
/// image (which holds no IBM software) with TM1 from a <see cref="Tm1Source"/>, its REST API on port
/// 8010, ready once <c>$metadata</c> answers. It starts with an empty database
/// (<see cref="Tm1Seed.Empty"/>: <c>admin</c>, no password) unless told otherwise.
/// <see cref="SharedAsync"/> gives the one server the tests of a run share.</summary>
/// <example><code>
/// await using var tm1 = new Tm1ServerContainer(Tm1Source.Discover()!).WithSeed(Tm1Seed.Sdata);
/// await tm1.StartAsync();
/// using var client = Tm1Client.Builder().BaseUrl(tm1.RestUrl).Authenticator(Tm1Authenticator.Basic(tm1.Username, tm1.Password)).Build();
/// </code></example>
public class Tm1ServerContainer : GenericContainer<Tm1ServerContainer>
{
    /// <summary>The image TM1 runs in, without its tag (the library's version, or <c>edge</c>).</summary>
    public const string DefaultImage = "ghcr.io/ajmyers/tm1-server";

    /// <summary>The setting that replaces <see cref="DefaultImage"/> (a mirror, a local build).</summary>
    public const string ServerImage = "TM1_SERVER_IMAGE";

    /// <summary>TM1's REST port.</summary>
    public const int RestPort = 8010;

    /// <summary>The volume bundles are unpacked into, shared by every run (and with the Java, Python
    /// and .NET tests), so a bundle is unpacked once.</summary>
    public const string CacheVolume = "tm1-testcontainers-cache";

    internal const string InstallPath = "/opt/ibm/cognos/tm1_64";
    private static readonly TimeSpan StartupTimeout = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(30);
    private static readonly SemaphoreSlim SharedLock = new(1, 1);
    private static Tm1ServerContainer? shared;
    private Tm1Version? expectedVersion;
    private Tm1Version? version;
    private IReadOnlyList<string> camAdmins = [];
    private CognosContainer.Namespace camNamespace = CognosContainer.Namespace.Ldap;

    /// <summary>TM1 from <paramref name="source"/>, in the image <see cref="DefaultImageFor"/> picks.</summary>
    public Tm1ServerContainer(Tm1Source source)
        : this(DefaultImageFor(source ?? throw new ArgumentNullException(nameof(source))), source) { }

    /// <summary>TM1 from <paramref name="source"/>, in <paramref name="image"/>.</summary>
    public Tm1ServerContainer(string image, Tm1Source source)
        : base(image)
    {
        ArgumentNullException.ThrowIfNull(source);
        Source = source;
        WithExposedPorts(RestPort);
        WithEnv("TM1_SEED", Tm1Seed.Empty.Value());
        WaitingForHttp("/api/v1/$metadata", RestPort, code => code == HttpStatusCode.OK, StartupTimeout);
        MountSource(source, Binds, (name, value) => WithEnv(name, value));
    }

    /// <summary>The one TM1 server the tests of a run share, started the first time from the
    /// discovered source (<see cref="Tm1Source.Discover()"/>), with reuse.</summary>
    /// <exception cref="InvalidOperationException">No source is configured.</exception>
    public static async Task<Tm1ServerContainer> SharedAsync(CancellationToken cancellationToken = default)
    {
        await SharedLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (shared is null)
            {
                var source = Tm1Source.Discover() ?? throw new InvalidOperationException(Xunit.RequiresTm1FactAttribute.NoSourceMessage);
                var container = new Tm1ServerContainer(source).WithReuse(true);
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

    /// <summary>Starts with <paramref name="seed"/>'s database, and logs in with its login.</summary>
    public Tm1ServerContainer WithSeed(Tm1Seed seed)
    {
        WithEnv("TM1_SEED", seed.Value());
        return WithCredentials(seed.Username(), seed.Password());
    }

    /// <summary>Starts with the database in <paramref name="dataDir"/>, copied into the container;
    /// set its login with <see cref="WithCredentials"/>.</summary>
    /// <exception cref="ArgumentException">It isn't a folder.</exception>
    public Tm1ServerContainer WithData(string dataDir)
    {
        if (!Directory.Exists(dataDir))
        {
            throw new ArgumentException("Not a folder: " + dataDir, nameof(dataDir));
        }
        return WithCopyFileToContainer(Settings.Absolute(dataDir), "/tm1/data/");
    }

    /// <summary>The login to check the server with and to hand to tests.</summary>
    public Tm1ServerContainer WithCredentials(string username, string password)
    {
        ArgumentNullException.ThrowIfNull(username);
        ArgumentNullException.ThrowIfNull(password);
        Username = username;
        Password = password;
        return this;
    }

    /// <summary>Puts TM1 in CAM security (mode 5) with <paramref name="cognos"/>'s LDAP namespace,
    /// its test users <paramref name="admins"/> TM1 administrators; it logs in as the first.</summary>
    public Tm1ServerContainer WithCamSecurity(CognosContainer cognos, params string[] admins) =>
        WithCamSecurity(cognos, CognosContainer.Namespace.Ldap, admins);

    /// <summary>As <see cref="WithCamSecurity(CognosContainer, string[])"/>, in <paramref name="namespace"/>.</summary>
    /// <exception cref="ArgumentException">No admin is named.</exception>
    public Tm1ServerContainer WithCamSecurity(CognosContainer cognos, CognosContainer.Namespace @namespace, params string[] admins)
    {
        ArgumentNullException.ThrowIfNull(cognos);
        camNamespace = @namespace;
        Cognos = cognos;
        if (admins.Length == 0)
        {
            throw new ArgumentException("Name at least one of the Cognos test users as a TM1 administrator", nameof(admins));
        }
        camAdmins = [.. admins];
        ShareNetwork(this, cognos);
        DependsOn(cognos);
        return this;
    }

    /// <summary>Registers TM1 with a shared Admin Server, on its network, rather than its own.</summary>
    public Tm1ServerContainer WithAdminServer(Tm1AdminServerContainer admin)
    {
        ArgumentNullException.ThrowIfNull(admin);
        AdminServer = admin;
        ShareNetwork(this, admin);
        DependsOn(admin);
        return WithEnv("TM1_ADMIN_HOST", Tm1AdminServerContainer.NetworkAlias);
    }

    /// <summary>The shared Admin Server, or null for TM1's own.</summary>
    public Tm1AdminServerContainer? AdminServer { get; private set; }

    /// <summary>The Cognos TM1 is in CAM security with, or null for TM1's own security.</summary>
    public CognosContainer? Cognos { get; private set; }

    /// <summary>The CAM namespace, or null without Cognos.</summary>
    public CognosContainer.Namespace? CamNamespace => Cognos is null ? null : camNamespace;

    /// <summary>The <c>Authorization</c> header that logs in as <see cref="Username"/>.</summary>
    public string Authorization =>
        Cognos is not null
            ? CognosContainer.CamAuthorization(Username, Password, camNamespace.Id())
            : "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(Username + ":" + Password));

    /// <summary>Refuses to start unless the server reports <paramref name="version"/>.</summary>
    public Tm1ServerContainer WithExpectedVersion(Tm1Version version)
    {
        expectedVersion = version ?? throw new ArgumentNullException(nameof(version));
        return this;
    }

    /// <summary>Where TM1 comes from.</summary>
    public Tm1Source Source { get; }

    /// <summary>The REST API's root, <c>http://host:port/api/v1/</c>.</summary>
    public string RestUrl => $"http://{Host}:{GetMappedPort(RestPort)}/api/v1/";

    /// <summary>The login.</summary>
    public string Username { get; private set; } = "admin";

    /// <summary>The login's password.</summary>
    public string Password { get; private set; } = "";

    /// <summary>The version the started server reports.</summary>
    /// <exception cref="InvalidOperationException">It isn't started.</exception>
    public Tm1Version Tm1Version => version ?? throw new InvalidOperationException("The container isn't started yet");

    /// <summary>Makes two containers that work together share one network: whichever either is on,
    /// or a new one.</summary>
    internal static void ShareNetwork<TA, TB>(GenericContainer<TA> a, GenericContainer<TB> b)
        where TA : GenericContainer<TA>
        where TB : GenericContainer<TB>
    {
        var network = a.Network ?? b.Network ?? Network.NewNetwork();
        Join(a, network);
        Join(b, network);
    }

    private static void Join<T>(GenericContainer<T> container, Network network)
        where T : GenericContainer<T>
    {
        if (container.Network is null)
        {
            container.WithNetwork(network);
        }
        else if (!ReferenceEquals(container.Network, network))
        {
            throw new InvalidOperationException(
                $"Containers that work together must share one Network, but {container.GetType().Name} is on another. Give them the same Network."
            );
        }
    }

    /// <inheritdoc/>
    protected internal override void Configure()
    {
        if (Cognos is not { } cognos)
        {
            return;
        }
        var admin = cognos.GetUser(camAdmins[0]);
        foreach (var name in camAdmins)
        {
            cognos.GetUser(name);
        }
        WithCredentials(admin.Name, admin.Password);
        WithEnv("TM1_CAM_URI", cognos.NetworkUrl);
        if (camNamespace == CognosContainer.Namespace.Oidc && cognos.Keycloak is null)
        {
            throw new InvalidOperationException("CAM security through OIDC needs the Cognos to have WithKeycloak");
        }
        WithEnv("TM1_CAM_NAMESPACE", camNamespace.Id());
        WithEnv("TM1_CAM_NAMESPACE_TYPE", camNamespace.Type());
        WithEnv("TM1_CAM_ADMINS", string.Join(' ', camAdmins));
        if (cognos.Groups.Count > 0)
        {
            WithEnv("TM1_CAM_GROUPS", string.Join(' ', cognos.Groups));
        }
    }

    /// <inheritdoc/>
    protected override async Task ContainerIsStartedAsync(CancellationToken cancellationToken)
    {
        version = await ReadVersionAsync(cancellationToken).ConfigureAwait(false);
        if (expectedVersion is not null && !expectedVersion.Equals(version))
        {
            throw new InvalidOperationException($"Expected TM1 {expectedVersion} but the server from {Source.Describe()} reports {version}");
        }
        Logger.LogInformation("TM1 {Version} is up at {Url} ({Source})", version, RestUrl, Source.Describe());
    }

    /// <summary>Mounts TM1 from <paramref name="source"/>, as Java's <c>mountSource</c>; the Admin Server's the same.</summary>
    internal static void MountSource(Tm1Source source, List<string> binds, Action<string, string> withEnv)
    {
        switch (source)
        {
            case Tm1Source.InstallDir dir:
                binds.Add($"{dir.Dir}:{InstallPath}:ro");
                break;
            case Tm1Source.InstallVolume volume:
                binds.Add($"{volume.Name}:{InstallPath}");
                break;
            case Tm1Source.Bundle bundle:
                binds.Add($"{bundle.Dir}:/ibm:ro");
                if (bundle.File is not null)
                {
                    withEnv("TM1_BUNDLE", bundle.File);
                }
                binds.Add($"{CacheVolume}:/tm1/cache");
                break;
            case Tm1Source.BundleUrl url:
                withEnv("TM1_BUNDLE_URL", url.Url.OriginalString);
                if (url.Sha256 is not null)
                {
                    withEnv("TM1_BUNDLE_SHA256", url.Sha256);
                }
                binds.Add($"{CacheVolume}:/tm1/cache");
                break;
        }
    }

    private async Task<Tm1Version> ReadVersionAsync(CancellationToken cancellationToken)
    {
        using var http = new HttpClient(new SocketsHttpHandler { UseCookies = false, ConnectTimeout = RequestTimeout }) { Timeout = RequestTimeout };
        using var request = new HttpRequestMessage(HttpMethod.Get, RestUrl + "Configuration/ProductVersion/$value");
        request.Headers.TryAddWithoutValidation("Authorization", Authorization);
        try
        {
            using var response = await http.SendAsync(request, cancellationToken).ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK)
            {
                throw new InvalidOperationException(
                    $"TM1 refused the login '{Username}' (HTTP {(int)response.StatusCode}): {body}. Set the database's login with WithCredentials."
                );
            }
            var cookie = response.Headers.TryGetValues("Set-Cookie", out var cookies)
                ? cookies.Select(c => c.Split(';', 2)[0].Trim()).FirstOrDefault(c => c.StartsWith("TM1SessionId=", StringComparison.Ordinal))
                : null;
            if (cookie is not null)
            {
                await CloseSessionAsync(http, cookie, cancellationToken).ConfigureAwait(false);
            }
            return Tm1Version.Parse(body);
        }
        catch (HttpRequestException e)
        {
            throw new IOException("Couldn't read the TM1 version from " + RestUrl, e);
        }
    }

    private async Task CloseSessionAsync(HttpClient http, string cookie, CancellationToken cancellationToken)
    {
        using var close = new HttpRequestMessage(HttpMethod.Post, RestUrl + "ActiveSession/tm1.Close")
        {
            Content = new StringContent("{}", Encoding.UTF8, "application/json"),
        };
        close.Headers.TryAddWithoutValidation("Cookie", cookie);
        try
        {
            using var _ = await http.SendAsync(close, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException e)
        {
            Logger.LogDebug(e, "Couldn't close the version-check session; TM1 will time it out");
        }
    }

    /// <summary>The image for <paramref name="source"/>: a private image as it is; otherwise
    /// <c>TM1_SERVER_IMAGE</c> if set; otherwise <see cref="DefaultImage"/> tagged with this
    /// library's version (<c>edge</c> for a development build).</summary>
    public static string DefaultImageFor(Tm1Source source) => source is Tm1Source.PrivateImage p ? p.Image : ImageFor(ServerImage, DefaultImage);

    internal static string ImageFor(string setting, string image) => Settings.Text(Settings.Get(setting)) ?? image + ":" + ImageTag(Settings.LibraryVersion());

    /// <summary>The image tag for a library version: <c>edge</c> for a snapshot (Java's
    /// <c>-SNAPSHOT</c>, .NET's <c>-dev</c> and a published <c>-dev.1</c>), the version otherwise.
    /// A development pre-release is published by hand, without the images a tagged release gets.</summary>
    internal static string ImageTag(string libraryVersion) =>
        libraryVersion.EndsWith("-SNAPSHOT", StringComparison.Ordinal)
        || libraryVersion.EndsWith("-dev", StringComparison.Ordinal)
        || libraryVersion.Contains("-dev.", StringComparison.Ordinal)
            ? "edge"
            : libraryVersion;
}
