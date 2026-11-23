using System;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Services;
using Skipcheck.Tm1.Client.Session;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client;

/// <summary>The client for one TM1 server: one session, and every service built on it, as the Java
/// client's <c>Tm1Client</c>:
/// <code>
/// await using var tm1 = Tm1Client.Builder().BaseUrl("http://localhost:8010/api/v1/").BasicAuth("admin", "apple").Build();
/// await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)))
/// {
///     Console.WriteLine(cube.Name);
/// }
/// </code>
/// Run a long process asynchronously, so no request stays open for its whole run; cancelling
/// stops the process on TM1:
/// <code>await tm1.WithAsync(TimeSpan.FromSeconds(2)).Processes.ExecuteWithReturnAsync("Rebuild Forecast");</code>
/// </summary>
public sealed class Tm1Client : IAsyncDisposable
{
    private Tm1Client(Tm1Session session)
    {
        Session = session ?? throw new ArgumentNullException(nameof(session));
        Dimensions = new DimensionService(session);
        Hierarchies = new HierarchyService(session);
        Elements = new ElementService(session);
        Edges = new EdgeService(session);
        Levels = new LevelService(session);
        Members = new MemberService(session);
        Subsets = new SubsetService(session);
        Attributes = new AttributeService(session);
        LocalizedAttributes = new LocalizedAttributeService(session);
        Cubes = new CubeService(session);
        Views = new ViewService(session);
        Cellsets = new CellsetService(session);
        Cells = new CellService(session);
        MemberSets = new MemberSetService(session);
        Sandboxes = new SandboxService(session);
        ChangeSets = new ChangeSetService(session);
        Drillthrough = new DrillthroughService(session);
        Processes = new ProcessService(session);
        ProcessDebugging = new ProcessDebugService(session);
        Chores = new ChoreService(session);
        Users = new UserService(session);
        Groups = new GroupService(session);
        Sessions = new SessionService(session);
        Threads = new ThreadService(session);
        Server = new ServerService(session);
        Logs = new LogService(session);
        Contents = new ContentService(session);
        Annotations = new AnnotationService(session);
        Git = new GitService(session);
        Encryption = new EncryptionService(session);
        OidcKeys = new OidcKeyService(session);
        SqlDataSources = new SqlDataSourceService(session);
    }

    /// <summary>A builder for a client of one TM1 server.</summary>
    public static Tm1ClientBuilder Builder() => new();

    /// <summary>A client over <paramref name="session"/>, which it shares.</summary>
    public static Tm1Client Of(Tm1Session session) => new(session);

    /// <summary>The session, which sends any request (<see cref="Tm1Request"/>).</summary>
    public Tm1Session Session { get; }

    public DimensionService Dimensions { get; }

    public HierarchyService Hierarchies { get; }

    public ElementService Elements { get; }

    public EdgeService Edges { get; }

    public LevelService Levels { get; }

    public MemberService Members { get; }

    public SubsetService Subsets { get; }

    public AttributeService Attributes { get; }

    public LocalizedAttributeService LocalizedAttributes { get; }

    public CubeService Cubes { get; }

    public ViewService Views { get; }

    public CellsetService Cellsets { get; }

    public CellService Cells { get; }

    public MemberSetService MemberSets { get; }

    public SandboxService Sandboxes { get; }

    public ChangeSetService ChangeSets { get; }

    public DrillthroughService Drillthrough { get; }

    public ProcessService Processes { get; }

    public ProcessDebugService ProcessDebugging { get; }

    public ChoreService Chores { get; }

    public UserService Users { get; }

    public GroupService Groups { get; }

    public SessionService Sessions { get; }

    public ThreadService Threads { get; }

    public ServerService Server { get; }

    public LogService Logs { get; }

    public ContentService Contents { get; }

    public AnnotationService Annotations { get; }

    public GitService Git { get; }

    public EncryptionService Encryption { get; }

    public OidcKeyService OidcKeys { get; }

    public SqlDataSourceService SqlDataSources { get; }

    /// <summary>A JSON <c>$batch</c>: calls through <see cref="Tm1Batch.Client"/> are queued, and
    /// <c>RunAsync</c> sends them together.</summary>
    public Tm1Batch Batch() => new(Session.Batch());

    /// <summary>A client whose requests TM1 runs asynchronously, polled every <paramref name="every"/>.</summary>
    public Tm1Client WithAsync(TimeSpan every) => new(Session.WithAsync(every));

    public Tm1Client WithoutAsync() => new(Session.WithoutAsync());

    /// <summary>A client whose requests run in sandbox <paramref name="name"/>.</summary>
    public Tm1Client WithSandbox(string name) => new(Session.WithSandbox(name));

    /// <summary>A client whose writes are recorded in change set <paramref name="id"/>.</summary>
    public Tm1Client WithChangeSet(string id) => new(Session.WithChangeSet(id));

    /// <summary>Logs in now, rather than on the first request.</summary>
    public async Task<Tm1Client> ConnectAsync(CancellationToken cancellationToken = default)
    {
        await Session.ConnectAsync(cancellationToken).ConfigureAwait(false);
        return this;
    }

    /// <summary>Ends the session on the server.</summary>
    public Task CloseAsync() => Session.CloseAsync();

    public ValueTask DisposeAsync() => new(CloseAsync());

    public override string ToString() => $"Tm1Client[{Session.ServiceRoot()}]";
}

/// <summary>A JSON <c>$batch</c> of client calls, as the Java client's <c>Tm1Batch</c>: each call's
/// task completes with its own result or error once the batch has run.</summary>
public sealed class Tm1Batch
{
    private readonly Tm1SessionBatch _batch;

    internal Tm1Batch(Tm1SessionBatch batch)
    {
        _batch = batch;
        Client = Tm1Client.Of(batch.Session);
    }

    /// <summary>The client whose calls join this batch.</summary>
    public Tm1Client Client { get; }

    public int Size() => _batch.Size();

    public Task RunAsync(CancellationToken cancellationToken = default) => _batch.RunAsync(cancellationToken);

    public Task RunAsync(int perRequest, CancellationToken cancellationToken = default) => _batch.RunAsync(perRequest, cancellationToken);
}

/// <summary>The session's settings, as the Java client's <c>Tm1Client.Builder</c>; <see cref="Build"/> makes the client.</summary>
public sealed class Tm1ClientBuilder
{
    private readonly Tm1SessionBuilder _session = new();

    public Tm1ClientBuilder BaseUrl(string baseUrl) => Do(() => _session.BaseUrl(baseUrl));

    public Tm1ClientBuilder Address(string address) => Do(() => _session.Address(address));

    public Tm1ClientBuilder Dialect(Tm1Dialect dialect) => Do(() => _session.Dialect(dialect));

    public Tm1ClientBuilder BasicAuth(string user, string? password) => Do(() => _session.BasicAuth(user, password));

    public Tm1ClientBuilder Authenticator(Tm1Authenticator authenticator) => Do(() => _session.Authenticator(authenticator));

    public Tm1ClientBuilder Impersonate(string user) => Do(() => _session.Impersonate(user));

    public Tm1ClientBuilder SessionContext(string sessionContext) => Do(() => _session.SessionContext(sessionContext));

    public Tm1ClientBuilder Timeouts(Tm1Timeouts timeouts) => Do(() => _session.Timeouts(timeouts));

    public Tm1ClientBuilder ErrorClassifier(Tm1ErrorClassifier classifier) => Do(() => _session.ErrorClassifier(classifier));

    public Tm1ClientBuilder HttpHandler(System.Net.Http.HttpMessageHandler handler) => Do(() => _session.HttpHandler(handler));

    public Tm1ClientBuilder CheckCapabilities(bool check) => Do(() => _session.CheckCapabilities(check));

    public Tm1Client Build() => Tm1Client.Of(_session.Build());

    private Tm1ClientBuilder Do(Func<Tm1SessionBuilder> change)
    {
        change();
        return this;
    }
}
