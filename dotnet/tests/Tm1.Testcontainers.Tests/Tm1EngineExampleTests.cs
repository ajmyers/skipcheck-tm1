// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Version;
using Skipcheck.Tm1.Testcontainers.Xunit;
using Xunit.Abstractions;
using Xunit.Sdk;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>Java's HelloTm1EngineExampleTest: a first test against TM1 v12.
/// <c>[RequiresTm1EngineFact]</c> skips without an engine, and
/// <see cref="Tm1DatabaseFixture{TTestClass}"/> starts one engine for the run and gives this class a
/// database of its own. TM1 v12 has no passwords: <c>db.Login()</c> logs in as <c>admin</c> through
/// the engine's application.</summary>
public class HelloTm1EngineExampleTests(Tm1DatabaseFixture<HelloTm1EngineExampleTests> tm1) : IClassFixture<Tm1DatabaseFixture<HelloTm1EngineExampleTests>>
{
    [RequiresTm1EngineFact]
    public async Task ReadsTheVersion()
    {
        var db = tm1.Database;
        var (status, body) = await Tm1EngineRest.GetAsync(db, db.Login(), "Configuration/ProductVersion/$value");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal(db.Version, Tm1Version.Parse(body));
        Assert.True(db.Version.IsAtLeast(Tm1Version.Parse("12")));
    }

    [RequiresTm1EngineFact]
    public async Task LogsInAsTheAdministrator()
    {
        var db = tm1.Database;
        var (status, body) = await Tm1EngineRest.GetAsync(db, db.Login(), "ActiveUser?$select=Name,Type");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Type\":\"Admin\"", body, StringComparison.Ordinal);
    }
}

/// <summary>Java's DatabasePerClassExampleTest: a database per test class, or one shared. With
/// <see cref="Tm1DatabaseFixture{TTestClass}"/> each class gets a new database, so it can create what
/// it likes under any name and leave it there. <see cref="Tm1EngineFixture"/> uses the engine's one
/// shared database instead (<see cref="DatabasePerClassSharedExampleTests"/>), which costs nothing to
/// get but is shared with every other such class.</summary>
[TestCaseOrderer(InOrder.TypeName, InOrder.AssemblyName)]
public class DatabasePerClassExampleTests(Tm1DatabaseFixture<DatabasePerClassExampleTests> tm1)
    : IClassFixture<Tm1DatabaseFixture<DatabasePerClassExampleTests>>
{
    private const string Dimension = "{\"Name\":\"Region\",\"Hierarchies\":[{\"Name\":\"Region\"}]}";

    [RequiresTm1EngineFact]
    [Order(1)]
    public async Task TheClassHasADatabaseOfItsOwn()
    {
        var (db, engine) = (tm1.Database, tm1.Engine);
        // Named after the class, with a few random characters
        Assert.StartsWith("databaseperclassexample", db.Name, StringComparison.Ordinal);
        var databases = await engine.DatabasesAsync();
        Assert.Contains(db, databases);
        Assert.Contains(engine.Database("tm1"), databases);

        // No need for a unique name: nothing else is in here
        Assert.Equal(HttpStatusCode.Created, (await Tm1EngineRest.PostAsync(db, db.Login(), "Dimensions", Dimension)).Status);
    }

    [RequiresTm1EngineFact]
    [Order(2)]
    public async Task EveryTestOfTheClassSeesTheSameDatabase()
    {
        var db = tm1.Database;
        Assert.Equal(HttpStatusCode.OK, (await Tm1EngineRest.GetAsync(db, db.Login(), "Dimensions('Region')?$select=Name")).Status);
    }
}

/// <summary>Java's DatabasePerClassExampleTest.Shared, the nested class that shares the engine's database.</summary>
public class DatabasePerClassSharedExampleTests(Tm1EngineFixture tm1) : IClassFixture<Tm1EngineFixture>
{
    [RequiresTm1EngineFact]
    public async Task ASharingClassGetsTheEnginesOwnDatabase()
    {
        var db = tm1.Database;
        Assert.Equal(Tm1EngineContainer.DefaultDatabase, db.Name);
        // Which the other class's dimension isn't in
        Assert.Equal(HttpStatusCode.NotFound, (await Tm1EngineRest.GetAsync(db, db.Login(), "Dimensions('Region')?$select=Name")).Status);
    }
}

/// <summary>Java's OAuth2ExampleTest: OAuth 2.0 logins, with Keycloak, the way TM1 v12 logs users in from an identity
/// provider. The engine takes tokens from a Keycloak beside it, which knows the users who were given a password. A test
/// gets a token as the user and sends it to TM1.</summary>
[Collection(OwnContainers.Name)]
public class OAuth2ExampleTests
{
    [RequiresTm1EngineFact]
    public async Task AUserLogsInWithATokenFromKeycloak()
    {
        var source = Tm1EngineSource.Discover() ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);
        // Disposed last: the Keycloak is yours to stop
        await using var keycloak = new KeycloakContainer();
        await using var engine = new Tm1EngineContainer(source).WithUser("analyst", "Analysts").WithLogin("analyst", "analystpw").WithOAuth2(keycloak);
        await engine.StartAsync();
        var db = engine.Database("tm1");

        using var request = await db.OAuth2Login("analyst")
            .AuthorizeWithTokenAsync(new HttpRequestMessage(HttpMethod.Get, db.RestUrl + "ActiveUser?$select=Name"));
        var (status, body) = await Tm1EngineRest.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Name\":\"analyst\"", body, StringComparison.OrdinalIgnoreCase);
    }
}

/// <summary>Java's S2sUsersExampleTest: users with different rights. An engine of this class's own,
/// with two users besides <c>admin</c>. TM1 v12 keeps no passwords, so a test logs in as any of them
/// by name, through the engine's application ("service-to-service"). An engine of your own takes
/// about 40 seconds to start.</summary>
[Collection(OwnContainers.Name)]
public class S2sUsersExampleTests(S2sUsersExampleTests.OwnEngine own) : IClassFixture<S2sUsersExampleTests.OwnEngine>
{
    private Tm1Database Planning => own.Engine.Database("planning");

    [RequiresTm1EngineFact]
    public async Task TheAdministratorIsAnAdministrator()
    {
        var (_, body) = await Tm1EngineRest.GetAsync(Planning, Planning.Login(), "ActiveUser?$select=Name,Type");

        Assert.Contains("\"Type\":\"Admin\"", body, StringComparison.Ordinal);
    }

    [RequiresTm1EngineFact]
    public async Task AUserIsInTheGroupsTheyWereGiven()
    {
        var (status, body) = await Tm1EngineRest.GetAsync(Planning, Planning.Login("analyst"), "ActiveUser?$select=Name,Type&$expand=Groups($select=Name)");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Type\":\"User\"", body, StringComparison.Ordinal);
        Assert.Contains("\"Name\":\"Analysts\"", body, StringComparison.Ordinal);
    }

    [RequiresTm1EngineFact]
    public async Task AUserWithoutGroupsCanStillLogIn()
    {
        var (status, body) = await Tm1EngineRest.GetAsync(Planning, Planning.Login("viewer"), "ActiveUser?$select=Name&$expand=Groups($select=Name)");

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Groups\":[]", body, StringComparison.Ordinal);
    }

    [RequiresTm1EngineFact]
    public async Task ANameTm1DoesntKnowGetsASessionButNoAccess()
    {
        // The application vouches for any name, so the login itself works
        var nobody = Planning.Login("nobody");
        Assert.StartsWith("TM1SessionId=", await nobody.CookieAsync(), StringComparison.Ordinal);

        Assert.Equal(HttpStatusCode.Forbidden, (await Tm1EngineRest.GetAsync(Planning, nobody, "ActiveUser")).Status);
    }

    /// <summary>The class's engine, as Java's <c>@BeforeAll</c> and <c>@AfterAll</c>. Nothing starts
    /// when the tests are skipped.</summary>
    public sealed class OwnEngine : IAsyncLifetime
    {
        private Tm1EngineContainer? engine;

        public Tm1EngineContainer Engine =>
            engine ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.EngineSkipReason() ?? "The engine isn't started");

        public async Task InitializeAsync()
        {
            if (RequiresTm1EngineFactAttribute.EngineSkipReason() is not null)
            {
                return;
            }
            var source = Tm1EngineSource.Discover() ?? throw new InvalidOperationException(RequiresTm1EngineFactAttribute.NoSourceMessage);
            engine = new Tm1EngineContainer(source).WithDatabase("planning").WithUser("analyst", "Analysts").WithUser("viewer");
            await engine.StartAsync();
        }

        public async Task DisposeAsync()
        {
            if (engine is not null)
            {
                await engine.DisposeAsync();
            }
        }
    }
}

/// <summary>Java's Tm1EngineEnvironmentExampleTest: TM1 11 and TM1 v12 in one environment.
/// <see cref="PlanningAnalyticsEnvironment"/> starts a TM1 server and a TM1 v12 engine on one network, for tests of
/// both versions, or of moving from one to the other. It needs a source for each.</summary>
[Collection(OwnContainers.Name)]
public class Tm1EngineEnvironmentExampleTests
{
    [RequiresTm1AndTm1EngineFact]
    public async Task AServerAndAnEngineSideBySide()
    {
        await using var env = PlanningAnalyticsEnvironment
            .Builder()
            .Tm1("legacy")
            .Tm1Engine(engine => engine.WithDatabase("planning").WithUser("analyst", "Analysts"))
            .Build();
        await env.StartAsync();

        await using (var legacy = new Tm1Rest(env.Tm1("legacy")))
        {
            Assert.Equal(0, await legacy.ModelCubeCountAsync());
        }
        var planning = env.Tm1Database("planning");
        var (status, body) = await Tm1EngineRest.GetAsync(planning, planning.Login("analyst"), "ActiveUser?$select=Name");
        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Contains("\"Name\":\"analyst\"", body, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>A test that needs both a TM1 11 and a TM1 v12 source, as Java's <c>@RequiresTm1</c> with
    /// <c>@RequiresTm1Engine</c>.</summary>
    [AttributeUsage(AttributeTargets.Method)]
    private sealed class RequiresTm1AndTm1EngineFactAttribute : RequiresTm1EngineFactAttribute
    {
        public RequiresTm1AndTm1EngineFactAttribute()
        {
            Skip ??= RequiresTm1FactAttribute.Tm1SkipReason();
        }
    }
}

/// <summary>A test's place among its class's, as JUnit's <c>@Order</c>; for a class with <see cref="InOrder"/>.</summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class OrderAttribute(int value) : Attribute
{
    public int Value { get; } = value;
}

/// <summary>Runs a class's tests by their <see cref="OrderAttribute"/>, as JUnit's
/// <c>MethodOrderer.OrderAnnotation</c>: xUnit runs them in no order one can rely on otherwise.</summary>
public sealed class InOrder : ITestCaseOrderer
{
    public const string TypeName = "Skipcheck.Tm1.Testcontainers.Tests.InOrder";

    public const string AssemblyName = "Skipcheck.Tm1.Testcontainers.Tests";

    public IEnumerable<TTestCase> OrderTestCases<TTestCase>(IEnumerable<TTestCase> testCases)
        where TTestCase : ITestCase => testCases.OrderBy(test => Place(test.TestMethod.Method));

    /// <summary>A test method's place: its <see cref="OrderAttribute"/>'s, or after those that have one.</summary>
    internal static int Place(IMethodInfo method) =>
        method.GetCustomAttributes(typeof(OrderAttribute).AssemblyQualifiedName!).FirstOrDefault()?.GetConstructorArguments().OfType<int>().FirstOrDefault()
        ?? int.MaxValue;
}

/// <summary>The tests of <see cref="InOrder"/>, which need no TM1.</summary>
public class InOrderTests
{
    [Fact]
    public void RunsTestsByTheirOrder()
    {
        Assert.Equal(InOrder.TypeName, typeof(InOrder).FullName);
        Assert.Equal(InOrder.AssemblyName, typeof(InOrder).Assembly.GetName().Name);
        var places = new[] { "Unplaced", "Second", "First" }.ToDictionary(name => name, name => InOrder.Place(Reflector.Wrap(typeof(Sample).GetMethod(name)!)));
        Assert.Equal(["First", "Second", "Unplaced"], places.OrderBy(place => place.Value).Select(place => place.Key));
    }

    [Fact]
    public void TheDatabasePerClassExampleCreatesItsDimensionFirst()
    {
        int Place(string test) => InOrder.Place(Reflector.Wrap(typeof(DatabasePerClassExampleTests).GetMethod(test)!));
        Assert.True(
            Place(nameof(DatabasePerClassExampleTests.TheClassHasADatabaseOfItsOwn))
                < Place(nameof(DatabasePerClassExampleTests.EveryTestOfTheClassSeesTheSameDatabase))
        );
        var orderer = Assert.Single(typeof(DatabasePerClassExampleTests).GetCustomAttributesData(), a => a.AttributeType == typeof(TestCaseOrdererAttribute));
        Assert.Equal([InOrder.TypeName, InOrder.AssemblyName], orderer.ConstructorArguments.Select(a => a.Value));
    }

    // Not public, so xunit doesn't list them
#pragma warning disable CA1822, CA1812
    private sealed class Sample
    {
        public void Unplaced() { }

        [Order(2)]
        public void Second() { }

        [Order(1)]
        public void First() { }
    }
#pragma warning restore CA1822, CA1812
}
