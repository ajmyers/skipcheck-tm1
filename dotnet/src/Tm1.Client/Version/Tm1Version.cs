// SPDX-License-Identifier: Apache-2.0

using System;
using System.Linq;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.Version;

/// <summary>A TM1 version, such as <c>11.8.03500.6</c> or schema <c>11.8.35</c>: compared part by
/// part, missing parts as 0, as the Java client's <c>Tm1Version</c>.</summary>
public sealed partial class Tm1Version : IComparable<Tm1Version>, IEquatable<Tm1Version>
{
    private readonly string _text;
    private readonly int[] _parts;

    private Tm1Version(string text, int[] parts)
    {
        _text = text;
        _parts = parts;
    }

    /// <summary>Parses a version such as <c>11.8.03500.6</c>. Surrounding whitespace is
    /// ignored.</summary>
    /// <exception cref="ArgumentException">The text isn't dot-separated numbers.</exception>
    public static Tm1Version Parse(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var trimmed = text.Trim();
        if (!Format().IsMatch(trimmed))
        {
            throw new ArgumentException($"Not a TM1 version: '{text}' (expected numbers separated by dots, like 11.8.03500.6)");
        }
        var parts = trimmed
            .Split('.')
            .Select(p => int.TryParse(p, out var n) ? n : throw new ArgumentException($"Not a TM1 version: '{text}' (part '{p}' is too large)"))
            .ToArray();
        return new Tm1Version(trimmed, parts);
    }

    /// <summary>True if this version is the same as or newer than
    /// <paramref name="other"/>.</summary>
    public bool IsAtLeast(Tm1Version other) => CompareTo(other) >= 0;

    /// <summary>True if this version is older than <paramref name="other"/>.</summary>
    public bool IsBefore(Tm1Version other) => CompareTo(other) < 0;

    /// <inheritdoc/>
    public int CompareTo(Tm1Version? other)
    {
        if (other is null)
        {
            return 1;
        }
        var length = Math.Max(_parts.Length, other._parts.Length);
        for (int i = 0; i < length; i++)
        {
            var a = i < _parts.Length ? _parts[i] : 0;
            var b = i < other._parts.Length ? other._parts[i] : 0;
            if (a != b)
            {
                return a.CompareTo(b);
            }
        }
        return 0;
    }

    /// <inheritdoc/>
    public bool Equals(Tm1Version? other) => other is not null && CompareTo(other) == 0;

    /// <inheritdoc/>
    public override bool Equals(object? obj) => obj is Tm1Version v && Equals(v);

    /// <inheritdoc/>
    public override int GetHashCode()
    {
        var end = _parts.Length;
        while (end > 0 && _parts[end - 1] == 0)
        {
            end--;
        }
        var hash = new HashCode();
        foreach (var p in _parts.Take(end))
        {
            hash.Add(p);
        }
        return hash.ToHashCode();
    }

    /// <summary>The version exactly as parsed, such as <c>11.8.03500.6</c>.</summary>
    public override string ToString() => _text;

    /// <summary>True if <paramref name="a"/> is older than <paramref name="b"/>.</summary>
    public static bool operator <(Tm1Version a, Tm1Version b) => a.CompareTo(b) < 0;

    /// <summary>True if <paramref name="a"/> is newer than <paramref name="b"/>.</summary>
    public static bool operator >(Tm1Version a, Tm1Version b) => a.CompareTo(b) > 0;

    /// <summary>True if <paramref name="a"/> is the same as or older than
    /// <paramref name="b"/>.</summary>
    public static bool operator <=(Tm1Version a, Tm1Version b) => a.CompareTo(b) <= 0;

    /// <summary>True if <paramref name="a"/> is the same as or newer than
    /// <paramref name="b"/>.</summary>
    public static bool operator >=(Tm1Version a, Tm1Version b) => a.CompareTo(b) >= 0;

    /// <summary>True if both are the same version, part by part: <c>11.8</c> equals <c>11.8.0.0</c>.</summary>
    public static bool operator ==(Tm1Version? a, Tm1Version? b) => a is null ? b is null : a.Equals(b);

    /// <summary>True if they are different versions.</summary>
    public static bool operator !=(Tm1Version? a, Tm1Version? b) => !(a == b);

    [GeneratedRegex(@"^\d+(\.\d+)*$")]
    private static partial Regex Format();
}
