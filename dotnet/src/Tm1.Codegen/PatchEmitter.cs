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
            EmitPatch(t);
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

    private void EmitPatch(JsonElement t)
    {
        var name = Spec.Str(t, "name")!;
        var patch = name + "Patch";
        var baseType = _spec.Base(t);
        var hasDerived = _spec.Derived(t).Any();
        var basePatch = baseType is { } b ? Spec.Str(b, "name") + "Patch" : "Tm1Patch";
        var typeId = baseType is null ? "null" : $"\"#{Spec.Str(t, "qualifiedName")}\"";
        _out.Append($"\n/// <summary>A write of <see cref=\"{name}\"/>: only the properties set are sent.</summary>\n");
        _out.Append($"public {(hasDerived ? "" : "sealed ")}partial class {patch} : {basePatch}\n{{\n");
        _out.Append(
            $"    {(hasDerived ? "protected" : "private")} {patch}(IReadOnlyDictionary<string, object?> values)\n        : base(values)\n    {{\n    }}\n\n"
        );
        _out.Append($"    /// <summary>A builder with nothing set{(baseType is null ? "" : " but <c>@odata.type</c>")}.</summary>\n");
        var hide = baseType is null ? "" : "new ";
        _out.Append($"    public {hide}static PatchBuilder Builder() => new();\n\n");
        _out.Append($"    /// <summary>Builds a <see cref=\"{patch}\"/>; each setter replaces what an earlier call set.</summary>\n");
        _out.Append($"    public {hide}sealed class PatchBuilder : BuilderBase<{patch}, PatchBuilder>\n    {{\n");
        _out.Append($"        public PatchBuilder()\n            : base(v => new {patch}(v), {typeId})\n        {{\n        }}\n");
        var taken = new HashSet<string> { "Build", "Property", "Set", "Bind", "BindAll" };
        foreach (var level in _spec.Chain(t))
        {
            foreach (var p in Spec.Array(level, "properties"))
            {
                var prop = Spec.Str(p, "name")!;
                var type = p.TryGetProperty("departure", out _) ? "object?" : PatchType(Spec.Str(p, "type")!, Spec.Bool(p, "collection"));
                if (type is null)
                {
                    continue;
                }
                var method = Names.Unique(Names.Member(prop), taken);
                _out.Append($"\n        public PatchBuilder {method}({type} value) => Set(\"{prop}\", value);\n");
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
                _out.Append($"        public PatchBuilder {method}({type} value) => Set(\"{nav}\", value);\n");
                if (_spec.IsEntity(Spec.Str(n, "type")!))
                {
                    var bind = Names.Unique("Bind" + Names.Member(nav), taken);
                    if (collection)
                    {
                        _out.Append($"\n        /// <summary>Binds <c>{nav}</c> to existing entities (<c>{nav}@odata.bind</c>).</summary>\n");
                        _out.Append($"        public PatchBuilder {bind}(params ODataPath[] targets) => BindAll(\"{nav}\", targets);\n");
                    }
                    else
                    {
                        _out.Append($"\n        /// <summary>Binds <c>{nav}</c> to an existing entity (<c>{nav}@odata.bind</c>).</summary>\n");
                        _out.Append($"        public PatchBuilder {bind}(ODataPath target) => Bind(\"{nav}\", target);\n");
                    }
                }
            }
        }
        if (_spec.Chain(t).Any(l => Spec.Bool(l, "open")))
        {
            _out.Append(
                "\n        /// <summary>Sets a property <c>$metadata</c> doesn't declare, which this open type can have, such as an attribute's value.</summary>\n"
            );
            _out.Append("        public PatchBuilder Property(string name, object? value) => Set(name, value);\n");
        }
        _out.Append("    }\n}\n");
    }
}
