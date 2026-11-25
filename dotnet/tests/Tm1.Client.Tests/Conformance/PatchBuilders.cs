// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>
/// A write as the generated builders make it, as the Java runner's PatchBuilders (#354): each key by the
/// setter that writes it, a nested write type's value by its own builder (a root's by the subtype its
/// <c>@odata.type</c> names), a binding by the bind setter from the path as written (already encoded, as
/// TM1 gets it), an open type's undeclared key by <c>Property</c>. Refused, as a caller couldn't send
/// them: a key no setter writes (a declared one included, on an open type too); a value that doesn't read
/// back as written (an enum TM1 doesn't have, a duration not written as the client writes it, a fraction
/// for a whole number, a date-time without its offset); a binding of the wrong shape; an
/// <c>@odata.type</c> the write type doesn't send.
/// </summary>
internal static class PatchBuilders
{
    private static readonly ConcurrentDictionary<Type, Dictionary<string, MethodInfo>> SettersOf = new();

    private static readonly JsonArray Types = JsonNode.Parse(File.ReadAllText(Repo.Spec))!["types"]!.AsArray();

    internal static object Build(Type patch, JsonObject json)
    {
        var type = Subtype(patch, json);
        var builder = Fresh(type);
        var setters = Setters(type);
        foreach (var (key, value) in json)
        {
            if (key == "@odata.type")
            {
                // The builder sends its own, which must be this one
                if (!Values(builder).TryGetValue("@odata.type", out var own) || own?.ToString() != value?.ToString())
                {
                    throw new ArgumentException($"{type.Name} doesn't send @odata.type {value}");
                }
                continue;
            }
            if (setters.TryGetValue(key, out var setter))
            {
                Invoke(setter, builder, Argument(setter, value));
            }
            else if (
                !key.StartsWith('@')
                && !Declared(type).Contains(key)
                && builder.GetType().GetMethod("Property", [typeof(string), typeof(object)]) is { } property
            )
            {
                Invoke(property, builder, [key, ConformanceCodec.Plain(value)]);
            }
            else
            {
                throw new ArgumentException($"{type.Name} has no setter for {key}");
            }
        }
        return Invoke(builder.GetType().GetMethod("Build", Type.EmptyTypes)!, builder, [])!;
    }

    private static Type Subtype(Type patch, JsonObject json)
    {
        if (json["@odata.type"] is not JsonValue id || id.GetValueKind() != JsonValueKind.String)
        {
            return patch;
        }
        var suffix = patch.Name.EndsWith("Create", StringComparison.Ordinal) ? "Create" : "Patch";
        var text = id.GetValue<string>();
        var sub = patch.Assembly.GetType($"{patch.Namespace}.{text[(text.LastIndexOf('.') + 1)..]}{suffix}");
        if (sub is null || !patch.IsAssignableFrom(sub))
        {
            throw new ArgumentException($"no write type for {text} that is a {patch.Name}");
        }
        return sub;
    }

    private static object?[] Argument(MethodInfo setter, JsonNode? value)
    {
        var parameter = setter.GetParameters()[0].ParameterType;
        if (parameter == typeof(ODataPath[]))
        {
            if (value is not JsonArray paths)
            {
                throw new ArgumentException($"{setter.Name} binds a list of paths, not {value?.ToJsonString()}");
            }
            return [paths.Select(p => Path(setter, p)).ToArray()];
        }
        if (parameter == typeof(ODataPath))
        {
            return [Path(setter, value)];
        }
        return [Value(parameter, value)];
    }

    /// <summary>A binding's path as written, already encoded, as TM1 gets it: its segments taken as they are,
    /// so a key's <c>%2F</c> stays one, and the bind setter writes it back the same.</summary>
    private static ODataPath Path(MethodInfo setter, JsonNode? value)
    {
        if (value is not JsonValue v || v.GetValueKind() != JsonValueKind.String)
        {
            throw new ArgumentException($"{setter.Name} binds a path, not {value?.ToJsonString() ?? "null"}");
        }
        var make = typeof(ODataPath).GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, [typeof(IReadOnlyList<string>)])!;
        return (ODataPath)make.Invoke([v.GetValue<string>().Split('/').ToList()]);
    }

    /// <summary>The names a write's type declares, its base's too, from the spec.</summary>
    private static HashSet<string> Declared(Type write)
    {
        var name = write.Name.EndsWith("Create", StringComparison.Ordinal) ? write.Name[..^"Create".Length] : write.Name[..^"Patch".Length];
        var byName = Types.ToDictionary(t => t!["qualifiedName"]!.GetValue<string>(), t => t!);
        var found = new HashSet<string>();
        for (var level = Types.FirstOrDefault(t => t!["name"]!.GetValue<string>() == name); level is not null; )
        {
            foreach (var p in level["properties"]!.AsArray().Concat(level["navigationProperties"]!.AsArray()))
            {
                found.Add(p!["name"]!.GetValue<string>());
            }
            level = level["baseType"] is JsonValue b && b.GetValueKind() == JsonValueKind.String ? byName[b.GetValue<string>()] : null;
        }
        return found;
    }

    private static object? Value(Type type, JsonNode? value)
    {
        if (value is null)
        {
            return null;
        }
        if (IsWrite(type))
        {
            return Build(type, Object(type, value));
        }
        if (type.IsGenericType && type.GetGenericTypeDefinition() == typeof(IEnumerable<>) && IsWrite(type.GetGenericArguments()[0]))
        {
            var element = type.GetGenericArguments()[0];
            var list = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(element))!;
            foreach (var item in value.AsArray())
            {
                list.Add(Build(element, Object(element, item)));
            }
            return list;
        }
        if (type == typeof(object))
        {
            return ConformanceCodec.Plain(value);
        }
        if (type == typeof(DateTimeOffset) || type == typeof(DateTimeOffset?))
        {
            // As a caller gives it, its offset kept: the client's converter makes a read's UTC, not a write's
            var text = value.GetValue<string>();
            if (!Regex.IsMatch(text, "(Z|[+-][0-9]{2}:[0-9]{2})$"))
            {
                throw new ArgumentException($"{text} has no offset");
            }
            return DateTimeOffset.Parse(text, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
        }
        object? read;
        try
        {
            read = value.Deserialize(type, Tm1Json.Options);
        }
        catch (JsonException e)
        {
            throw new ArgumentException($"{value.ToJsonString()} doesn't read back as written: {e.Message}", e);
        }
        var underlying = Nullable.GetUnderlyingType(type) ?? type;
        if (underlying.IsEnum || underlying == typeof(TimeSpan))
        {
            // What the client sends for it: as the client writes enums and durations
            JsonNode? back;
            try
            {
                back = JsonNode.Parse(JsonSerializer.Serialize(read, type, Tm1Json.Options));
            }
            catch (InvalidOperationException e)
            {
                // An enum TM1 doesn't have reads as Unknown, which the client won't send
                throw new ArgumentException($"{value.ToJsonString()} doesn't read back as written: {e.Message}", e);
            }
            if (!JsonNode.DeepEquals(back, value))
            {
                throw new ArgumentException($"{value.ToJsonString()} doesn't read back as written, but as {back?.ToJsonString()}");
            }
        }
        return read;
    }

    private static JsonObject Object(Type type, JsonNode? value) =>
        value as JsonObject ?? throw new ArgumentException($"A {type.Name} is an object, not {value?.ToJsonString() ?? "null"}");

    private static bool IsWrite(Type type) =>
        type.Namespace == typeof(Cube).Namespace
        && typeof(Tm1Patch).IsAssignableFrom(type)
        && (type.Name.EndsWith("Patch", StringComparison.Ordinal) || type.Name.EndsWith("Create", StringComparison.Ordinal));

    private static object Fresh(Type type) => type.GetMethod("Builder", BindingFlags.Public | BindingFlags.Static, Type.EmptyTypes)!.Invoke(null, null)!;

    private static object? Invoke(MethodInfo method, object target, object?[] args)
    {
        try
        {
            return method.Invoke(target, args);
        }
        catch (TargetInvocationException e) when (e.InnerException is not null)
        {
            throw e.InnerException;
        }
    }

    /// <summary>The builder's setters, by the JSON name each writes: each called once on a fresh builder,
    /// as the builder manifest finds them.</summary>
    private static Dictionary<string, MethodInfo> Setters(Type type) => SettersOf.GetOrAdd(type, t =>
        {
            var builderType = Fresh(t).GetType();
            var initial = Values(Fresh(t));
            var found = new Dictionary<string, MethodInfo>();
            foreach (
                var m in builderType
                    .GetMethods(BindingFlags.Public | BindingFlags.Instance)
                    .Where(m => m.ReturnType == builderType && m.Name is not ("Build" or "Property") && m.GetParameters().Length == 1)
            )
            {
                var b = Fresh(t);
                var parameter = m.GetParameters()[0].ParameterType;
                object? dummy =
                    parameter == typeof(ODataPath[]) ? Array.Empty<ODataPath>()
                    : parameter == typeof(ODataPath) ? ODataPath.Of("x")
                    : parameter.IsValueType && Nullable.GetUnderlyingType(parameter) is null ? Activator.CreateInstance(parameter)
                    : null;
                m.Invoke(b, [dummy]);
                var added = Values(b).Keys.Where(k => !initial.ContainsKey(k)).ToList();
                if (added.Count != 1)
                {
                    throw new InvalidOperationException($"{t.Name}.{m.Name} writes {string.Join(", ", added)}");
                }
                found[added[0]] = m;
            }
            return found;
        });

    private static Dictionary<string, object?> Values(object builder)
    {
        for (var t = builder.GetType(); t is not null; t = t.BaseType)
        {
            var field = t.GetField("_values", BindingFlags.NonPublic | BindingFlags.Instance);
            if (field is not null)
            {
                return ((IEnumerable<KeyValuePair<string, object?>>)field.GetValue(builder)!).ToDictionary(kv => kv.Key, kv => kv.Value);
            }
        }
        throw new InvalidOperationException("No values in " + builder.GetType());
    }
}
