using System;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>The server's logs: the message log, the transaction log (cell changes), the audit log
/// (changes to objects and security), and the error logs processes write.
/// <para>What TM1 (11.8) does here that you might not expect:</para>
/// <list type="bullet">
/// <item>An entry's <c>ID</c> numbers it within the response, not in the log: the same entry gets
/// another ID in the next request, and TM1 refuses to fetch an entry by its ID (501, "Indexing of
/// message log entries is not supported"). So there's no get by ID here.</item>
/// <item>A message log entry without a time, such as the log's first line, has the <c>TimeStamp</c>
/// <c>0000-00-00T00:00Z</c>; it's read as null.</item>
/// <item>The transaction and audit logs only fill when they're switched on (a cube's logging, and
/// <c>AuditLog</c> in the configuration).</item>
/// </list>
/// <para>Read the newest message log entries:</para>
/// <code>await foreach (var entry in tm1.Logs.MessageLogAsync(true, ODataQuery.None().Top(10))) { … }</code>
/// <para>Follow the message log, until cancelled:</para>
/// <code>await foreach (var entry in tm1.Logs.TailMessageLogAsync(TimeSpan.FromSeconds(5), cancellationToken)) { … }</code>
/// <para>Read a process's error logs:</para>
/// <code>
/// await foreach (var file in tm1.Logs.ErrorLogFilesAsync(ODataQuery.None().Filter(Filter.Contains(ErrorLogFile.Fields.Filename, "Load"))))
/// {
///     Console.WriteLine(await tm1.Logs.ErrorLogContentAsync(file.Filename!));
/// }
/// </code>
/// </summary>
public sealed class LogService : Tm1Service
{
    private const string ByteOrderMark = "﻿";
    private static readonly ODataPath ErrorLogFiles = ODataPath.Of("ErrorLogFiles");

    public LogService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET MessageLogEntries</c>, streamed, oldest first unless ordered otherwise.</summary>
    [Tm1Endpoint("EntitySet MessageLogEntries")]
    public IAsyncEnumerable<MessageLogEntry> MessageLogEntriesAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<MessageLogEntry>(ODataPath.Of("MessageLogEntries"), query), cancellationToken);

    /// <summary><c>MessageLog(Reverse=…)</c>: the message log, streamed, newest first if <paramref name="reverse"/>.</summary>
    [Tm1Endpoint("FunctionImport MessageLog")]
    public IAsyncEnumerable<MessageLogEntry> MessageLogAsync(bool reverse, ODataQuery? query = null, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.MessageLog;
        var path = ODataPath.FunctionImport(operation.ImportName!, new Dictionary<string, object?> { ["Reverse"] = reverse });
        return Session.StreamAsync(Tm1Request.List<MessageLogEntry>(path, query).Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>GET TransactionLogEntries</c>, streamed. Besides cell changes, the log holds status
    /// lines (such as <c>CubeSerialized: …</c>) with only <c>StatusMessage</c> set.</summary>
    [Tm1Endpoint("EntitySet TransactionLogEntries")]
    public IAsyncEnumerable<TransactionLogEntry> TransactionLogEntriesAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<TransactionLogEntry>(ODataPath.Of("TransactionLogEntries"), query), cancellationToken);

    /// <summary><c>TransactionLog(Reverse=…,IgnoreStatusMessages=…)</c>: the transaction log, streamed.</summary>
    [Tm1Endpoint("FunctionImport TransactionLog")]
    public IAsyncEnumerable<TransactionLogEntry> TransactionLogAsync(
        bool reverse,
        bool ignoreStatusMessages,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.TransactionLog;
        var parameters = new Dictionary<string, object?> { ["Reverse"] = reverse, ["IgnoreStatusMessages"] = ignoreStatusMessages };
        var path = ODataPath.FunctionImport(operation.ImportName!, parameters);
        return Session.StreamAsync(Tm1Request.List<TransactionLogEntry>(path, query).Requires(operation.Capability()), cancellationToken);
    }

    /// <summary><c>GET AuditLogEntries</c>, streamed; empty unless the audit log is on. Expand
    /// <c>AuditDetails</c> for each entry's details.</summary>
    [Tm1Endpoint("EntitySet AuditLogEntries")]
    [Tm1Endpoint("Navigation AuditLogEntry/AuditDetails")]
    public IAsyncEnumerable<AuditLogEntry> AuditLogEntriesAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<AuditLogEntry>(ODataPath.Of("AuditLogEntries"), query), cancellationToken);

    /// <summary>Follows the message log: the entries written from the moment of enumerating, polling
    /// every <paramref name="every"/> until the caller stops or cancels. Built on
    /// <c>TailMessageLog()</c> (schema 11.3.0). <c>TailMessageLog()</c> returns a delta link, but TM1
    /// (11.8) answers it with every entry since the link was made, each time, and with no next link.
    /// So this keeps the one link and skips the entries it has already yielded, and each poll fetches
    /// more the longer it runs. For a long watch, start afresh now and then.</summary>
    [Tm1Endpoint("FunctionImport TailMessageLog")]
    public IAsyncEnumerable<MessageLogEntry> TailMessageLogAsync(TimeSpan every, CancellationToken cancellationToken = default) =>
        Tail<MessageLogEntry>(Tm1Operations.TailMessageLog, every, cancellationToken);

    /// <summary>Follows the transaction log, as <see cref="TailMessageLogAsync"/> does the message log.
    /// Built on <c>TailTransactionLog()</c> (schema 11.3.0).</summary>
    [Tm1Endpoint("FunctionImport TailTransactionLog")]
    public IAsyncEnumerable<TransactionLogEntry> TailTransactionLogAsync(TimeSpan every, CancellationToken cancellationToken = default) =>
        Tail<TransactionLogEntry>(Tm1Operations.TailTransactionLog, every, cancellationToken);

    /// <summary>Follows the audit log, as <see cref="TailMessageLogAsync"/> does the message log. Built
    /// on <c>TailAuditLog()</c> (schema 11.6.0).</summary>
    [Tm1Endpoint("FunctionImport TailAuditLog")]
    public IAsyncEnumerable<AuditLogEntry> TailAuditLogAsync(TimeSpan every, CancellationToken cancellationToken = default) =>
        Tail<AuditLogEntry>(Tm1Operations.TailAuditLog, every, cancellationToken);

    /// <summary><c>GET ErrorLogFiles</c>, streamed: the error logs processes have written, such as
    /// <c>tm1processerror_&lt;time&gt;_&lt;process&gt;.log</c>.</summary>
    [Tm1Endpoint("EntitySet ErrorLogFiles")]
    public IAsyncEnumerable<ErrorLogFile> ErrorLogFilesAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ErrorLogFile>(ErrorLogFiles, query), cancellationToken);

    /// <summary><c>GET ErrorLogFiles('name')</c>.</summary>
    [Tm1Endpoint("EntitySet ErrorLogFiles")]
    public Task<ErrorLogFile> ErrorLogFileAsync(string filename, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ErrorLogFile>(ErrorLogFiles.Key(Require(filename, nameof(filename))), query), cancellationToken);

    /// <summary><c>GET ErrorLogFiles('name')/Content</c>: what the file says, as text, read whole. TM1
    /// (11.8) starts it with a byte order mark, which is dropped.</summary>
    [Tm1Endpoint("EntitySet ErrorLogFiles")]
    public async Task<string> ErrorLogContentAsync(string filename, CancellationToken cancellationToken = default)
    {
        var path = ErrorLogFiles.Key(Require(filename, nameof(filename))).Nav("Content");
        var text = await Session.ExecuteAsync(Tm1Request.Text(path), cancellationToken).ConfigureAwait(false);
        return text.StartsWith(ByteOrderMark, StringComparison.Ordinal) ? text[1..] : text;
    }

    private async IAsyncEnumerable<T> Tail<T>(Tm1Operation operation, TimeSpan every, [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var request = Tm1Request.Get<JsonElement>(ODataPath.FunctionImport(operation.ImportName!), null).Requires(operation.Capability());
        var answer = await Session.ExecuteAsync(request, cancellationToken).ConfigureAwait(false);
        var link = ODataPath.Link(answer.GetProperty("value").GetString()!);
        long seen = 0;
        while (true)
        {
            long index = 0;
            await foreach (var entry in Session.StreamAsync(Tm1Request.List<T>(link), cancellationToken).ConfigureAwait(false))
            {
                if (index++ >= seen)
                {
                    seen++;
                    yield return entry;
                }
            }
            await Task.Delay(every, cancellationToken).ConfigureAwait(false);
        }
    }
}
