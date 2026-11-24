// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Codegen;

/// <summary>The spec, with the lookups every emitter needs (as tm1_pygen's spec.py).</summary>
public sealed class Spec
{
    private readonly JsonElement _root;

    public Spec(JsonElement root)
    {
        _root = root;
        Types = root.GetProperty("types").EnumerateArray().ToList();
        Enums = root.GetProperty("enums").EnumerateArray().ToList();
        Operations = root.GetProperty("operations").EnumerateArray().ToList();
        Endpoints = root.GetProperty("endpoints").EnumerateArray().ToList();
        TypeByName = Types.ToDictionary(t => Str(t, "qualifiedName")!);
        EnumByName = Enums.ToDictionary(e => Str(e, "qualifiedName")!);
        if (root.TryGetProperty("offers", out var offers))
        {
            foreach (var b in offers.EnumerateObject())
            {
                Offers[b.Name] = b.Value.EnumerateObject().Select(s => s.Name).ToHashSet();
            }
        }
        foreach (var s in root.GetProperty("schemas").EnumerateArray())
        {
            if (Str(s, "alias") is { } alias)
            {
                Aliases[Str(s, "namespace")!] = alias;
            }
        }
    }

    public int Version => _root.GetProperty("specVersion").GetInt32();

    public string SchemaVersion => _root.GetProperty("source").GetProperty("schemaVersion").GetString()!;

    public JsonElement Container => _root.GetProperty("container");

    public List<JsonElement> Types { get; }

    public List<JsonElement> Enums { get; }

    public List<JsonElement> Operations { get; }

    public List<JsonElement> Endpoints { get; }

    public Dictionary<string, JsonElement> TypeByName { get; }

    public Dictionary<string, JsonElement> EnumByName { get; }

    public Dictionary<string, string> Aliases { get; } = new();

    /// <summary>The write types to generate (<c>CubeCreate</c>), each with the setters it offers by the JSON
    /// names they write (<c>Dimensions@odata.bind</c> for a binding): what the TM1 probe verified, or the
    /// overlay keeps. Empty in a spec from before them, when every type gets a patch.</summary>
    public Dictionary<string, HashSet<string>> Offers { get; } = new();

    /// <summary>Whether the spec says which write types and setters to generate (#338).</summary>
    public bool VerifiedOnly => Offers.Count > 0;

    /// <summary>The entity set whose members are of <paramref name="t"/>'s type, or failing that of the
    /// nearest type it derives from; or null. As the Java, Python and TypeScript generators.</summary>
    public string? EntitySet(JsonElement t)
    {
        foreach (var level in Enumerable.Reverse(Chain(t)))
        {
            foreach (var s in Array(Container, "entitySets"))
            {
                if (Str(s, "entityType") == Str(level, "qualifiedName"))
                {
                    return Str(s, "name");
                }
            }
        }
        return null;
    }

    /// <summary>The key of <paramref name="t"/>'s type, declared on it or failing that on the nearest type it
    /// derives from.</summary>
    public List<string> Key(JsonElement t) =>
        Enumerable.Reverse(Chain(t)).Select(l => Array(l, "key").Select(k => k.GetString()!).ToList()).FirstOrDefault(k => k.Count > 0) ?? [];

    public static string? Str(JsonElement e, string name) => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    public static bool Bool(JsonElement e, string name) => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.True;

    public static IEnumerable<JsonElement> Array(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Array ? v.EnumerateArray() : Enumerable.Empty<JsonElement>();

    public JsonElement? Base(JsonElement t) => Str(t, "baseType") is { } b ? TypeByName[b] : null;

    public JsonElement Root(JsonElement t)
    {
        while (Base(t) is { } b)
        {
            t = b;
        }
        return t;
    }

    /// <summary>The type and the types it derives from, root first.</summary>
    public List<JsonElement> Chain(JsonElement t)
    {
        var chain = new List<JsonElement> { t };
        while (Base(chain[0]) is { } b)
        {
            chain.Insert(0, b);
        }
        return chain;
    }

    public IEnumerable<JsonElement> Derived(JsonElement t) => Array(t, "derivedTypes").Select(d => TypeByName[d.GetString()!]);

    public bool IsEntity(string typeName) => TypeByName.TryGetValue(typeName, out var t) && Str(t, "kind") == "entity";

    public bool IsStructured(string typeName) => TypeByName.ContainsKey(typeName);

    public string AliasName(string qualified)
    {
        foreach (var (ns, alias) in Aliases)
        {
            if (qualified.StartsWith(ns + "."))
            {
                return alias + qualified[ns.Length..];
            }
        }
        return qualified;
    }
}

/// <summary>C# names for TM1's.</summary>
public static class Names
{
    private static readonly HashSet<string> Keywords = new()
    {
        "abstract",
        "as",
        "base",
        "bool",
        "break",
        "byte",
        "case",
        "catch",
        "char",
        "checked",
        "class",
        "const",
        "continue",
        "decimal",
        "default",
        "delegate",
        "do",
        "double",
        "else",
        "enum",
        "event",
        "explicit",
        "extern",
        "false",
        "finally",
        "fixed",
        "float",
        "for",
        "foreach",
        "goto",
        "if",
        "implicit",
        "in",
        "int",
        "interface",
        "internal",
        "is",
        "lock",
        "long",
        "namespace",
        "new",
        "null",
        "object",
        "operator",
        "out",
        "override",
        "params",
        "private",
        "protected",
        "public",
        "readonly",
        "ref",
        "return",
        "sbyte",
        "sealed",
        "short",
        "sizeof",
        "stackalloc",
        "static",
        "string",
        "struct",
        "switch",
        "this",
        "throw",
        "true",
        "try",
        "typeof",
        "uint",
        "ulong",
        "unchecked",
        "unsafe",
        "ushort",
        "using",
        "virtual",
        "void",
        "volatile",
        "while",
    };

    /// <summary>The Java client's <c>componentName</c>, capitalized: <c>ID</c> → <c>Id</c>,
    /// <c>UIData</c> → <c>UiData</c>, <c>Name</c> → <c>Name</c>.</summary>
    public static string Member(string property)
    {
        int upper = 0;
        while (upper < property.Length && char.IsUpper(property[upper]))
        {
            upper++;
        }
        string camel;
        if (upper == property.Length)
        {
            camel = property.ToLowerInvariant();
        }
        else if (upper <= 1)
        {
            camel = char.ToLowerInvariant(property[0]) + property[1..];
        }
        else
        {
            camel = property[..(upper - 1)].ToLowerInvariant() + property[(upper - 1)..];
        }
        var pascal = char.ToUpperInvariant(camel[0]) + camel[1..];
        return Regex.Replace(pascal, "[^A-Za-z0-9_]", "_");
    }

    /// <summary>A parameter name: the member name in camel case, <c>@</c>-escaped if a keyword.</summary>
    public static string Parameter(string name)
    {
        var m = Member(name);
        var camel = char.ToLowerInvariant(m[0]) + m[1..];
        return Keywords.Contains(camel) ? "@" + camel : camel;
    }

    /// <summary>The Java client's <c>constantName</c> words, in Pascal case: <c>Chore</c> +
    /// <c>Activate</c> → <c>ChoreActivate</c>, <c>ExecuteMDX</c> → <c>ExecuteMdx</c>.</summary>
    public static string Constant(string name)
    {
        var snake = Regex.Replace(name, "([a-z])([A-Z])", "$1_$2");
        snake = Regex.Replace(snake, "([A-Z]+)([A-Z][a-z])", "$1_$2");
        snake = Regex.Replace(snake, "[^A-Za-z0-9_]", "_");
        var sb = new StringBuilder();
        foreach (var word in snake.Split('_', System.StringSplitOptions.RemoveEmptyEntries))
        {
            sb.Append(char.ToUpperInvariant(word[0])).Append(word[1..].ToLowerInvariant());
        }
        var s = sb.ToString();
        return char.IsDigit(s[0]) ? "_" + s : s;
    }

    public static string Unique(string name, HashSet<string> taken)
    {
        var candidate = name;
        for (int n = 2; !taken.Add(candidate); n++)
        {
            candidate = name + n;
        }
        return candidate;
    }

    /// <summary>Text as an XML doc comment's content.</summary>
    public static string Doc(string text) => Regex.Replace(text.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;"), "`([^`]*)`", "<c>$1</c>");
}
