using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Xml;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Json;

/// <summary>Marks a generated enum: its qualified CSDL name, for OData literals.</summary>
[AttributeUsage(AttributeTargets.Enum)]
public sealed class Tm1EnumAttribute(string qualifiedName) : Attribute
{
    public string QualifiedName { get; } = qualifiedName;
}

/// <summary>A generated enum member's name as TM1 writes it.</summary>
[AttributeUsage(AttributeTargets.Field)]
public sealed class Tm1EnumMemberAttribute(string name) : Attribute
{
    public string Name { get; } = name;
}

/// <summary>The member a value this client doesn't know reads as; it's never sent.</summary>
[AttributeUsage(AttributeTargets.Field)]
public sealed class Tm1EnumFallbackAttribute : Attribute
{
}

/// <summary>The JSON options every request and answer uses: TM1's enum names with a fallback for
/// new values, ISO 8601 durations (TM1's <c>P1DT00H00M00S</c> too, and bare seconds), hierarchies
/// read by <c>@odata.type</c>, patches written as set, nulls not written for models.</summary>
public static class Tm1Json
{
    public static JsonSerializerOptions Options { get; } = Create();

    private static JsonSerializerOptions Create()
    {
        var options = new JsonSerializerOptions
        {
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
            NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
            // TM1's names have quotes and non-ASCII letters: write them as they are (this isn't HTML)
            Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        };
        options.Converters.Add(new Tm1EnumConverterFactory());
        options.Converters.Add(new Iso8601TimeSpanConverter());
        options.Converters.Add(new Iso8601DateTimeOffsetConverter());
        options.Converters.Add(new Tm1PatchConverter());
        options.Converters.Add(new Tm1PolymorphicConverterFactory());
        options.MakeReadOnly(populateMissingResolver: true);
        return options;
    }

    public static T? Read<T>(JsonElement element) => element.Deserialize<T>(Options);

    public static object? Read(JsonElement element, Type type) => element.Deserialize(type, Options);

    public static JsonElement Write(object? value) => JsonSerializer.SerializeToElement(value, Options);
}

/// <summary>Reads and writes a generated enum by TM1's names.</summary>
public static class Tm1EnumConverter
{
    private static readonly ConcurrentDictionary<Type, Description?> Cache = new();

    /// <summary>What a generated enum is called in TM1, or null for another enum.</summary>
    public static Description? Describe(Type type) => Cache.GetOrAdd(type, t =>
    {
        if (!t.IsEnum || t.GetCustomAttribute<Tm1EnumAttribute>() is not { } e)
        {
            return null;
        }
        var byName = new Dictionary<string, object>();
        var byValue = new Dictionary<object, string>();
        object? fallback = null;
        foreach (var field in t.GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            var value = field.GetValue(null)!;
            if (field.GetCustomAttribute<Tm1EnumMemberAttribute>() is { } m)
            {
                byName[m.Name] = value;
                byValue[value] = m.Name;
            }
            else if (field.GetCustomAttribute<Tm1EnumFallbackAttribute>() is not null)
            {
                fallback = value;
            }
        }
        return new Description(e.QualifiedName, byName, byValue, fallback!);
    });

    public sealed record Description(string QualifiedName, IReadOnlyDictionary<string, object> ByName, IReadOnlyDictionary<object, string> ByValue, object Fallback)
    {
        public string Name(Enum value) => ByValue.TryGetValue(value, out var n) ? n
            : throw new InvalidOperationException($"{value.GetType().Name}.{value} stands for a value this client doesn't know, and isn't sent");

        public string Literal(Enum value) => ODataLiterals.EnumMember(QualifiedName, Name(value));
    }
}

internal sealed class Tm1EnumConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type typeToConvert) => Tm1EnumConverter.Describe(typeToConvert) is not null;

    public override JsonConverter CreateConverter(Type typeToConvert, JsonSerializerOptions options) =>
        (JsonConverter)Activator.CreateInstance(typeof(Converter<>).MakeGenericType(typeToConvert))!;

    private sealed class Converter<T> : JsonConverter<T>
        where T : struct, Enum
    {
        private readonly Tm1EnumConverter.Description _d = Tm1EnumConverter.Describe(typeof(T))!;

        public override T Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            var name = reader.GetString();
            return (T)(name is not null && _d.ByName.TryGetValue(name, out var v) ? v : _d.Fallback);
        }

        public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options) => writer.WriteStringValue(_d.Name(value));
    }
}

/// <summary>Durations as ISO 8601: TM1 writes <c>P1DT00H00M00S</c> (and sometimes bare seconds), and
/// accepts the standard form.</summary>
public sealed class Iso8601TimeSpanConverter : JsonConverter<TimeSpan>
{
    public override TimeSpan Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) => reader.TokenType switch
    {
        JsonTokenType.Number => TimeSpan.FromSeconds(reader.GetDouble()),
        _ => XmlConvert.ToTimeSpan(reader.GetString()!),
    };

    public override void Write(Utf8JsonWriter writer, TimeSpan value, JsonSerializerOptions options) => writer.WriteStringValue(Format(value));

    /// <summary>As the Java client writes a duration (<c>Duration.toString</c>): hours, minutes and
    /// seconds, never days (<c>PT26H3.5S</c>, <c>PT1H30M</c>, <c>PT0S</c>).</summary>
    internal static string Format(TimeSpan value)
    {
        const long PerSecond = TimeSpan.TicksPerSecond;
        var ticks = value.Ticks;
        if (ticks == 0)
        {
            return "PT0S";
        }
        // As Java's Duration: whole seconds (rounded down) and a positive fraction
        var seconds = Math.DivRem(ticks, PerSecond, out var fraction);
        if (fraction < 0)
        {
            fraction += PerSecond;
            seconds--;
        }
        var total = seconds < 0 && fraction > 0 ? seconds + 1 : seconds;
        var hours = total / 3600;
        var minutes = total % 3600 / 60;
        var secs = total % 60;
        var b = new System.Text.StringBuilder("PT");
        if (hours != 0)
        {
            b.Append(hours.ToString(CultureInfo.InvariantCulture)).Append('H');
        }
        if (minutes != 0)
        {
            b.Append(minutes.ToString(CultureInfo.InvariantCulture)).Append('M');
        }
        if (secs == 0 && fraction == 0 && b.Length > 2)
        {
            return b.ToString();
        }
        b.Append(seconds < 0 && fraction > 0 && secs == 0 ? "-0" : secs.ToString(CultureInfo.InvariantCulture));
        if (fraction > 0)
        {
            var digits = (seconds < 0 ? 2 * PerSecond - fraction : fraction + PerSecond).ToString(CultureInfo.InvariantCulture);
            b.Append('.').Append(digits[1..].TrimEnd('0'));
        }
        return b.Append('S').ToString();
    }
}

/// <summary>Date-times as the Java client has them: read as their instant in UTC, written in ISO 8601
/// with the fraction of a second without trailing zeros and <c>Z</c> for UTC
/// (<c>2026-01-02T03:04:00.12Z</c>).</summary>
public sealed class Iso8601DateTimeOffsetConverter : JsonConverter<DateTimeOffset>
{
    public override DateTimeOffset Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.GetDateTimeOffset().ToUniversalTime();

    public override void Write(Utf8JsonWriter writer, DateTimeOffset value, JsonSerializerOptions options) =>
        writer.WriteStringValue(Format(value));

    internal static string Format(DateTimeOffset value) => value.ToString(
        value.Offset == TimeSpan.Zero ? "yyyy-MM-dd'T'HH:mm:ss.FFFFFFF'Z'" : "yyyy-MM-dd'T'HH:mm:ss.FFFFFFFzzz", CultureInfo.InvariantCulture);
}

/// <summary>A date-time TM1 sometimes sends something else in (<c>0000-00-00T00:00Z</c>): read as null.</summary>
public sealed class LenientDateTimeOffsetConverter : JsonConverter<DateTimeOffset?>
{
    public override DateTimeOffset? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.TokenType == JsonTokenType.String && DateTimeOffset.TryParse(reader.GetString(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var d) ? d.ToUniversalTime() : null;

    public override void Write(Utf8JsonWriter writer, DateTimeOffset? value, JsonSerializerOptions options)
    {
        if (value is { } v)
        {
            writer.WriteStringValue(Iso8601DateTimeOffsetConverter.Format(v));
        }
        else
        {
            writer.WriteNullValue();
        }
    }
}

/// <summary>Writes a patch: the values set, in order, explicit nulls included.</summary>
internal sealed class Tm1PatchConverter : JsonConverter<Tm1Patch>
{
    public override bool CanConvert(Type typeToConvert) => typeof(Tm1Patch).IsAssignableFrom(typeToConvert);

    public override Tm1Patch Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        throw new NotSupportedException("Patches are written, never read");

    public override void Write(Utf8JsonWriter writer, Tm1Patch value, JsonSerializerOptions options)
    {
        writer.WriteStartObject();
        foreach (var (name, v) in value.Values)
        {
            writer.WritePropertyName(name);
            JsonSerializer.Serialize(writer, v, v?.GetType() ?? typeof(object), options);
        }
        writer.WriteEndObject();
    }
}

/// <summary>Reads the root of a hierarchy (<c>View</c>) as the type <c>@odata.type</c> names
/// (<c>NativeView</c>); a type this client doesn't know reads as the root.</summary>
internal sealed class Tm1PolymorphicConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type typeToConvert) => Tm1ModelRegistry.Hierarchies.ContainsKey(typeToConvert);

    public override JsonConverter CreateConverter(Type typeToConvert, JsonSerializerOptions options) =>
        (JsonConverter)Activator.CreateInstance(typeof(Converter<>).MakeGenericType(typeToConvert))!;

    private static readonly ConcurrentDictionary<JsonSerializerOptions, JsonSerializerOptions> Plain = new();

    /// <summary>The same options without this converter, to read the chosen type itself.</summary>
    private static JsonSerializerOptions Without(JsonSerializerOptions options) => Plain.GetOrAdd(options, o =>
    {
        var copy = new JsonSerializerOptions(o);
        foreach (var c in copy.Converters.OfType<Tm1PolymorphicConverterFactory>().ToList())
        {
            copy.Converters.Remove(c);
        }
        copy.MakeReadOnly(populateMissingResolver: true);
        return copy;
    });

    private sealed class Converter<T> : JsonConverter<T>
    {
        private readonly IReadOnlyDictionary<string, Type> _types = Tm1ModelRegistry.Hierarchies[typeof(T)];

        public override T? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            using var doc = JsonDocument.ParseValue(ref reader);
            var type = typeof(T);
            if (doc.RootElement.ValueKind == JsonValueKind.Object
                && doc.RootElement.TryGetProperty(ODataJson.Type, out var t)
                && t.GetString() is { } name
                && _types.TryGetValue(name.TrimStart('#'), out var chosen))
            {
                type = chosen;
            }
            return (T?)doc.RootElement.Deserialize(type, Without(options));
        }

        public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options) =>
            JsonSerializer.Serialize(writer, value, value!.GetType(), Without(options));
    }
}
