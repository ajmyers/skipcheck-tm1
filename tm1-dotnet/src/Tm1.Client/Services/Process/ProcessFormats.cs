using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace AjMyers.Tm1.Client.Services;

/// <summary>What the process services share: parameters as TM1's name-value pairs, and dates and
/// times written as Java's <c>toString</c> writes them, which TM1 takes and keys by.</summary>
internal static class ProcessFormats
{
    /// <summary><paramref name="body"/> with <c>Parameters</c>, as <c>[{"Name": …, "Value": …}]</c>
    /// in order, if there are any.</summary>
    public static Dictionary<string, object?> WithParameters(Dictionary<string, object?> body, IReadOnlyDictionary<string, object?>? parameters)
    {
        if (parameters is { Count: > 0 })
        {
            body["Parameters"] = parameters.Select(p => new Dictionary<string, object?> { ["Name"] = p.Key, ["Value"] = p.Value }).ToList();
        }
        return body;
    }

    /// <summary>Java's <c>LocalDate.toString</c>: <c>2026-01-02</c>.</summary>
    public static string Date(DateOnly date) => date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    /// <summary>Java's <c>LocalTime.toString</c>: <c>02:00</c>, <c>02:00:30</c>, <c>02:00:30.500</c>.</summary>
    public static string Time(TimeOnly time) => Time(time.Hour, time.Minute, time.Second, time.Ticks % TimeSpan.TicksPerSecond);

    /// <summary>Java's <c>OffsetDateTime.toString</c>: <c>2026-01-02T03:04Z</c>, <c>…:05.123+01:00</c>.</summary>
    public static string DateTime(DateTimeOffset value)
    {
        var offset = value.Offset == TimeSpan.Zero ? "Z" : value.ToString("zzz", CultureInfo.InvariantCulture);
        return value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + "T"
            + Time(value.Hour, value.Minute, value.Second, value.Ticks % TimeSpan.TicksPerSecond) + offset;
    }

    private static string Time(int hour, int minute, int second, long ticks)
    {
        var text = string.Create(CultureInfo.InvariantCulture, $"{hour:00}:{minute:00}");
        if (second == 0 && ticks == 0)
        {
            return text;
        }
        text += string.Create(CultureInfo.InvariantCulture, $":{second:00}");
        if (ticks == 0)
        {
            return text;
        }
        var nanos = ticks * 100;
        return text + (nanos % 1_000_000 == 0 ? "." + (nanos / 1_000_000).ToString("000", CultureInfo.InvariantCulture)
            : nanos % 1000 == 0 ? "." + (nanos / 1000).ToString("000000", CultureInfo.InvariantCulture)
            : "." + nanos.ToString("000000000", CultureInfo.InvariantCulture));
    }
}
