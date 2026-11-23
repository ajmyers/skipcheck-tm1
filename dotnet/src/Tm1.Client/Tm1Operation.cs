using System;
using System.Collections.Generic;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client;

/// <summary>An action or function of the REST API, as <c>$metadata</c> declares it: what the
/// generated <c>Tm1Operations</c> catalog holds, as the Java client's <c>Tm1Operation</c>.</summary>
public sealed record Tm1Operation
{
    /// <summary>Whether an operation is an action or a function.</summary>
    public enum OperationKind
    {
        /// <summary>An action, invoked with <c>POST</c>; it may change the server's
        /// state.</summary>
        Action,

        /// <summary>A function, invoked with <c>GET</c>; it doesn't change anything.</summary>
        Function,
    }

    /// <summary>A parameter of an operation.</summary>
    /// <param name="Name">Its name.</param>
    /// <param name="Type">Its type, as <c>$metadata</c> writes it.</param>
    /// <param name="Nullable">Whether it may be null.</param>
    public sealed record Parameter(string Name, string Type, bool Nullable);

    /// <summary>An operation.</summary>
    /// <param name="kind">Whether it's an action or a function.</param>
    /// <param name="name">Its name, qualified as in <c>$metadata</c>, such as
    /// <c>tm1.Activate</c>.</param>
    /// <param name="bindingType">The qualified type it's bound to, such as <c>tm1.Chore</c>, or
    /// null for an operation invoked through an import from the service root.</param>
    /// <param name="importName">The import's name, such as <c>ExecuteMDX</c>, for an unbound
    /// operation; null for a bound one.</param>
    /// <param name="parameters">The parameters a caller passes, without the binding
    /// parameter.</param>
    /// <param name="returnType">The return type as <c>$metadata</c> writes it, such as
    /// <c>Collection(tm1.Cube)</c>, or null if it returns nothing.</param>
    /// <param name="addedIn">The schema version that added it, or null if not known.</param>
    /// <param name="deprecatedIn">The schema version that deprecated it, or null.</param>
    /// <exception cref="ArgumentException">It has both a binding type and an import name, or
    /// neither.</exception>
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

    /// <summary>Whether it's an action or a function.</summary>
    public OperationKind Kind { get; }

    /// <summary>Its name in a path: <c>tm1.Activate</c>.</summary>
    public string Name { get; }

    /// <summary>The qualified type it's bound to, such as <c>tm1.Chore</c>, or null for an
    /// operation invoked through an import from the service root.</summary>
    public string? BindingType { get; }

    /// <summary>The import's name, such as <c>ExecuteMDX</c>, for an unbound operation; null for a
    /// bound one.</summary>
    public string? ImportName { get; }

    /// <summary>The parameters a caller passes, without the binding parameter.</summary>
    public IReadOnlyList<Parameter> Parameters { get; }

    /// <summary>The return type as <c>$metadata</c> writes it, such as <c>Collection(tm1.Cube)</c>,
    /// or null if it returns nothing.</summary>
    public string? ReturnType { get; }

    /// <summary>The schema version that added it, or null if not known.</summary>
    public string? AddedIn { get; }

    /// <summary>The schema version that deprecated it, or null.</summary>
    public string? DeprecatedIn { get; }

    /// <summary>Whether it's bound to a type, rather than invoked from the service root.</summary>
    public bool Bound() => BindingType is not null;

    /// <summary>Whether <c>$metadata</c> marks it deprecated.</summary>
    public bool Deprecated() => DeprecatedIn is not null;

    /// <summary>Its coverage-gate ID: <c>Action Chore/Activate</c>, <c>ActionImport ExecuteMDX</c>.</summary>
    public string Endpoint()
    {
        var kind = Kind == OperationKind.Action ? "Action" : "Function";
        return BindingType is not null ? $"{kind} {Unqualified(BindingType)}/{Unqualified(Name)}" : $"{kind}Import {ImportName}";
    }

    /// <summary>What a request using it needs the server to have, with the version that added it,
    /// for <see cref="Tm1Request{T}.Requires"/>.</summary>
    public Tm1Capability Capability()
    {
        var capability = BindingType is not null ? Tm1Capability.BoundOperation(BindingType, Name) : Tm1Capability.UnboundOperation(Import);
        return AddedIn is null ? capability : capability.Since(AddedIn);
    }

    /// <summary>The import's name, for the services that call an unbound operation.</summary>
    internal string Import => ImportName ?? throw new InvalidOperationException($"{Name} is bound to {BindingType}: it has no import");

    private static string Unqualified(string name) => name[(name.LastIndexOf('.') + 1)..];
}
