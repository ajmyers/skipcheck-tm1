using System;
using System.Collections.Generic;
using System.Linq;
using System.Xml.Linq;
using Skipcheck.Tm1.Client.Errors;

namespace Skipcheck.Tm1.Client.Version;

/// <summary>What a server's <c>$metadata</c> defines, and its versions: what requests check before
/// they're sent, as the Java client's <c>Tm1Capabilities</c>. <see cref="Endpoints"/> are made the
/// way the spec's inventory is.</summary>
public sealed class Tm1Capabilities
{
    private static readonly XNamespace Edm = "http://docs.oasis-open.org/odata/ns/edm";

    private readonly Dictionary<string, (string? Base, HashSet<string> Members)> _types;
    private readonly Dictionary<string, HashSet<string>> _bound;
    private readonly HashSet<string> _entitySets;
    private readonly HashSet<string> _singletons;
    private readonly HashSet<string> _imports;
    private readonly Dictionary<string, string> _aliases;

    private Tm1Capabilities(
        string? schemaVersion,
        Tm1Version? productVersion,
        IReadOnlySet<string> endpoints,
        Dictionary<string, (string?, HashSet<string>)> types,
        Dictionary<string, HashSet<string>> bound,
        HashSet<string> entitySets,
        HashSet<string> singletons,
        HashSet<string> imports,
        Dictionary<string, string> aliases
    )
    {
        SchemaVersion = schemaVersion;
        ProductVersion = productVersion;
        Endpoints = endpoints;
        _types = types;
        _bound = bound;
        _entitySets = entitySets;
        _singletons = singletons;
        _imports = imports;
        _aliases = aliases;
    }

    /// <summary>The REST API schema version the server reports in <c>Core.SchemaVersion</c>, such
    /// as <c>11.8.35</c>, or null if it reports none. Not the product version: a different
    /// number.</summary>
    public string? SchemaVersion { get; }

    /// <summary>The server's product version, such as <c>11.8.03500.6</c>, or null if not
    /// known.</summary>
    public Tm1Version? ProductVersion { get; }

    /// <summary>The endpoints, as coverage-gate IDs.</summary>
    public IReadOnlySet<string> Endpoints { get; }

    /// <summary>Reads a <c>$metadata</c> document.</summary>
    public static Tm1Capabilities Parse(string metadata, Tm1Version? productVersion = null)
    {
        XDocument doc;
        try
        {
            doc = XDocument.Parse(metadata);
        }
        catch (System.Xml.XmlException e)
        {
            throw new Tm1ProtocolException("TM1's $metadata isn't XML: " + e.Message, e);
        }
        if (doc.Root?.Name.LocalName != "Edmx")
        {
            throw new Tm1ProtocolException("TM1's $metadata isn't CSDL: its root is " + doc.Root?.Name.LocalName);
        }
        var schemas = doc.Descendants(Edm + "Schema").ToList();
        var aliases = schemas.Where(s => s.Attribute("Alias") is not null).ToDictionary(s => Required(s, "Alias"), s => Required(s, "Namespace"));
        string Qualify(string name)
        {
            var dot = name.LastIndexOf('.');
            if (dot < 0)
            {
                return name;
            }
            var prefix = name[..dot];
            return (aliases.TryGetValue(prefix, out var ns) ? ns : prefix) + name[dot..];
        }
        var endpoints = new HashSet<string>();
        var types = new Dictionary<string, (string?, HashSet<string>)>();
        var bound = new Dictionary<string, HashSet<string>>();
        var sets = new HashSet<string>();
        var singletons = new HashSet<string>();
        var imports = new HashSet<string>();
        string? version = null;
        foreach (var schema in schemas)
        {
            var ns = Required(schema, "Namespace");
            foreach (
                var a in schema
                    .Elements(Edm + "Annotation")
                    .Where(a => ((string?)a.Attribute("Term") ?? "").EndsWith("SchemaVersion", StringComparison.Ordinal))
            )
            {
                version = (string?)a.Attribute("String") ?? a.Value.Trim();
            }
            foreach (var kind in new[] { "Action", "Function" })
            {
                foreach (var op in schema.Elements(Edm + kind).Where(o => (string?)o.Attribute("IsBound") == "true"))
                {
                    var first = op.Elements(Edm + "Parameter").FirstOrDefault();
                    if (first is null)
                    {
                        continue;
                    }
                    var binding = Required(first, "Type");
                    var collection = binding.StartsWith("Collection(", StringComparison.Ordinal);
                    var inner = collection ? binding["Collection(".Length..^1] : binding;
                    var simple = inner[(inner.LastIndexOf('.') + 1)..];
                    endpoints.Add($"{kind} {(collection ? $"Collection({simple})" : simple)}/{Required(op, "Name")}");
                    var key = Qualify(inner);
                    if (!bound.TryGetValue(key, out var names))
                    {
                        bound[key] = names = new HashSet<string>();
                    }
                    names.Add(Required(op, "Name"));
                }
            }
            foreach (var t in schema.Elements(Edm + "EntityType").Concat(schema.Elements(Edm + "ComplexType")))
            {
                var name = Required(t, "Name");
                var members = new HashSet<string>();
                foreach (var p in t.Elements(Edm + "Property"))
                {
                    members.Add(Required(p, "Name"));
                }
                foreach (var n in t.Elements(Edm + "NavigationProperty"))
                {
                    members.Add(Required(n, "Name"));
                    endpoints.Add($"Navigation {name}/{Required(n, "Name")}");
                }
                var baseType = (string?)t.Attribute("BaseType");
                types[$"{ns}.{name}"] = (baseType is null ? null : Qualify(baseType), members);
            }
            foreach (var c in schema.Elements(Edm + "EntityContainer"))
            {
                foreach (var (tag, set) in new[] { ("EntitySet", sets), ("Singleton", singletons), ("ActionImport", imports), ("FunctionImport", imports) })
                {
                    foreach (var item in c.Elements(Edm + tag))
                    {
                        endpoints.Add($"{tag} {Required(item, "Name")}");
                        set.Add(Required(item, "Name"));
                    }
                }
            }
        }
        return new Tm1Capabilities(version, productVersion, endpoints, types, bound, sets, singletons, imports, aliases);
    }

    /// <summary>A copy that knows the server's product version.</summary>
    public Tm1Capabilities WithProductVersion(Tm1Version? productVersion) =>
        new(SchemaVersion, productVersion, Endpoints, _types, _bound, _entitySets, _singletons, _imports, _aliases);

    /// <summary>Whether the server's <c>$metadata</c> defines
    /// <paramref name="capability"/>.</summary>
    public bool Supports(Tm1Capability capability)
    {
        ArgumentNullException.ThrowIfNull(capability);
        return capability.Kind switch
        {
            Tm1Capability.CapabilityKind.EntitySet => _entitySets.Contains(capability.Name),
            Tm1Capability.CapabilityKind.Singleton => _singletons.Contains(capability.Name),
            Tm1Capability.CapabilityKind.UnboundOperation => _imports.Contains(capability.Name),
            Tm1Capability.CapabilityKind.BoundOperation => Hierarchy(capability.Type!) // not null for a bound operation or a property: Tm1Capability's constructor checks
                .Any(t => _bound.TryGetValue(t, out var ops) && ops.Contains(Simple(capability.Name))),
            _ => Hierarchy(capability.Type!).Any(t => _types[t].Members.Contains(capability.Name)),
        };
    }

    /// <summary>Null if the server has it; otherwise the error saying what's missing, as Java's.</summary>
    public Tm1UnsupportedOperationException? Check(Tm1Capability capability, string server)
    {
        if (Supports(capability))
        {
            return null;
        }
        var missing = capability.Describe();
        var added = capability.AddedInVersion();
        var message = $"{missing} isn't defined by the TM1 server at {server}" + (added is not null ? $": it was added in schema {added}, and " : " (");
        message += SchemaVersion is not null ? "the server reports schema " + SchemaVersion : "the server reports no schema version";
        if (ProductVersion is not null)
        {
            message += ", product version " + ProductVersion;
        }
        message += added is not null ? "" : ")";
        return new Tm1UnsupportedOperationException(message, missing);
    }

    /// <inheritdoc/>
    public override string ToString() => $"Tm1Capabilities[schema {SchemaVersion ?? "unknown"}, product {ProductVersion?.ToString() ?? "unknown"}]";

    private List<string> Hierarchy(string type)
    {
        var result = new List<string>();
        var current = Qualify(type);
        while (current is not null && _types.TryGetValue(current, out var t))
        {
            result.Add(current);
            current = t.Base;
        }
        return result;
    }

    private string Qualify(string name)
    {
        var dot = name.LastIndexOf('.');
        if (dot < 0)
        {
            return name;
        }
        var prefix = name[..dot];
        return (_aliases.TryGetValue(prefix, out var ns) ? ns : prefix) + name[dot..];
    }

    /// <summary>An attribute CSDL requires: without it, the document isn't one the client can read.</summary>
    private static string Required(XElement element, string attribute) =>
        (string?)element.Attribute(attribute) ?? throw new Tm1ProtocolException($"TM1's $metadata isn't CSDL: a {element.Name.LocalName} has no {attribute}");

    private static string Simple(string qualified) => qualified[(qualified.LastIndexOf('.') + 1)..];
}
