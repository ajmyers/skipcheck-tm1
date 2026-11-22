using System;
using System.Linq;
using System.Text.RegularExpressions;

namespace AjMyers.Tm1.Client.Version;

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

    public static Tm1Version Parse(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var trimmed = text.Trim();
        if (!Format().IsMatch(trimmed))
        {
            throw new ArgumentException($"Not a TM1 version: '{text}' (expected numbers separated by dots, like 11.8.03500.6)");
        }
        var parts = trimmed.Split('.').Select(p => int.TryParse(p, out var n) ? n : throw new ArgumentException($"Not a TM1 version: '{text}' (part '{p}' is too large)")).ToArray();
        return new Tm1Version(trimmed, parts);
    }

    public bool IsAtLeast(Tm1Version other) => CompareTo(other) >= 0;

    public bool IsBefore(Tm1Version other) => CompareTo(other) < 0;

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

    public bool Equals(Tm1Version? other) => other is not null && CompareTo(other) == 0;

    public override bool Equals(object? obj) => obj is Tm1Version v && Equals(v);

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

    public override string ToString() => _text;

    public static bool operator <(Tm1Version a, Tm1Version b) => a.CompareTo(b) < 0;

    public static bool operator >(Tm1Version a, Tm1Version b) => a.CompareTo(b) > 0;

    public static bool operator <=(Tm1Version a, Tm1Version b) => a.CompareTo(b) <= 0;

    public static bool operator >=(Tm1Version a, Tm1Version b) => a.CompareTo(b) >= 0;

    [GeneratedRegex(@"^\d+(\.\d+)*$")]
    private static partial Regex Format();
}
