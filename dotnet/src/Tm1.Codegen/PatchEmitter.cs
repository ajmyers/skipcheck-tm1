// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace Skipcheck.Tm1.Codegen;

/// <summary>Emits Patches.g.cs: the write types, a patch and a create, as the Java model's <c>CubeCreate</c>:
/// <c>CubeCreate.Builder().Name("Sales").BindDimensions(dims.Key("Region")).Build()</c>. Only the
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
            using System.Linq;
            using System.Text.Json;
            using Skipcheck.Tm1.Client.OData;

            namespace Skipcheck.Tm1.Client.Model;

            """
        );
        foreach (var t in _spec.Types.Where(Patchable))
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
            // A nested entity is sent as its create where it has one: a deep insert creates it, as does a
            // collection a PATCH replaces or appends to
            element = Spec.Str(t, "name") + (Spec.Str(t, "kind") == "entity" && Creatable(t) ? "Create" : "Patch");
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

    private static readonly HashSet<string> Writing = ["applied", "writeOnly", "transformed", "untested"];

    private static bool Writes(string? outcome) => outcome is null || Writing.Contains(outcome);

    private static string? Outcome(JsonElement t, string method, string prop) =>
        t.TryGetProperty("write", out var w) && w.GetProperty(method).GetProperty("properties").TryGetProperty(prop, out var o) ? o.GetString() : null;

    /// <summary>Whether a POST of <paramref name="t"/> takes a property, by the probe's writes.</summary>
    private bool Creates(JsonElement t, string prop) =>
        t.TryGetProperty("write", out _)
            ? Writes(Outcome(t, "create", prop))
            : _spec.Derived(t).Where(d => d.TryGetProperty("write", out _)).All(d => Writes(Outcome(d, "create", prop)));

    /// <summary>
    /// Whether <c>Builder(read)</c> copies a property into a patch: a PATCH applies it, and it isn't a
    /// collection a PATCH appends to (copying one would add its members again), ignores or rejects. A
    /// collection a PATCH replaces is copied: the read holds all of it. As the Java generator.
    /// </summary>
    private static bool Patches(JsonElement t, string prop) =>
        Writes(Outcome(t, "patch", prop)) && Collection(t.TryGetProperty("write", out var w) ? w : default, prop) is not ("appends" or "ignored" or "rejected");

    /// <summary>What the probe found a PATCH does with a collection or navigation: <c>replaces</c>, <c>appends</c>, <c>ignored</c>, <c>rejected</c>; or null.</summary>
    private static string? Collection(JsonElement w, string prop) =>
        w.ValueKind == JsonValueKind.Object && w.TryGetProperty("collections", out var c) && c.TryGetProperty(prop, out var o) ? o.GetString() : null;

    private static bool WriteOffersInPatch(JsonElement w, string prop)
    {
        var patch = w.GetProperty("patch");
        if (prop == "Name")
        {
            return !patch.TryGetProperty("rename", out var rename) || rename.ValueKind == JsonValueKind.Null || rename.GetString() == "applied";
        }
        var outcome = patch.GetProperty("properties").TryGetProperty(prop, out var o) ? o.GetString() : null;
        return outcome is not ("rejected" or "ignored") && Collection(w, prop) is not ("rejected" or "ignored");
    }

    /// <summary>
    /// Whether a patch of <paramref name="t"/> offers a property: not if a PATCH rejects or ignores it, by the
    /// probe, nor <c>Name</c> where a PATCH doesn't rename; for the root of a hierarchy without writes of its
    /// own, if every probed subtype's patch offers it. As the Java generator.
    /// </summary>
    private bool OffersInPatch(JsonElement t, string prop) =>
        t.TryGetProperty("write", out var w)
            ? WriteOffersInPatch(w, prop)
            : _spec.Derived(t).Where(d => d.TryGetProperty("write", out _)).All(d => WriteOffersInPatch(d.GetProperty("write"), prop));

    /// <summary>Whether a type gets a create: the spec's offers list one; without offers, the probe has writes
    /// for it, or for a subtype it's the root of.</summary>
    private bool Creatable(JsonElement t) =>
        Spec.Str(t, "kind") == "entity"
        && (
            _spec.VerifiedOnly
                ? _spec.Offers.ContainsKey(Spec.Str(t, "name") + "Create")
                : t.TryGetProperty("write", out _) || _spec.Derived(t).Any(d => d.TryGetProperty("write", out _))
        );

    /// <summary>Whether a type gets a patch: every type does, unless the offers say which (#338).</summary>
    private bool Patchable(JsonElement t) => !_spec.VerifiedOnly || _spec.Offers.ContainsKey(Spec.Str(t, "name") + "Patch");

    /// <summary>A write type's property, for its setter and bind methods: <c>Set</c> whether it has its
    /// setter (for a navigation, the nested one), <c>Bind</c> whether a navigation to an entity has its
    /// <c>@odata.bind</c> one; <c>Type</c> the value the setter takes.</summary>
    private sealed record WriteProperty(JsonElement Property, string Json, string Type, bool Navigation, bool Collection, bool Set, bool Bind)
    {
        public string Method { get; set; } = "";

        public string BindMethod { get; set; } = "";
    }

    /// <summary>
    /// What a write type of <paramref name="t"/> offers, in the order its setters are declared: what the spec's
    /// offers list for it; without offers, what a POST takes for a create, and for a patch what a PATCH doesn't
    /// reject or ignore, navigations included (a cube's <c>Dimensions</c> isn't).
    /// </summary>
    private List<WriteProperty> Writable(JsonElement t, bool create)
    {
        var offered = _spec.Offers.GetValueOrDefault(Spec.Str(t, "name") + (create ? "Create" : "Patch")) ?? [];
        var result = new List<WriteProperty>();
        var taken = new HashSet<string> { "Build", "Property", "Set", "Bind", "BindAll" };
        foreach (var level in _spec.Chain(t))
        {
            foreach (var p in Spec.Array(level, "properties"))
            {
                var prop = Spec.Str(p, "name")!;
                var type = p.TryGetProperty("departure", out _) ? "object?" : PatchType(Spec.Str(p, "type")!, Spec.Bool(p, "collection"));
                if (
                    type is null
                    || !(
                        _spec.VerifiedOnly ? offered.Contains(prop)
                        : create ? Creates(t, prop)
                        : OffersInPatch(t, prop)
                    )
                )
                {
                    continue;
                }
                result.Add(
                    new WriteProperty(p, prop, type, false, Spec.Bool(p, "collection"), true, false) { Method = Names.Unique(Names.Member(prop), taken) }
                );
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
                var entity = _spec.IsEntity(Spec.Str(n, "type")!);
                var set = _spec.VerifiedOnly ? offered.Contains(nav) : create || OffersInPatch(t, nav);
                var bind = entity && (_spec.VerifiedOnly ? offered.Contains(nav + "@odata.bind") : set);
                if (!set && !bind)
                {
                    continue;
                }
                var w = new WriteProperty(n, nav, type, true, collection, set, bind);
                if (set)
                {
                    w.Method = Names.Unique(Names.Member(nav), taken);
                }
                if (bind)
                {
                    w.BindMethod = Names.Unique("Bind" + Names.Member(nav), taken);
                }
                result.Add(w);
            }
        }
        return result;
    }

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
        var properties = Writable(t, create);
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
            EmitFrom(t, create, builderName, properties);
        }
        else if (create)
        {
            // A hierarchy's root: its create starts from a read of any of its types, with that type's
            // Builder(read). Its builders don't derive from this one's, so it's returned as what builds it.
            _out.Append(
                $"    /// <summary>A create of what <paramref name=\"read\"/> holds that a POST applies, to recreate it, by its type: that type's <c>Builder(read)</c>. Change it with that builder's setters.</summary>\n"
            );
            _out.Append("    /// <param name=\"read\">An entity read from TM1.</param>\n");
            _out.Append("    /// <exception cref=\"ArgumentException\">TM1 makes none of its type.</exception>\n");
            _out.Append($"    public static IBuilder<{patch}> Builder({name} read) =>\n        read switch\n        {{\n");
            foreach (var sub in _spec.Derived(t).Where(Creatable))
            {
                var subName = Spec.Str(sub, "name")!;
                _out.Append($"            {subName} v => {subName}Create.Builder(v),\n");
            }
            _out.Append(
                "            _ => throw new ArgumentException(\"A \" + read.GetType().Name + \" has no create: TM1 makes none of its type\", nameof(read)),\n        };\n\n"
            );
        }
        else
        {
            // A hierarchy's root: its patch reads a value of any of its types, by its type, for nested values
            _out.Append($"    /// <summary>A patch of what <paramref name=\"read\"/> holds that a write takes, by its type: for nested values.</summary>\n");
            _out.Append("    /// <param name=\"read\">A value read from TM1.</param>\n");
            _out.Append($"    internal static {patch} Of({name} read) =>\n        read switch\n        {{\n");
            foreach (var sub in _spec.Derived(t).Where(Patchable))
            {
                var subName = Spec.Str(sub, "name")!;
                _out.Append($"            {subName} v => {subName}Patch.Builder(v).Build(),\n");
            }
            _out.Append($"            _ => Unknown(read).Build(),\n        }};\n\n");
            EmitFrom(t, create, builderName, properties, "Unknown");
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
            _out.Append($"        public override {patch} Build()\n        {{\n");
            foreach (var r in required.EnumerateArray().Select(r => r.GetString()!.Replace("@odata.bind", "")))
            {
                _out.Append(
                    $"            if (!Has(\"{r}\"))\n            {{\n                throw new InvalidOperationException(\"A {name} create needs {r}: TM1 refuses one without it\");\n            }}\n"
                );
            }
            _out.Append("            return base.Build();\n        }\n");
        }
        foreach (var p in properties)
        {
            if (p.Set && !p.Navigation)
            {
                _out.Append($"\n        public {builderName} {p.Method}({p.Type} value) => Set(\"{p.Json}\", value);\n");
            }
            else if (p.Set)
            {
                _out.Append($"\n        /// <summary>Sets <c>{p.Json}</c> (nested, for a deep insert).</summary>\n");
                _out.Append($"        public {builderName} {p.Method}({p.Type} value) => Set(\"{p.Json}\", value);\n");
            }
            if (p.Bind && p.Collection)
            {
                _out.Append($"\n        /// <summary>Binds <c>{p.Json}</c> to existing entities (<c>{p.Json}@odata.bind</c>).</summary>\n");
                _out.Append($"        public {builderName} {p.BindMethod}(params ODataPath[] targets) => BindAll(\"{p.Json}\", targets);\n");
            }
            else if (p.Bind)
            {
                _out.Append($"\n        /// <summary>Binds <c>{p.Json}</c> to an existing entity (<c>{p.Json}@odata.bind</c>).</summary>\n");
                _out.Append($"        public {builderName} {p.BindMethod}(ODataPath target) => Bind(\"{p.Json}\", target);\n");
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

    /// <summary>
    /// <c>Builder(read)</c>: a builder holding what a read holds of the offered setters, nulls left out. A
    /// structured value goes through its own patch's; a patch leaves out what a PATCH doesn't apply and the
    /// collections it appends to (copying one would add its members again). A navigation the read holds
    /// expanded is bound where its targets' paths follow from it, though a patch binds only a single-valued
    /// one, never a collection (sent back, it would replace or append to what's there), and one held empty
    /// isn't bound; an open type's undeclared properties are copied, but for nulls and annotations. As the Java generator's <c>builder(read)</c>, in its order. For a root's patch, the private
    /// <c>Unknown(read)</c> its <c>Of</c> falls back to.
    /// </summary>
    private void EmitFrom(JsonElement t, bool create, string builderName, List<WriteProperty> properties, string method = "Builder")
    {
        var name = Spec.Str(t, "name")!;
        var members = ModelEmitter.MemberNames(_spec, t);
        var open = _spec.Chain(t).Any(l => Spec.Bool(l, "open"));
        var what = create ? "a POST applies, to recreate it" : "a PATCH applies, leaving out collections a PATCH appends to";
        if (method == "Builder")
        {
            _out.Append(
                $"    /// <summary>A builder holding what <paramref name=\"read\"/> holds that {what}: change it with the builder's setters. A navigation the read holds expanded is bound, where its targets' paths follow from it: an entity set's by key, an element's or member's by its <c>UniqueName</c>; one it doesn't hold, or holds empty, or a subset or a view (whose path the read can't tell), isn't, nor are nested ones or nulls{(create ? "" : ", nor a collection, which a PATCH would replace or append to")}.</summary>\n"
            );
            _out.Append("    /// <param name=\"read\">An entity read from TM1.</param>\n");
            _out.Append($"    public static {builderName} Builder({name} read)\n    {{\n");
        }
        else
        {
            _out.Append(
                $"    /// <summary>A builder holding what <paramref name=\"read\"/>, of none of the types derived from this one, holds that {what}.</summary>\n"
            );
            _out.Append($"    private static {builderName} {method}({name} read)\n    {{\n");
        }
        var statements = new List<string>();
        var run = new List<string>();
        var json = false;
        void Flush()
        {
            if (run.Count > 0)
            {
                statements.Add($"b.Copy(json, [{string.Join(", ", run.Select(c => $"\"{c}\""))}]);");
                run.Clear();
                json = true;
            }
        }
        foreach (var p in properties.Where(p => p.Set && !p.Navigation))
        {
            var prop = p.Json;
            if (Spec.Str(p.Property, "type") == "Edm.Stream" || (!create && !Patches(t, prop)))
            {
                continue;
            }
            if (p.Property.TryGetProperty("departure", out _) || !_spec.TypeByName.TryGetValue(Spec.Str(p.Property, "type")!, out var structured))
            {
                // A primitive, an enum or a departure: as the read wrote it
                run.Add(prop);
                continue;
            }
            Flush();
            var valueName = Spec.Str(structured, "name")!;
            var root = _spec.Derived(structured).Any();
            if (root && p.Type.Contains("Create"))
            {
                // A root's create has no Builder(read) to take a nested value with
                continue;
            }
            var write = root ? valueName + "Patch" : p.Type.TrimEnd('?').Replace("IEnumerable<", "").TrimEnd('>');
            string Convert(string v) => root ? $"{write}.Of({v})" : $"{write}.Builder({v}).Build()";
            var member = members[prop];
            var local = Local(member);
            statements.Add(
                p.Collection
                    ? $"if (read.{member} is {{ }} {local})\n{{\n    b.{p.Method}({local}.Select(v => {Convert("v")}).ToList());\n}}"
                    : $"if (read.{member} is {{ }} {local})\n{{\n    b.{p.Method}({Convert(local)});\n}}"
            );
        }
        Flush();
        // A patch from a read binds no collection: sent back, it would replace or append to what's there,
        // whichever TM1 does for it, on an unrelated change
        foreach (var p in properties.Where(p => p.Navigation && p.Bind && (create || !p.Collection)))
        {
            if (ReadBinding(p, members[p.Json]) is { } binding)
            {
                statements.Add(binding);
            }
        }
        if (open)
        {
            var declared = _spec
                .Chain(t)
                .SelectMany(l => Spec.Array(l, "properties").Concat(Spec.Array(l, "navigationProperties")))
                .Select(p => $"\"{Spec.Str(p, "name")}\"");
            statements.Add($"b.CopyUndeclared(json, [{string.Join(", ", declared)}]);");
            json = true;
        }
        if (json)
        {
            _out.Append("        var json = ReadJson(read);\n");
        }
        _out.Append($"        var b = new {builderName}();\n");
        foreach (var s in statements)
        {
            _out.Append(string.Join("\n", s.Split('\n').Select(l => "        " + l))).Append('\n');
        }
        _out.Append("        return b;\n    }\n\n");
    }

    /// <summary>
    /// The code binding a navigation a read holds: by key for a target in an entity set, by <c>UniqueName</c>
    /// for an element or a member; null for any other (a subset, public or private, or a view, whose cube the
    /// read doesn't say). A collection is bound only when it has members and every member's path can be made.
    /// </summary>
    private string? ReadBinding(WriteProperty p, string member)
    {
        if (!_spec.TypeByName.TryGetValue(Spec.Str(p.Property, "type")!, out var target) || Spec.Str(target, "kind") != "entity")
        {
            return null;
        }
        var set = _spec.EntitySet(target);
        var key = _spec.Key(target);
        var targetName = Spec.Str(target, "name")!;
        string helper;
        string argument;
        string collectionName;
        if (set is not null && key.Count == 1)
        {
            helper = p.Collection ? "ByKeys" : "ByKey";
            argument = ModelEmitter.MemberNames(_spec, target)[key[0]];
            collectionName = set;
        }
        else if (targetName is "Element" or "Member")
        {
            helper = p.Collection ? "AllInHierarchy" : "InHierarchy";
            argument = ModelEmitter.MemberNames(_spec, target)["UniqueName"];
            collectionName = targetName + "s";
        }
        else
        {
            return null;
        }
        var local = Local(member);
        var value = p.Collection ? $"{local}.Select(v => (object?)v.{argument}).ToList()" : $"{local}.{argument}";
        return $"if (read.{member} is {{ }} {local} && ReadBindings.{helper}(\"{collectionName}\", {value}) is {{ }} {local}Targets)\n{{\n    b.{p.BindMethod}({local}Targets);\n}}";
    }

    private static string Local(string member) => "read" + member;
}
