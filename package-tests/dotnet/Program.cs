// SPDX-License-Identifier: Apache-2.0

// Runs the installed packages, as someone who got them from nuget.org would: the guide's Hello
// World against a fake TM1 on localhost, and a type of the testcontainers package. Its argument
// is the repository's root. See package-tests/README.md.
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Testcontainers;

var repo = args.Length > 0 ? args[0] : throw new ArgumentException("usage: Smoke <repository root>");
var snapshot = File.ReadAllBytes(Path.Combine(repo, "java", "tm1-client", "src", "model", "metadata", "tm1-schema-11.8.35.xml"));

int port;
using (var probe = new TcpListener(IPAddress.Loopback, 0))
{
    probe.Start();
    port = ((IPEndPoint)probe.LocalEndpoint).Port;
}

// Answers what Hello World asks: a login, the version, $metadata and the cubes
var requests = new List<string>();
using var listener = new HttpListener();
listener.Prefixes.Add($"http://127.0.0.1:{port}/");
listener.Start();
var serving = Task.Run(async () =>
{
    while (listener.IsListening)
    {
        HttpListenerContext context;
        try
        {
            context = await listener.GetContextAsync();
        }
        catch (Exception e) when (e is HttpListenerException or ObjectDisposedException)
        {
            return;
        }
        var path = context.Request.RawUrl ?? "";
        lock (requests)
        {
            requests.Add($"{context.Request.HttpMethod} {path}");
        }
        byte[] body = [];
        var response = context.Response;
        response.ContentType = "application/json";
        if (context.Request.HttpMethod == "POST")
        {
            response.StatusCode = 204;
        }
        else if (path.StartsWith("/api/v1/ActiveSession", StringComparison.Ordinal))
        {
            response.Headers.Add("Set-Cookie", "TM1SessionId=s1; Path=/api/; HttpOnly");
            body = Encoding.UTF8.GetBytes("{\"ID\":1}");
        }
        else if (path.StartsWith("/api/v1/Server", StringComparison.Ordinal))
        {
            body = Encoding.UTF8.GetBytes("{\"ProductVersion\":\"11.8.03500.6\"}");
        }
        else if (path == "/api/v1/$metadata")
        {
            response.ContentType = "application/xml";
            body = snapshot;
        }
        else if (path.StartsWith("/api/v1/ModelCubes()", StringComparison.Ordinal) || path.StartsWith("/api/v1/Cubes", StringComparison.Ordinal))
        {
            body = Encoding.UTF8.GetBytes("{\"value\":[{\"Name\":\"Sales\"},{\"Name\":\"Plan\"}]}");
        }
        else
        {
            response.StatusCode = 404;
        }
        response.ContentLength64 = body.Length;
        await response.OutputStream.WriteAsync(body);
        response.Close();
    }
});

// The guide's Hello World (dotnet/examples/Tm1.Client.Examples/HelloWorld.cs)
string version;
var cubes = new List<string?>();
await using (var tm1 = Tm1Client.Builder().BaseUrl($"http://127.0.0.1:{port}/api/v1/").BasicAuth("admin", "").Build())
{
    version = (await tm1.Session.VersionAsync()).ToString() ?? "";
    await foreach (var cube in tm1.Cubes.ModelCubesAsync(ODataQuery.None().Select(Cube.Fields.Name)))
    {
        cubes.Add(cube.Name);
    }
}
listener.Stop();
await serving;

static void Require(bool condition, string message)
{
    if (!condition)
    {
        throw new InvalidOperationException(message);
    }
}

Require(version.StartsWith("11.8", StringComparison.Ordinal), version);
Require(string.Join(",", cubes) == "Sales,Plan", string.Join(",", cubes));
Require(requests.Exists(r => r.StartsWith("POST /api/v1/ActiveSession/tm1.Close", StringComparison.Ordinal)), string.Join("\n", requests));

// tm1-testcontainers: a type of it loads, with what it depends on
Require(typeof(Tm1ServerContainer).Assembly.GetName().Name == "Skipcheck.Tm1.Testcontainers", "Tm1ServerContainer's assembly");
var packages = $"{typeof(Tm1Client).Assembly.GetName().Name} {typeof(Tm1Client).Assembly.GetName().Version}, {typeof(Tm1Source).Assembly.GetName().Name} {typeof(Tm1Source).Assembly.GetName().Version}";
Console.WriteLine($"ok: Hello from TM1 {version}, cubes {string.Join(",", cubes)}, packages {packages}");
