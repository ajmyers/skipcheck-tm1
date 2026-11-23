// SPDX-License-Identifier: Apache-2.0

using System;
using System.Globalization;
using System.Numerics;
using System.Xml;
using Skipcheck.Tm1.Client.Json;

namespace Skipcheck.Tm1.Client.OData;

/// <summary>Something that writes itself as an OData literal.</summary>
public interface IODataLiteral
{
    /// <summary>The literal, exactly as it should appear before percent-encoding.</summary>
    string ODataLiteral();
}

/// <summary>OData literals, as the Java client's <c>ODataLiterals</c>, for keys, function parameters
/// and filters.</summary>
public static class ODataLiterals
{
    /// <summary><c>O'Neil</c> → <c>'O''Neil'</c>.</summary>
    public static string String(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return "'" + value.Replace("'", "''") + "'";
    }

    /// <summary><c>ibm.tm1.api.v1.ElementType'Numeric'</c>.</summary>
    public static string EnumMember(string qualifiedType, string member)
    {
        ArgumentNullException.ThrowIfNull(qualifiedType);
        return qualifiedType + String(member);
    }

    /// <summary>A value as an OData literal: <c>null</c>, quoted strings, <c>true</c>/<c>false</c>,
    /// numbers as plain decimals (<c>NaN</c>, <c>INF</c>), the generated enums as
    /// <c>ibm.tm1.api.v1.Type'Member'</c>, dates and times in ISO 8601, durations as
    /// <c>duration'P…'</c>; <see cref="Raw"/> as is.</summary>
    public static string Of(object? value) =>
        value switch
        {
            null => "null",
            IODataLiteral literal => literal.ODataLiteral(),
            string s => String(s),
            bool b => b ? "true" : "false",
            Enum e when Tm1EnumConverter.Describe(e.GetType()) is { } d => d.Literal(e),
            decimal m => m.ToString(CultureInfo.InvariantCulture),
            int or long or short or byte or sbyte or uint or ulong or ushort or BigInteger => Convert.ToString(value, CultureInfo.InvariantCulture)!, // null only for a null value, and this is a number
            double d => Double(d),
            float f => Double(f),
            DateTimeOffset dto => dto.ToString(
                dto.Offset == TimeSpan.Zero ? "yyyy-MM-dd'T'HH:mm:ss.FFFFFFF'Z'" : "yyyy-MM-dd'T'HH:mm:ss.FFFFFFFzzz",
                CultureInfo.InvariantCulture
            ),
            DateOnly date => date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            TimeOnly time => time.ToString("HH:mm:ss.FFFFFFF", CultureInfo.InvariantCulture),
            TimeSpan span => "duration'" + XmlConvert.ToString(span) + "'",
            Guid g => g.ToString(),
            _ => throw new ArgumentException($"No OData literal for {value.GetType().FullName}; pass ODataLiterals.Raw for a preformed literal"),
        };

    private static string Double(double d)
    {
        if (double.IsNaN(d))
        {
            return "NaN";
        }
        if (double.IsInfinity(d))
        {
            return d > 0 ? "INF" : "-INF";
        }
        // As Java's BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString()
        return decimal.TryParse(d.ToString("R", CultureInfo.InvariantCulture), NumberStyles.Float, CultureInfo.InvariantCulture, out var m)
            ? m.ToString("0.############################", CultureInfo.InvariantCulture)
            : d.ToString("R", CultureInfo.InvariantCulture);
    }

    /// <summary>A preformed literal, sent as is.</summary>
    public sealed record Raw(string Literal) : IODataLiteral
    {
        /// <inheritdoc/>
        public string ODataLiteral() => Literal;
    }
}
