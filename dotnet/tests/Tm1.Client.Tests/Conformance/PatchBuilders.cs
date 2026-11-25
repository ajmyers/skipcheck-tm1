// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using Skipcheck.Tm1.Client.Json;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Conformance;

/// <summary>
/// A write as the generated builders make it, as the Java runner's PatchBuilders (#354): each key by the
/// setter that writes it, a nested write type's value by its own builder (a root's by the subtype its
/// <c>@odata.type</c> names), a binding by the bind setter (its path decoded, so the setter encodes it
/// once), an open type's undeclared key by <c>Property</c>. A key no setter writes is refused: a caller
/// can't send it, so neither can a scenario.
/// </summary>
internal static class PatchBuilders
{
    private static readonly ConcurrentDictionary<Type, Dictionary<string, MethodInfo>> SettersOf = new();

    internal static object Build(Type patch, JsonObject json)
    {
        var type = Subtype(patch, json);
        var builder = Fresh(type);
        var setters = Setters(type);
        foreach (var (key, value) in json)
        {
            if (key == "@odata.type")
            {
                // The builder sends its own; the subtype was picked by it
                continue;
            }
            if (setters.TryGetValue(key, out var setter))
            {
                Invoke(setter, builder, Argument(setter, value));
            }
            else if (!key.StartsWith('@') && builder.GetType().GetMethod("Property", [typeof(string), typeof(object)]) is { } property)
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
            return [value!.AsArray().Select(p => Path(p!.GetValue<string>())).ToArray()];
        }
        if (parameter == typeof(ODataPath))
        {
            return [Path(value!.GetValue<string>())];
        }
        return [Value(parameter, value)];
    }

    /// <summary>A binding's path, which the JSON holds as sent, percent-encoded.</summary>
    private static ODataPath Path(string encoded) => ODataPath.Link(Uri.UnescapeDataString(encoded));

    private static object? Value(Type type, JsonNode? value)
    {
        if (value is null)
        {
            return null;
        }
        if (IsWrite(type))
        {
            return Build(type, value.AsObject());
        }
        if (type.IsGenericType && type.GetGenericTypeDefinition() == typeof(IEnumerable<>) && IsWrite(type.GetGenericArguments()[0]))
        {
            var element = type.GetGenericArguments()[0];
            var list = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(element))!;
            foreach (var item in value.AsArray())
            {
                list.Add(Build(element, item!.AsObject()));
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
            return DateTimeOffset.Parse(value.GetValue<string>(), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
        }
        return value.Deserialize(type, Tm1Json.Options);
    }

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
