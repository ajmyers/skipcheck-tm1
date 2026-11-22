using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using AjMyers.Tm1.Client.Errors;
using AjMyers.Tm1.Client.Json;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;

namespace AjMyers.Tm1.Client.Tests.Conformance;

/// <summary>A tag or type this runner can't decode: a failure, never a skip.</summary>
public sealed class UnknownConformanceValueException(string message) : Exception(message);

/// <summary>The language-neutral encoding of the conformance fixtures (spec/conformance/README.md):
/// arguments decoded into this client's values for the parameter they go to, results and errors
/// encoded to compare with what the Java client recorded, and the request comparison every runner
/// makes.</summary>
public static class ConformanceCodec
{
    public const int SpecVersion = 1;

    private static readonly HashSet<string> Headers = ["accept", "content-type", "if-match", "if-none-match", "odata-version", "odata-maxversion", "prefer"];

    private static readonly Assembly Client = typeof(Tm1Client).Assembly;

    /// <summary><c>modelCubes</c> → <c>ModelCubes</c>; <c>PRIVATE_VIEWS</c> → <c>PrivateViews</c>.</summary>
    public static string Pascal(string name) => name.Contains('_', StringComparison.Ordinal) || name.All(c => !char.IsLower(c))
        ? string.Concat(name.ToLowerInvariant().Split('_').Select(w => w.Length == 0 ? w : char.ToUpperInvariant(w[0]) + w[1..]))
        : char.ToUpperInvariant(name[0]) + name[1..];

    /// <summary>An argument, as the parameter of type <paramref name="type"/> takes it.</summary>
    public static object? Decode(JsonNode? node, Type type)
    {
        var target = Nullable.GetUnderlyingType(type) ?? type;
        switch (node)
        {
            case null:
                return null;
            case JsonArray array:
                {
                    var element = ElementType(target) ?? throw new UnknownConformanceValueException($"a list for a {type}");
                    var list = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(element))!;
                    foreach (var item in array)
                    {
                        list.Add(Decode(item, element));
                    }
                    if (target.IsArray)
                    {
                        var copy = Array.CreateInstance(element, list.Count);
                        list.CopyTo(copy, 0);
                        return copy;
                    }
                    return list;
                }
            case JsonValue value:
                return Primitive(value, target);
            case JsonObject tagged:
                return Tagged(tagged, target);
        }
        throw new UnknownConformanceValueException("not an encoded value: " + node.ToJsonString());
    }

    private static object? Primitive(JsonValue value, Type target)
    {
        var element = Element(value);
        return element.ValueKind switch
        {
            JsonValueKind.String when target == typeof(string) || target == typeof(object) => element.GetString(),
            JsonValueKind.True or JsonValueKind.False => element.GetBoolean(),
            JsonValueKind.Number when target == typeof(object) => element.TryGetInt64(out var l) ? l : element.GetDouble(),
            JsonValueKind.Number => Convert.ChangeType(element.GetDouble(), target, CultureInfo.InvariantCulture),
            _ => throw new UnknownConformanceValueException($"{element} for a {target}"),
        };
    }

    private static object? Tagged(JsonObject node, Type target)
    {
        var tag = node.First().Key;
        var body = node[tag];
        switch (tag)
        {
            case "$double":
                return Convert.ChangeType(body!.GetValue<double>(), target == typeof(object) ? typeof(double) : target, CultureInfo.InvariantCulture);
            case "$query":
                return Query(body!.AsObject());
            case "$path":
                return ODataPath.Link(body!.GetValue<string>());
            case "$map":
                {
                    var args = target.IsGenericType ? target.GetGenericArguments() : [typeof(string), typeof(object)];
                    var map = (IDictionary)Activator.CreateInstance(typeof(Dictionary<,>).MakeGenericType(args[0], args[1]))!;
                    foreach (var (key, value) in body!.AsObject())
                    {
                        map[key] = Decode(value, args[1]);
                    }
                    return map;
                }
            case "$patch":
                {
                    var type = Client.GetType("AjMyers.Tm1.Client.Model." + body!.GetValue<string>())
                        ?? throw new UnknownConformanceValueException("no patch " + body);
                    var values = new Dictionary<string, object?>();
                    foreach (var (key, value) in node["json"]!.AsObject())
                    {
                        values[key] = Plain(value);
                    }
                    var ctor = type.GetConstructors(BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public)
                        .Single(c => c.GetParameters() is [var p] && p.ParameterType.IsAssignableFrom(typeof(Dictionary<string, object?>)));
                    return ctor.Invoke([values]);
                }
            case "$model":
                {
                    var type = Client.GetType("AjMyers.Tm1.Client.Model." + body!.GetValue<string>())
                        ?? throw new UnknownConformanceValueException("no model " + body);
                    return Tm1Json.Read(JsonSerializer.SerializeToElement(node["json"]), type);
                }
            case "$enum":
                {
                    if (!target.IsEnum || target.Name != body!.GetValue<string>())
                    {
                        throw new UnknownConformanceValueException($"enum {body} for a {target}");
                    }
                    return Enum.Parse(target, Pascal(node["name"]!.GetValue<string>()), ignoreCase: true);
                }
            case "$duration":
                return XmlConvert.ToTimeSpan(body!.GetValue<string>());
            case "$dateTime":
                return DateTimeOffset.Parse(body!.GetValue<string>(), CultureInfo.InvariantCulture);
            case "$date":
                return DateOnly.Parse(body!.GetValue<string>(), CultureInfo.InvariantCulture);
            case "$time":
                return TimeOnly.Parse(body!.GetValue<string>(), CultureInfo.InvariantCulture);
            case "$bytes":
                return Convert.FromBase64String(body!.GetValue<string>());
            case "$type":
                {
                    var name = body!.GetValue<string>();
                    var type = Client.GetTypes().SingleOrDefault(t => t.IsPublic && t.Name == name && t.Namespace == "AjMyers.Tm1.Client.Services")
                        ?? throw new UnknownConformanceValueException("no type " + name);
                    var args = node["args"]!.AsArray();
                    var factory = type.GetMethods(BindingFlags.Public | BindingFlags.Static)
                        .SingleOrDefault(m => m.Name == Pascal(node["factory"]!.GetValue<string>()) && m.GetParameters().Length == args.Count)
                        ?? throw new UnknownConformanceValueException($"no factory {name}.{node["factory"]}");
                    var parameters = factory.GetParameters();
                    return factory.Invoke(null, args.Select((a, i) => Decode(a, parameters[i].ParameterType)).ToArray());
                }
        }
        throw new UnknownConformanceValueException("unknown tag " + tag);
    }

    /// <summary>JSON as plain values, as a patch holds them.</summary>
    private static object? Plain(JsonNode? node) => node switch
    {
        null => null,
        JsonArray a => a.Select(Plain).ToList(),
        JsonObject o => o.ToDictionary(p => p.Key, p => Plain(p.Value)),
        JsonValue v => Element(v) switch
        {
            { ValueKind: JsonValueKind.String } e => e.GetString(),
            { ValueKind: JsonValueKind.True } => true,
            { ValueKind: JsonValueKind.False } => false,
            { ValueKind: JsonValueKind.Number } e => e.TryGetInt64(out var l) ? l : e.GetDouble(),
            var e => throw new UnknownConformanceValueException(e.ToString()),
        },
        _ => throw new UnknownConformanceValueException(node.ToJsonString()),
    };

    private static JsonElement Element(JsonNode node) => JsonSerializer.SerializeToElement(node);

    private static Type? ElementType(Type type) => type.IsArray ? type.GetElementType()
        : type.IsGenericType && type.GetGenericArguments().Length == 1 ? type.GetGenericArguments()[0]
        : type == typeof(object) ? typeof(object) : null;

    /// <summary>The overload that takes Java's parameters by name, with the rest left to their
    /// defaults (the <c>CancellationToken</c> among them).</summary>
    public static (MethodInfo, object?[]) Bind(Type type, string name, List<string> names, JsonArray args)
    {
        var fits = new List<(MethodInfo, object?[])>();
        foreach (var m in type.GetMethods(BindingFlags.Public | BindingFlags.Instance).Where(m => m.Name == name))
        {
            var parameters = m.GetParameters();
            if (names.Any(n => parameters.All(p => p.Name != n)))
            {
                continue;
            }
            if (parameters.Any(p => !names.Contains(p.Name!) && !p.HasDefaultValue && p.ParameterType != typeof(CancellationToken)))
            {
                continue;
            }
            var values = parameters.Select(object? (p) =>
            {
                var i = names.IndexOf(p.Name!);
                return i >= 0 ? ConformanceCodec.Decode(args[i], p.ParameterType)
                    : p.HasDefaultValue ? p.DefaultValue : p.ParameterType.IsValueType ? Activator.CreateInstance(p.ParameterType) : null;
            }).ToArray();
            fits.Add((m, values));
        }
        return fits.Count == 1 ? fits[0]
            : throw new UnknownConformanceValueException($"{fits.Count} methods {type.Name}.{name} take ({string.Join(", ", names)})");
    }

    /// <summary>Calls the method and encodes what it gave; its exceptions propagate unwrapped.</summary>
    public static async Task<JsonNode?> Call(object service, MethodInfo method, object?[] values)
    {
        object? returned;
        try
        {
            returned = method.Invoke(service, values);
        }
        catch (TargetInvocationException e)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(e.InnerException!).Throw();
            throw;
        }
        return Result(await Await(returned, method.ReturnType));
    }

    /// <summary>A query rebuilt from its parts, with this client's own query builder.</summary>
    public static ODataQuery Query(JsonObject parts)
    {
        string[] known = ["select", "expand", "filter", "orderBy", "top", "skip", "count", "options", "aliases"];
        var unknown = parts.Select(p => p.Key).Except(known).ToList();
        if (unknown.Count > 0)
        {
            throw new UnknownConformanceValueException("unknown query parts " + string.Join(", ", unknown));
        }
        var q = ODataQuery.None();
        if (parts["select"] is JsonArray select)
        {
            q = q.Select(select.Select(s => s!.GetValue<string>()).ToArray());
        }
        if (parts["expand"] is JsonObject expand)
        {
            foreach (var (name, nested) in expand)
            {
                var inner = nested!.AsObject();
                q = inner.Count > 0 ? q.Expand(name, _ => Query(inner)) : q.Expand(name);
            }
        }
        if (parts["filter"] is JsonValue filter)
        {
            q = q.Filter(Filter.Raw(filter.GetValue<string>()));
        }
        if (parts["orderBy"] is JsonArray orderBy)
        {
            foreach (var key in orderBy.Select(k => k!.GetValue<string>()))
            {
                q = key.EndsWith(" desc", StringComparison.Ordinal) ? q.OrderByDescending(key[..^" desc".Length]) : q.OrderBy(key);
            }
        }
        if (parts["top"] is JsonValue top)
        {
            q = q.Top(top.GetValue<int>());
        }
        if (parts["skip"] is JsonValue skip)
        {
            q = q.Skip(skip.GetValue<int>());
        }
        if (parts["count"] is JsonValue count && count.GetValue<bool>())
        {
            q = q.WithCount();
        }
        foreach (var (name, value) in parts["options"]?.AsObject() ?? [])
        {
            q = q.Option(name, value!.GetValue<string>());
        }
        foreach (var (name, value) in parts["aliases"]?.AsObject() ?? [])
        {
            q = q.Alias(new ODataPath.Alias(name), value!.GetValue<string>());
        }
        return q;
    }

    /// <summary>What a call gave: a <c>Task</c>'s value, or an <c>IAsyncEnumerable</c>'s items.</summary>
    public static async Task<object?> Await(object? returned, Type declared)
    {
        switch (returned)
        {
            case Task task:
                await task;
                // By the declared type: a method that returns Task may hand back a Task<T> underneath
                return declared.IsGenericType && declared.GetGenericTypeDefinition() == typeof(Task<>)
                    ? declared.GetProperty("Result")!.GetValue(task) : null;
            case null:
                return null;
        }
        var enumerable = returned.GetType().GetInterfaces().FirstOrDefault(i => i.IsGenericType && i.GetGenericTypeDefinition() == typeof(IAsyncEnumerable<>));
        if (enumerable is null)
        {
            return returned;
        }
        var collect = typeof(ConformanceCodec).GetMethod(nameof(Collect), BindingFlags.NonPublic | BindingFlags.Static)!
            .MakeGenericMethod(enumerable.GetGenericArguments()[0]);
        return await (Task<List<object?>>)collect.Invoke(null, [returned])!;
    }

    private static async Task<List<object?>> Collect<T>(IAsyncEnumerable<T> items)
    {
        var list = new List<object?>();
        await foreach (var item in items)
        {
            list.Add(item);
        }
        return list;
    }

    /// <summary>A result, encoded as the Java recorder encodes its own.</summary>
    public static JsonNode? Result(object? value) => value switch
    {
        null => null,
        string or bool => JsonValue.Create(value),
        byte[] bytes => new JsonObject { ["$bytes"] = Convert.ToBase64String(bytes) },
        int or long or short => JsonValue.Create(Convert.ToInt64(value, CultureInfo.InvariantCulture)),
        double or float or decimal => JsonValue.Create(Convert.ToDouble(value, CultureInfo.InvariantCulture)),
        IList list => new JsonArray(list.Cast<object?>().Select(Result).ToArray()),
        _ when value.GetType().Namespace == "AjMyers.Tm1.Client.Model" && !value.GetType().IsEnum =>
            new JsonObject { ["$model"] = value.GetType().Name, ["json"] = JsonNode.Parse(Tm1Json.Write(value).GetRawText()) },
        _ => JsonNode.Parse(Tm1Json.Write(value).GetRawText()),
    };

    /// <summary>How a call failed, as the Java recorder writes it (kinds in Java's constant case).</summary>
    public static JsonObject Error(Exception e)
    {
        var o = new JsonObject { ["exception"] = e.GetType().Name };
        switch (e)
        {
            case Tm1ApiException api:
                o["category"] = "api";
                o["kind"] = ConstantCase(api.Kind.ToString());
                o["status"] = api.Status;
                if (!string.IsNullOrEmpty(api.Error?.Code))
                {
                    o["code"] = api.Error.Code;
                }
                break;
            case Tm1UnsupportedOperationException:
                o["category"] = "unsupported";
                break;
            case Tm1ProtocolException:
                o["category"] = "protocol";
                break;
            case Tm1TransportException:
                o["category"] = "transport";
                break;
            case ArgumentException:
                o["category"] = "argument";
                break;
            case InvalidOperationException:
                o["category"] = "state";
                break;
            default:
                throw new InvalidOperationException("No conformance category for " + e, e);
        }
        return o;
    }

    private static string ConstantCase(string pascal) =>
        string.Concat(pascal.Select((c, i) => i > 0 && char.IsUpper(c) ? "_" + c : c.ToString())).ToUpperInvariant();

    /// <summary>Equal but for the exception's name, which is each language's own.</summary>
    public static bool SameError(JsonObject expected, JsonObject got)
    {
        string Pick(JsonObject o) => string.Join("|", new[] { "category", "kind", "status", "code" }.Select(k => o[k]?.ToJsonString() ?? "-"));
        return Pick(expected) == Pick(got);
    }

    /// <summary>For comparing: numbers as doubles, the order of members kept; with
    /// <paramref name="dropNulls"/>, null members left out and a model by its JSON alone.</summary>
    public static string Comparable(JsonNode? node, bool dropNulls = false) => Normal(node, dropNulls)?.ToJsonString() ?? "null";

    private static JsonNode? Normal(JsonNode? node, bool dropNulls) => node switch
    {
        null => null,
        JsonArray a => new JsonArray(a.Select(x => Normal(x, dropNulls)).ToArray()),
        JsonObject o when dropNulls && o.ContainsKey("$model") => Normal(o["json"], dropNulls),
        JsonObject o => Members(o, dropNulls),
        JsonValue v when Element(v).ValueKind == JsonValueKind.Number => JsonValue.Create(Element(v).GetDouble()),
        JsonValue v => JsonNode.Parse(v.ToJsonString()),
        _ => throw new InvalidOperationException(),
    };

    private static JsonObject Members(JsonObject o, bool dropNulls)
    {
        var copy = new JsonObject();
        IEnumerable<KeyValuePair<string, JsonNode?>> members = dropNulls ? o.Where(p => p.Value is not null).OrderBy(p => p.Key, StringComparer.Ordinal) : o;
        foreach (var (key, value) in members)
        {
            copy[key] = Normal(value, dropNulls);
        }
        return copy;
    }

    /// <summary>A request as a fixture records it.</summary>
    public static JsonObject Request(string method, string target, IReadOnlyDictionary<string, string> headers, byte[] body)
    {
        var kept = new JsonObject();
        foreach (var (key, value) in headers.OrderBy(h => h.Key, StringComparer.Ordinal))
        {
            if (Headers.Contains(key) || (key.StartsWith("tm1-", StringComparison.Ordinal) && key != "tm1-sessioncontext"))
            {
                kept[key] = value;
            }
        }
        var o = new JsonObject { ["method"] = method, ["target"] = target, ["headers"] = kept };
        if (body.Length > 0)
        {
            var type = headers.TryGetValue("content-type", out var t) ? t.ToLowerInvariant() : "";
            if (type.StartsWith("application/json", StringComparison.Ordinal))
            {
                o["json"] = JsonNode.Parse(body);
            }
            else if (type.StartsWith("text/", StringComparison.Ordinal))
            {
                o["text"] = Encoding.UTF8.GetString(body);
            }
            else
            {
                o["bytes"] = Convert.ToBase64String(body);
            }
        }
        return o;
    }
}
