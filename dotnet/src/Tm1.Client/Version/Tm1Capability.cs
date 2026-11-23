// SPDX-License-Identifier: Apache-2.0

using System;

namespace Skipcheck.Tm1.Client.Version;

/// <summary>Something a request needs the server's <c>$metadata</c> to define, as the Java
/// client's <c>Tm1Capability</c>.</summary>
public sealed record Tm1Capability
{
    /// <summary>The kinds of element a request can need.</summary>
    public enum CapabilityKind
    {
        /// <summary>An entity set in the entity container, such as <c>GitPlans</c>.</summary>
        EntitySet,

        /// <summary>A singleton in the entity container, such as <c>ActiveSession</c>.</summary>
        Singleton,

        /// <summary>An action or function bound to a type, such as
        /// <c>tm1.Cube/tm1.Reserve</c>.</summary>
        BoundOperation,

        /// <summary>An action or function import, invoked from the service root, such as
        /// <c>ExecuteMDX</c>.</summary>
        UnboundOperation,

        /// <summary>A structural or navigation property of a type, such as
        /// <c>tm1.Cube/Rules</c>.</summary>
        Property,
    }

    /// <summary>A capability.</summary>
    /// <param name="kind">What kind of element this is.</param>
    /// <param name="type">The qualified type it belongs to (a bound operation's binding type, a
    /// property's structured type), or null.</param>
    /// <param name="name">The element's name.</param>
    /// <param name="addedIn">The schema version that added it, or null if not known.</param>
    public Tm1Capability(CapabilityKind kind, string? type, string name, string? addedIn)
    {
        ArgumentNullException.ThrowIfNull(name);
        if (kind is CapabilityKind.BoundOperation or CapabilityKind.Property && type is null)
        {
            throw new ArgumentException(kind + " needs the type it belongs to");
        }
        Kind = kind;
        Type = type;
        Name = name;
        AddedIn = addedIn;
    }

    /// <summary>What kind of element this is.</summary>
    public CapabilityKind Kind { get; }

    /// <summary>The qualified type it belongs to (a bound operation's binding type, a property's
    /// structured type), or null.</summary>
    public string? Type { get; }

    /// <summary>The element's name.</summary>
    public string Name { get; }

    /// <summary>The schema version that added it, or null if not known.</summary>
    public string? AddedIn { get; }

    /// <summary>An entity set, such as <c>GitPlans</c>.</summary>
    public static Tm1Capability EntitySet(string name) => new(CapabilityKind.EntitySet, null, name, null);

    /// <summary>A singleton, such as <c>ActiveSession</c>.</summary>
    public static Tm1Capability Singleton(string name) => new(CapabilityKind.Singleton, null, name, null);

    /// <summary>An action or function bound to a type, or to one of its base types.</summary>
    /// <param name="bindingType">The qualified type, such as <c>tm1.Cube</c>.</param>
    /// <param name="operation">The qualified operation name, such as <c>tm1.Reserve</c>.</param>
    public static Tm1Capability BoundOperation(string bindingType, string operation) => new(CapabilityKind.BoundOperation, bindingType, operation, null);

    /// <summary>An action or function import, invoked from the service root, such as
    /// <c>ExecuteMDX</c>.</summary>
    public static Tm1Capability UnboundOperation(string name) => new(CapabilityKind.UnboundOperation, null, name, null);

    /// <summary>A structural or navigation property of a type, or of one of its base
    /// types.</summary>
    /// <param name="type">The qualified type, such as <c>tm1.Cube</c>.</param>
    /// <param name="property">The property's name, such as <c>Rules</c>.</param>
    public static Tm1Capability Property(string type, string property) => new(CapabilityKind.Property, type, property, null);

    /// <summary>A copy recording the schema version that added it, from <c>Core.Revisions</c>, such
    /// as <c>11.8.34</c>.</summary>
    public Tm1Capability Since(string schemaVersion) => new(Kind, Type, Name, schemaVersion ?? throw new ArgumentNullException(nameof(schemaVersion)));

    /// <summary>The schema version that added it, or null if not known.</summary>
    public string? AddedInVersion() => AddedIn;

    /// <summary>How it reads in messages, with aliases and namespaces dropped: <c>Cube.Reserve</c>,
    /// <c>entity set GitPlans</c>.</summary>
    public string Describe() =>
        Kind switch
        {
            CapabilityKind.EntitySet => "entity set " + Name,
            CapabilityKind.Singleton => "singleton " + Name,
            CapabilityKind.UnboundOperation => "operation " + Name,
            _ => Simple(Type!) + "." + Simple(Name), // a bound operation or a property: the constructor checks it has a type
        };

    private static string Simple(string qualified) => qualified[(qualified.LastIndexOf('.') + 1)..];
}
