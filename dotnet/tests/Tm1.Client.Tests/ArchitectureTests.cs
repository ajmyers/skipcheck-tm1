using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Services;
using Skipcheck.Tm1.Client.Session;
using Skipcheck.Tm1.Testcontainers;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>Rules about the compiled libraries, each with its reason: the Java client's
/// ArchitectureTest (ArchUnit), read here from the assemblies' own metadata and IL. A rule is a
/// test; what it found is its failure message.</summary>
public class ArchitectureTests
{
    private static readonly Assembly Client = typeof(Tm1Client).Assembly;

    private static readonly Assembly Containers = typeof(Tm1ServerContainer).Assembly;

    private static readonly List<Type> ServiceTypes = Client
        .GetTypes()
        .Where(t => t.Namespace == "Skipcheck.Tm1.Client.Services" && t.Name.EndsWith("Service", StringComparison.Ordinal) && !t.IsAbstract && !t.IsNested)
        .ToList();

    /// <summary>Java's neverBlocksOnAReactiveChain: there, block() on a Mono or Flux; here, waiting
    /// for a task on the calling thread.</summary>
    [Fact]
    public void NeverBlocksOnATask()
    {
        var found = Uses(Client).Where(Blocks).Select(Describe);
        Because(
            found,
            "the client is asynchronous only: blocking a thread pool thread on a task stalls it, and can deadlock a caller with a synchronization context. Await it instead; callers who want to block do so themselves"
        );
    }

    /// <summary>Java's neverStartsWorkNobodySubscribedTo: there, a bare subscribe(); here, an async
    /// void method, which is the same thing: started, and nobody holds its task.</summary>
    [Fact]
    public void NeverStartsWorkNobodyCanAwait()
    {
        var found = AllMethods(Client)
            .Where(m => m is MethodInfo { ReturnType: var r } && r == typeof(void) && m.GetCustomAttribute<AsyncStateMachineAttribute>() is not null)
            .Select(m => $"{m.DeclaringType!.FullName}.{m.Name}");
        Because(
            found,
            "an async void method starts work outside the caller's await: its errors go nowhere and the caller can't wait for it or cancel it. Return the Task instead"
        );
    }

    /// <summary>Java's buildsRequestUrisRatherThanUsingUriTemplates. .NET's HttpClient has no URI
    /// templates to misuse, so the rule is its point: a service can't write a URL at all. A request is
    /// made from an ODataPath, and only the session turns one into a URL.</summary>
    [Fact]
    public void BuildsRequestUrlsFromPathsOnly()
    {
        var factories = typeof(Tm1Request)
            .GetMethods(BindingFlags.Public | BindingFlags.Static)
            .Where(m => m.ReturnType.Name.StartsWith("Tm1Request", StringComparison.Ordinal));
        var found = factories
            .Where(m => m.GetParameters().FirstOrDefault()?.ParameterType != typeof(OData.ODataPath))
            .Select(m => $"Tm1Request.{m.Name} doesn't take an ODataPath first")
            .Concat(typeof(Tm1Request<>).GetConstructors().Select(c => "Tm1Request<T> has a public constructor"))
            .Concat(
                Uses(Client)
                    .Where(u => Outer(u.From).Namespace == "Skipcheck.Tm1.Client.Services")
                    .Where(u => u.Type == typeof(Uri) || u.Type == typeof(UriBuilder) || u.Type.Namespace == "System.Net.Http")
                    .Select(Describe)
            );
        Because(
            found,
            "TM1 object names contain braces, quotes and slashes (control objects such as }Clients), which a URL written by hand gets wrong; requests use a URL already encoded by ODataPath and ODataQuery"
        );
    }

    /// <summary>Java's logsThroughSlf4jOnly, for both libraries. .NET's standard logging is
    /// Microsoft.Extensions.Logging: the client logs nothing, and Tm1.Testcontainers logs through the
    /// ILogger Testcontainers gives a container.</summary>
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void LogsThroughILoggerOnly(bool containers)
    {
        var assembly = containers ? Containers : Client;
        string[] banned = ["System.Console", "System.Diagnostics.Debug", "System.Diagnostics.Trace"];
        string[] loggers = ["Serilog", "NLog", "log4net", "Common.Logging"];
        var found = Uses(assembly)
            .Where(u =>
                banned.Contains(u.Type.FullName)
                || loggers.Any(l => u.Type.Namespace == l || u.Type.Namespace?.StartsWith(l + ".", StringComparison.Ordinal) == true)
            )
            .Select(Describe)
            .Concat(assembly.GetReferencedAssemblies().Where(a => loggers.Contains(a.Name) || a.Name == "System.Console").Select(a => "references " + a.Name));
        Because(
            found,
            "logging goes through Microsoft.Extensions.Logging's ILogger, so applications control where it goes and the library never writes to the console"
        );
    }

    /// <summary>Java's keepsTheODataLayerFreeOfTm1, with the same three packages. (It does use
    /// Json.Tm1EnumConverter, to write a generated enum as a literal: an enum can't implement
    /// IODataLiteral here, as Java's do.)</summary>
    [Fact]
    public void KeepsTheODataLayerFreeOfTm1()
    {
        string[] tm1 = ["Skipcheck.Tm1.Client", "Skipcheck.Tm1.Client.Session", "Skipcheck.Tm1.Client.Version"];
        var found = Uses(Client)
            .Where(u => Outer(u.From).Namespace == "Skipcheck.Tm1.Client.OData")
            .Where(u => u.Type.Assembly == Client && tm1.Contains(Outer(u.Type).Namespace))
            .Select(Describe);
        Because(found, "the OData layer is TM1-agnostic: OData correctness lives in one place and doesn't depend on sessions, requests or TM1 versions");
    }

    /// <summary>Java's servicesAreStatelessFinalClassesBuiltFromASession. The session is Tm1Service's,
    /// so a service declares no instance field at all.</summary>
    [Fact]
    public void ServicesAreStatelessSealedClassesBuiltFromASession()
    {
        Assert.NotEmpty(ServiceTypes);
        var found = new List<string>();
        foreach (var s in ServiceTypes)
        {
            if (!s.IsSealed)
            {
                found.Add(s.Name + " isn't sealed");
            }
            var constructors = s.GetConstructors(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            if (
                constructors.Length != 1
                || !constructors[0].IsPublic
                || !constructors[0].GetParameters().Select(p => p.ParameterType).SequenceEqual([typeof(Tm1Session)])
            )
            {
                found.Add(s.Name + " doesn't have one public constructor, taking a Tm1Session");
            }
            const BindingFlags declared = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly;
            found.AddRange(s.GetFields(declared | BindingFlags.Instance).Select(f => $"{s.Name}.{f.Name} is state"));
            found.AddRange(
                s.GetFields(declared | BindingFlags.Static)
                    .Where(f => !f.IsInitOnly && !f.IsLiteral)
                    .Select(f => $"{s.Name}.{f.Name} is a static that can change")
            );
        }
        Because(found, "services are free to create and safe to share: everything they need is the session");
    }

    /// <summary>Java's serviceMethodsAreReactiveAndDeclareTheirEndpoints: there a Mono or a Flux,
    /// here a Task or an IAsyncEnumerable.</summary>
    [Fact]
    public void ServiceMethodsAreAsynchronousAndDeclareTheirEndpoints()
    {
        var found = new List<string>();
        foreach (
            var m in ServiceTypes.SelectMany(s => s.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
        )
        {
            var r = m.ReturnType;
            if (!(IsTask(r) || (r.IsGenericType && r.GetGenericTypeDefinition() == typeof(IAsyncEnumerable<>))))
            {
                found.Add($"{m.DeclaringType!.Name}.{m.Name} returns {r.Name}, not a Task or an IAsyncEnumerable");
            }
            if (!m.GetCustomAttributes<Tm1EndpointAttribute>().Any())
            {
                found.Add($"{m.DeclaringType!.Name}.{m.Name} declares no [Tm1Endpoint]");
            }
        }
        Because(found, "each service method is one REST call that never blocks, and the coverage gate counts what's implemented from [Tm1Endpoint]");
    }

    /// <summary>The rules above pass only if the scan sees what code does: it finds the session's
    /// HttpClient, and the one place the tests themselves block (Live.Target, to start TM1).</summary>
    [Fact]
    public void TheScanSeesWhatTheCodeUses()
    {
        Assert.Contains(Uses(Client), u => u.From.DeclaringType!.Name == "Connection" && u.Type == typeof(System.Net.Http.HttpClient));
        Assert.Contains(Uses(typeof(ArchitectureTests).Assembly).Where(Blocks), u => u.From.DeclaringType == typeof(Live.Live));
    }

    private static bool Blocks(Use u) =>
        !StateMachine(u.From)
        && u.Member is MethodBase m
        && (
            (m.DeclaringType == typeof(System.Threading.Thread) && m.Name == "Sleep")
            || (IsTask(m.DeclaringType) && m.Name is "Wait" or "WaitAll" or "WaitAny" or "RunSynchronously")
            || (IsAwaiter(m.DeclaringType) && m.Name == "GetResult")
        );

    private static void Because(IEnumerable<string> found, string reason)
    {
        var all = found.Distinct().Order(StringComparer.Ordinal).ToList();
        Assert.True(all.Count == 0, $"{string.Join("\n", all)}\nbecause {reason}");
    }

    private static bool IsTask(Type? t) =>
        t is not null
        && (
            t == typeof(Task)
            || t == typeof(ValueTask)
            || (t.IsGenericType && t.GetGenericTypeDefinition() is var d && (d == typeof(Task<>) || d == typeof(ValueTask<>)))
        );

    private static bool IsAwaiter(Type? t) =>
        t is not null
        && (t.IsGenericType ? t.GetGenericTypeDefinition() : t) is var d
        && (
            d == typeof(TaskAwaiter)
            || d == typeof(TaskAwaiter<>)
            || d == typeof(ValueTaskAwaiter)
            || d == typeof(ValueTaskAwaiter<>)
            || d == typeof(ConfiguredTaskAwaitable.ConfiguredTaskAwaiter)
            || d == typeof(ConfiguredTaskAwaitable<>.ConfiguredTaskAwaiter)
            || d == typeof(ConfiguredValueTaskAwaitable.ConfiguredValueTaskAwaiter)
            || d == typeof(ConfiguredValueTaskAwaitable<>.ConfiguredValueTaskAwaiter)
        );

    /// <summary>Whether a method is the compiler's own, of an async method or iterator: its
    /// GetResult() reads a task that has completed.</summary>
    private static bool StateMachine(MethodBase method) =>
        method.DeclaringType is { } t
        && (
            typeof(IAsyncStateMachine).IsAssignableFrom(t)
            || t.GetInterfaces().Any(i => i.IsGenericType && i.GetGenericTypeDefinition() == typeof(IAsyncEnumerator<>))
        );

    private static Type Outer(MethodBase method) => Outer(method.DeclaringType!);

    private static Type Outer(Type type)
    {
        while (type.DeclaringType is { } outer)
        {
            type = outer;
        }
        return type;
    }

    /// <summary>A method, and a type it uses (with the member, when it calls or reads one).</summary>
    private sealed record Use(MethodBase From, Type Type, MemberInfo? Member);

    private static string Describe(Use use) =>
        $"{use.From.DeclaringType!.FullName}.{use.From.Name} uses {use.Type.FullName}{(use.Member is null ? "" : "." + use.Member.Name)}";

    private static IEnumerable<MethodBase> AllMethods(Assembly assembly)
    {
        const BindingFlags all = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;
        return assembly.GetTypes().SelectMany(t => t.GetMethods(all).Cast<MethodBase>().Concat(t.GetConstructors(all)));
    }

    private static readonly Dictionary<short, OpCode> OpCodesByValue = typeof(OpCodes)
        .GetFields(BindingFlags.Public | BindingFlags.Static)
        .Select(f => (OpCode)f.GetValue(null)!)
        .ToDictionary(o => o.Value);

    private static readonly Dictionary<Assembly, List<Use>> Scanned = [];

    /// <summary>Every type the assembly's code uses: in signatures, fields and locals, and in the IL
    /// of its method bodies (calls, field reads, casts, typeof), the compiler's closures and state
    /// machines included.</summary>
    private static List<Use> Uses(Assembly assembly)
    {
        lock (Scanned)
        {
            if (!Scanned.TryGetValue(assembly, out var uses))
            {
                Scanned[assembly] = uses = AllMethods(assembly).SelectMany(UsesOf).ToList();
            }
            return uses;
        }
    }

    private static IEnumerable<Use> UsesOf(MethodBase method)
    {
        var uses = new List<Use>();
        void Add(Type? type, MemberInfo? member = null)
        {
            for (; type is not null && type.HasElementType; type = type.GetElementType()) { }
            if (type is null || type.IsGenericParameter)
            {
                return;
            }
            uses.Add(new Use(method, type.IsGenericType ? type.GetGenericTypeDefinition() : type, member));
            foreach (var argument in type.IsGenericType ? type.GetGenericArguments() : [])
            {
                Add(argument);
            }
        }
        foreach (var p in method.GetParameters())
        {
            Add(p.ParameterType);
        }
        Add((method as MethodInfo)?.ReturnType);
        foreach (
            var f in method.DeclaringType!.GetFields(
                BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly
            )
        )
        {
            Add(f.FieldType);
        }
        if (method.GetMethodBody() is not { } body)
        {
            return uses;
        }
        foreach (var local in body.LocalVariables)
        {
            Add(local.LocalType);
        }
        var il = body.GetILAsByteArray()!;
        var typeArguments = method.DeclaringType.IsGenericType ? method.DeclaringType.GetGenericArguments() : null;
        var methodArguments = method.IsGenericMethod ? method.GetGenericArguments() : null;
        for (var i = 0; i < il.Length; )
        {
            var code = il[i] == 0xFE ? OpCodesByValue[(short)(0xFE00 | il[i + 1])] : OpCodesByValue[il[i]];
            i += code.Size;
            switch (code.OperandType)
            {
                case OperandType.InlineMethod:
                case OperandType.InlineField:
                case OperandType.InlineType:
                case OperandType.InlineTok:
                    var member = method.Module.ResolveMember(BitConverter.ToInt32(il, i), typeArguments, methodArguments);
                    if (member is Type type)
                    {
                        Add(type);
                    }
                    else if (member is not null)
                    {
                        Add(member.DeclaringType, member);
                    }
                    i += 4;
                    break;
                case OperandType.InlineSwitch:
                    i += 4 + 4 * BitConverter.ToInt32(il, i);
                    break;
                case OperandType.InlineNone:
                    break;
                case OperandType.ShortInlineBrTarget:
                case OperandType.ShortInlineI:
                case OperandType.ShortInlineVar:
                    i += 1;
                    break;
                case OperandType.InlineVar:
                    i += 2;
                    break;
                case OperandType.InlineI8:
                case OperandType.InlineR:
                    i += 8;
                    break;
                default:
                    i += 4;
                    break;
            }
        }
        return uses;
    }
}
