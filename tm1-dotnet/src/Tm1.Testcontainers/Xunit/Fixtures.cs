using System;
using System.Threading.Tasks;
using AjMyers.Tm1.Client;
using AjMyers.Tm1.Client.Auth;
using Xunit;

namespace AjMyers.Tm1.Testcontainers.Xunit;

/// <summary>The shared TM1 server for a test class (<c>IClassFixture&lt;Tm1ServerFixture&gt;</c>),
/// as Java's <c>@RequiresTm1</c> resolves a <c>Tm1ServerContainer</c> parameter: every class gets
/// the same <see cref="Tm1ServerContainer.SharedAsync"/> server, started once per run. Nothing
/// starts when <see cref="RequiresTm1FactAttribute"/> would skip.</summary>
public class Tm1ServerFixture : IAsyncLifetime
{
    private Tm1ServerContainer? tm1;

    /// <summary>The shared TM1 server.</summary>
    /// <exception cref="InvalidOperationException">There's no TM1 to test against.</exception>
    public Tm1ServerContainer Tm1Server => tm1 ?? throw new InvalidOperationException(RequiresTm1FactAttribute.Tm1SkipReason() ?? "The TM1 server isn't started");

    /// <summary>A new client of the shared server, logged in as its <see cref="Tm1ServerContainer.Username"/>.</summary>
    public Tm1Client Client() => ClientOf(Tm1Server);

    /// <summary>A new client of <paramref name="server"/>, logged in as its <see cref="Tm1ServerContainer.Username"/>.</summary>
    public static Tm1Client ClientOf(Tm1ServerContainer server)
    {
        ArgumentNullException.ThrowIfNull(server);
        var authenticator = server.CamNamespace is { } ns
            ? Tm1Authenticator.CamNamespace(server.Username, server.Password, ns.Id())
            : Tm1Authenticator.Basic(server.Username, server.Password);
        return Tm1Client.Builder().BaseUrl(server.RestUrl).Authenticator(authenticator).Build();
    }

    /// <inheritdoc/>
    public virtual async Task InitializeAsync()
    {
        if (RequiresTm1FactAttribute.Tm1SkipReason() is null)
        {
            tm1 = await Tm1ServerContainer.SharedAsync().ConfigureAwait(false);
        }
    }

    /// <inheritdoc/>
    public virtual Task DisposeAsync() => Task.CompletedTask; // shared for the run; removed when it ends
}

/// <summary>The shared TM1 Web (and TM1 server) for a test class, as Java's <c>@RequiresTm1Web</c>
/// resolves a <c>Tm1WebContainer</c> parameter.</summary>
public sealed class Tm1WebFixture : Tm1ServerFixture
{
    private Tm1WebContainer? web;

    /// <summary>The shared TM1 Web.</summary>
    public Tm1WebContainer Tm1Web => web ?? throw new InvalidOperationException(new RequiresTm1WebFactAttribute().Skip ?? "TM1 Web isn't started");

    /// <inheritdoc/>
    public override async Task InitializeAsync()
    {
        if (new RequiresTm1WebFactAttribute().Skip is null)
        {
            await base.InitializeAsync().ConfigureAwait(false);
            web = await Tm1WebContainer.SharedAsync().ConfigureAwait(false);
        }
    }
}

/// <summary>The shared PAW (and TM1 server) for a test class, as Java's <c>@RequiresPaw</c>
/// resolves a <c>PawContainer</c> parameter.</summary>
public sealed class PawFixture : Tm1ServerFixture
{
    private PawContainer? paw;

    /// <summary>The shared PAW.</summary>
    public PawContainer Paw => paw ?? throw new InvalidOperationException(new RequiresPawFactAttribute().Skip ?? "PAW isn't started");

    /// <inheritdoc/>
    public override async Task InitializeAsync()
    {
        if (new RequiresPawFactAttribute().Skip is null)
        {
            await base.InitializeAsync().ConfigureAwait(false);
            paw = await PawContainer.SharedAsync().ConfigureAwait(false);
        }
    }
}

/// <summary>The Cognos source for a test class, as Java's <c>@RequiresCognos</c> resolves a
/// <c>CognosSource</c> parameter (Cognos tests start their own, with their own users).</summary>
public sealed class CognosFixture
{
    /// <summary>The discovered source.</summary>
    /// <exception cref="InvalidOperationException">None is configured.</exception>
    public CognosSource Source => CognosSource.Discover() ?? throw new InvalidOperationException(RequiresCognosFactAttribute.NoSourceMessage);
}
