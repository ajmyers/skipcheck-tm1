using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Processes (TurboIntegrator): <c>/Processes</c>, compiling and running them, and their
/// error logs.
/// <para>Parameters go as a dictionary of name to value, in order; a numeric parameter needs a
/// number, not text. Running a process returns when it ends, however long that takes: give the
/// request a timeout that fits, and know that cancelling the call doesn't stop the process on the
/// server (cancel its thread with <c>ThreadService.CancelAsync</c>, or run it through
/// <c>Tm1Client.WithAsync</c>, where cancelling does).</para>
/// <para>Create a process, compiling it first, since TM1 saves code that doesn't compile:</para>
/// <code>
/// var process = ProcessPatch.Builder()
///     .Name("Load Sales")
///     .Parameters([
///         ProcessParameterPatch.Builder().Name("pVersion").Value("Actual").Type(ProcessVariableType.String).Build(),
///         ProcessParameterPatch.Builder().Name("pYear").Value(2026).Type(ProcessVariableType.Numeric).Build()])
///     .PrologProcedure("LogOutput('INFO', 'Loading ' | pVersion | ' for ' | NumberToString(pYear));\n")
///     .Build();
/// var errors = await tm1.Processes.CompileAsync(process).ToListAsync();
/// if (errors.Count == 0)
/// {
///     await tm1.Processes.CreateAsync(process);
/// }
/// </code>
/// <para>Run it with parameters, and fail if it wrote an error log:</para>
/// <code>
/// var result = await tm1.Processes.ExecuteWithReturnAsync("Load Sales",
///     new Dictionary&lt;string, object?&gt; { ["pVersion"] = "Forecast", ["pYear"] = 2026 },
///     ODataQuery.None().Expand(ProcessExecuteResult.Fields.ErrorLogFile));
/// if (result.ErrorLogFile is { } log)
/// {
///     throw new InvalidOperationException($"{result.ProcessExecuteStatusCode}: see {log.Filename}");
/// }
/// </code>
/// <para>Run code without saving it as a process:</para>
/// <code>
/// var cleanup = ProcessPatch.Builder().Name("Clear Sales Staging").PrologProcedure("CubeClearData('Sales Staging');\n").Build();
/// await tm1.Processes.ExecuteWithReturnAsync(cleanup);
/// </code>
/// </summary>
public sealed class ProcessService : Tm1Service
{
    private static readonly ODataPath Processes = ODataPath.Of("Processes");

    public ProcessService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>GET Processes</c>, streamed.</summary>
    [Tm1Endpoint("EntitySet Processes")]
    public IAsyncEnumerable<Process> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<Process>(Processes, query), cancellationToken);

    /// <summary><c>GET Processes('name')</c>. TM1 (11.8) leaves most of <c>DataSource</c>, and
    /// <c>UIData</c> and <c>VariablesUIData</c>, out of a <c>$select=*</c>: select them by name, or
    /// select nothing, to get them.</summary>
    [Tm1Endpoint("EntitySet Processes")]
    public Task<Process> GetAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Process>(ProcessPath(name), query), cancellationToken);

    /// <summary><c>POST Processes</c>: creates a process; it needs a name. TM1 (11.8) saves code that
    /// doesn't compile: check it with <see cref="CompileAsync(ProcessPatch, CancellationToken)"/> first.</summary>
    [Tm1Endpoint("EntitySet Processes")]
    public Task<Process> CreateAsync(ProcessPatch process, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<Process>(Processes, Require(process, nameof(process))), cancellationToken);

    /// <summary><c>PATCH Processes('name')</c>: changes the properties the patch sets, such as a
    /// procedure. With <paramref name="etag"/> (<c>Process.ETag</c>), sent as <c>If-Match</c>: only if
    /// the process hasn't changed since, else a <c>Tm1PreconditionFailedException</c>. TM1 (11.8) saves
    /// code that doesn't compile.</summary>
    [Tm1Endpoint("EntitySet Processes")]
    public async Task UpdateAsync(string name, ProcessPatch patch, string? etag = null, CancellationToken cancellationToken = default)
    {
        var request = Tm1Request.Patch(ProcessPath(name), Require(patch, nameof(patch)));
        await Session.ExecuteAsync(etag is null ? request : request.IfMatch(etag), cancellationToken).ConfigureAwait(false);
    }

    /// <summary><c>DELETE Processes('name')</c>.</summary>
    [Tm1Endpoint("EntitySet Processes")]
    public Task DeleteAsync(string name, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(ProcessPath(name)), cancellationToken);

    /// <summary><c>tm1.Compile</c>: compiles a saved process: the syntax errors, by procedure and line;
    /// none if it compiles. Needs schema 11.0.0.</summary>
    [Tm1Endpoint("Action Process/Compile")]
    public IAsyncEnumerable<ProcessSyntaxError> CompileAsync(string name, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ProcessCompile;
        var request = Tm1Request.PostList<ProcessSyntaxError>(ProcessPath(name).Action(operation.Name), new Dictionary<string, object?>());
        return Session.StreamAsync(request.Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>CompileProcess</c>: compiles a process definition that needn't be saved, such as one
    /// about to be created. Needs schema 11.0.0.</summary>
    [Tm1Endpoint("ActionImport CompileProcess")]
    public IAsyncEnumerable<ProcessSyntaxError> CompileAsync(ProcessPatch definition, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.CompileProcess;
        var body = new Dictionary<string, object?> { ["Process"] = Require(definition, nameof(definition)) };
        return Session.StreamAsync(Tm1Request.PostList<ProcessSyntaxError>(Root(operation), body).Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>tm1.ExecuteWithReturn</c>: runs a process and says how it ended
    /// (<c>ProcessExecuteStatusCode</c>, and the error log's file if it wrote one: expand
    /// <c>ErrorLogFile</c> to see it). Needs schema 11.3.0. A <c>ProcessError</c>, or a runtime error,
    /// ends it <c>Aborted</c>, with an <c>ErrorLogFile</c> named
    /// <c>tm1processerror_&lt;time&gt;_&lt;process&gt;.log</c>. A run cancelled through its thread
    /// (<c>ThreadService.CancelAsync</c>) fails instead, with a <c>Tm1ServerException</c>
    /// (<c>TM1UserException: Cancel</c>, code 174).</summary>
    [Tm1Endpoint("Action Process/ExecuteWithReturn")]
    [Tm1Endpoint("Navigation ProcessExecuteResult/ErrorLogFile")]
    public Task<ProcessExecuteResult> ExecuteWithReturnAsync(string name, IReadOnlyDictionary<string, object?>? parameters = null, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ProcessExecuteWithReturn;
        var request = Tm1Request.Post<ProcessExecuteResult>(ProcessPath(name).Action(operation.Name), ProcessFormats.WithParameters(new(), parameters));
        return Session.ExecuteAsync(request.WithQuery(query).Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>ExecuteProcessWithReturn</c>: runs a process definition that needn't be saved, and
    /// says how it ended. Needs schema 11.3.0.</summary>
    [Tm1Endpoint("ActionImport ExecuteProcessWithReturn")]
    [Tm1Endpoint("Navigation ProcessExecuteResult/ErrorLogFile")]
    public Task<ProcessExecuteResult> ExecuteWithReturnAsync(ProcessPatch definition, IReadOnlyDictionary<string, object?>? parameters = null, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ExecuteProcessWithReturn;
        var body = ProcessFormats.WithParameters(new() { ["Process"] = Require(definition, nameof(definition)) }, parameters);
        return Session.ExecuteAsync(Tm1Request.Post<ProcessExecuteResult>(Root(operation), body).WithQuery(query).Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>tm1.Execute</c>: runs a process, without saying how it ended. Deprecated in TM1
    /// schema 11.3.0: use <c>ExecuteWithReturnAsync</c>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.3.0: use ExecuteWithReturnAsync.")]
    [Tm1Endpoint("Action Process/Execute")]
    public Task ExecuteAsync(string name, IReadOnlyDictionary<string, object?>? parameters = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post(ProcessPath(name).Action(Tm1Operations.ProcessExecute.Name), ProcessFormats.WithParameters(new(), parameters)), cancellationToken);

    /// <summary><c>ExecuteProcess</c>: runs a process definition, without saying how it ended. Needs
    /// schema 11.0.0; deprecated in 11.3.0: use <c>ExecuteWithReturnAsync</c>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.3.0: use ExecuteWithReturnAsync.")]
    [Tm1Endpoint("ActionImport ExecuteProcess")]
    public Task ExecuteAsync(ProcessPatch definition, IReadOnlyDictionary<string, object?>? parameters = null, CancellationToken cancellationToken = default)
    {
        var body = ProcessFormats.WithParameters(new() { ["Process"] = Require(definition, nameof(definition)) }, parameters);
        return Session.ExecuteAsync(Tm1Request.Post(Root(Tm1Operations.ExecuteProcess), body), cancellationToken);
    }

    /// <summary><c>GET Processes('name')/ErrorLogs</c>, streamed: the logs its failed runs wrote, keyed
    /// by their timestamps. Deprecated in TM1 schema 11.3.0, with <c>ProcessErrorLog</c>: a run's log is
    /// the <c>ErrorLogFile</c> that <c>ExecuteWithReturnAsync</c> returns.</summary>
    [Obsolete("Deprecated in TM1 schema 11.3.0, with ProcessErrorLog: a run's log is the ErrorLogFile that ExecuteWithReturnAsync returns.")]
    [Tm1Endpoint("Navigation Process/ErrorLogs")]
    public IAsyncEnumerable<ProcessErrorLog> ErrorLogsAsync(string name, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ProcessErrorLog>(ProcessPath(name).Nav("ErrorLogs"), query), cancellationToken);

    /// <summary><c>GET Processes('name')/ErrorLogs('timestamp')/Content</c>: an error log's text. TM1
    /// keys error logs by their timestamp as a quoted string, as it lists it.</summary>
    [Obsolete("Deprecated in TM1 schema 11.3.0, with ProcessErrorLog: a run's log is the ErrorLogFile that ExecuteWithReturnAsync returns.")]
    [Tm1Endpoint("Navigation Process/ErrorLogs")]
    public Task<string> ErrorLogContentAsync(string name, ProcessErrorLog log, CancellationToken cancellationToken = default)
    {
        var timestamp = Require(log, nameof(log)).Timestamp ?? throw new ArgumentException("the log's timestamp is missing", nameof(log));
        var key = new ODataLiterals.Raw(ODataLiterals.Of(ProcessFormats.DateTime(timestamp)));
        return Session.ExecuteAsync(Tm1Request.Text(ProcessPath(name).Nav("ErrorLogs").Key(key).Nav("Content")), cancellationToken);
    }

    private static ODataPath Root(Tm1Operation operation) => ODataPath.Of(operation.ImportName!);

    private static ODataPath ProcessPath(string name) => Processes.Key(Require(name, nameof(name)));
}
