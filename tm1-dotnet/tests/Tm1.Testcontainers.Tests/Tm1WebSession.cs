using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace AjMyers.Tm1.Testcontainers.Tests;

/// <summary>A browser's session with TM1 Web, as Java's test Tm1WebSession: TM1 Web's DWR calls
/// (list servers, log on) and its evalservice.</summary>
public sealed partial class Tm1WebSession : IDisposable
{
    private readonly CookieContainer cookies = new();
    private readonly HttpClient http;
    private readonly Uri root;
    private string scriptSessionId = "";
    private int batch;

    private Tm1WebSession(Tm1WebContainer web)
    {
        root = new Uri(web.Url);
        http = new HttpClient(new HttpClientHandler { CookieContainer = cookies }) { Timeout = TimeSpan.FromSeconds(60) };
    }

    public static async Task<Tm1WebSession> OpenAsync(Tm1WebContainer web)
    {
        var session = new Tm1WebSession(web);
        (await session.http.GetAsync(session.root)).Dispose();
        var id = (await session.CallAsync("__System", "generateId")).Trim().Trim('"');
        session.cookies.Add(session.root, new Cookie("DWRSESSIONID", id, session.root.AbsolutePath.TrimEnd('/')));
        session.scriptSessionId = id + "/" + Guid.NewGuid();
        return session;
    }

    public async Task<List<string>> ServersAsync(string adminHost) =>
        [.. Name().Matches(await CallAsync("TM1Service", "getTM1Servers", "string:" + adminHost)).Select(m => m.Groups[1].Value)];

    public async Task<string> LoginAsync(string adminHost, string server, string username, string password)
    {
        var result = await CallAsync("TM1Service", "login", "string:" + adminHost, "string:" + server, "string:" + username, "string:" + password);
        var m = SessionId().Match(result);
        return m.Success ? m.Groups[1].Value : throw new InvalidOperationException("TM1 Web logged on but sent no TM1 session ID: " + result);
    }

    public async Task<string> EvaluateMdxAsync(string serviceRoot, string server, string tm1SessionId, string mdx)
    {
        var token = "{\"host\":" + Json(serviceRoot) + ",\"type\":\"tm1\",\"id\":" + Json(tm1SessionId) + "}";
        var mapping = "{\"server\":" + Json(server) + ",\"host\":" + Json(serviceRoot) + ",\"datasource\":" + Json(server) + "}";
        var entry = "{\"type\":\"mdx\",\"id\":\"1\",\"server\":" + Json(server) + ",\"args\":[" + Json(mdx) + "]}";
        var body = "{\"tokens\":[" + token + "],\"mappings\":[" + mapping + "],\"workEntries\":[" + entry + "]}";
        using var content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await http.PostAsync(new Uri(root, "/evalservice/batch"), content);
        return await response.Content.ReadAsStringAsync();
    }

    public void Dispose() => http.Dispose();

    private static string Json(string value) => "\"" + value.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal) + "\"";

    private async Task<string> CallAsync(string script, string method, params string[] parameters)
    {
        batch++;
        var body = new StringBuilder("callCount=1\nnextReverseAjaxIndex=0\n");
        body.Append("c0-scriptName=").Append(script).Append("\nc0-methodName=").Append(method).Append("\nc0-id=0\n");
        for (var i = 0; i < parameters.Length; i++)
        {
            body.Append("c0-param").Append(i).Append('=').Append(parameters[i]).Append('\n');
        }
        body.Append("batchId=").Append(batch).Append("\ninstanceId=0\npage=").Append(root.AbsolutePath).Append('\n');
        body.Append("httpSessionId=").Append(cookies.GetCookies(root)["JSESSIONID"]?.Value ?? "").Append('\n');
        body.Append("scriptSessionId=").Append(scriptSessionId).Append('\n');
        using var content = new StringContent(body.ToString(), Encoding.UTF8, "text/plain");
        using var response = await http.PostAsync(new Uri(root, "dwr/call/plaincall/" + script + "." + method + ".dwr"), content);
        var text = await response.Content.ReadAsStringAsync();
        var result = Callback().Match(text);
        if (response.StatusCode == HttpStatusCode.OK && result.Success)
        {
            return result.Groups[1].Value;
        }
        var error = Error().Match(text);
        throw new InvalidOperationException($"{script}.{method} failed (HTTP {(int)response.StatusCode}): {(error.Success ? error.Groups[1].Value : text)}");
    }

    [GeneratedRegex("handleCallback\\(\"\\d+\",\"0\",(.*)\\);")]
    private static partial Regex Callback();

    [GeneratedRegex("handle(?:Batch)?Exception\\((.*?)\\);", RegexOptions.Singleline)]
    private static partial Regex Error();

    [GeneratedRegex("name:\"([^\"]*)\"")]
    private static partial Regex Name();

    [GeneratedRegex("tm1SessionId:\"([^\"]*)\"")]
    private static partial Regex SessionId();
}
