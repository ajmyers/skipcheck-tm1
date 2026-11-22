using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Views: a cube's public and private views (<see cref="ViewScope"/>), native
/// (<c>NativeView</c>: subsets on columns, rows and titles) or MDX (<c>MDXView</c>). Each reads as
/// its own type, by <c>@odata.type</c>.
/// <para>Create an MDX view:</para>
/// <code>
/// await tm1.Views.CreateAsync("Sales", ViewScope.Public, MDXViewPatch.Builder().Name("Default").MDX("SELECT … FROM [Sales]").Build());
/// </code>
/// <para>Read a native view with its axes' subsets:</para>
/// <code>
/// await tm1.Views.GetNativeAsync("Sales", ViewScope.Public, "Default", ODataQuery.None().Expand("Columns/Subset").Expand("Rows/Subset").Expand("Titles/Subset"));
/// </code>
/// </summary>
public sealed class ViewService : Tm1Service
{
    public ViewService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>GET Cubes('c')/Views</c> (or the private ones), streamed; a native view's axes
    /// expand through its type, as <c>tm1.NativeView/Columns/Subset</c>.</summary>
    [Tm1Endpoint("Navigation Cube/Views")]
    [Tm1Endpoint("Navigation Cube/PrivateViews")]
    public IAsyncEnumerable<View> ListAsync(string cube, ViewScope scope, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<View>(Views(cube, scope), query), cancellationToken);

    /// <summary><c>GET Cubes('c')/Views('v')</c>: the view as its own type, or a
    /// <c>Tm1NotFoundException</c> if there's none.</summary>
    [Tm1Endpoint("Navigation Cube/Views")]
    [Tm1Endpoint("Navigation Cube/PrivateViews")]
    public Task<View> GetAsync(string cube, ViewScope scope, string view, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<View>(ViewPath(cube, scope, view), query), cancellationToken);

    /// <summary><c>GET Cubes('c')/Views('v')/tm1.NativeView</c>: reads a native view through its type,
    /// so its axes can be expanded: <c>Columns/Subset</c>, <c>Rows/Subset</c>, <c>Titles/Subset</c>,
    /// <c>Titles/Selected</c>. They're complex properties, so TM1 wants paths, not nested options.
    /// An MDX view is an error.</summary>
    [Tm1Endpoint("Navigation Cube/Views")]
    [Tm1Endpoint("Navigation Cube/PrivateViews")]
    [Tm1Endpoint("Navigation ViewAxisSelection/Subset")]
    [Tm1Endpoint("Navigation ViewTitle/Subset")]
    [Tm1Endpoint("Navigation ViewTitle/Selected")]
    public Task<NativeView> GetNativeAsync(string cube, ViewScope scope, string view, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<NativeView>(ViewPath(cube, scope, view).Cast("ibm.tm1.api.v1.NativeView"), query), cancellationToken);

    /// <summary><c>POST Cubes('c')/Views</c>: creates a view; the patch's type says which kind
    /// (<c>MDXViewPatch</c>, <c>NativeViewPatch</c>), and a native view's axes bind subsets. TM1
    /// (11.8) refuses a native view that leaves any of the cube's dimensions off its columns, rows
    /// and titles (400, "All cube dimensions must be placed on an axis").</summary>
    [Tm1Endpoint("Navigation Cube/Views")]
    [Tm1Endpoint("Navigation Cube/PrivateViews")]
    public Task<View> CreateAsync(string cube, ViewScope scope, ViewPatch view, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<View>(Views(cube, scope), Require(view, nameof(view))), cancellationToken);

    /// <summary><c>PATCH Cubes('c')/Views('v')</c>: changes the properties the patch sets, such as an
    /// MDX view's <c>MDX</c>. With <paramref name="etag"/>, sent as <c>If-Match</c>: a
    /// <c>Tm1PreconditionFailedException</c> if the view had changed.</summary>
    [Tm1Endpoint("Navigation Cube/Views")]
    [Tm1Endpoint("Navigation Cube/PrivateViews")]
    public async Task UpdateAsync(string cube, ViewScope scope, string view, ViewPatch patch, string? etag = null, CancellationToken cancellationToken = default)
    {
        var request = Tm1Request.Patch(ViewPath(cube, scope, view), Require(patch, nameof(patch)));
        await Session.ExecuteAsync(etag is null ? request : request.IfMatch(etag), cancellationToken).ConfigureAwait(false);
    }

    /// <summary><c>DELETE Cubes('c')/Views('v')</c>; its subsets stay.</summary>
    [Tm1Endpoint("Navigation Cube/Views")]
    [Tm1Endpoint("Navigation Cube/PrivateViews")]
    public Task DeleteAsync(string cube, ViewScope scope, string view, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Delete(ViewPath(cube, scope, view)), cancellationToken);

    /// <summary><c>GET Cubes('c')/Views('v')/Cube</c>: the view's cube.</summary>
    [Tm1Endpoint("Navigation View/Cube")]
    public Task<Cube> CubeAsync(string cube, ViewScope scope, string view, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<Cube>(ViewPath(cube, scope, view).Nav("Cube"), query), cancellationToken);

    private static ODataPath Views(string cube, ViewScope scope) => ODataPath.Of("Cubes").Key(Require(cube, nameof(cube))).Nav(scope.Navigation());

    private static ODataPath ViewPath(string cube, ViewScope scope, string view) => Views(cube, scope).Key(Require(view, nameof(view)));
}
