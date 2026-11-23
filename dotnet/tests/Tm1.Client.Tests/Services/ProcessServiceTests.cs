using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

#pragma warning disable CS0618 // the deprecated Execute and ErrorLogs are tested too

namespace Skipcheck.Tm1.Client.Tests.Services;

/// <summary>ProcessService against a fake TM1: each method sends what the Java client's does.</summary>
public class ProcessServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsGetsCreatesUpdatesAndDeletes()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { Name = "Load" });
        var tm1 = _tm1.Client();
        Assert.Equal("Load", Assert.Single(await tm1.Processes.ListAsync(ODataQuery.None().Select("Name")).ToListAsync()).Name);
        Assert.Equal("GET Processes?$select=Name", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => new Dictionary<string, object?> { ["Name"] = "Load", ["@odata.etag"] = "W/\"1\"" };
        Assert.Equal("W/\"1\"", (await tm1.Processes.GetAsync("Load")).ETag);
        Assert.Equal("Processes('Load')", _tm1.Last.Target);
        await tm1.Processes.CreateAsync(ProcessPatch.Builder().Name("Load").PrologProcedure("x=1;").Build());
        Assert.Equal(("POST", "Processes", """{"Name":"Load","PrologProcedure":"x=1;"}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => null;
        await tm1.Processes.UpdateAsync("Load", ProcessPatch.Builder().PrologProcedure("x=2;").Build());
        Assert.Equal(("PATCH", "Processes('Load')"), (_tm1.Last.Method, _tm1.Last.Target));
        Assert.False(_tm1.Last.Headers.ContainsKey("if-match"));
        await tm1.Processes.UpdateAsync("Load", ProcessPatch.Builder().PrologProcedure("x=2;").Build(), "W/\"1\"");
        Assert.Equal("W/\"1\"", _tm1.Last.Headers["if-match"]);
        await tm1.Processes.DeleteAsync("Load");
        Assert.Equal(("DELETE", "Processes('Load')"), (_tm1.Last.Method, _tm1.Last.Target));
    }

    [Fact]
    public async Task CompilesByNameOrDefinition()
    {
        _tm1.Respond = _ => FakeTm1.Collection(new { LineNumber = 3, Message = "bad" });
        var tm1 = _tm1.Client();
        var errors = await tm1.Processes.CompileAsync("Load").ToListAsync();
        Assert.Equal(3, Assert.Single(errors).LineNumber);
        Assert.Equal(("POST", "Processes('Load')/tm1.Compile", "{}"), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => FakeTm1.Collection();
        Assert.Empty(await tm1.Processes.CompileAsync(ProcessPatch.Builder().Name("x").Build()).ToListAsync());
        Assert.Equal(("POST", "CompileProcess", """{"Process":{"Name":"x"}}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
    }

    [Fact]
    public async Task ExecutesWithReturnAndParametersInOrder()
    {
        _tm1.Respond = _ => new Dictionary<string, object?> { ["ProcessExecuteStatusCode"] = "CompletedSuccessfully", ["ErrorLogFile"] = null };
        var tm1 = _tm1.Client();
        var parameters = new Dictionary<string, object?> { ["pYear"] = 2026, ["pVersion"] = "Actual" };
        var result = await tm1.Processes.ExecuteWithReturnAsync("Load", parameters, ODataQuery.None().Expand("ErrorLogFile"));
        Assert.Equal(ProcessExecuteStatusCode.CompletedSuccessfully, result.ProcessExecuteStatusCode);
        Assert.Null(result.ErrorLogFile);
        Assert.Equal("Processes('Load')/tm1.ExecuteWithReturn?$expand=ErrorLogFile", _tm1.Last.Target);
        Assert.Equal("""{"Parameters":[{"Name":"pYear","Value":2026},{"Name":"pVersion","Value":"Actual"}]}""", _tm1.Last.Text);

        await tm1.Processes.ExecuteWithReturnAsync("Load");
        Assert.Equal(("Processes('Load')/tm1.ExecuteWithReturn", "{}"), (_tm1.Last.Target, _tm1.Last.Text));

        await tm1.Processes.ExecuteWithReturnAsync(ProcessPatch.Builder().Name("adhoc").Build(), new Dictionary<string, object?> { ["p"] = "v" });
        Assert.Equal("ExecuteProcessWithReturn", _tm1.Last.Target);
        Assert.Equal("""{"Process":{"Name":"adhoc"},"Parameters":[{"Name":"p","Value":"v"}]}""", _tm1.Last.Text);
    }

    [Fact]
    public async Task TheDeprecatedExecuteAndErrorLogs()
    {
        _tm1.Respond = _ => null;
        var tm1 = _tm1.Client();
        await tm1.Processes.ExecuteAsync("Load", new Dictionary<string, object?> { ["p"] = 1 });
        Assert.Equal(
            ("POST", "Processes('Load')/tm1.Execute", """{"Parameters":[{"Name":"p","Value":1}]}"""),
            (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text)
        );
        await tm1.Processes.ExecuteAsync(ProcessPatch.Builder().Name("adhoc").Build());
        Assert.Equal(("ExecuteProcess", """{"Process":{"Name":"adhoc"}}"""), (_tm1.Last.Target, _tm1.Last.Text));

        _tm1.Respond = _ => FakeTm1.Collection(new { Timestamp = "2026-01-02T03:04:05Z" });
        var log = Assert.Single(await tm1.Processes.ErrorLogsAsync("Load").ToListAsync());
        Assert.Equal("Processes('Load')/ErrorLogs", _tm1.Last.Target);

        _tm1.Respond = _ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("line 1", Encoding.UTF8, "text/plain") };
        Assert.Equal("line 1", await tm1.Processes.ErrorLogContentAsync("Load", log));
        // Keyed by the timestamp as Java's OffsetDateTime writes it, quoted
        Assert.Equal("Processes('Load')/ErrorLogs('2026-01-02T03:04:05Z')/Content", Uri.UnescapeDataString(_tm1.Last.Target));
        await Assert.ThrowsAsync<ArgumentException>(() => tm1.Processes.ErrorLogContentAsync("Load", new ProcessErrorLog()));
    }

    [Fact]
    public void JavaFormatsForDatesAndTimes()
    {
        Assert.Equal("2026-01-02", Skipcheck.Tm1.Client.Services.ProcessFormats.Date(new DateOnly(2026, 1, 2)));
        Assert.Equal("02:00", Skipcheck.Tm1.Client.Services.ProcessFormats.Time(new TimeOnly(2, 0)));
        Assert.Equal("02:00:30", Skipcheck.Tm1.Client.Services.ProcessFormats.Time(new TimeOnly(2, 0, 30)));
        Assert.Equal("2026-01-02T03:04Z", Skipcheck.Tm1.Client.Services.ProcessFormats.DateTime(new DateTimeOffset(2026, 1, 2, 3, 4, 0, TimeSpan.Zero)));
        Assert.Equal("02:00:30.500", Skipcheck.Tm1.Client.Services.ProcessFormats.Time(new TimeOnly(2, 0, 30, 500)));
    }
}
