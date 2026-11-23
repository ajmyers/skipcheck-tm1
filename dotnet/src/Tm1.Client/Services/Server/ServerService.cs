using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>The server: what it is (<c>Server</c>), how it's configured, and its loggers. The static
/// configuration is <c>tm1s.cfg</c>'s, and the one to change; the active one is what the server runs
/// with now, read only.
/// <para>Read the server's name and version:</para>
/// <code>
/// var server = await tm1.Server.ServerAsync(ODataQuery.None().Select(Server.Fields.Name, Server.Fields.ProductVersion));
/// </code>
/// <para>Read a setting the server runs with now:</para>
/// <code>
/// var settings = await tm1.Server.ActiveConfigurationAsync(ODataQuery.None().Select("Administration/DataBaseDirectory"));
/// </code>
/// <para>Change a logger's level:</para>
/// <code>await tm1.Server.SetLoggerLevelAsync("TM1.Server", LogLevel.Debug);</code>
/// </summary>
public sealed class ServerService : Tm1Service
{
    private static readonly ODataPath Loggers = ODataPath.Of("Loggers");

    public ServerService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET Server</c>: the server's name, version and ports. TM1 (11.8) sends no ETag for it.</summary>
    [Tm1Endpoint("Singleton Server")]
    public Task<Server> ServerAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Server>(ODataPath.Of("Server"), query), cancellationToken);

    /// <summary><c>GET StaticConfiguration</c>: the settings the server started with, from <c>tm1s.cfg</c>.</summary>
    [Tm1Endpoint("Singleton StaticConfiguration")]
    public Task<ServerSettings> StaticConfigurationAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ServerSettings>(ODataPath.Of("StaticConfiguration"), query), cancellationToken);

    /// <summary><c>PATCH StaticConfiguration</c>: changes <c>tm1s.cfg</c>; most settings take effect when
    /// the server next starts.</summary>
    [Tm1Endpoint("Singleton StaticConfiguration")]
    public Task UpdateStaticConfigurationAsync(ServerSettingsPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(ODataPath.Of("StaticConfiguration"), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>GET ActiveConfiguration</c>: the settings the server runs with now. Read only: TM1
    /// (11.8) refuses a PATCH here ("PATCH not allowed on this resource"); settings change through
    /// <see cref="UpdateStaticConfigurationAsync"/>.</summary>
    [Tm1Endpoint("Singleton ActiveConfiguration")]
    public Task<ServerSettings> ActiveConfigurationAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ServerSettings>(ODataPath.Of("ActiveConfiguration"), query), cancellationToken);

    /// <summary><c>GET Configuration</c>: the server's configuration in the older, flat form.
    /// Deprecated in TM1 schema 11.0.0: use <see cref="StaticConfigurationAsync"/> and
    /// <see cref="ActiveConfigurationAsync"/>.</summary>
    [System.Obsolete("Deprecated in TM1 schema 11.0.0: use StaticConfigurationAsync and ActiveConfigurationAsync")]
    [Tm1Endpoint("Singleton Configuration")]
    public Task<Configuration> ConfigurationAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Configuration>(ODataPath.Of("Configuration"), query), cancellationToken);

    /// <summary><c>GET Loggers</c>, streamed: the server's loggers and their levels.</summary>
    [Tm1Endpoint("EntitySet Loggers")]
    public IAsyncEnumerable<Logger> LoggersAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Logger>(Loggers, query), cancellationToken);

    /// <summary><c>GET Loggers('name')</c>.</summary>
    [Tm1Endpoint("EntitySet Loggers")]
    public Task<Logger> LoggerAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Logger>(Loggers.Key(Require(name, nameof(name))), query), cancellationToken);

    /// <summary><c>PATCH Loggers('name')</c>: changes a logger's level, at once.</summary>
    [Tm1Endpoint("EntitySet Loggers")]
    public Task SetLoggerLevelAsync(string name, LogLevel level, CancellationToken cancellationToken = default)
    {
        // As the Java client's: a level this client doesn't know is a bad argument, and isn't sent
        var json = Tm1EnumConverter.Describe(typeof(LogLevel))!.ByValue.TryGetValue(level, out var known)
            ? known
            : throw new System.ArgumentException($"LogLevel.{level} stands for a level this client doesn't know, and isn't sent", nameof(level));
        var body = new Dictionary<string, object?> { ["Level"] = json };
        return Session.ExecuteAsync(Tm1Request.Patch(Loggers.Key(Require(name, nameof(name))), body), cancellationToken);
    }
}
