// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace Skipcheck.Tm1.Codegen;

/// <summary>Emits Operations.g.cs: <c>Tm1Operations</c>, a <c>Tm1Operation</c> per action and
/// function, named as in the Java client (<c>CHORE_ACTIVATE</c> → <c>ChoreActivate</c>).</summary>
public sealed class OperationEmitter
{
    private readonly Spec _spec;

    public OperationEmitter(Spec spec) => _spec = spec;

    private string TypeText(JsonElement r)
    {
        var name = Spec.Str(r, "type")!;
        if (!name.StartsWith("Edm."))
        {
            name = _spec.AliasName(name);
        }
        return Spec.Bool(r, "collection") ? $"Collection({name})" : name;
    }

    private static string Literal(string? s) => s is null ? "null" : "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    public string Emit()
    {
        var o = new StringBuilder(ModelEmitter.Header);
        o.Append(
            """
            using System.Collections.Generic;
            using Skipcheck.Tm1.Client;

            namespace Skipcheck.Tm1.Client.Model;

            /// <summary>Every action and function of the TM1 REST API, as <c>$metadata</c> declares them.
            /// Services refer to these, so a request checks the server has the operation.</summary>
            public static class Tm1Operations
            {

            """
        );
        var taken = new HashSet<string> { "All" };
        var all = new List<string>();
        void Line(string constant, JsonElement op, string name, string? binding, string? import)
        {
            var parameters = string.Join(
                ", ",
                Spec.Array(op, "parameters").Select(p => $"new(\"{Spec.Str(p, "name")}\", \"{TypeText(p)}\", {(Spec.Bool(p, "nullable") ? "true" : "false")})")
            );
            var returns = op.TryGetProperty("returns", out var r) && r.ValueKind == JsonValueKind.Object ? Literal(TypeText(r)) : "null";
            var deprecated = op.TryGetProperty("deprecated", out var d) && d.ValueKind == JsonValueKind.Object ? Literal(Spec.Str(d, "version")) : "null";
            var kind = Spec.Str(op, "kind") == "action" ? "Action" : "Function";
            if (Spec.Str(op, "description") is { } desc)
            {
                o.Append("    /// <summary>")
                    .Append(Names.Doc(string.Join(" ", desc.Split((char[]?)null, System.StringSplitOptions.RemoveEmptyEntries))))
                    .Append("</summary>\n");
            }
            o.Append(
                $"    public static readonly Tm1Operation {constant} = new(Tm1Operation.OperationKind.{kind}, {Literal(name)}, {Literal(binding)}, {Literal(import)}, [{parameters}], {returns}, {Literal(Spec.Str(op, "since"))}, {deprecated});\n\n"
            );
            all.Add(constant);
        }
        foreach (
            var op in _spec
                .Operations.Where(op => Spec.Bool(op, "bound"))
                .OrderBy(op => Spec.Str(op.GetProperty("binding"), "type"), System.StringComparer.Ordinal)
                .ThenBy(op => Spec.Str(op, "name"), System.StringComparer.Ordinal)
        )
        {
            var binding = _spec.AliasName(Spec.Str(op.GetProperty("binding"), "type")!);
            var simple = binding[(binding.LastIndexOf('.') + 1)..];
            Line(Names.Unique(Names.Constant(simple) + Names.Constant(Spec.Str(op, "name")!), taken), op, Spec.Str(op, "pathName")!, binding, null);
        }
        var imports = Spec.Array(_spec.Container, "actionImports")
            .Concat(Spec.Array(_spec.Container, "functionImports"))
            .Select(i => (Name: Spec.Str(i, "name")!, Op: Spec.Str(i, "operation")!))
            .OrderBy(i => i.Name, System.StringComparer.Ordinal);
        var unbound = _spec.Operations.Where(op => !Spec.Bool(op, "bound")).ToDictionary(op => Spec.Str(op, "qualifiedName")!);
        foreach (var (name, qualified) in imports)
        {
            Line(Names.Unique(Names.Constant(name), taken), unbound[qualified], _spec.AliasName(qualified), null, name);
        }
        o.Append("    /// <summary>Every operation above, bound ones first.</summary>\n");
        o.Append("    public static readonly IReadOnlyList<Tm1Operation> All = [" + string.Join(", ", all) + "];\n}\n");
        return o.ToString();
    }
}
