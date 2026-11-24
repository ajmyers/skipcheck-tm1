// SPDX-License-Identifier: Apache-2.0

using System;

namespace Skipcheck.Tm1.Client.Version;

/// <summary>How a member departs from <c>$metadata</c>, as the Java client's <c>Tm1Departure.Kind</c>.</summary>
public enum Tm1DepartureKind
{
    /// <summary>TM1 sends it, but <c>$metadata</c> doesn't declare it.</summary>
    Undeclared,

    /// <summary>TM1 sends it in another type than <c>$metadata</c> declares: it's read as JSON.</summary>
    Type,

    /// <summary>A date-time TM1 sometimes sends something else in: that reads as null.</summary>
    LenientDate,
}

/// <summary>A property of the generated model that departs from TM1's <c>$metadata</c>: one
/// <c>$metadata</c> doesn't declare, one TM1 sends in another type than declared, or a date TM1
/// sometimes garbles. The model carries it so every departure can be found, and checked again on a
/// new TM1 version, as the Java client's <c>@Tm1Departure</c>. Undeclared properties come from
/// probing a running TM1 (<c>./gradlew probeTm1</c>); the others from <c>spec/tm1-overlay.json</c>.
/// Every client tags the same members.</summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class Tm1DepartureAttribute(Tm1DepartureKind value) : Attribute
{
    /// <summary>How the member departs.</summary>
    public Tm1DepartureKind Value { get; } = value;

    /// <summary>The TM1 schema versions it was seen on, such as <c>11.8.35</c>; empty when it
    /// wasn't found by a probe.</summary>
    public string[] ObservedIn { get; init; } = [];

    /// <summary>What TM1 does, in words.</summary>
    public string Note { get; init; } = "";
}
