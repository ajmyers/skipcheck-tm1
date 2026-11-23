using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;
using Tm1Thread = AjMyers.Tm1.Client.Model.Thread;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Process debugging: a run of a process that stops at breakpoints and steps line by line,
/// held on the server as a <c>ProcessDebugContext</c> until deleted.
/// <para><see cref="DebugAsync"/> starts one, paused before the first line; <see cref="ResumeAsync"/>
/// runs to the next breakpoint (or the end), and the <c>Step…</c> methods move a line at a time. Each
/// returns the context as it then is. TM1 (11.8) answers a start, a resume or a step without
/// waiting for the run to stop again, so the status is <c>Running</c> or <c>Paused</c> depending on which came first;
/// <see cref="GetAsync"/> reads it again. A step past a procedure's last line either pauses with
/// an empty call stack or completes the run.</para>
/// <para>TM1 (11.8) refuses to delete a debug context: a run ends by running to completion, which
/// <see cref="FinishAsync"/> does, and <c>DebuggingAsync</c> does when its work is done. A finished
/// context stays readable until the session ends. <c>ProcessDebugContexts</c> lists none, though each
/// reads by ID.</para>
/// <para>Stop at a breakpoint and see where the run is, with <c>DebuggingAsync</c> finishing the run
/// afterwards:</para>
/// <code>
/// var line = await tm1.ProcessDebugging.DebuggingAsync("Load Sales", new Dictionary&lt;string, object?&gt;(), async context =>
/// {
///     await tm1.ProcessDebugging.AddBreakpointAsync(context.Id!, ProcessDebugContextBreakpointPatch.Builder()
///         .Id(1).Enabled(true).HitMode(ProcessDebugContextHitMode.BreakAlways).Build());
///     await tm1.ProcessDebugging.ResumeAsync(context.Id!);
///     return await tm1.ProcessDebugging.CallStackAsync(context.Id!).FirstAsync();
/// });
/// </code>
/// <para>Step through a run started with <see cref="DebugAsync"/>, and finish it:</para>
/// <code>
/// var context = await tm1.ProcessDebugging.DebugAsync("Load Sales");
/// await tm1.ProcessDebugging.StepOverAsync(context.Id!);
/// await tm1.ProcessDebugging.FinishAsync(context.Id!);
/// </code>
/// </summary>
public sealed class ProcessDebugService : Tm1Service
{
    private static readonly ODataPath Contexts = ODataPath.Of("ProcessDebugContexts");

    /// <summary>How many times <see cref="FinishAsync"/> resumes a run that keeps pausing before giving up.</summary>
    private const int MaxResumes = 100;

    public ProcessDebugService(Tm1Session session)
        : base(session) { }

    /// <summary><c>tm1.Debug</c>: starts debugging a process, paused before its first line.
    /// <paramref name="parameters"/> are its parameters, by name, in order; may be empty.</summary>
    [Tm1Endpoint("Action Process/Debug")]
    public Task<ProcessDebugContext> DebugAsync(
        string process,
        IReadOnlyDictionary<string, object?>? parameters = null,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    )
    {
        var operation = Tm1Operations.ProcessDebug;
        var body = ProcessFormats.WithParameters(new Dictionary<string, object?>(), parameters);
        return Session.ExecuteAsync(
            Tm1Request
                .Post<ProcessDebugContext>(ODataPath.Of("Processes").Key(Require(process, nameof(process))).Action(operation.Name), body)
                .WithQuery(query)
                .Requires(operation.Capability()),
            cancellationToken
        );
    }

    /// <summary>Debugs a process for the length of <paramref name="work"/>: starts the run, streams what
    /// the work yields, and finishes the run afterwards, when the work completes, fails or is
    /// cancelled.</summary>
    [Tm1Endpoint("Action Process/Debug")]
    [Tm1Endpoint("EntitySet ProcessDebugContexts")]
    public async IAsyncEnumerable<TResult> DebuggingAsync<TResult>(
        string process,
        IReadOnlyDictionary<string, object?>? parameters,
        Func<ProcessDebugContext, IAsyncEnumerable<TResult>> work,
        [EnumeratorCancellation] CancellationToken cancellationToken = default
    )
    {
        Require(work, nameof(work));
        var context = await DebugAsync(process, parameters, null, cancellationToken).ConfigureAwait(false);
        try
        {
            await foreach (var item in work(context).WithCancellation(cancellationToken).ConfigureAwait(false))
            {
                yield return item;
            }
        }
        finally
        {
            await FinishContextAsync(context).ConfigureAwait(false);
        }
    }

    /// <summary>Debugs a process for the length of <paramref name="work"/>, as the streaming overload,
    /// for work with a single result.</summary>
    [Tm1Endpoint("Action Process/Debug")]
    [Tm1Endpoint("EntitySet ProcessDebugContexts")]
    public async Task<TResult> DebuggingAsync<TResult>(
        string process,
        IReadOnlyDictionary<string, object?>? parameters,
        Func<ProcessDebugContext, Task<TResult>> work,
        CancellationToken cancellationToken = default
    )
    {
        Require(work, nameof(work));
        var context = await DebugAsync(process, parameters, null, cancellationToken).ConfigureAwait(false);
        try
        {
            return await work(context).ConfigureAwait(false);
        }
        finally
        {
            await FinishContextAsync(context).ConfigureAwait(false);
        }
    }

    /// <summary><c>GET ProcessDebugContexts</c>, streamed. TM1 (11.8) lists none, though each reads by ID.</summary>
    [Tm1Endpoint("EntitySet ProcessDebugContexts")]
    public IAsyncEnumerable<ProcessDebugContext> ListAsync(ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ProcessDebugContext>(Contexts, query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')</c>.</summary>
    [Tm1Endpoint("EntitySet ProcessDebugContexts")]
    public Task<ProcessDebugContext> GetAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ProcessDebugContext>(ContextPath(id), query), cancellationToken);

    /// <summary>Ends a run: removes its breakpoints (those with an ID of 0 or more; TM1 stores one
    /// without as -1, which can't be removed) and resumes until it's complete, up to 100 times.
    /// Returns the context as it last was.</summary>
    [Tm1Endpoint("Action ProcessDebugContext/Continue")]
    [Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")]
    public async Task<ProcessDebugContext> FinishAsync(string id, CancellationToken cancellationToken = default)
    {
        var breakpoints = await BreakpointsAsync(id, null, cancellationToken).ToListAsync(cancellationToken).ConfigureAwait(false);
        foreach (var breakpoint in breakpoints)
        {
            if (breakpoint.Id is { } number and >= 0)
            {
                await RemoveBreakpointAsync(id, number, cancellationToken).ConfigureAwait(false);
            }
        }
        var context = await ResumeAsync(id, null, cancellationToken).ConfigureAwait(false);
        for (var resumes = 1; resumes < MaxResumes && context.Status != ProcessDebugContextStatus.Complete; resumes++)
        {
            context = await ResumeAsync(id, null, cancellationToken).ConfigureAwait(false);
        }
        return context;
    }

    /// <summary><c>tm1.Continue</c>: runs to the next breakpoint, or the end.</summary>
    [Tm1Endpoint("Action ProcessDebugContext/Continue")]
    public Task<ProcessDebugContext> ResumeAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Step(Tm1Operations.ProcessDebugContextContinue, id, query), cancellationToken);

    /// <summary><c>tm1.StepIn</c>: one line, into an <c>ExecuteProcess</c>.</summary>
    [Tm1Endpoint("Action ProcessDebugContext/StepIn")]
    public Task<ProcessDebugContext> StepInAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Step(Tm1Operations.ProcessDebugContextStepIn, id, query), cancellationToken);

    /// <summary><c>tm1.StepOut</c>: runs out of the current process, back to its caller.</summary>
    [Tm1Endpoint("Action ProcessDebugContext/StepOut")]
    public Task<ProcessDebugContext> StepOutAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Step(Tm1Operations.ProcessDebugContextStepOut, id, query), cancellationToken);

    /// <summary><c>tm1.StepOver</c>: one line, over an <c>ExecuteProcess</c>.</summary>
    [Tm1Endpoint("Action ProcessDebugContext/StepOver")]
    public Task<ProcessDebugContext> StepOverAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Step(Tm1Operations.ProcessDebugContextStepOver, id, query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/Breakpoints</c>, streamed.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")]
    public IAsyncEnumerable<ProcessDebugContextBreakpoint> BreakpointsAsync(
        string id,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<ProcessDebugContextBreakpoint>(ContextPath(id).Nav("Breakpoints"), query), cancellationToken);

    /// <summary><c>POST ProcessDebugContexts('id')/Breakpoints</c>: adds a breakpoint (a line, data or
    /// object breakpoint, by its patch's type). It needs an <c>ID</c> of 0 or more: TM1 stores one
    /// without as -1, which can't be changed or removed, so one without is refused here with an
    /// <see cref="ArgumentException"/>.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")]
    public Task<ProcessDebugContextBreakpoint> AddBreakpointAsync(
        string id,
        ProcessDebugContextBreakpointPatch breakpoint,
        CancellationToken cancellationToken = default
    )
    {
        Require(breakpoint, nameof(breakpoint));
        var number = breakpoint.Values.Where(v => v.Key == "ID").Select(v => v.Value).FirstOrDefault();
        if (number is not (int or long or short or byte) || Convert.ToInt64(number, System.Globalization.CultureInfo.InvariantCulture) < 0)
        {
            throw new ArgumentException(
                "A breakpoint needs an ID of 0 or more: TM1 stores one without as -1, which can't be changed or removed",
                nameof(breakpoint)
            );
        }
        return Session.ExecuteAsync(Tm1Request.Post<ProcessDebugContextBreakpoint>(ContextPath(id).Nav("Breakpoints"), breakpoint), cancellationToken);
    }

    /// <summary><c>PATCH ProcessDebugContexts('id')/Breakpoints(n)</c>: changes a breakpoint, such as
    /// switching it off.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")]
    public Task UpdateBreakpointAsync(string id, int breakpoint, ProcessDebugContextBreakpointPatch patch, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Patch(ContextPath(id).Nav("Breakpoints").Key(breakpoint), Require(patch, nameof(patch))), cancellationToken);

    /// <summary><c>DELETE ProcessDebugContexts('id')/Breakpoints(n)</c>.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/Breakpoints")]
    public Task RemoveBreakpointAsync(string id, int breakpoint, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(ContextPath(id).Nav("Breakpoints").Key(breakpoint)), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/CurrentBreakpoint</c>: the breakpoint the run is
    /// paused at.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/CurrentBreakpoint")]
    public Task<ProcessDebugContextBreakpoint> CurrentBreakpointAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<ProcessDebugContextBreakpoint>(ContextPath(id).Nav("CurrentBreakpoint"), query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/CallStack</c>: where the run is, innermost first, streamed.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/CallStack")]
    public IAsyncEnumerable<ProcessDebugContextStackFrame> CallStackAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<ProcessDebugContextStackFrame>(ContextPath(id).Nav("CallStack"), query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/CallStack('frame')/Variables</c>: a frame's variables, streamed.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContextStackFrame/Variables")]
    public IAsyncEnumerable<ProcessDebugContextVariable> VariablesAsync(
        string id,
        string frame,
        ODataQuery? query = null,
        CancellationToken cancellationToken = default
    ) => Session.StreamAsync(Tm1Request.List<ProcessDebugContextVariable>(FramePath(id, frame).Nav("Variables"), query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/CallStack('frame')/Process</c>: the process a frame runs.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContextStackFrame/Process")]
    public Task<Process> FrameProcessAsync(string id, string frame, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Process>(FramePath(id, frame).Nav("Process"), query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/Process</c>: the process being debugged.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/Process")]
    public Task<Process> ProcessAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Process>(ContextPath(id).Nav("Process"), query), cancellationToken);

    /// <summary><c>GET ProcessDebugContexts('id')/Thread</c>: the thread running the process.</summary>
    [Tm1Endpoint("Navigation ProcessDebugContext/Thread")]
    public Task<Tm1Thread> ThreadAsync(string id, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Tm1Thread>(ContextPath(id).Nav("Thread"), query), cancellationToken);

    private async Task FinishContextAsync(ProcessDebugContext context)
    {
        if (context.Id is { } id)
        {
            // Not the work's token: a cancelled run must still be finished on TM1
            await FinishAsync(id, CancellationToken.None).ConfigureAwait(false);
        }
    }

    private static Tm1Request<ProcessDebugContext> Step(Tm1Operation operation, string id, ODataQuery? query) =>
        Tm1Request
            .Post<ProcessDebugContext>(ContextPath(id).Action(operation.Name), new Dictionary<string, object?>())
            .WithQuery(query)
            .Requires(operation.Capability());

    private static ODataPath ContextPath(string id) => Contexts.Key(Require(id, nameof(id)));

    private static ODataPath FramePath(string id, string frame) => ContextPath(id).Nav("CallStack").Key(Require(frame, nameof(frame)));
}
