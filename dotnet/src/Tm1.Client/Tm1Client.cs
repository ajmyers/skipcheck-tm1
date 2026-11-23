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

    /// <summary>Dimensions, on this client's session.</summary>
    public DimensionService Dimensions { get; }

    /// <summary>Hierarchies, on this client's session.</summary>
    public HierarchyService Hierarchies { get; }

    /// <summary>Elements, on this client's session.</summary>
    public ElementService Elements { get; }

    /// <summary>Edges, on this client's session.</summary>
    public EdgeService Edges { get; }

    /// <summary>Levels, on this client's session.</summary>
    public LevelService Levels { get; }

    /// <summary>Members, on this client's session.</summary>
    public MemberService Members { get; }

    /// <summary>Subsets, on this client's session.</summary>
    public SubsetService Subsets { get; }

    /// <summary>Attribute definitions, on this client's session.</summary>
    public AttributeService Attributes { get; }

    /// <summary>Translated attribute values, on this client's session.</summary>
    public LocalizedAttributeService LocalizedAttributes { get; }

    /// <summary>Cubes, on this client's session.</summary>
    public CubeService Cubes { get; }

    /// <summary>Views, on this client's session.</summary>
    public ViewService Views { get; }

    /// <summary>Cellsets, on this client's session.</summary>
    public CellsetService Cellsets { get; }

    /// <summary>Cell writes, on this client's session.</summary>
    public CellService Cells { get; }

    /// <summary>Member sets, on this client's session.</summary>
    public MemberSetService MemberSets { get; }

    /// <summary>Sandboxes, on this client's session.</summary>
    public SandboxService Sandboxes { get; }

    /// <summary>Change sets, on this client's session.</summary>
    public ChangeSetService ChangeSets { get; }

    /// <summary>Drillthrough, on this client's session.</summary>
    public DrillthroughService Drillthrough { get; }

    /// <summary>Processes, on this client's session.</summary>
    public ProcessService Processes { get; }

    /// <summary>Process debugging, on this client's session.</summary>
    public ProcessDebugService ProcessDebugging { get; }

    /// <summary>Chores, on this client's session.</summary>
    public ChoreService Chores { get; }

    /// <summary>Users, on this client's session.</summary>
    public UserService Users { get; }

    /// <summary>Groups, on this client's session.</summary>
    public GroupService Groups { get; }

    /// <summary>Server sessions, on this client's session.</summary>
    public SessionService Sessions { get; }

    /// <summary>Server threads, on this client's session.</summary>
    public ThreadService Threads { get; }

    /// <summary>The server, on this client's session.</summary>
    public ServerService Server { get; }

    /// <summary>The server's logs, on this client's session.</summary>
    public LogService Logs { get; }

    /// <summary>The contents tree, on this client's session.</summary>
    public ContentService Contents { get; }

    /// <summary>Annotations on cube cells, on this client's session.</summary>
    public AnnotationService Annotations { get; }

    /// <summary>TM1 Git, on this client's session.</summary>
    public GitService Git { get; }

    /// <summary>Encryption at rest, on this client's session.</summary>
    public EncryptionService Encryption { get; }

    /// <summary>The cached OpenID Connect keys, on this client's session.</summary>
    public OidcKeyService OidcKeys { get; }

    /// <summary>The ODBC data sources on TM1's host, on this client's session.</summary>
    public SqlDataSourceService SqlDataSources { get; }

    /// <summary>A JSON <c>$batch</c>: calls through <see cref="Tm1Batch.Client"/> are queued, and
    /// <c>RunAsync</c> sends them together.</summary>
    public Tm1Batch Batch() => new(Session.Batch());

    /// <summary>A client whose requests TM1 runs asynchronously, polled every <paramref name="every"/>.</summary>
    public Tm1Client WithAsync(TimeSpan every) => new(Session.WithAsync(every));

    /// <summary>A client on a synchronous view of this client's session
    /// (<see cref="Tm1Session.WithoutAsync"/>).</summary>
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

    /// <summary>Closes the session: <see cref="CloseAsync"/>.</summary>
    public ValueTask DisposeAsync() => new(CloseAsync());

    /// <inheritdoc/>
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

    /// <summary>How many requests have joined.</summary>
    public int Size() => _batch.Size();

    /// <summary>Sends the queued requests
    /// (<see cref="Tm1SessionBatch.RunAsync(CancellationToken)"/>); completes when every call has
    /// its result.</summary>
    public Task RunAsync(CancellationToken cancellationToken = default) => _batch.RunAsync(cancellationToken);

    /// <summary>Sends the queued requests, at most <paramref name="perRequest"/> to each
    /// <c>$batch</c>; completes when every call has its result.</summary>
    public Task RunAsync(int perRequest, CancellationToken cancellationToken = default) => _batch.RunAsync(perRequest, cancellationToken);
}

/// <summary>The session's settings, as the Java client's <c>Tm1Client.Builder</c>; <see cref="Build"/> makes the client.</summary>
public sealed class Tm1ClientBuilder
{
    private readonly Tm1SessionBuilder _session = new();

    /// <summary>The service root, such as <c>http://localhost:8010/api/v1/</c>: see
    /// <see cref="Tm1SessionBuilder.BaseUrl"/>.</summary>
    public Tm1ClientBuilder BaseUrl(string baseUrl) => Do(() => _session.BaseUrl(baseUrl));

    /// <summary>The server's address, such as <c>http://tm1:8010</c>: see
    /// <see cref="Tm1SessionBuilder.Address"/>.</summary>
    public Tm1ClientBuilder Address(string address) => Do(() => _session.Address(address));

    /// <summary>The kind of deployment: see <see cref="Tm1SessionBuilder.Dialect"/>.</summary>
    public Tm1ClientBuilder Dialect(Tm1Dialect dialect) => Do(() => _session.Dialect(dialect));

    /// <summary>Basic authentication: see <see cref="Tm1SessionBuilder.BasicAuth"/>.</summary>
    public Tm1ClientBuilder BasicAuth(string user, string? password) => Do(() => _session.BasicAuth(user, password));

    /// <summary>What supplies the credentials for each login: see
    /// <see cref="Tm1SessionBuilder.Authenticator"/>.</summary>
    public Tm1ClientBuilder Authenticator(Tm1Authenticator authenticator) => Do(() => _session.Authenticator(authenticator));

    /// <summary>The TM1 user to act as: see <see cref="Tm1SessionBuilder.Impersonate"/>.</summary>
    public Tm1ClientBuilder Impersonate(string user) => Do(() => _session.Impersonate(user));

    /// <summary>The session context sent to TM1: see
    /// <see cref="Tm1SessionBuilder.SessionContext"/>.</summary>
    public Tm1ClientBuilder SessionContext(string sessionContext) => Do(() => _session.SessionContext(sessionContext));

    /// <summary>The timeouts: see <see cref="Tm1SessionBuilder.Timeouts"/>.</summary>
    public Tm1ClientBuilder Timeouts(Tm1Timeouts timeouts) => Do(() => _session.Timeouts(timeouts));

    /// <summary>The classifier for error responses: see
    /// <see cref="Tm1SessionBuilder.ErrorClassifier"/>.</summary>
    public Tm1ClientBuilder ErrorClassifier(Tm1ErrorClassifier classifier) => Do(() => _session.ErrorClassifier(classifier));

    /// <summary>The HTTP handler to send through: see <see cref="Tm1SessionBuilder.HttpHandler"/>,
    /// which says what a handler needs for names outside ASCII.</summary>
    public Tm1ClientBuilder HttpHandler(System.Net.Http.HttpMessageHandler handler) => Do(() => _session.HttpHandler(handler));

    /// <summary>Whether requests check the server's <c>$metadata</c> for what they need: see
    /// <see cref="Tm1SessionBuilder.CheckCapabilities"/>.</summary>
    public Tm1ClientBuilder CheckCapabilities(bool check) => Do(() => _session.CheckCapabilities(check));

    /// <summary>The client. Nothing is sent to TM1 until the first request.</summary>
    public Tm1Client Build() => Tm1Client.Of(_session.Build());

    private Tm1ClientBuilder Do(Func<Tm1SessionBuilder> change)
    {
        change();
        return this;
    }
}
