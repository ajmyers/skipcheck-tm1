// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Security.Cryptography;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Auth;
using Xunit;

namespace Skipcheck.Tm1.Testcontainers.Xunit;

/// <summary>The shared TM1 server for a test class (<c>IClassFixture&lt;Tm1ServerFixture&gt;</c>),
/// as Java's <c>@RequiresTm1</c> resolves a <c>Tm1ServerContainer</c> parameter: every class gets
/// the same <see cref="Tm1ServerContainer.SharedAsync"/> server, started once per run. Nothing
/// starts when <see cref="RequiresTm1FactAttribute"/> would skip.</summary>
public class Tm1ServerFixture : IAsyncLifetime
{
    private Tm1ServerContainer? tm1;

    /// <summary>The shared TM1 server.</summary>
    /// <exception cref="InvalidOperationException">There's no TM1 to test against.</exception>
    public Tm1ServerContainer Tm1Server =>
        tm1 ?? throw new InvalidOperationException(RequiresTm1FactAttribute.Tm1SkipReason() ?? "The TM1 server isn't started");

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

/// <summary>The shared TM1 v12 engine for a test class (<c>IClassFixture&lt;Tm1EngineFixture&gt;</c>), as
/// Java's <c>@RequiresTm1Engine(shareDatabase = true)</c> resolves a <c>Tm1EngineContainer</c> and a
/// <c>Tm1Database</c> parameter: every class gets the same
/// <see cref="Tm1EngineContainer.SharedAsync"/> engine, started once per run, and its one shared
/// database, <c>tm1</c>. For a database of the class's own, use
/// <see cref="Tm1DatabaseFixture{TTestClass}"/>. Nothing starts when
/// <see cref="RequiresTm1EngineFactAttribute"/> would skip.</summary>
public class Tm1EngineFixture : IAsyncLifetime
{
    private Tm1EngineContainer? engine;

    /// <summary>The shared engine.</summary>
    /// <exception cref="InvalidOperationException">There's no TM1 v12 to test against.</exception>
    public Tm1EngineContainer Engine =>
        engine ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.EngineSkipReason() ?? "The TM1 engine isn't started");

    /// <summary>The engine's shared database, <c>tm1</c>: every class with this fixture uses the same one.</summary>
    /// <exception cref="InvalidOperationException">There's no TM1 v12 to test against.</exception>
    public virtual Tm1Database Database => Engine.Database(Tm1EngineContainer.DefaultDatabase);

    /// <inheritdoc/>
    public virtual async Task InitializeAsync()
    {
        if (RequiresTm1EngineFactAttribute.EngineSkipReason() is null)
        {
            engine = await Tm1EngineContainer.SharedAsync().ConfigureAwait(false);
        }
    }

    /// <inheritdoc/>
    public virtual Task DisposeAsync() => Task.CompletedTask; // shared for the run; removed when it ends

    /// <summary>A test class's database name, as Java's <c>databaseName</c>: the class's name, as far as
    /// a database's name allows, and a few random characters.</summary>
    internal static string DatabaseName(string type) => DatabaseName(type, Base36(RandomNumberGenerator.GetInt32(36 * 36 * 36 * 36)));

    internal static string DatabaseName(string type, string suffix)
    {
        var letters = string.Concat(type.ToLowerInvariant().Where(c => c is (>= 'a' and <= 'z') or (>= '0' and <= '9')));
        var start = letters.Length == 0 ? "test" : letters[..Math.Min(letters.Length, 24)];
        return start + "_" + suffix.PadLeft(4, '0');
    }

    private static string Base36(int value)
    {
        const string Digits = "0123456789abcdefghijklmnopqrstuvwxyz";
        var text = "";
        do
        {
            text = Digits[value % 36] + text;
            value /= 36;
        } while (value > 0);
        return text;
    }
}

/// <summary>A database of the test class's own in the shared TM1 v12 engine
/// (<c>IClassFixture&lt;Tm1DatabaseFixture&lt;CubeTests&gt;&gt;</c>), as Java's <c>@RequiresTm1Engine</c>
/// resolves a <c>Tm1Database</c> parameter: <b>one database per xUnit test class</b>, created before
/// the class's first test and dropped after its last, so the class can create what it likes under
/// any name and leave it there. It's named after <typeparamref name="TTestClass"/>, with a few random
/// characters. Takes about 10 seconds to create and 7 to drop.</summary>
/// <typeparam name="TTestClass">The test class, which the database is named after: a class fixture
/// isn't told whose it is.</typeparam>
public sealed class Tm1DatabaseFixture<TTestClass> : Tm1EngineFixture
{
    private Tm1Database? database;

    /// <summary>The class's own database.</summary>
    /// <exception cref="InvalidOperationException">There's no TM1 v12 to test against.</exception>
    public override Tm1Database Database =>
        database ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.EngineSkipReason() ?? "The database isn't created");

    /// <inheritdoc/>
    public override async Task InitializeAsync()
    {
        if (RequiresTm1EngineFactAttribute.EngineSkipReason() is null)
        {
            await base.InitializeAsync().ConfigureAwait(false);
            database = await Engine.CreateDatabaseAsync(DatabaseName(typeof(TTestClass).Name)).ConfigureAwait(false);
        }
    }

    /// <inheritdoc/>
    public override async Task DisposeAsync()
    {
        if (database is { } own)
        {
            database = null;
            if (own.Engine.IsRunning)
            {
                await own.Engine.DropDatabaseAsync(own).ConfigureAwait(false);
            }
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
