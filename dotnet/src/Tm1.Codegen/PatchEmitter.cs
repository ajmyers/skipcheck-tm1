// SPDX-License-Identifier: Apache-2.0

using System;
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

    /// <summary>A write type's plan (the spec's <c>builders</c>), which the Java generator works out once.</summary>
    private JsonElement Plan(JsonElement t, bool create) => _spec.Builders[Spec.Str(t, "name") + (create ? "Create" : "Patch")];

    /// <summary>The type a setter takes: the write type the plan gives a structured value, else the model's.</summary>
    private string? PatchType(string typeName, bool collection, string? value)
    {
        if (value is not null)
        {
            return collection ? $"IEnumerable<{value}>?" : value + "?";
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

    /// <summary>Whether a type gets a create: the write plan has one.</summary>
    private bool Creatable(JsonElement t) => _spec.Builders.ContainsKey(Spec.Str(t, "name") + "Create");

    /// <summary>Whether a type gets a patch: the write plan has one.</summary>
    private bool Patchable(JsonElement t) => _spec.Builders.ContainsKey(Spec.Str(t, "name") + "Patch");

    /// <summary>A write type's property, for its setter and bind methods: <c>Set</c> whether it has its
    /// setter (for a navigation, the nested one), <c>Bind</c> whether a navigation to an entity has its
    /// <c>@odata.bind</c> one; <c>Type</c> the value the setter takes.</summary>
    private sealed record WriteProperty(JsonElement Property, string Json, string Type, bool Navigation, bool Collection, bool Set, bool Bind)
    {
        public string Method { get; set; } = "";

        public string BindMethod { get; set; } = "";
    }

    /// <summary>
    /// What a write type of <paramref name="t"/> offers, in the order its setters are declared: the setters the
    /// write plan lists for it.
    /// </summary>
    private List<WriteProperty> Writable(JsonElement t, bool create)
    {
        var plan = Spec.Array(Plan(t, create), "setters").ToList();
        var result = new List<WriteProperty>();
        var taken = new HashSet<string> { "Build", "Property", "Set", "Bind", "BindAll" };
        foreach (var level in _spec.Chain(t))
        {
            JsonElement? Setter(string name) =>
                plan.Where(s => Spec.Str(s, "json") == name && Spec.Str(s, "declaredOn") == Spec.Str(level, "qualifiedName"))
                    .Cast<JsonElement?>()
                    .FirstOrDefault();
            foreach (var p in Spec.Array(level, "properties"))
            {
                var prop = Spec.Str(p, "name")!;
                if (Setter(prop) is not { } s || !Spec.Bool(s, "set"))
                {
                    continue;
                }
                var type = p.TryGetProperty("departure", out _) ? "object?" : PatchType(Spec.Str(p, "type")!, Spec.Bool(p, "collection"), Spec.Str(s, "value"));
                if (type is null)
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
                if (Setter(nav) is not { } s || PatchType(Spec.Str(n, "type")!, collection, Spec.Str(s, "value")) is not { } type)
                {
                    continue;
                }
                var set = Spec.Bool(s, "set");
                var bind = Spec.Bool(s, "bind");
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
        var plan = Plan(t, create);
        // A subtype's base: its root's write type
        var baseType = Spec.Str(plan, "base");
        var hasDerived = plan.GetProperty("dispatch").ValueKind == JsonValueKind.Array;
        var basePatch = baseType ?? "Tm1Patch";
        var typeId = Spec.Str(plan, "odataType") is { } id ? $"\"{id}\"" : "null";
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
            foreach (var c in Spec.Array(plan, "dispatch"))
            {
                var subName = Spec.Str(_spec.TypeByName[Spec.Str(c, "type")!], "name")!;
                _out.Append($"            {subName} v => {Spec.Str(c, "builder")}.Builder(v),\n");
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
            foreach (var c in Spec.Array(plan, "dispatch"))
            {
                var subName = Spec.Str(_spec.TypeByName[Spec.Str(c, "type")!], "name")!;
                _out.Append($"            {subName} v => {Spec.Str(c, "builder")}.Builder(v).Build(),\n");
            }
            _out.Append($"            _ => Unknown(read).Build(),\n        }};\n\n");
            EmitFrom(t, create, builderName, properties, "Unknown");
        }
        _out.Append($"    /// <summary>Builds a <see cref=\"{patch}\"/>; each setter replaces what an earlier call set.</summary>\n");
        _out.Append($"    public {hide}sealed class {builderName} : BuilderBase<{patch}, {builderName}>\n    {{\n");
        _out.Append($"        public {builderName}()\n            : base(v => new {patch}(v), {typeId})\n        {{\n        }}\n");
        var required = create ? Spec.Array(Plan(t, true), "required").Select(r => r.GetString()!).ToList() : [];
        if (required.Count > 0)
        {
            _out.Append($"\n        /// <summary>The create, with what's been set so far.</summary>\n");
            _out.Append($"        /// <exception cref=\"InvalidOperationException\">It lacks what a create needs.</exception>\n");
            _out.Append($"        public override {patch} Build()\n        {{\n");
            foreach (var r in required)
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
        if (Spec.Bool(plan, "open"))
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
        var from = Plan(t, create).GetProperty("fromRead");
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
        foreach (var c in Spec.Array(from, "copy"))
        {
            var prop = Spec.Str(c, "json")!;
            if (Spec.Str(c, "via") is not { } via)
            {
                // A primitive, an enum or a departure: as the read wrote it
                run.Add(prop);
                continue;
            }
            Flush();
            var p = properties.First(p => p.Set && !p.Navigation && p.Json == prop);
            var write = Spec.Str(c, "value")!;
            string Convert(string v) => via == "of" ? $"{write}.Of({v})" : $"{write}.Builder({v}).Build()";
            var member = members[prop];
            var local = Local(member);
            statements.Add(
                p.Collection
                    ? $"if (read.{member} is {{ }} {local})\n{{\n    b.{p.Method}({local}.Select(v => {Convert("v")}).ToList());\n}}"
                    : $"if (read.{member} is {{ }} {local})\n{{\n    b.{p.Method}({Convert(local)});\n}}"
            );
        }
        Flush();
        foreach (var binding in Spec.Array(from, "bindings"))
        {
            var p = properties.First(p => p.Navigation && p.Json == Spec.Str(binding, "json"));
            statements.Add(ReadBinding(binding, p, members[p.Json]));
        }
        if (from.TryGetProperty("undeclared", out var undeclared) && undeclared.ValueKind == JsonValueKind.Array)
        {
            var declared = undeclared.EnumerateArray().Select(n => $"\"{n.GetString()}\"");
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
    /// The code binding a navigation a read holds, as the write plan says: by key for a target in an entity set,
    /// by <c>UniqueName</c> for an element or a member. A collection is bound only when it has members and every
    /// member's path can be made.
    /// </summary>
    private string ReadBinding(JsonElement binding, WriteProperty p, string member)
    {
        var target = _spec.TypeByName[Spec.Str(p.Property, "type")!];
        var byKey = Spec.Str(binding, "by") switch
        {
            "key" => true,
            "uniqueName" => false,
            var by => throw new InvalidOperationException($"A binding by {by}: by key or uniqueName"),
        };
        var helper = byKey ? (p.Collection ? "ByKeys" : "ByKey") : (p.Collection ? "AllInHierarchy" : "InHierarchy");
        var argument = ModelEmitter.MemberNames(_spec, target)[Spec.Str(binding, "key")!];
        var collectionName = Spec.Str(binding, "set")!;
        var local = Local(member);
        var value = p.Collection ? $"{local}.Select(v => (object?)v.{argument}).ToList()" : $"{local}.{argument}";
        return $"if (read.{member} is {{ }} {local} && ReadBindings.{helper}(\"{collectionName}\", {value}) is {{ }} {local}Targets)\n{{\n    b.{p.BindMethod}({local}Targets);\n}}";
    }

    private static string Local(string member) => "read" + member;
}
