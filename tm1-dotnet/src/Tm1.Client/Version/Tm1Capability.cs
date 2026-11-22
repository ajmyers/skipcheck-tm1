using System;

namespace AjMyers.Tm1.Client.Version;

/// <summary>Something a request needs the server's <c>$metadata</c> to define, as the Java
/// client's <c>Tm1Capability</c>.</summary>
public sealed record Tm1Capability
{
    public enum CapabilityKind
    {
        EntitySet,
        Singleton,
        BoundOperation,
        UnboundOperation,
        Property,
    }

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

    public CapabilityKind Kind { get; }

    public string? Type { get; }

    public string Name { get; }

    public string? AddedIn { get; }

    public static Tm1Capability EntitySet(string name) => new(CapabilityKind.EntitySet, null, name, null);

    public static Tm1Capability Singleton(string name) => new(CapabilityKind.Singleton, null, name, null);

    public static Tm1Capability BoundOperation(string bindingType, string operation) => new(CapabilityKind.BoundOperation, bindingType, operation, null);

    public static Tm1Capability UnboundOperation(string name) => new(CapabilityKind.UnboundOperation, null, name, null);

    public static Tm1Capability Property(string type, string property) => new(CapabilityKind.Property, type, property, null);

    public Tm1Capability Since(string schemaVersion) => new(Kind, Type, Name, schemaVersion ?? throw new ArgumentNullException(nameof(schemaVersion)));

    public string? AddedInVersion() => AddedIn;

    public string Describe() => Kind switch
    {
        CapabilityKind.EntitySet => "entity set " + Name,
        CapabilityKind.Singleton => "singleton " + Name,
        CapabilityKind.UnboundOperation => "operation " + Name,
        _ => Simple(Type!) + "." + Simple(Name),
    };

    private static string Simple(string qualified) => qualified[(qualified.LastIndexOf('.') + 1)..];
}
