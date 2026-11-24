// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace Skipcheck.Tm1.Codegen;

/// <summary>Emits Patches.g.cs: a patch per type, as the Java model's <c>CubePatch</c>:
/// <c>CubePatch.Builder().Name("Sales").BindDimensions(dims.Key("Region")).Build()</c>. Only the
/// properties set are sent, an explicit <c>null</c> included; a subtype's patch sends its
/// <c>@odata.type</c> first.</summary>
public sealed class PatchEmitter
{
    private readonly Spec _spec;
    private readonly StringBuilder _out = new();

    public PatchEmitter(Spec spec) => _spec = spec;

    public string Emit()
    {
        _out.Append(ModelEmitter.Header);
        _out.Append(
            """
            using System;
            using System.Collections.Generic;
            using System.Text.Json;
            using Skipcheck.Tm1.Client.OData;

            namespace Skipcheck.Tm1.Client.Model;

            """
        );
        foreach (var t in _spec.Types)
        {
            EmitPatch(t, false);
        }
        foreach (var t in _spec.Types.Where(Creatable))
        {
            EmitPatch(t, true);
        }
        return _out.ToString();
    }

    private string? PatchType(string typeName, bool collection)
    {
        string element;
        if (_spec.TypeByName.TryGetValue(typeName, out var t))
        {
            element = Spec.Str(t, "name") + "Patch";
            return collection ? $"IEnumerable<{element}>?" : element + "?";
        }
        if (ModelEmitter.Element(_spec, typeName) is not { } el)
        {
            return null;
        }
        if (el.Type == "JsonElement")
        {
            return collection ? "IEnumerable<object?>?" : "object?";
        }
        return collection ? $"IEnumerable<{el.Type}>?" : el.Type + "?";
    }

    private static readonly HashSet<string> Writing = ["applied", "transformed", "untested"];

    private static bool Writes(string? outcome) => outcome is null || Writing.Contains(outcome);

    private static string? Outcome(JsonElement t, string method, string prop) =>
        t.TryGetProperty("write", out var w) && w.GetProperty(method).GetProperty("properties").TryGetProperty(prop, out var o) ? o.GetString() : null;

    /// <summary>Whether a POST of <paramref name="t"/> takes a property, by the probe's writes.</summary>
    private bool Creates(JsonElement t, string prop) =>
        t.TryGetProperty("write", out _)
            ? Writes(Outcome(t, "create", prop))
            : _spec.Derived(t).Where(d => d.TryGetProperty("write", out _)).All(d => Writes(Outcome(d, "create", prop)));

    /// <summary>Whether a PATCH applies a property, leaving out collections it replaces or appends to.</summary>
    private static bool Patches(JsonElement t, string prop) =>
        Writes(Outcome(t, "patch", prop))
        && !(t.TryGetProperty("write", out var w) && w.TryGetProperty("collections", out var c) && c.TryGetProperty(prop, out _));

    /// <summary>Whether a type gets a create: the probe has writes for it, or for a subtype it's the root of.</summary>
    private bool Creatable(JsonElement t) =>
        Spec.Str(t, "kind") == "entity" && (t.TryGetProperty("write", out _) || _spec.Derived(t).Any(d => d.TryGetProperty("write", out _)));

    private void EmitPatch(JsonElement t, bool create)
    {
        var name = Spec.Str(t, "name")!;
        var suffix = create ? "Create" : "Patch";
        var patch = name + suffix;
        var builderName = create ? "CreateBuilder" : "PatchBuilder";
        var baseType = _spec.Base(t);
        var hasDerived = _spec.Derived(t).Any();
        var basePatch = baseType is { } b ? Spec.Str(b, "name") + suffix : "Tm1Patch";
        var typeId = baseType is null ? "null" : $"\"#{Spec.Str(t, "qualifiedName")}\"";
        if (create)
        {
            _out.Append(
                $"\n/// <summary>A create of <see cref=\"{name}\"/>, for a POST: only the properties set are sent, and only those TM1 applies in a create are offered{(t.TryGetProperty("write", out _) ? ", as the probe found" : "")}. <c>Build()</c> checks what a create needs.</summary>\n"
            );
        }
        else
        {
            _out.Append($"\n/// <summary>A write of <see cref=\"{name}\"/>: only the properties set are sent.</summary>\n");
        }
        _out.Append($"public {(hasDerived ? "" : "sealed ")}partial class {patch} : {basePatch}\n{{\n");
        _out.Append(
            $"    {(hasDerived ? "protected" : "private")} {patch}(IReadOnlyDictionary<string, object?> values)\n        : base(values)\n    {{\n    }}\n\n"
        );
        _out.Append($"    /// <summary>A builder with nothing set{(baseType is null ? "" : " but <c>@odata.type</c>")}.</summary>\n");
        var hide = baseType is null ? "" : "new ";
        _out.Append($"    public {hide}static {builderName} Builder() => new();\n\n");
        if (!hasDerived)
        {
            var copied = new List<string>();
            foreach (var level in _spec.Chain(t))
            {
                foreach (var p in Spec.Array(level, "properties"))
                {
                    var prop = Spec.Str(p, "name")!;
                    if (Spec.Str(p, "type") != "Edm.Stream" && !copied.Contains(prop) && (create ? Creates(t, prop) : Patches(t, prop)))
                    {
                        copied.Add(prop);
                    }
                }
            }
            var declared = _spec.Chain(t).Any(l => Spec.Bool(l, "open"))
                ? "["
                    + string.Join(
                        ", ",
                        _spec
                            .Chain(t)
                            .SelectMany(l => Spec.Array(l, "properties").Concat(Spec.Array(l, "navigationProperties")))
                            .Select(p => $"\"{Spec.Str(p, "name")}\"")
                    )
                    + "]"
                : "null";
            var what = create ? "a POST applies, to recreate it" : "a PATCH applies, leaving out collections a PATCH replaces or appends to";
            _out.Append(
                $"    /// <summary>A builder holding what <paramref name=\"read\"/> holds that {what}: change it with the builder's setters. Navigation properties aren't copied, nor nulls.</summary>\n"
            );
            _out.Append($"    /// <param name=\"read\">An entity read from TM1.</param>\n");
            _out.Append(
                $"    public static {builderName} Builder({name} read) => new {builderName}().Copy(read, [{string.Join(", ", copied.Select(c => $"\"{c}\""))}], {declared});\n\n"
            );
        }
        _out.Append($"    /// <summary>Builds a <see cref=\"{patch}\"/>; each setter replaces what an earlier call set.</summary>\n");
        _out.Append($"    public {hide}sealed class {builderName} : BuilderBase<{patch}, {builderName}>\n    {{\n");
        _out.Append($"        public {builderName}()\n            : base(v => new {patch}(v), {typeId})\n        {{\n        }}\n");
        if (
            create
            && t.TryGetProperty("write", out var write)
            && write.GetProperty("create").TryGetProperty("required", out var required)
            && required.GetArrayLength() > 0
        )
        {
            _out.Append($"\n        /// <summary>The create, with what's been set so far.</summary>\n");
            _out.Append($"        /// <exception cref=\"InvalidOperationException\">It lacks what a create needs.</exception>\n");
            _out.Append($"        public new {patch} Build()\n        {{\n");
            foreach (var r in required.EnumerateArray().Select(r => r.GetString()!.Replace("@odata.bind", "")))
            {
                _out.Append(
                    $"            if (!Has(\"{r}\"))\n            {{\n                throw new InvalidOperationException(\"A {name} create needs {r}: TM1 refuses one without it\");\n            }}\n"
                );
            }
            _out.Append("            return base.Build();\n        }\n");
        }
        var taken = new HashSet<string> { "Build", "Property", "Set", "Bind", "BindAll" };
        foreach (var level in _spec.Chain(t))
        {
            foreach (var p in Spec.Array(level, "properties"))
            {
                var prop = Spec.Str(p, "name")!;
                var type = p.TryGetProperty("departure", out _) ? "object?" : PatchType(Spec.Str(p, "type")!, Spec.Bool(p, "collection"));
                if (type is null || (create && !Creates(t, prop)))
                {
                    continue;
                }
                var method = Names.Unique(Names.Member(prop), taken);
                _out.Append($"\n        public {builderName} {method}({type} value) => Set(\"{prop}\", value);\n");
            }
            foreach (var n in Spec.Array(level, "navigationProperties"))
            {
                var nav = Spec.Str(n, "name")!;
                var collection = Spec.Bool(n, "collection");
                var type = PatchType(Spec.Str(n, "type")!, collection);
                if (type is null)
                {
                    continue;
                }
                var method = Names.Unique(Names.Member(nav), taken);
                _out.Append($"\n        /// <summary>Sets <c>{nav}</c> (nested, for a deep insert).</summary>\n");
                _out.Append($"        public {builderName} {method}({type} value) => Set(\"{nav}\", value);\n");
                if (_spec.IsEntity(Spec.Str(n, "type")!))
                {
                    var bind = Names.Unique("Bind" + Names.Member(nav), taken);
                    if (collection)
                    {
                        _out.Append($"\n        /// <summary>Binds <c>{nav}</c> to existing entities (<c>{nav}@odata.bind</c>).</summary>\n");
                        _out.Append($"        public {builderName} {bind}(params ODataPath[] targets) => BindAll(\"{nav}\", targets);\n");
                    }
                    else
                    {
                        _out.Append($"\n        /// <summary>Binds <c>{nav}</c> to an existing entity (<c>{nav}@odata.bind</c>).</summary>\n");
                        _out.Append($"        public {builderName} {bind}(ODataPath target) => Bind(\"{nav}\", target);\n");
                    }
                }
            }
        }
        if (_spec.Chain(t).Any(l => Spec.Bool(l, "open")))
        {
            _out.Append(
                "\n        /// <summary>Sets a property <c>$metadata</c> doesn't declare, which this open type can have, such as an attribute's value.</summary>\n"
            );
            _out.Append($"        public {builderName} Property(string name, object? value) => Set(name, value);\n");
        }
        _out.Append("    }\n}\n");
    }
}
