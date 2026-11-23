using System;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>The endpoints of the REST API a method implements, as coverage-gate IDs
/// (<c>EntitySet Cubes</c>, <c>Action Cube/Reserve</c>), as the Java client's <c>@Tm1Endpoint</c>.
/// The coverage gate counts them.</summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = true)]
public sealed class Tm1EndpointAttribute(string value) : Attribute
{
    public string Value { get; } = value;
}
