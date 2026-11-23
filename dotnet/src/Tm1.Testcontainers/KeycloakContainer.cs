using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>Keycloak, the OpenID Connect provider behind a <see cref="CognosContainer"/>'s OIDC
/// namespace, as the Java <c>KeycloakContainer</c>: reached as <c>keycloak</c> on its network, with
/// the <c>cognos</c> realm the Cognos image imports and its <c>tm1-app</c> client.</summary>
public partial class KeycloakContainer : GenericContainer<KeycloakContainer>
{
    /// <summary>The image Keycloak runs in.</summary>
    public const string DefaultImage = "quay.io/keycloak/keycloak:26.4";

    /// <summary>The setting that replaces <see cref="DefaultImage"/>.</summary>
    public const string Image = "KEYCLOAK_IMAGE";

    /// <summary>Keycloak's HTTP port.</summary>
    public const int HttpPort = 8080;

    /// <summary>Its name on the network.</summary>
    public const string NetworkAlias = "keycloak";

    /// <summary>The realm the Cognos users are in.</summary>
    public const string Realm = "cognos";

    /// <summary>The client an app gets ID tokens with.</summary>
    public const string AppClient = "tm1-app";

    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(30);

    /// <summary>Keycloak in <see cref="DefaultImage"/>, or <c>KEYCLOAK_IMAGE</c> if set.</summary>
    public KeycloakContainer()
        : this(Settings.Text(Settings.Get(Image)) ?? DefaultImage) { }

    /// <summary>Keycloak in <paramref name="image"/>.</summary>
    public KeycloakContainer(string image)
        : base(image)
    {
        WithCommand("start-dev");
        WithExposedPorts(HttpPort);
        WithNetworkAliases(NetworkAlias);
        WithEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
        WithEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin");
        WithEnv("KC_HTTP_ENABLED", "true");
        WithEnv("KC_HOSTNAME", NetworkUrl);
        WaitingForHttp("/realms/master", HttpPort, code => code == HttpStatusCode.OK, TimeSpan.FromMinutes(3));
    }

    /// <summary>Keycloak's address on its network, as Cognos and TM1 reach it.</summary>
    public string NetworkUrl => "http://" + NetworkAlias + ":" + HttpPort;

    /// <summary>Keycloak's address on the Docker host.</summary>
    public string Url => $"http://{Host}:{GetMappedPort(HttpPort)}";

    /// <summary>An ID token for a test user, by the password grant of <see cref="AppClient"/>.</summary>
    /// <exception cref="InvalidOperationException">Keycloak refused.</exception>
    public async Task<string> IdTokenAsync(string user, string password, CancellationToken cancellationToken = default)
    {
        using var http = new HttpClient { Timeout = Timeout };
        using var form = new FormUrlEncodedContent(
            new Dictionary<string, string>
            {
                ["grant_type"] = "password",
                ["client_id"] = AppClient,
                ["scope"] = "openid",
                ["username"] = user,
                ["password"] = password,
            }
        );
        using var response = await http.PostAsync(new Uri(Url + "/realms/" + Realm + "/protocol/openid-connect/token"), form, cancellationToken)
            .ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        var m = IdToken().Match(body);
        if (response.StatusCode != HttpStatusCode.OK || !m.Success)
        {
            throw new InvalidOperationException($"Keycloak refused {user} (HTTP {(int)response.StatusCode}): {body}");
        }
        return m.Groups[1].Value;
    }

    [GeneratedRegex("\"id_token\"\\s*:\\s*\"([^\"]+)\"")]
    private static partial Regex IdToken();
}
