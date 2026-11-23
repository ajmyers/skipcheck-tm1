// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Numerics;
using System.Text.RegularExpressions;
using YamlDotNet.Core;
using YamlDotNet.Core.Events;
using Map = System.Collections.Generic.OrderedDictionary<object, object?>;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>Loads YAML as PyYAML's <c>yaml.safe_load</c> does, which pa-setup reads <c>pa-environment.yaml</c> with: one
/// document; plain scalars resolved as YAML 1.1 (null and <c>~</c>; true, false, yes, no, on and off; integers, octal with a
/// leading 0, hex, binary and base 60; floats); quoted scalars are strings. Mappings keep their order, and a key given twice
/// keeps the last value. Mappings are <see cref="OrderedDictionary{TKey, TValue}"/>, sequences lists of objects, integers
/// <see cref="long"/> (or <see cref="BigInteger"/>), floats <see cref="double"/>; a null key is <see cref="NullKey"/>.</summary>
internal static partial class Yaml11
{
    private const string Tag = "tag:yaml.org,2002:";

    /// <summary>The key of a mapping entry whose key is null.</summary>
    internal static readonly object NullKey = new NullKeyType();

    /// <summary>The document in <paramref name="text"/>, or null when there's none.</summary>
    /// <exception cref="YamlException">It isn't valid YAML, or holds more than one document.</exception>
    internal static object? Load(string text)
    {
        var parser = new Parser(new StringReader(text));
        parser.Consume<StreamStart>();
        if (parser.TryConsume<StreamEnd>(out _))
        {
            return null;
        }
        parser.Consume<DocumentStart>();
        var anchors = new Dictionary<string, object?>(StringComparer.Ordinal);
        var root = Node(parser, anchors);
        parser.Consume<DocumentEnd>();
        if (parser.Current is not StreamEnd)
        {
            throw new YamlException(parser.Current!.Start, parser.Current.End, "expected a single document in the stream");
        }
        return root;
    }

    private static object? Node(IParser parser, Dictionary<string, object?> anchors)
    {
        if (parser.TryConsume<AnchorAlias>(out var alias))
        {
            return anchors.TryGetValue(alias.Value.Value, out var found)
                ? found
                : throw new YamlException(alias.Start, alias.End, "found undefined alias " + alias.Value.Value);
        }
        object? node;
        NodeEvent start;
        if (parser.TryConsume<Scalar>(out var scalar))
        {
            start = scalar;
            node = Resolve(scalar);
        }
        else if (parser.TryConsume<SequenceStart>(out var sequence))
        {
            start = sequence;
            CheckTag(sequence, "seq");
            var list = new List<object?>();
            while (!parser.TryConsume<SequenceEnd>(out _))
            {
                list.Add(Node(parser, anchors));
            }
            node = list;
        }
        else
        {
            var mapping = parser.Consume<MappingStart>();
            start = mapping;
            CheckTag(mapping, "map");
            var map = new Map();
            while (!parser.TryConsume<MappingEnd>(out _))
            {
                var at = parser.Current!;
                var key = Node(parser, anchors);
                if (key is Map or List<object?>)
                {
                    throw new YamlException(at.Start, at.End, "found unhashable key");
                }
                map[key ?? NullKey] = Node(parser, anchors);
            }
            node = map;
        }
        if (!start.Anchor.IsEmpty)
        {
            anchors[start.Anchor.Value] = node;
        }
        return node;
    }

    private static void CheckTag(NodeEvent node, string expected)
    {
        if (!node.Tag.IsEmpty && !node.Tag.IsNonSpecific && node.Tag.Value != Tag + expected)
        {
            throw new YamlException(node.Start, node.End, "could not determine a constructor for the tag " + node.Tag.Value);
        }
    }

    private static object? Resolve(Scalar scalar)
    {
        var value = scalar.Value;
        if (!scalar.Tag.IsEmpty && !scalar.Tag.IsNonSpecific)
        {
            return scalar.Tag.Value switch
            {
                Tag + "str" => value,
                Tag + "null" => null,
                Tag + "bool" when Bool(value) is { } flag => flag,
                Tag + "int" when Int(value) is { } number => number,
                Tag + "float" when Float(value) is { } number => number,
                _ => throw new YamlException(scalar.Start, scalar.End, "could not determine a constructor for the tag " + scalar.Tag.Value),
            };
        }
        if (scalar.Style != ScalarStyle.Plain || scalar.Tag.IsNonSpecific)
        {
            return value;
        }
        if (NullPattern().IsMatch(value))
        {
            return null;
        }
        return Bool(value) ?? Int(value) ?? Float(value) ?? (object)value;
    }

    private static object? Bool(string value) =>
        value switch
        {
            "yes" or "Yes" or "YES" or "true" or "True" or "TRUE" or "on" or "On" or "ON" => true,
            "no" or "No" or "NO" or "false" or "False" or "FALSE" or "off" or "Off" or "OFF" => false,
            _ => null,
        };

    private static object? Int(string value)
    {
        if (!IntPattern().IsMatch(value))
        {
            return null;
        }
        var text = value.Replace("_", "", StringComparison.Ordinal);
        var negative = text.StartsWith('-');
        if (text[0] is '-' or '+')
        {
            text = text[1..];
        }
        BigInteger n;
        if (text == "0")
        {
            n = BigInteger.Zero;
        }
        else if (text.StartsWith("0b", StringComparison.Ordinal))
        {
            n = Digits(text[2..], 2);
        }
        else if (text.StartsWith("0x", StringComparison.Ordinal))
        {
            n = BigInteger.Parse("0" + text[2..], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
        }
        else if (text[0] == '0')
        {
            n = Digits(text[1..], 8);
        }
        else if (text.Contains(':', StringComparison.Ordinal))
        {
            n = BigInteger.Zero;
            foreach (var part in text.Split(':'))
            {
                n = n * 60 + BigInteger.Parse(part, CultureInfo.InvariantCulture);
            }
        }
        else
        {
            n = BigInteger.Parse(text, CultureInfo.InvariantCulture);
        }
        if (negative)
        {
            n = -n;
        }
        return n >= long.MinValue && n <= long.MaxValue ? (object)(long)n : n;
    }

    private static BigInteger Digits(string digits, int radix)
    {
        var n = BigInteger.Zero;
        foreach (var c in digits)
        {
            n = n * radix + (c - '0');
        }
        return n;
    }

    private static object? Float(string value)
    {
        if (!FloatPattern().IsMatch(value))
        {
            return null;
        }
        var text = value.Replace("_", "", StringComparison.Ordinal).ToLowerInvariant();
        var sign = 1.0;
        if (text[0] is '-' or '+')
        {
            sign = text[0] == '-' ? -1 : 1;
            text = text[1..];
        }
        if (text == ".inf")
        {
            return sign * double.PositiveInfinity;
        }
        if (text == ".nan")
        {
            return double.NaN;
        }
        if (text.Contains(':', StringComparison.Ordinal))
        {
            var n = 0.0;
            foreach (var part in text.Split(':'))
            {
                n = n * 60 + double.Parse(part, CultureInfo.InvariantCulture);
            }
            return sign * n;
        }
        return sign * double.Parse(text, NumberStyles.Float, CultureInfo.InvariantCulture);
    }

    /// <summary>A float as Python's <c>str()</c> writes it: <c>1.0</c>, <c>0.0001</c>, <c>1e-05</c>, <c>1e+16</c>, <c>inf</c>.</summary>
    internal static string PythonFloat(double d)
    {
        if (double.IsNaN(d))
        {
            return "nan";
        }
        if (double.IsInfinity(d))
        {
            return d > 0 ? "inf" : "-inf";
        }
        var sign = d < 0 || (d == 0 && double.IsNegative(d)) ? "-" : "";
        // The shortest digits that read back as d, and where the point goes in them
        var r = Math.Abs(d).ToString("R", CultureInfo.InvariantCulture).Split('E');
        var mantissa = r[0];
        var exponent = r.Length > 1 ? int.Parse(r[1], CultureInfo.InvariantCulture) : 0;
        var point = mantissa.IndexOf('.', StringComparison.Ordinal);
        var digits = mantissa.Replace(".", "", StringComparison.Ordinal);
        var at = (point < 0 ? mantissa.Length : point) + exponent;
        var lead = digits.Length - digits.TrimStart('0').Length;
        digits = digits.Trim('0');
        if (digits.Length == 0)
        {
            return sign + "0.0";
        }
        at -= lead;
        var x = at - 1;
        if (x is < -4 or >= 16)
        {
            return sign
                + digits[0]
                + (digits.Length > 1 ? "." + digits[1..] : "")
                + "e"
                + (x < 0 ? "-" : "+")
                + Math.Abs(x).ToString("00", CultureInfo.InvariantCulture);
        }
        if (at <= 0)
        {
            return sign + "0." + new string('0', -at) + digits;
        }
        if (at >= digits.Length)
        {
            return sign + digits + new string('0', at - digits.Length) + ".0";
        }
        return sign + digits[..at] + "." + digits[at..];
    }

    [GeneratedRegex("^(?:~|null|Null|NULL|)$")]
    private static partial Regex NullPattern();

    [GeneratedRegex("^(?:[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$")]
    private static partial Regex IntPattern();

    [GeneratedRegex(
        @"^(?:[-+]?(?:[0-9][0-9_]*)\.[0-9_]*(?:[eE][-+][0-9]+)?|\.[0-9_]+(?:[eE][-+][0-9]+)?|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\.[0-9_]*|[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN))$"
    )]
    private static partial Regex FloatPattern();

    private sealed class NullKeyType
    {
        public override string ToString() => "null";
    }
}
