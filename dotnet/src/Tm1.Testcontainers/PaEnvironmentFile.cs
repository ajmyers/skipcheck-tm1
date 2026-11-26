// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Text.RegularExpressions;
using YamlDotNet.Core;
using Map = System.Collections.Generic.OrderedDictionary<object, object?>;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>Reads pa-setup's <c>pa-environment.yaml</c> onto a <see cref="PlanningAnalyticsEnvironment.EnvironmentBuilder"/>,
/// as the Java <c>PaEnvironmentFile</c>. The checks and defaults are <c>docker/pa-setup/pa_setup.py</c>'s (<c>normalize</c>),
/// in its order and with its messages, so a file one accepts the other does too, except for what the environment can't run,
/// which fails here with a message naming the setting.</summary>
internal sealed partial class PaEnvironmentFile
{
    private static readonly string[] Seeds = ["empty", "sdata", "24retail"];

    private readonly string file;

    private PaEnvironmentFile(string file)
    {
        this.file = file;
    }

    /// <summary>Reads and checks the file, and configures a new builder from it.</summary>
    /// <exception cref="ArgumentException">It can't be read, isn't valid, or asks for what the environment can't run.</exception>
    internal static PlanningAnalyticsEnvironment.EnvironmentBuilder Read(string path)
    {
        var reader = new PaEnvironmentFile(Path.GetFileName(path));
        var absolute = Settings.Absolute(path);
        object? cfg;
        try
        {
            cfg = Yaml11.Load(File.ReadAllText(absolute, System.Text.Encoding.UTF8));
        }
        catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException)
        {
            throw reader.Error("not found: " + absolute);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw reader.Error("can't be read: " + e.Message);
        }
        catch (YamlException e)
        {
            throw reader.Error("isn't valid YAML: " + e.Message);
        }
        var dir = Path.GetDirectoryName(absolute) ?? throw new ArgumentException("No folder for " + absolute, nameof(path));
        return reader.Apply(cfg ?? new Map(), dir);
    }

    private PlanningAnalyticsEnvironment.EnvironmentBuilder Apply(object root, string dir)
    {
        // pa_setup.normalize: the same checks, in the same order
        var cfg = Keys(root, ["version", "name", "ibm", "files", "images", "tm1", "tm1Web", "paw", "cognos", "tm1Engine"], "the file");
        if (Get(cfg, "version") is not 1L)
        {
            throw Error("version: must be 1");
        }
        var ibm = Truthy(Get(cfg, "ibm")) ? HostPath(Get(cfg, "ibm"), "ibm", dir) : null;
        HostPath(Or(Get(cfg, "files"), "pa-files"), "files", dir);
        var images = Keys(Or(Get(cfg, "images"), new Map()), ["tm1Server", "tm1Web", "cognosServer", "keycloak", "tm1Engine", "mongo"], "images");

        var tm1 = Section(cfg, "tm1");
        var servers = new List<(string Name, Server Server)>();
        int? admin = null;
        if (tm1 is not null)
        {
            Keys(tm1, ["servers", "adminServer", "adminPort"], "tm1");
            if (Get(tm1, "servers") is not Map named || named.Count == 0)
            {
                throw Error("tm1.servers: name at least one server, e.g. tm1: {servers: {tm1: {}}}");
            }
            var usedPorts = new HashSet<int>();
            var lower = new HashSet<string>(StringComparer.Ordinal);
            var i = 0;
            foreach (var (key, value) in named)
            {
                var where = "tm1.servers." + Str(key);
                if (key is not string name || !Name().IsMatch(name))
                {
                    throw Error(where + ": server names are letters, digits, _ and -");
                }
                if (!lower.Add(name.ToLowerInvariant()))
                {
                    throw Error(where + ": two servers differ only in case");
                }
                var s = Keys(Or(value, new Map()), ["port", "seed", "cfg"], where);
                var p = Port(Get(s, "port"), where, 8010 + i);
                if (!usedPorts.Add(p))
                {
                    throw Error(where + ": port " + p + " is used twice");
                }
                var seed = Truthy(Get(s, "seed")) ? Str(Get(s, "seed")) : "empty";
                if (!Seeds.Contains(seed))
                {
                    throw Error(where + ".seed: one of " + string.Join(", ", Seeds));
                }
                var extra = Mapping(Or(Get(s, "cfg"), new Map()), where + ".cfg");
                var settings = new List<(string, string)>();
                foreach (var (settingKey, settingValue) in extra)
                {
                    var k = Str(settingKey);
                    if (!Name().IsMatch(k))
                    {
                        throw Error(where + ".cfg: '" + k + "' isn't a tm1s.cfg parameter name");
                    }
                    settings.Add((k, CfgValue(settingValue, where + ".cfg." + k)));
                }
                servers.Add((name, new Server(p, seed, settings)));
                i++;
            }
            var wantsAdmin = Get(tm1, "adminServer");
            if (wantsAdmin is true || (wantsAdmin is null && servers.Count > 1))
            {
                admin = Port(Get(tm1, "adminPort"), "tm1.adminPort", 5895);
            }
            else if (wantsAdmin is false && servers.Count > 1)
            {
                throw Error("tm1.adminServer: several servers need a shared Admin Server");
            }
        }

        var web = Section(cfg, "tm1Web");
        int? webPort = null;
        string? heap = null;
        if (web is not null)
        {
            Keys(web, ["port", "heap"], "tm1Web");
            if (servers.Count == 0)
            {
                throw Error("tm1Web: needs a TM1 server (tm1.servers)");
            }
            webPort = Port(Get(web, "port"), "tm1Web", 9510);
            heap = Truthy(Get(web, "heap")) ? Str(Get(web, "heap")) : "1g";
        }

        var cognos = Section(cfg, "cognos");
        Cognos? cam = null;
        if (cognos is not null)
        {
            Keys(cognos, ["port", "users", "oidc", "cam", "memory"], "cognos");
            var users = new List<CognosContainer.User>();
            if (Or(Get(cognos, "users"), new List<object?>()) is not List<object?> listed)
            {
                throw Error("cognos.users: a list of users, each user:password[:group,group]");
            }
            for (var u = 0; u < listed.Count; u++)
            {
                users.Add(User(listed[u], "cognos.users[" + u + "]"));
            }
            var names = users.Select(u => u.Name).ToList();
            if (names.Distinct(StringComparer.Ordinal).Count() != names.Count)
            {
                throw Error("cognos.users: a user is listed twice");
            }
            var oidc = Get(cognos, "oidc");
            if (oidc is true)
            {
                oidc = new Map();
            }
            int? keycloakPort = null;
            // On with true or a mapping, an empty one too: only absent or false is off
            if (oidc is not null and not false)
            {
                var o = Keys(oidc, ["keycloakPort"], "cognos.oidc");
                if (users.Count == 0)
                {
                    throw Error("cognos.oidc: needs cognos.users");
                }
                keycloakPort = Port(Get(o, "keycloakPort"), "cognos.oidc", 8180);
            }
            var camSection = Keys(Or(Get(cognos, "cam"), new Map()), ["namespace", "admins"], "cognos.cam");
            var ns = (Truthy(Get(camSection, "namespace")) ? Str(Get(camSection, "namespace")) : "ldap").ToLowerInvariant();
            if (ns is not "ldap" and not "oidc")
            {
                throw Error("cognos.cam.namespace: ldap or oidc");
            }
            if (ns == "oidc" && keycloakPort is null)
            {
                throw Error("cognos.cam.namespace: oidc needs cognos.oidc");
            }
            var admins = new List<string>();
            if (Truthy(Get(camSection, "admins")))
            {
                if (Get(camSection, "admins") is not List<object?> listedAdmins)
                {
                    throw Error("cognos.cam.admins: a list of cognos.users");
                }
                admins.AddRange(listedAdmins.Select(Str));
            }
            else if (names.Count > 0)
            {
                admins.Add(names[0]);
            }
            if (servers.Count > 0 && users.Count == 0)
            {
                throw Error("cognos.users: TM1 in CAM security needs test users");
            }
            foreach (var a in admins.Where(a => !names.Contains(a)))
            {
                throw Error("cognos.cam.admins: " + a + " isn't one of cognos.users");
            }
            if (Or(Get(cognos, "memory"), 6144L) is not long mb || mb < 2048 || mb > int.MaxValue)
            {
                throw Error("cognos.memory: the dispatcher heap in MB, at least 2048 (6144 recommended)");
            }
            var cognosPort = Port(Get(cognos, "port"), "cognos", 9300);
            cam = new Cognos(cognosPort, users, keycloakPort, ns == "oidc" ? CognosContainer.Namespace.Oidc : CognosContainer.Namespace.Ldap, admins, (int)mb);
        }

        var paw = Section(cfg, "paw");
        int? pawPort = null;
        string? pawZip = null;
        if (paw is not null)
        {
            Keys(paw, ["port", "acceptLicense", "zip"], "paw");
            if (servers.Count == 0)
            {
                throw Error("paw: needs a TM1 server (tm1.servers)");
            }
            if (Get(paw, "acceptLicense") is not true)
            {
                throw Error(
                    "paw.acceptLicense: PAW starts only once you accept IBM's license (true); "
                        + "read it with: unzip -p ipa_workspace_local_*.zip licenses/LA_en"
                );
            }
            if (ibm is null)
            {
                throw Error("paw: needs ibm, the folder with the PAW Local zip");
            }
            pawPort = Port(Get(paw, "port"), "paw", 8800);
            pawZip = Truthy(Get(paw, "zip")) ? Str(Get(paw, "zip")) : null;
        }

        var engineSection = Section(cfg, "tm1Engine");
        Engine? engine = null;
        int? enginePort = null;
        int? datastorePort = null;
        if (engineSection is not null)
        {
            Keys(engineSection, ["port", "databases", "users", "datastore"], "tm1Engine");
            // name and instance, in the file's order
            var databases = new List<(string Name, string Instance)>();
            var raw = Get(engineSection, "databases");
            if (raw is null)
            {
                databases.Add((Tm1EngineContainer.DefaultDatabase, Tm1EngineContainer.DefaultInstance));
            }
            else if (raw is not Map named || named.Count == 0)
            {
                throw Error("tm1Engine.databases: name at least one database, e.g. tm1Engine: {databases: {planning: {}}}");
            }
            else
            {
                foreach (var (key, value) in named)
                {
                    var where = "tm1Engine.databases." + Str(key);
                    // No check for names that differ only in case, as tm1.servers has: Sales and sales are two databases to TM1 v12
                    if (key is not string name)
                    {
                        throw Error(where + ": YAML reads this name as a number or true/false: put it in quotes");
                    }
                    if (!EngineName().IsMatch(name))
                    {
                        throw Error(where + ": database names are letters, digits, _ and -, starting with a letter or digit");
                    }
                    if (name.Length < 3)
                    {
                        throw Error(where + ": TM1 needs database names of at least 3 characters");
                    }
                    var d = Keys(Or(value, new Map()), ["instance"], where);
                    if ((Get(d, "instance") ?? Tm1EngineContainer.DefaultInstance) is not string instance || !EngineName().IsMatch(instance))
                    {
                        throw Error(where + ".instance: instance names are letters, digits, _ and -, starting with a letter or digit");
                    }
                    databases.Add((name, instance));
                }
            }
            if ((Truthy(Get(engineSection, "users")) ? Get(engineSection, "users") : new List<object?>()) is not List<object?> list)
            {
                throw Error("tm1Engine.users must be a list, e.g. users: [analyst::Analysts]");
            }
            // Every user first, then the names, as pa_setup: a bad entry is reported before a name listed twice
            var engineUsers = new List<EngineUser>();
            for (var i = 0; i < list.Count; i++)
            {
                engineUsers.Add(ParseEngineUser(list[i], "tm1Engine.users[" + i + "]"));
            }
            var engineNames = new HashSet<string>(StringComparer.Ordinal);
            foreach (var user in engineUsers)
            {
                if (!engineNames.Add(user.Name.ToLowerInvariant()))
                {
                    throw Error("tm1Engine.users: a user is listed twice");
                }
            }
            var datastore = Get(engineSection, "datastore");
            var ds = datastore is null or bool ? new Map() : Keys(datastore, ["port"], "tm1Engine.datastore");
            enginePort = Port(Get(engineSection, "port"), "tm1Engine", 4444);
            // Published only when a port is given
            datastorePort = Get(ds, "port") is null ? null : Port(Get(ds, "port"), "tm1Engine.datastore", 27017);
            foreach (var (name, _) in servers)
            {
                // The engine's two compose services in pa-setup: a server of that name would be replaced by them
                if (name.ToLowerInvariant() is "tm1-engine" or "tm1-engine-mongo")
                {
                    throw Error("tm1.servers." + name + ": that name is the TM1 v12 engine's here; give the server another");
                }
            }
            engine = new Engine(databases, engineUsers);
        }

        if (servers.Count == 0 && cam is null && engine is null)
        {
            throw Error("nothing to run: add tm1.servers, tm1Engine and/or cognos");
        }
        var ports = new List<int>();
        AddIfSet(ports, enginePort);
        AddIfSet(ports, datastorePort);
        ports.AddRange(servers.Select(s => s.Server.Port));
        AddIfSet(ports, admin);
        AddIfSet(ports, webPort);
        if (cam is not null)
        {
            ports.Add(cam.Port);
            AddIfSet(ports, cam.KeycloakPort);
        }
        AddIfSet(ports, pawPort);
        var dupes = ports.GroupBy(p => p).Where(g => g.Count() > 1).Select(g => g.Key).Order().ToList();
        if (dupes.Count > 0)
        {
            throw Error("port(s) " + string.Join(", ", dupes) + " used by more than one service");
        }

        // What pa-setup runs and this environment can't
        if (cam is not null && servers.Count == 0)
        {
            throw Error("cognos: PlanningAnalyticsEnvironment needs a TM1 server too (tm1.servers); for Cognos alone, use CognosContainer");
        }
        foreach (var (name, s) in servers.Where(s => s.Server.Seed == "24retail"))
        {
            throw Error("tm1.servers." + name + ".seed: 24retail isn't available in tm1-testcontainers (Tm1Seed has empty and sdata)");
        }

        var b = Configure(servers, admin is not null, heap, cam, pawPort is not null, ibm, pawZip, images);
        if (engine is not null)
        {
            b.Tm1Engine(container =>
            {
                foreach (var (name, instance) in engine.Databases)
                {
                    container.WithDatabase(instance, name);
                }
                // TM1 v12 keeps no passwords: a user's is checked and not used
                foreach (var user in engine.Users)
                {
                    container.WithUser(user.Name, [.. user.Groups]);
                }
            });
            b.Tm1EngineImage = Image(images, "tm1Engine");
        }
        return b;
    }

    private static PlanningAnalyticsEnvironment.EnvironmentBuilder Configure(
        List<(string Name, Server Server)> servers,
        bool admin,
        string? heap,
        Cognos? cam,
        bool paw,
        string? ibm,
        string? pawZip,
        Map images
    )
    {
        var b = PlanningAnalyticsEnvironment.Builder();
        foreach (var (name, s) in servers)
        {
            b.Tm1(name, server =>
            {
                server.WithSeed(s.Seed == "sdata" ? Tm1Seed.Sdata : Tm1Seed.Empty);
                foreach (var (key, value) in s.Cfg)
                {
                    server.WithEnv("TM1_CFG_" + key, value);
                }
            });
        }
        if (admin)
        {
            b.AdminServer();
        }
        if (heap is not null)
        {
            b.Tm1Web(web => web.WithEnv("TM1_WEB_HEAP", heap));
        }
        if (cam is not null)
        {
            b.Cognos(cognos =>
            {
                foreach (var u in cam.Users)
                {
                    cognos.WithUser(u.Name, u.Password, [.. u.Groups]);
                }
                cognos.WithEnv("COGNOS_MAX_MEMORY", cam.Memory.ToString(CultureInfo.InvariantCulture));
            });
            if (cam.KeycloakPort is not null)
            {
                b.Keycloak();
            }
            b.CamSecurity(cam.Namespace, [.. cam.Admins]);
        }
        if (paw)
        {
            b.Paw(p => p.AcceptLicense());
        }
        b.Ibm = ibm;
        b.PawZip = pawZip;
        b.Tm1Image = Image(images, "tm1Server");
        b.Tm1WebImage = Image(images, "tm1Web");
        b.CognosImage = Image(images, "cognosServer");
        b.KeycloakImage = Image(images, "keycloak");
        return b;
    }

    private static string? Image(Map images, string key) => Truthy(Get(images, key)) ? Str(Get(images, key)).Trim() : null;

    private static void AddIfSet(List<int> ports, int? port)
    {
        if (port is not null)
        {
            ports.Add(port.Value);
        }
    }

    private static object? Get(Map map, string key) => map.TryGetValue(key, out var value) ? value : null;

    /// <summary>pa_setup.check_keys: a mapping with only the allowed settings.</summary>
    private Map Keys(object? section, string[] allowed, string where)
    {
        var map = Mapping(section, where);
        var unknown = map.Keys.Select(Str).Where(k => !allowed.Contains(k)).Distinct().Order(StringComparer.Ordinal).ToList();
        if (unknown.Count > 0)
        {
            throw Error(where + ": unknown setting(s) " + string.Join(", ", unknown) + " (allowed: " + string.Join(", ", allowed) + ")");
        }
        return map;
    }

    private Map Mapping(object? section, string where) => section as Map ?? throw Error(where + " must be a mapping");

    /// <summary>pa_setup.section: absent or false is off; true is on with defaults.</summary>
    private Map? Section(Map cfg, string key) =>
        Get(cfg, key) switch
        {
            null or false => null,
            true => new Map(),
            var value => Mapping(value, key),
        };

    private int Port(object? value, string where, int fallback)
    {
        var p = value ?? (long)fallback;
        if (p is not long port || port < 1 || port > 65535)
        {
            throw Error(where + ": port must be a number from 1 to 65535, not " + Str(p));
        }
        return (int)port;
    }

    private string HostPath(object? value, string what, string dir)
    {
        if (value is not string text || string.IsNullOrWhiteSpace(text))
        {
            throw Error(what + " must be a path");
        }
        var path = text.Trim();
        if (path == "~" || path.StartsWith("~/", StringComparison.Ordinal) || path.StartsWith("~\\", StringComparison.Ordinal))
        {
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            return Settings.Absolute(path.Length > 1 ? Path.Combine(home, path[2..]) : home);
        }
        return Settings.Absolute(Path.Combine(dir, path));
    }

    private CognosContainer.User User(object? entry, string where)
    {
        object? name;
        object? password;
        var groups = new List<object?>();
        if (entry is string text)
        {
            var parts = text.Split(':');
            if (parts.Length is not 2 and not 3)
            {
                throw Error(where + ": expected user:password[:group,group], not '" + text + "'");
            }
            name = parts[0];
            password = parts[1];
            if (parts.Length == 3)
            {
                groups.AddRange(parts[2].Split(',').Where(g => g.Length > 0));
            }
        }
        else if (entry is Map)
        {
            var map = Keys(entry, ["name", "password", "groups"], where);
            name = Get(map, "name");
            password = Get(map, "password");
            if (Or(Get(map, "groups"), new List<object?>()) is not List<object?> list)
            {
                throw Error(where + ": groups is a list");
            }
            groups.AddRange(list);
        }
        else
        {
            throw Error(where + ": a user is user:password[:groups] or a mapping");
        }
        // Strings only, as in pa_setup: a missing name or a number isn't a user name
        if (name is not string userName || !UserName().IsMatch(userName))
        {
            throw Error(where + ": user name '" + Str(name) + "' must be letters, digits, '.', '_' and '-'");
        }
        if (password is not string pw || pw.Length == 0 || PasswordBreak().IsMatch(pw))
        {
            throw Error(where + ": " + userName + "'s password must be non-empty, without ':' or spaces");
        }
        var groupNames = new List<string>();
        foreach (var g in groups)
        {
            if (g is not string group || !UserName().IsMatch(group))
            {
                throw Error(where + ": group '" + Str(g) + "' must be letters, digits, '.', '_' and '-'");
            }
            groupNames.Add(group);
        }
        return new CognosContainer.User(userName, pw, [.. groupNames]);
    }

    /// <summary>pa_setup.parse_user with password_optional: an engine's user, whose password may be left out or empty,
    /// since TM1 v12 keeps none.</summary>
    private EngineUser ParseEngineUser(object? entry, string where)
    {
        object? name;
        object? password;
        var groups = new List<object?>();
        if (entry is string text)
        {
            var parts = text.Split(':');
            if (parts.Length == 2 && parts[1].Length > 0)
            {
                // The image's own form has groups there; here the second part is a password
                throw Error(where + ": '" + text + "' reads as a password, which TM1 v12 doesn't keep: for a group, write " + parts[0] + "::" + parts[1]);
            }
            if (parts.Length > 3)
            {
                throw Error(where + ": expected user, user::group,group or user:password:group,group, not '" + text + "'");
            }
            name = parts[0];
            password = parts.Length > 1 ? parts[1] : "";
            if (parts.Length == 3)
            {
                groups.AddRange(parts[2].Split(',').Where(g => g.Length > 0));
            }
        }
        else if (entry is Map)
        {
            var map = Keys(entry, ["name", "password", "groups"], where);
            name = Get(map, "name");
            password = Get(map, "password") ?? "";
            if ((Truthy(Get(map, "groups")) ? Get(map, "groups") : new List<object?>()) is not List<object?> list)
            {
                throw Error(where + ": groups must be a list, e.g. groups: [Analysts]");
            }
            groups.AddRange(list);
        }
        else
        {
            throw Error(where + ": a user is user:password[:groups] or a mapping");
        }
        if (name is not string userName || !UserName().IsMatch(userName))
        {
            throw Error(where + ": user name '" + Str(name) + "' must be letters, digits, '.', '_' and '-'");
        }
        if (password is not string pw || PasswordBreak().IsMatch(pw))
        {
            throw Error(where + ": " + userName + "'s password must be text without ':' or spaces (quote a number)");
        }
        var groupNames = new List<string>();
        foreach (var g in groups)
        {
            if (g is not string group || !UserName().IsMatch(group))
            {
                throw Error(where + ": group '" + Str(g) + "' must be letters, digits, '.', '_' and '-'");
            }
            groupNames.Add(group);
        }
        return new EngineUser(userName, groupNames);
    }

    /// <summary>A tm1s.cfg value as pa_setup writes it, with Python's <c>str()</c>: true is True.</summary>
    private string CfgValue(object? value, string where) =>
        value switch
        {
            bool flag => flag ? "True" : "False",
            string or long or BigInteger or double => Str(value),
            _ => throw Error(where + ": a string, number or boolean"),
        };

    /// <summary>pa_setup's <c>value or fallback</c>.</summary>
    private static object? Or(object? value, object fallback) => Truthy(value) ? value : fallback;

    /// <summary>Python's truth: null, false, 0, "" and empty collections are false.</summary>
    private static bool Truthy(object? value) =>
        value switch
        {
            null or false => false,
            long n => n != 0,
            BigInteger n => !n.IsZero,
            double d => d != 0,
            string s => s.Length > 0,
            Map m => m.Count > 0,
            List<object?> l => l.Count > 0,
            _ => true,
        };

    /// <summary>A value as text, as the Java reader's <c>String.valueOf</c>; numbers and booleans as Python writes them.</summary>
    private static string Str(object? value) =>
        value switch
        {
            null => "null",
            bool flag => flag ? "True" : "False",
            long n => n.ToString(CultureInfo.InvariantCulture),
            BigInteger n => n.ToString(CultureInfo.InvariantCulture),
            double d => Yaml11.PythonFloat(d),
            string s => s,
            Map m => "{" + string.Join(", ", m.Select(e => Str(e.Key) + ": " + Str(e.Value))) + "}",
            List<object?> l => "[" + string.Join(", ", l.Select(Str)) + "]",
            _ => value.ToString() ?? "",
        };

    private ArgumentException Error(string message) => new(file + ": " + message);

    [GeneratedRegex("^[A-Za-z0-9_-]+$")]
    private static partial Regex Name();

    [GeneratedRegex("^[A-Za-z0-9._-]+$")]
    private static partial Regex UserName();

    // The tm1-engine image's rule for a database's and an instance's name
    [GeneratedRegex(@"\A[A-Za-z0-9][A-Za-z0-9_-]*\z")]
    private static partial Regex EngineName();

    [GeneratedRegex(@"[:\s]")]
    private static partial Regex PasswordBreak();

    private sealed record Engine(List<(string Name, string Instance)> Databases, List<EngineUser> Users);

    private sealed record EngineUser(string Name, List<string> Groups);

    private sealed record Server(int Port, string Seed, List<(string Key, string Value)> Cfg);

    private sealed record Cognos(
        int Port,
        List<CognosContainer.User> Users,
        int? KeycloakPort,
        CognosContainer.Namespace Namespace,
        List<string> Admins,
        int Memory
    );
}
