using System;
using System.Collections.Generic;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client;

/// <summary>An action or function of the REST API, as <c>$metadata</c> declares it: what the
/// generated <c>Tm1Operations</c> catalog holds, as the Java client's <c>Tm1Operation</c>.</summary>
public sealed record Tm1Operation
{
    public enum OperationKind
    {
        Action,
        Function,
    }

    public sealed record Parameter(string Name, string Type, bool Nullable);

    public Tm1Operation(
        OperationKind kind,
        string name,
        string? bindingType,
        string? importName,
        IReadOnlyList<Parameter> parameters,
        string? returnType,
        string? addedIn,
        string? deprecatedIn
    )
    {
        ArgumentNullException.ThrowIfNull(name);
        if ((bindingType is null) == (importName is null))
        {
            throw new ArgumentException(name + " needs exactly one of a binding type and an import name");
        }
        Kind = kind;
        Name = name;
        BindingType = bindingType;
        ImportName = importName;
        Parameters = parameters;
        ReturnType = returnType;
        AddedIn = addedIn;
        DeprecatedIn = deprecatedIn;
    }

    public OperationKind Kind { get; }

    /// <summary>Its name in a path: <c>tm1.Activate</c>.</summary>
    public string Name { get; }

    public string? BindingType { get; }

    public string? ImportName { get; }

    public IReadOnlyList<Parameter> Parameters { get; }

    public string? ReturnType { get; }

    public string? AddedIn { get; }

    public string? DeprecatedIn { get; }

    public bool Bound() => BindingType is not null;

    public bool Deprecated() => DeprecatedIn is not null;

    /// <summary>Its coverage-gate ID: <c>Action Chore/Activate</c>, <c>ActionImport ExecuteMDX</c>.</summary>
    public string Endpoint()
    {
        var kind = Kind == OperationKind.Action ? "Action" : "Function";
        return BindingType is not null ? $"{kind} {Unqualified(BindingType)}/{Unqualified(Name)}" : $"{kind}Import {ImportName}";
    }

    public Tm1Capability Capability()
    {
        var capability = BindingType is not null ? Tm1Capability.BoundOperation(BindingType, Name) : Tm1Capability.UnboundOperation(ImportName!);
        return AddedIn is null ? capability : capability.Since(AddedIn);
    }

    private static string Unqualified(string name) => name[(name.LastIndexOf('.') + 1)..];
}
